package com.junkfood.seal.download

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.VideoInfo
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map

private const val TAG = "TaskStore"

internal const val MAX_BACKUP_COMPLETED = 40
internal const val BACKUP_DEBOUNCE_MS = 1_000L

/**
 * Strips high-frequency progress text and intermediate percentages from a
 * [Task.State] when deciding whether to persist the queue backup. Preserves
 * the `progress > 0f` boundary so process-death recovery still recognizes an
 * interrupted transfer without writing megabytes of JSON on every 10–30 Hz tick.
 */
internal fun Task.State.backupComparisonKey(): Task.State =
    when (val ds = downloadState) {
        is Running ->
            copy(
                downloadState =
                    ds.copy(
                        progress = if (ds.progress > 0f) 1f else 0f,
                        progressText = "",
                    )
            )
        else -> this
    }

/**
 * Completed downloads persist too so reopening the app shows what finished,
 * while capping the retained completed tail so a long session cannot grow MMKV
 * without bound.
 */
internal fun capTaskBackup(
    state: Map<Task, Task.State>,
    maxCompleted: Int = MAX_BACKUP_COMPLETED,
): Map<Task, Task.State> {
    val entries = state.entries.toList()
    val active = entries.filter { it.value.downloadState !is Completed }
    val completed = entries.filter { it.value.downloadState is Completed }.takeLast(maxCompleted)
    return (active + completed).associate { it.key to it.value }
}

@OptIn(FlowPreview::class)
internal fun Flow<Map<Task, Task.State>>.coalesceForBackup(
    debounceMs: Long = BACKUP_DEBOUNCE_MS,
    maxCompleted: Int = MAX_BACKUP_COMPLETED,
): Flow<Map<Task, Task.State>> =
    map { state -> capTaskBackup(state, maxCompleted) }
        .distinctUntilChangedBy { map ->
            map.mapValues { (_, taskState) -> taskState.backupComparisonKey() }
        }
        .debounce(debounceMs)

internal fun Map<Task, Task.State>.countRunning(): Int = count { (_, state) ->
    state.downloadState is Running || state.downloadState is FetchingInfo
}

/**
 * Owns the canonical [taskStateMap], serialised state-machine mutations under
 * [stateLock], process-death snapshot restoration, and coalesced MMKV backup
 * persistence.
 */
