package com.junkfood.seal.download

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.junkfood.seal.App
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.util.NetworkPolicy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private const val TAG = "TaskScheduler"

internal const val MAX_CONCURRENCY = 3
private const val LOW_RAM_THRESHOLD_BYTES = 4L * 1024 * 1024 * 1024
private const val METERED_LOG_THROTTLE_MS = 30_000L

internal fun maxConcurrencyForRam(totalRamBytes: Long, isLowRamDevice: Boolean): Int =
    if (isLowRamDevice || (totalRamBytes > 0L && totalRamBytes <= LOW_RAM_THRESHOLD_BYTES)) {
        2
    } else {
        MAX_CONCURRENCY
    }

internal fun resolveMaxConcurrency(context: Context): Int {
    val am =
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return MAX_CONCURRENCY
    val memInfo = ActivityManager.MemoryInfo()
    am.getMemoryInfo(memInfo)
    return maxConcurrencyForRam(memInfo.totalMem, am.isLowRamDevice)
}

/**
 * Owns queue scheduling under [scheduleLock] (enforcing RAM-aware concurrency
 * so concurrent wakeups cannot overshoot `maxConcurrency`), process-death
 * [pendingResume] recovery, [NetworkPolicy.onNetworkRestored] wakeup wiring,
 * and foreground [App.startService] / [App.stopService] coordination.
 */
internal class TaskScheduler(
    private val store: TaskStore,
    private val scope: CoroutineScope,
    private val maxConcurrencyProvider: () -> Int,
    private val canStartDownloads: () -> Boolean = { NetworkPolicy.canStartDownloads() },
    private val canStartTask: (String) -> Boolean = { NetworkPolicy.canStartTask(it) },
    private val startService: () -> Unit = { App.startService() },
    private val stopService: () -> Unit = { App.stopService() },
    private val onPrepare: (Task) -> Unit,
    private val onDownload: (Task) -> Unit,
    private val onRestart: (Task) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Serialises slot checks and task dispatch so concurrent wakeups (snapshot
     * emission + network restored / metered permit) cannot overshoot
     * [maxConcurrencyProvider]. Lock ordering is strictly
     * `scheduleLock -> store.stateLock`.
     */
    private val scheduleLock = Any()

    /**
     * Tasks a relaunch owes a restart: the backup left them in flight, but the
     * network refused at boot. Kept until the network allows the transfer, so
     * a phone that came back on mobile data resumes on Wi-Fi instead of
     * silently spending bytes.
     */
    private val pendingResume: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Processor time of the last "parked on a metered network" log line. */
    @Volatile private var lastMeteredLogAt = 0L

    fun startObserving() {
        scope.launch(Dispatchers.Default) {
            store.snapshotFlow
                .onEach {
                    // The scheduler must survive any single bad task: a cancel
                    // racing the pick or a removed task mid-callback must cost
                    // one tick, not the whole download pipeline.
                    runCatching { doYourWork() }
                        .onFailure { runCatching { Log.e(TAG, "scheduler tick failed", it) } }
                }
                .map { it.countRunning() }
                .distinctUntilChanged()
                .collect { runningCount ->
                    if (runningCount > 0) {
                        // Some OEMs deny plain startService from background
                        // contexts; a throw here would kill the scheduler
                        // coroutine and stall every future download.
                        runCatching { startService() }
                            .onFailure { runCatching { Log.e(TAG, "startService failed", it) } }
                    } else {
                        runCatching { stopService() }
                            .onFailure { runCatching { Log.e(TAG, "stopService failed", it) } }
                    }
                }
        }

        // A network that comes back must wake the queue: snapshotFlow only
        // re-emits on a map change, so a task parked by the metered gate would
        // otherwise sit there until the user touched it.
        NetworkPolicy.onNetworkRestored = {
            scope.launch(Dispatchers.Default) {
                // A batch killed mid-transfer that woke up metered resumes
                // here, before the scheduler tick looks for runnable work.
                resumePending()
                kickFromNetwork()
            }
        }
    }

    fun recordPendingResume(taskIds: Collection<String>) {
        taskIds.forEach(pendingResume::add)
        resumePending()
    }

    /**
     * Restarts what the backup left owed, but only when the network allows a
     * transfer: on mobile data without the opt-in the rows stay parked and the
     * network callback above finishes the job on Wi-Fi.
     */
    fun resumePending() {
        if (pendingResume.isEmpty() || !canStartDownloads()) return
        val due = pendingResume.toList().toSet()
        // Collect before writing: the restarts below change the same map this
        // reads, and a row removed since the kill simply owes nothing.
        val owed =
            synchronized(store.stateLock) {
                store.taskStateMap.entries
                    .filter { it.key.id in due }
                    .map { (task, state) -> task to state.match }
            }
        pendingResume.removeAll(due)
        owed.forEach { (task, match) ->
            // A held book has no verified source; its only exit is an explicit
            // pick, so an automatic restart would download the refused upload.
            if (match?.held == true || match?.pending == true) return@forEach
            onRestart(task)
        }
    }

    /**
     * Wakes the scheduler when a network becomes usable again. Calling
     * [doYourWork] directly is required: `Snapshot.sendApplyNotifications()`
     * alone does not re-emit `snapshotFlow` when the map did not change.
     */
    fun kickFromNetwork(): Boolean = doYourWork()

    fun kickAsync() {
        scope.launch(Dispatchers.Default) { doYourWork() }
    }

    /** Processes pending tasks, prioritizing downloads with info already ready. */
    fun doYourWork(): Boolean {
        synchronized(scheduleLock) {
            // The one enforcement point for the metered gate. It sits here, above
            // the yt-dlp/aria2/Ghost dispatch, so every lane shares it and a
            // transfer already in flight is never interrupted mid-stream.
            if (!canStartDownloads()) {
                val now = nowMs()
                if (now - lastMeteredLogAt > METERED_LOG_THROTTLE_MS) {
                    lastMeteredLogAt = now
                    runCatching { Log.i(TAG, "queue parked: metered network, no permit") }
                }
                return false
            }

            val maxConcurrency = maxConcurrencyProvider()
            val picked =
                synchronized(store.stateLock) {
                    if (store.taskStateMap.countRunning() >= maxConcurrency) return false
                    store.taskStateMap.entries
                        .sortedBy { (_, state) -> state.downloadState.schedulerRank }
                        .firstOrNull { (task, state) ->
                            (state.downloadState == ReadyWithInfo || state.downloadState == Idle) &&
                                // A permit granted from one card names its tasks: another
                                // parked burst must not ride it.
                                canStartTask(task.id)
                        }
                        ?.let { (task, state) -> task to state.downloadState }
                } ?: return false

            // The picked state may already be stale (cancel/remove raced the
            // pick); prepare/download re-check and no-op in that case.
            val (task, downloadState) = picked
            when (downloadState) {
                Idle -> onPrepare(task)
                ReadyWithInfo -> onDownload(task)
                else -> return false
            }
            return true
        }
    }
}
