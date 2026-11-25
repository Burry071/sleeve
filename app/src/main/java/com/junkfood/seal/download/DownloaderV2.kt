package com.junkfood.seal.download

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.download.Task.TypeInfo
import com.junkfood.seal.util.ArchiveIndex
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.NetworkPolicy
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent

private const val TAG = "DownloaderV2"

interface DownloaderV2 {
    fun getTaskStateMap(): SnapshotStateMap<Task, Task.State>

    fun cancel(task: Task): Boolean

    fun cancel(taskId: String): Boolean {
        return getTaskStateMap().keys.find { it.id == taskId }?.let { cancel(it) } ?: false
    }

    fun restart(task: Task)

    /** Enqueue a [Task] with an empty [Task.State] */
    fun enqueue(task: Task)

    fun enqueue(task: Task, state: Task.State)

    fun enqueue(taskWithState: TaskFactory.TaskWithState) {
        val (task, state) = taskWithState
        enqueue(task, state)
    }

    fun remove(task: Task): Boolean

    /**
     * Fetch a finished task again as a new transfer. The download-archive entry
     * the earlier run wrote is dropped first, or `--download-archive` refuses
     * the very job the user just asked for.
     *
     * @return true when the card was reset to a fresh transfer.
     */
    fun redownload(task: Task): Boolean = false

    /**
     * Drop queue cards that finished into [paths] — the files whose history row
     * the user just deleted. The row and the card describe the same file, so
     * removing one must not leave the other pointing at it.
     *
     * @return the number of cards removed.
     */
    fun removeCompletedForPaths(paths: Set<String>): Int = 0

    /**
     * Re-run the catalogue search for one row with a user-edited query. Only a
     * canceled or failed row may be re-searched; the edited terms are kept for
     * this process so a later Resume repeats them.
     *
     * @return true when the row is now searching again.
     */
    fun searchAgain(task: Task, query: String): Boolean = false

    /**
     * True when this process restored a task that was cut off mid-transfer by the
     * system, not by the user. Memory only: a later occurrence in a new process is
     * still explained.
     */
    val interruptedRestore: Boolean get() = false

    fun dismissInterruptedRestore() {}

    /** Transfers an engine update would pull the binary out from under. */
    fun activeTransfers(): Int =
        getTaskStateMap().values.count {
            it.downloadState is Running || it.downloadState is FetchingInfo
        }

    /**
     * Replace a held catalogue book with one task per verified part, all in the
     * book's batch. Each part downloads its own upload as a separate file;
     * nothing is concatenated.
     *
     * @return the number of part tasks queued; 0 when nothing was queued —
     *   no parts, no batch for them to belong to, or a task this queue does
     *   not hold.
     */
    fun enqueueParts(task: Task, parts: List<MatchCandidate>): Int = 0

    /** Stop every unfinished song in the batch. */
    fun cancelBatch(batchId: String) {
        BatchOps.stopTargets(getTaskStateMap(), batchId).forEach { cancel(it) }
    }

    /** Retry every canceled or failed song in the batch. */
    fun resumeBatch(batchId: String) {
        BatchOps.resumeTargets(getTaskStateMap(), batchId).forEach { restart(it) }
    }

    /**
     * The 1-tap "Download anyway" on a metered-paused card or batch: arms the
     * one-off permit for exactly these tasks. The engine ticks its scheduler
     * afterwards, which will not re-emit on its own because no state changed.
     */
    fun allowMeteredOnce(tasks: Set<String>) {
        NetworkPolicy.allowMeteredOnce(tasks)
    }

    /** Stop unfinished songs, drop them and failed/canceled rows, keep completed files. */
    fun dismissBatch(batchId: String) {
        val plan = BatchOps.dismissPlan(getTaskStateMap(), batchId)
        plan.cancel.forEach { cancel(it) }
        // A song can finish between planning and this loop; re-read its state
        // so a just-completed card is re-keyed instead of silently dropped.
        (plan.remove + plan.completed.map { it.first }).forEach { task ->
            val state = getTaskStateMap()[task]
            remove(task)
            if (state?.downloadState is Completed) enqueue(task.copy(batch = null), state)
        }
    }

