package com.junkfood.seal.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log

/**
 * One fail-open gate for "may a transfer start right now?".
 *
 * The rule is the user's own: a download may start on an unmetered network, on
 * a metered one when [CELLULAR_DOWNLOAD] is on, or during the short window a
 * one-off "Allow once" opened. The permit is deliberately *time-boxed, not
 * consumed*: a consumed-once permit would strand every song after the first in
 * a batch the user just approved.
 *
 * Nothing here may brick downloads: a missing [ConnectivityManager] counts as
 * unmetered, and [canStartDownloads] never throws.
 */
object NetworkPolicy {
    private const val TAG = "NetworkPolicy"

    /**
     * "Allow once" covers the whole burst the user just approved, not forever:
     * long enough for a batch of songs, short enough that a phone left on
     * mobile data tomorrow does not silently keep downloading.
     */
    internal const val PERMIT_WINDOW_MS = 5 * 60 * 1000L

    @Volatile private var registered = false
    @Volatile private var metered = false

    /** `0` means no permit; anything else is an [SystemClock.elapsedRealtime] stamp. */
    @Volatile private var permitArmedAt = 0L

    /**
     * Fired when an unmetered network becomes the default one. The downloader
     * wires this to a scheduler kick: a parked queue would otherwise wait for
     * the next user action because `snapshotFlow` only re-emits on a map change.
     */
    @Volatile var onNetworkRestored: (() -> Unit)? = null

    /** Idempotent: `App.onCreate()` is the only production caller. */
    fun init(context: Context) {
        if (registered) return
        registered = true
        val manager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
        if (manager == null) {
            Log.w(TAG, "no ConnectivityManager; downloads stay allowed")
            metered = false
            return
        }
        refresh(manager)
        runCatching {
                manager.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            refresh(manager)
                            if (!metered) clearMeteredPermit()
                            // A metered default still resumes a parked queue
                            // when the user already allowed cellular downloads;
                            // otherwise that queue waits for a manual tap.
                            notifyIfUsable()
                        }

                        override fun onLost(network: Network) {
                            refresh(manager)
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            capabilities: NetworkCapabilities,
                        ) {
                            val unmetered =
                                capabilities.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_NOT_METERED
                                )
                            metered = !unmetered
                            // Back on Wi-Fi: the permit has nothing left to
                            // do, and a later metered switch must not inherit
                            // it.
                            if (unmetered) clearMeteredPermit()
                            notifyIfUsable()
                        }
                    }
                )
            }
            .onFailure {
                // No callback on this device/build: the active-network read
                // still answers, so only live switching is lost.
                Log.w(TAG, "network callback unavailable: ${it.message}")
            }
    }

    /** Side-effect free, never throws. */
    fun canStartDownloads(): Boolean =
        runCatching {
                val cellularAllowed = with(PreferenceUtil) { CELLULAR_DOWNLOAD.getBoolean() }
                val metered = isMetered()
                val remaining = permitRemainingMs()
                // The one line that answers "why did this burst download?":
                // a permit that expired mid-batch is the usual surprise.
                if (metered && !cellularAllowed && remaining > 0) {
                    Log.i(TAG, "metered permit honored, ${remaining / 1000}s left")
                }
                permits(
                    cellularAllowed = cellularAllowed,
                    metered = metered,
                    permitArmed = remaining > 0,
                )
            }
            .getOrDefault(true)

    /** Arms the non-persisted "Allow once" window; gone on process death. */
    fun allowMeteredOnce() {
        permitArmedAt = SystemClock.elapsedRealtime()
    }

    fun isMetered(): Boolean = metered

    /** Unmetered callback and test hook. */
    internal fun clearMeteredPermit() {
        permitArmedAt = 0L
    }

    /** Wakes a parked queue only when the new default network may carry it. */
    private fun notifyIfUsable() {
        if (canStartDownloads()) onNetworkRestored?.invoke()
    }

    internal fun permits(
        cellularAllowed: Boolean,
        metered: Boolean,
        permitArmed: Boolean,
    ): Boolean = cellularAllowed || !metered || permitArmed

    /** Milliseconds left on the "Allow once" permit; `0` when none is armed. */
    private fun permitRemainingMs(): Long {
        val armedAt = permitArmedAt
        if (armedAt == 0L) return 0L
        val elapsed = SystemClock.elapsedRealtime() - armedAt
        if (elapsed in 0..PERMIT_WINDOW_MS) return PERMIT_WINDOW_MS - elapsed
        // Expired: drop the stale stamp so a clock jump cannot resurrect it.
        permitArmedAt = 0L
        return 0L
    }

    private fun refresh(manager: ConnectivityManager) {
        // Fail open: an unreadable network is treated as unmetered.
        metered = runCatching { manager.isActiveNetworkMetered }.getOrDefault(false)
    }
}
