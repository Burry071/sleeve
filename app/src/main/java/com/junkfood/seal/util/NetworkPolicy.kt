package com.junkfood.seal.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.junkfood.seal.download.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One fail-open gate for "may a transfer start right now?".
 *
 * The rule is the user's own: a download may start on an unmetered network, on
 * a metered one when [CELLULAR_DOWNLOAD] is on, or during the short window a
 * one-off "Allow once" opened. The permit is deliberately *time-boxed, not
 * consumed*: a consumed-once permit would strand every song after the first in
 * a batch the user just approved. A permit granted from a card names the tasks
 * it covers, so it can only start the burst the user tapped; the sheet's
 * answer predates any task and binds to whichever burst starts first.
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

    private val mutableCanStartFlow = MutableStateFlow(true)
    val canStartFlow: StateFlow<Boolean> = mutableCanStartFlow.asStateFlow()

    /** `0` means no permit; anything else is an [SystemClock.elapsedRealtime] stamp. */
    @Volatile private var permitArmedAt = 0L

    /**
     * The tasks the permit was granted for. Empty means "whichever burst starts
     * first": the confirm sheet's one-off answer predates any task id, so it
     * cannot name one.
     */
    @Volatile private var permitScope: Set<String> = emptySet()

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
            refreshCanStart()
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
                            // Read the default network, not the callback's: this
                            // can fire for a VPN or a secondary network, and
                            // every side-effect-free query must agree with it.
                            refresh(manager)
                            // Back on Wi-Fi: the permit has nothing left to
                            // do, and a later metered switch must not inherit
                            // it.
                            if (!metered) clearMeteredPermit()
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

    /** Side-effect free, never throws. Asks whether *any* transfer may start. */
    fun canStartDownloads(): Boolean =
        runCatching { decide(null) }.getOrDefault(true).also { mutableCanStartFlow.value = it }

    /**
     * Re-evaluates [canStartDownloads] and updates [canStartFlow] so Compose
     * screens recompose live on Wi-Fi ↔ cellular or `CELLULAR_DOWNLOAD` changes.
     */
    fun refreshCanStart(): Boolean = canStartDownloads()

    /**
     * Pure UI helper: true when a task is waiting in the scheduler solely
     * because the metered gate refuses to start it. Covers both [Idle] and
     * [ReadyWithInfo] so neither state can deadlock without a rescue action.
     */
    fun isMeteredParked(state: Task.DownloadState, canStart: Boolean): Boolean =
        !canStart &&
            (state == Task.DownloadState.Idle || state == Task.DownloadState.ReadyWithInfo)

    /**
     * Whether this particular task may start. A permit granted from a card's
     * "Download anyway" names the tasks it covers, so it never starts a
     * different burst the user has not approved.
     */
    fun canStartTask(taskId: String): Boolean = runCatching { decide(taskId) }.getOrDefault(true)

    private fun decide(taskId: String?): Boolean {
        val cellularAllowed = with(PreferenceUtil) { CELLULAR_DOWNLOAD.getBoolean() }
        val metered = isMetered()
        val remaining = permitRemainingMs()
        val covers = remaining > 0 && (taskId == null || scopeCovers(permitScope, taskId))
        // The one line that answers "why did this burst download?":
        // a permit that expired mid-batch is the usual surprise.
        if (metered && !cellularAllowed && covers) {
            Log.i(TAG, "metered permit honored, ${remaining / 1000}s left")
        }
        return permitsTask(cellularAllowed = cellularAllowed, metered = metered, permitCovers = covers)
    }

    /**
     * Arms the non-persisted "Allow once" window; gone on process death. An
     * empty [tasks] binds it to whichever burst starts first — the confirm
     * sheet's answer predates any task id.
     */
    fun allowMeteredOnce(tasks: Set<String> = emptySet()) {
        permitScope = tasks
        permitArmedAt = SystemClock.elapsedRealtime()
        refreshCanStart()
    }

    fun isMetered(): Boolean = metered

    /**
     * Drops cancelled or removed tasks from the permit. A card's permit names
     * its tasks, so one cancel leaves the rest of the approved burst running;
     * an unbound sheet permit has no scope to shrink, so any cancellation
     * revokes it — otherwise a burst the user stopped could carry over.
     */
    internal fun revokeMetered(taskIds: Set<String>) {
        if (permitArmedAt == 0L) return
        if (permitScope.isEmpty()) {
            clearMeteredPermit()
            return
        }
        permitScope = permitScope - taskIds
        if (permitScope.isEmpty()) clearMeteredPermit() else refreshCanStart()
    }

    /** Unmetered callback and test hook. */
    internal fun clearMeteredPermit() {
        permitArmedAt = 0L
        permitScope = emptySet()
        refreshCanStart()
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

    /**
     * The per-task form of [permits]: [permitCovers] already folds in the
     * permit window and whether the permit names this task.
     */
    internal fun permitsTask(
        cellularAllowed: Boolean,
        metered: Boolean,
        permitCovers: Boolean,
    ): Boolean = permits(cellularAllowed, metered, permitArmed = permitCovers)

    /** An unbound permit covers whichever burst starts first. */
    internal fun scopeCovers(scope: Set<String>, taskId: String): Boolean =
        scope.isEmpty() || taskId in scope

    /** Milliseconds left on the "Allow once" permit; `0` when none is armed. */
    private fun permitRemainingMs(): Long {
        val armedAt = permitArmedAt
        if (armedAt == 0L) return 0L
        val elapsed = SystemClock.elapsedRealtime() - armedAt
        if (elapsed in 0..PERMIT_WINDOW_MS) return PERMIT_WINDOW_MS - elapsed
        // Expired: drop the stale stamp so a clock jump cannot resurrect it.
        clearMeteredPermit()
        return 0L
    }

    private fun refresh(manager: ConnectivityManager) {
        // Fail open: an unreadable network is treated as unmetered.
        metered = runCatching { manager.isActiveNetworkMetered }.getOrDefault(false)
        refreshCanStart()
    }
}