    /**
     * Mark every flagged pick in the batch as seen, clearing the review entry.
     * A pick that is still [MatchResult.pending] is left alone: its replacement
     * file is not on disk yet, so acknowledging it would hide the review entry
     * and leave the swap unverifiable. A [MatchResult.held] book is left alone
     * too: Accept all must never clear a refusal the user has not answered.
     */
    fun acknowledgeMatches(batchId: String) {
        val map = getTaskStateMap()
        BatchOps.entries(map, batchId).forEach { (task, state) ->
            val match = state.match ?: return@forEach
            if (MatchOps.acknowledgeable(state)) {
                map[task] = state.copy(match = match.copy(acknowledged = true))
            }
        }
        Snapshot.sendApplyNotifications()
    }

    /**
     * Re-download the songs whose picks the user swapped. Only the chosen
     * candidate is fetched — never a new search — and only that song's file is
     * produced. One [SwapResult] per distinct task id, in input order; never
     * throws and never deletes a file.
     */
    suspend fun swapMatches(selections: List<Pair<Task, MatchCandidate>>): List<SwapResult> =
        emptyList()
}

internal object FakeDownloaderV2 : DownloaderV2 {
    override fun getTaskStateMap(): SnapshotStateMap<Task, Task.State> {
        return mutableStateMapOf()
    }

    override fun cancel(task: Task): Boolean {
        return false
    }

    override fun restart(task: Task) {}

    override fun enqueue(task: Task) {}

    override fun enqueue(task: Task, state: Task.State) {}

    override fun remove(task: Task): Boolean {
        return true
    }

    override fun enqueueParts(task: Task, parts: List<MatchCandidate>): Int {
        return 0
    }
}

/**
 * Deleting a live task must stop its worker first. Dropping the map entry alone
 * left the job running invisibly and its notification with nothing to clear it.
 */
internal fun needsCancelBeforeRemoval(state: Task.State): Boolean =
    state.downloadState is DownloadState.Cancelable

/**
 * Façade wiring [TaskStore], [TaskScheduler], [TaskWorker],
 * [MatchSwapCoordinator], and [TaskNotificationSync] behind [DownloaderV2].
 */
class DownloaderV2Impl(private val appContext: Context) : DownloaderV2, KoinComponent {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val maxConcurrency: Int by lazy { resolveMaxConcurrency(appContext) }

    /**
     * Search terms the user typed for a "Search again" row, by task id. Process
     * local: they matter only while this app run is resolving that row.
     */
    private val searchOverrides = ConcurrentHashMap<String, String>()

    private val store = TaskStore()
    private val notificationSync =
        TaskNotificationSync(appContext = appContext, store = store, scope = scope)
    private val swapCoordinator =
        MatchSwapCoordinator(
            store = store,
            scope = scope,
            liveRefusal = { liveRefusal(appContext, it) },
            setLiveNotice = { task, liveStatus -> setLiveNotice(appContext, store, task, liveStatus) },
            shortReason = ::shortReason,
            isDocumentValid = { uri -> isDocumentOutputValid(appContext, uri) },
            onEnqueuePart = ::enqueue,
            onRemoveTask = ::remove,
        )
    private val worker =
        TaskWorker(
            appContext = appContext,
            store = store,
            scope = scope,
            swapCoordinator = swapCoordinator,
            auditCookieState = ::auditCookieState,
            searchOverrideFor = { searchOverrides[it].orEmpty() },
        )
    private val scheduler =
        TaskScheduler(
            store = store,
            scope = scope,
            maxConcurrencyProvider = { maxConcurrency },
            onPrepare = worker::prepare,
            onDownload = worker::download,
            onRestart = worker::restart,
        )

    override val interruptedRestore: Boolean
        get() = store.interruptedRestore

    override fun dismissInterruptedRestore() = store.dismissInterruptedRestore()

    init {
        store.onTransition = notificationSync::syncNotifications
        // Observation starts with the downloader. Idempotent: App.onCreate()
        // registers first, and this keeps a DownloaderV2 built in isolation
        // from silently missing live network state.
        NetworkPolicy.init(appContext)
        scheduler.startObserving()

        scope.launch(Dispatchers.IO) {
            // don't write before we read
            val interruptedIds = store.restoreFromBackup(onTaskRestored = ::auditCookieState)
            scheduler.recordPendingResume(interruptedIds)
            notificationSync.refreshAllBatchNotifications()
            store.persistBackupChanges()
        }
    }