internal class TaskStore(
    onTransition: (Task, DownloadState) -> Unit = { _, _ -> },
    private val decodeBackup: () -> Map<Task, Task.State> = {
        PreferenceUtil.decodeTaskListBackup()
    },
    private val encodeBackup: (Map<Task, Task.State>) -> Unit = {
        PreferenceUtil.encodeTaskListBackup(it)
    },
    private val sendApplyNotifications: () -> Unit = { Snapshot.sendApplyNotifications() },
) {
    val taskStateMap: SnapshotStateMap<Task, Task.State> = mutableStateMapOf()

    /**
     * Serialises every read-modify-write of a [Task.State]. Workers run on
     * several dispatchers, so the transition guard and the copy it writes must
     * be one critical section or a late result can clobber a cancel.
     */
    val stateLock = Any()

    @Volatile var onTransition: (Task, DownloadState) -> Unit = onTransition

    var interruptedRestore by mutableStateOf(false)
        private set

    fun dismissInterruptedRestore() {
        interruptedRestore = false
    }

    val snapshotFlow: Flow<Map<Task, Task.State>> = snapshotFlow { taskStateMap.toMap() }

    private val Canceled.interrupted: Boolean
        get() = progress != null && progress > 0f && progress <= 1f && !byUser

    // All state accessors tolerate removal: a cancel/remove racing a running
    // task used to throw (taskStateMap[this]!!) and could crash the scheduler
    // collector. Reads fall back to a fresh Idle state without writing; writes
    // to a removed task are dropped instead of resurrecting it in the map.
    fun stateOf(task: Task): Task.State =
        taskStateMap[task] ?: Task.State(Idle, null, Task.ViewState(url = task.url, title = task.url))

    fun downloadStateOf(task: Task): DownloadState = stateOf(task).downloadState

    fun get(task: Task): Task.State? = taskStateMap[task]

    fun contains(task: Task): Boolean = taskStateMap.containsKey(task)

    fun findById(taskId: String): Task? =
        synchronized(stateLock) { taskStateMap.keys.firstOrNull { it.id == taskId } }

    fun distinctBatches(): List<BatchInfo> =
        synchronized(stateLock) { taskStateMap.keys.mapNotNull { it.batch }.distinctBy { it.id } }

    fun completedTasksForPaths(paths: Set<String>): List<Task> =
        synchronized(stateLock) {
            taskStateMap.keys.filter { task ->
                val filePath = (taskStateMap[task]?.downloadState as? Completed)?.filePath
                filePath != null && filePath in paths
            }
        }

    /**
     * The one writer for [Task.DownloadState]. [accept] sees the state as it is
     * under the lock, so a worker's late result is judged against the state a
     * cancel actually left behind. A refused or illegal edge is dropped and
     * logged; an accepted one notifies through [onTransition].
     */
    fun applyTransition(
        task: Task,
        next: DownloadState,
        accept: (DownloadState) -> Boolean,
    ): Boolean {
        synchronized(stateLock) {
            val current = taskStateMap[task] ?: return false
            val from = current.downloadState
            if (!accept(from)) return false
            if (!TaskTransitions.isLegal(from, next)) {
                runCatching {
                    Log.w(TAG, "${task.id} illegal transition ${from.label} -> ${next.label}")
                }
                return false
            }
            taskStateMap[task] = current.copy(downloadState = next)
        }
        sendApplyNotifications()
        runCatching { onTransition(task, next) }
            .onFailure { runCatching { Log.e(TAG, "notification sync failed", it) } }
        return true
    }

    /** Applies [transform] to the newest state under the lock, never a stale copy. */
    fun updateState(task: Task, transform: (Task.State) -> Task.State): Boolean {
        synchronized(stateLock) {
            val current = taskStateMap[task] ?: return false
            taskStateMap[task] = transform(current)
        }
        sendApplyNotifications()
        return true
    }

    fun setVideoInfo(task: Task, value: VideoInfo?): Boolean =
        updateState(task) { it.copy(videoInfo = value) }

    fun setViewState(task: Task, value: Task.ViewState): Boolean =
        updateState(task) { it.copy(viewState = value) }

    fun setMatch(task: Task, value: MatchResult?): Boolean =
        updateState(task) { it.copy(match = value) }

    fun setNotice(task: Task, value: String?): Boolean =
        updateState(task) { it.copy(notice = value) }

    /**
     * Inserts a fresh [Idle] state unless an identical task is already in
     * flight. Terminal work is a new submission and starts over.
     */
    fun putIfEnqueueAllowed(task: Task): Boolean {
        synchronized(stateLock) {
            val existing =
                taskStateMap.entries.firstOrNull { (queued, _) -> queued.isSameWork(task) }?.value
            if (!TaskTransitions.enqueueReplaces(existing?.downloadState)) return false
            taskStateMap[task] =
                Task.State(Idle, null, Task.ViewState(url = task.url, title = task.url))
        }
        // snapshotFlow only re-checks after an apply notification, and the
        // frame loop that sends them does not run when no UI is attached —
        // e.g. a share quick-download that starts with the app closed.
        sendApplyNotifications()
        return true
    }

    fun put(task: Task, state: Task.State) {
        synchronized(stateLock) { taskStateMap[task] = state }
        sendApplyNotifications()
    }

    fun remove(task: Task): Task.State? {
        val removed = synchronized(stateLock) { taskStateMap.remove(task) }
        if (removed != null) sendApplyNotifications()
        return removed
    }

    /**
     * Restores persisted queue state after process death and returns the task
     * ids that were interrupted mid-flight and are owed an automatic resume
     * once the network allows transfers.
     */
    fun restoreFromBackup(onTaskRestored: (Task) -> Unit = {}): Set<String> {
        val backup = decodeBackup()
        // Only a process kill stores a task in flight, so the entries below are
        // exactly the ones a relaunch may restart on its own. An entry already
        // persisted as Canceled was stopped by the user and stays stopped.
        val interrupted =
            backup.filterValues { TaskTransitions.resumesAfterProcessDeath(it.downloadState) }
        val taskList =
            backup.mapValues { (_, state) ->
                val downloadState = TaskTransitions.restore(state.downloadState)
                state.copy(
                    downloadState = downloadState,
                    // A pending swap is persisted, but the old path it
                    // replaces is not. Never restore a pending state that
                    // has no way to be resolved: completed -> promote;
                    // otherwise fall back to the previous pick.
                    match =
                        state.match?.restoreAfterProcessDeath(
                            completed = downloadState is Completed
                        ),
                )
            }
        taskList.forEach { (task, state) ->
            put(task, state)
            onTaskRestored(task)
        }
        // Only a restore of Running keeps progress above zero (and a user cancel
        // is recorded), so this cannot fire on a state that was already terminal.
        if (taskList.values.any { (it.downloadState as? Canceled)?.interrupted == true }) {
            interruptedRestore = true
        }
        return interrupted.keys.map { it.id }.toSet()
    }

    suspend fun persistBackupChanges(debounceMs: Long = BACKUP_DEBOUNCE_MS) {
        snapshotFlow.coalesceForBackup(debounceMs = debounceMs).collect { encodeBackup(it) }
    }
}