    override fun getTaskStateMap(): SnapshotStateMap<Task, Task.State> = store.taskStateMap

    override fun enqueue(task: Task) {
        if (!store.putIfEnqueueAllowed(task)) return
        auditCookieState(task)
    }

    override fun enqueue(task: Task, state: Task.State) {
        store.put(task, state)
        auditCookieState(task)
    }

    /**
     * Noted the caller is responsible for stopping the [task] before removing it
     *
     * @return true if the task was removed
     */
    override fun remove(task: Task): Boolean {
        if (store.contains(task)) {
            val currentState = store.stateOf(task)
            if (needsCancelBeforeRemoval(currentState)) {
                // worker.cancel stops the worker and clears the task's notification.
                worker.cancel(task)
            } else if (clearsOwnNotificationOnRemoval(currentState, task.usesOwnNotification)) {
                notificationSync.cancelOwnNotification(task)
            }
            store.remove(task)
            TaskAuditLog.clear(task.id)
            searchOverrides.remove(task.id)
            swapCoordinator.deleteOrphanedSwapAsync(task.id)
            task.batch?.let { notificationSync.updateBatchNotification(it) }
            NetworkPolicy.revokeMetered(setOf(task.id))
            return true
        }
        return false
    }

    override fun removeCompletedForPaths(paths: Set<String>): Int {
        if (paths.isEmpty()) return 0
        return store.completedTasksForPaths(paths).count { remove(it) }
    }

    override fun redownload(task: Task): Boolean {
        if (!store.contains(task)) return false
        val info = store.get(task)?.videoInfo
        if (info != null) {
            // Synchronous on purpose: the engine checks the archive when it
            // starts, so the entry must be gone before the task leaves Idle.
            runCatching { with(FileUtil) { appContext.getArchiveFile() } }
                .onSuccess { ArchiveIndex.forget(it, info.extractorKey, info.id) }
                .onFailure { Log.w(TAG, "could not open download archive: ${it.message}") }
        }
        enqueue(task)
        return true
    }

    override fun enqueueParts(task: Task, parts: List<MatchCandidate>): Int =
        swapCoordinator.enqueueParts(task, parts)

    override fun cancel(task: Task): Boolean =
        worker.cancel(task).also { canceled ->
            // A stopped task must not keep the metered permit it was approved
            // under; the rest of a card-scoped batch keeps its own.
            if (canceled) NetworkPolicy.revokeMetered(setOf(task.id))
        }

    override fun allowMeteredOnce(tasks: Set<String>) {
        super.allowMeteredOnce(tasks)
        // The scheduler only re-checks on a state change, and a parked task's
        // state does not change when a permit arrives, so tick it here.
        scheduler.kickAsync()
    }

    override fun restart(task: Task) {
        worker.restart(task)
    }

    override fun searchAgain(task: Task, query: String): Boolean {
        val current = store.get(task) ?: return false
        // Only a terminal row may be re-searched: a new search replaces the
        // match the worker may still be using.
        if (!TaskTransitions.acceptsSearchAgain(current.downloadState)) return false
        val terms = query.trim().takeIf { it.isNotBlank() } ?: return false
        if (task.type !is TypeInfo.MetadataTrack) return false
        searchOverrides[task.id] = terms
        TaskAuditLog.append(task.id, "search again: query=$terms")
        // Idle, not restartTarget: this must re-run the catalogue search, never
        // re-download the info a previous pick cached.
        val queued = store.applyTransition(task, Idle) { it == current.downloadState }
        if (!queued) searchOverrides.remove(task.id)
        return queued
    }

    override suspend fun swapMatches(
        selections: List<Pair<Task, MatchCandidate>>
    ): List<SwapResult> = swapCoordinator.swapMatches(selections)

    override fun acknowledgeMatches(batchId: String) {
        super.acknowledgeMatches(batchId)
        notificationSync.updateBatchNotificationForId(batchId)
    }

    /** Runtime evidence that the quick fence held (or that a user task uses cookies). */
    private fun auditCookieState(task: Task) {
        val line = task.cookieAudit
        Log.i(TAG, "${task.id} $line")
        if (TaskAuditLog.read(task.id).none { it.startsWith("cookies:") }) {
            TaskAuditLog.append(task.id, line)
        }
    }
}
