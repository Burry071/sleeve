package com.junkfood.seal.download

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.download.Task.RestartableAction.Download
import com.junkfood.seal.download.Task.RestartableAction.FetchInfo
import com.junkfood.seal.download.Task.TypeInfo
import com.junkfood.seal.util.ArchiveIndex
import com.junkfood.seal.util.AudioVerify
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.DownloadErrorMessages
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.LiveGate
import com.junkfood.seal.util.M4bTagOverrides
import com.junkfood.seal.util.NetworkPolicy
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.Truncation
import com.junkfood.seal.util.VerifyResult
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.makeToast
import com.junkfood.seal.util.toDurationText
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent

private const val TAG = "DownloaderV2"

private const val MAX_CONCURRENCY = 3
private const val LOW_RAM_THRESHOLD_BYTES = 4L * 1024 * 1024 * 1024

internal fun maxConcurrencyForRam(totalRamBytes: Long, isLowRamDevice: Boolean): Int =
    if (isLowRamDevice || (totalRamBytes > 0L && totalRamBytes <= LOW_RAM_THRESHOLD_BYTES)) {
        2
    } else {
        MAX_CONCURRENCY
    }

/**
 * True when [path] refers to an existing, non-empty download output — either a
 * local file or a SAF `content://` document. Treating a `content://` URI as a
 * [File] always returns false, which broke swap validation and fallback retention
 * on SD-card storage.
 */
internal fun isValidOutputPath(
    path: String?,
    isLocalValid: (String) -> Boolean = { p -> File(p).let { it.isFile && it.length() > 0L } },
    isDocumentValid: (String) -> Boolean = { uri -> with(FileUtil) { uri.getFileSize() } > 0L },
): Boolean {
    if (path.isNullOrBlank()) return false
    return if (path.startsWith("content://")) isDocumentValid(path) else isLocalValid(path)
}

/** The provider the Audible gate and the post-download check both cover. */
private const val AUDIBLE_PROVIDER = "Audible"

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
 * TODO:
 *     - Notification
 *     - Custom commands
 *     - States for ViewModels
 */
/**
 * Deleting a live task must stop its worker first. Dropping the map entry alone
 * left the job running invisibly and its notification with nothing to clear it.
 */
internal fun needsCancelBeforeRemoval(state: Task.State): Boolean =
    state.downloadState is DownloadState.Cancelable

/**
 * A cancelable task clears its notification inside [cancelImpl]. Every other
 * state keeps a finished/failed notification whose actions would point at a
 * task that no longer exists after removal.
 */
internal fun clearsOwnNotificationOnRemoval(state: Task.State, usesOwnNotification: Boolean): Boolean =
    usesOwnNotification && state.downloadState !is DownloadState.Cancelable

/**
 * A metadata task normally resolves by searching the catalogue for its book or
 * track. A split part already carries the verified URL of one upload, so it
 * must go straight to extraction: re-running the search would gate that part as
 * if it were the whole book and hold it forever.
 */
internal val TypeInfo.MetadataTrack.needsSearch: Boolean
    get() = !directUrl

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

@OptIn(FlowPreview::class)
class DownloaderV2Impl(private val appContext: Context) : DownloaderV2, KoinComponent {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskStateMap = mutableStateMapOf<Task, Task.State>()
    private val maxConcurrency: Int by lazy { resolveMaxConcurrency(appContext) }

    override var interruptedRestore by mutableStateOf(false)
        private set

    override fun dismissInterruptedRestore() {
        interruptedRestore = false
    }

    private val snapshotFlow = snapshotFlow { taskStateMap.toMap() }

    /**
     * Serialises every read-modify-write of a [Task.State]. Workers run on
     * several dispatchers, so the transition guard and the copy it writes must
     * be one critical section or a late result can clobber a cancel.
     */
    private val stateLock = Any()

    /**
     * Tasks a relaunch owes a restart: the backup left them in flight, but the
     * network refused at boot. Kept until the network allows the transfer, so
     * a phone that came back on mobile data resumes on Wi-Fi instead of
     * silently spending bytes.
     */
    private val pendingResume: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    init {
        // Observation starts with the downloader. Idempotent: App.onCreate()
        // registers first, and this keeps a DownloaderV2 built in isolation
        // from silently missing live network state.
        NetworkPolicy.init(appContext)

        scope.launch(Dispatchers.Default) {
            snapshotFlow
                .onEach { state ->
                    // The scheduler must survive any single bad task: a cancel
                    // racing the pick or a removed task mid-callback must cost
                    // one tick, not the whole download pipeline.
                    runCatching { doYourWork() }
                        .onFailure { Log.e(TAG, "scheduler tick failed", it) }
                }
                .map { it.countRunning() }
                .distinctUntilChanged()
                .collect {
                    if (it > 0) {
                        // Some OEMs deny plain startService from background
                        // contexts; a throw here would kill the scheduler
                        // coroutine and stall every future download.
                        runCatching { App.startService() }
                            .onFailure { Log.e(TAG, "startService failed", it) }
                    } else {
                        runCatching { App.stopService() }
                            .onFailure { Log.e(TAG, "stopService failed", it) }
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

        scope.launch(Dispatchers.IO) {
            // don't write before we read
            enqueueFromBackup()
            // A restored batch is all Canceled/Idle, so no state write will
            // ever reach the notification hooks below. Without this refresh the
            // ongoing, non-dismissable notification of the killed process would
            // survive with stale progress: cancel it when nothing is active,
            // re-post it when the restore left an active song.
            taskStateMap.keys
                .mapNotNull { it.batch }
                .distinctBy { it.id }
                .forEach { updateBatchNotification(it) }

            snapshotFlow
                .map { state ->
                    // Completed downloads persist too: the user reopening the
                    // app must still see what they downloaded. Cap the backup
                    // so a long session cannot grow MMKV without bound.
                    val entries = state.entries.toList()
                    val active = entries.filter { it.value.downloadState !is Completed }
                    val completed =
                        entries.filter { it.value.downloadState is Completed }.takeLast(40)
                    (active + completed).associate { it.key to it.value }
                }
                .distinctUntilChangedBy { map ->
                    map.mapValues { (_, taskState) -> taskState.backupComparisonKey() }
                }
                .debounce(1_000L)
                .collect { PreferenceUtil.encodeTaskListBackup(it) }
        }
    }

    private fun enqueueFromBackup() {
        val backup = PreferenceUtil.decodeTaskListBackup()
        // Only a process kill stores a task in flight, so the entries below are
        // exactly the ones a relaunch may restart on its own. An entry already
        // persisted as Canceled was stopped by the user and stays stopped.
        val interrupted = backup.filterValues { TaskTransitions.resumesAfterProcessDeath(it.downloadState) }
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
        taskList.forEach { (task, state) -> enqueue(task, state) }
        // Only a restore of Running keeps progress above zero (and a user cancel
        // is recorded), so this cannot fire on a state that was already terminal.
        if (taskList.values.any { (it.downloadState as? Canceled)?.interrupted == true }) {
            interruptedRestore = true
        }
        interrupted.keys.map { it.id }.forEach(pendingResume::add)
        resumePending()
    }

    /**
     * Restarts what the backup left owed, but only when the network allows a
     * transfer: on mobile data without the opt-in the rows stay parked and the
     * network callback above finishes the job on Wi-Fi.
     */
    private fun resumePending() {
        if (pendingResume.isEmpty() || !NetworkPolicy.canStartDownloads()) return
        val due = pendingResume.toList().toSet()
        // Collect before writing: the restarts below change the same map this
        // reads, and a row removed since the kill simply owes nothing.
        val owed =
            taskStateMap.entries.filter { it.key.id in due }.map { (task, state) -> task to state.match }
        pendingResume.removeAll(due)
        owed.forEach { (task, match) ->
            // A held book has no verified source; its only exit is an explicit
            // pick, so an automatic restart would download the refused upload.
            if (match?.held == true || match?.pending == true) return@forEach
            task.restartImpl()
        }
    }

    private val Canceled.interrupted: Boolean
        get() = progress != null && progress > 0f && progress <= 1f && !byUser

    private fun Map<Task, Task.State>.countRunning(): Int = count { (_, state) ->
        state.downloadState is Running || state.downloadState is FetchingInfo
    }

    override fun getTaskStateMap(): SnapshotStateMap<Task, Task.State> {
        return taskStateMap
    }

    override fun enqueue(task: Task) {
        // A second share/confirm for a task already in flight must not reset it
        // to Idle: the scheduler would start a duplicate transfer over the
        // same output path while the first job keeps running. Terminal work is
        // a new submission and starts over.
        val existing =
            taskStateMap.entries.firstOrNull { (queued, _) -> queued.isSameWork(task) }?.value
        if (!TaskTransitions.enqueueReplaces(existing?.downloadState)) return
        taskStateMap[task] =
            Task.State(Idle, null, Task.ViewState(url = task.url, title = task.url))
        auditCookieState(task)
        // snapshotFlow only re-checks after an apply notification, and the
        // frame loop that sends them does not run when no UI is attached —
        // e.g. a share quick-download that starts with the app closed.
        Snapshot.sendApplyNotifications()
    }

    override fun enqueue(task: Task, state: Task.State) {
        taskStateMap += task to state
        auditCookieState(task)
        Snapshot.sendApplyNotifications()
    }

    /**
     * Noted the caller is responsible for stopping the [task] before removing it
     *
     * @return true if the task was removed
     */
    override fun remove(task: Task): Boolean {
        if (taskStateMap.contains(task)) {
            if (needsCancelBeforeRemoval(task.state)) {
                // cancelImpl stops the worker and clears the task's notification.
                task.cancelImpl()
            } else if (clearsOwnNotificationOnRemoval(task.state, task.usesOwnNotification)) {
                NotificationUtil.cancelNotification(task.notificationId)
            }
            taskStateMap.remove(task)
            TaskAuditLog.clear(task.id)
            searchOverrides.remove(task.id)
            // A swap in flight parks the file it would have replaced. Removing
            // the task drops every remaining claim on that file, so it must not
            // survive as an orphan: row first, then the file, exactly like a
            // completed swap.
            pendingSwapCleanup.remove(task.id)?.takeIf { it.isNotBlank() }?.let { orphaned ->
                scope.launch(Dispatchers.IO) {
                    DatabaseUtil.deleteInfoByPath(orphaned)
                    FileUtil.deleteFile(orphaned)
                        .onFailure {
                            Log.w(TAG, "could not delete orphaned swapped file: ${it.message}")
                        }
                }
            }
            Snapshot.sendApplyNotifications()
            task.batch?.let { updateBatchNotification(it) }
            NetworkPolicy.revokeMetered(setOf(task.id))
            return true
        }
        return false
    }

    override fun removeCompletedForPaths(paths: Set<String>): Int {
        if (paths.isEmpty()) return 0
        val matching =
            taskStateMap.keys.filter { task ->
                val filePath = (taskStateMap[task]?.downloadState as? Completed)?.filePath
                filePath != null && filePath in paths
            }
        return matching.count { remove(it) }
    }

    override fun redownload(task: Task): Boolean {
        if (!taskStateMap.containsKey(task)) return false
        val info = taskStateMap[task]?.videoInfo
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

    /**
     * A split book: no single upload is the book, so the held task is replaced
     * by one task per verified part, inside the same batch.
     *
     * Every part is a [TypeInfo.MetadataTrack] with
     * [TypeInfo.MetadataTrack.directUrl] set, so it downloads its own URL
     * instead of searching for the book again (and being held again). Its own
     * duration is carried, so the post-download check verifies this part, not
     * the book.
     *
     * The part tasks are built before the book task is removed: a failure while
     * building must not leave the batch without its container. The book task is
     * never downloaded through the pick the gate refused.
     */
    override fun enqueueParts(task: Task, parts: List<MatchCandidate>): Int {
        if (parts.isEmpty()) return 0
        val book = task.type as? TypeInfo.MetadataTrack ?: return 0
        // A part set lives in the book's batch; without one there is no group
        // to report progress or completion against, so nothing is invented.
        val batch = task.batch ?: return 0
        // A fetch or download in flight must not be torn out from under its
        // worker: a split plan replaces a held book, which is never active.
        val state = taskStateMap[task] ?: return 0
        if (state.downloadState is FetchingInfo || state.downloadState is Running) return 0

        val bookTitle = book.title.ifBlank { batch.title }
        val audioPreferences = task.preferences.copy(extractAudio = true)
        val partTasks =
            parts.mapIndexed { position, part ->
                val title = "$bookTitle — Part ${position + 1}"
                val metadata =
                    book.copy(
                        index = position,
                        provider = AUDIBLE_PROVIDER,
                        title = title,
                        durationMs = part.duration * 1000L,
                        directUrl = true,
                    )
                val partTask =
                    Task(
                        url = part.url,
                        type = metadata,
                        preferences = audioPreferences,
                        batch = batch,
                        origin = task.origin,
                    )
                partTask to
                    Task.State(
                        downloadState = Idle,
                        videoInfo = null,
                        viewState =
                            Task.ViewState(
                                url = part.url,
                                title = title,
                                uploader = book.artist,
                                duration = part.duration.toInt(),
                            ),
                    )
            }

        // Add the parts before dropping the held book: a process death between
        // the two leaves a duplicate review entry, not an empty batch.
        partTasks.forEach { (partTask, partState) -> enqueue(partTask, partState) }
        remove(task)
        return partTasks.size
    }

    override fun cancel(task: Task): Boolean =
        task.cancelImpl().also { canceled ->
            // A stopped task must not keep the metered permit it was approved
            // under; the rest of a card-scoped batch keeps its own.
            if (canceled) NetworkPolicy.revokeMetered(setOf(task.id))
        }

    override fun allowMeteredOnce(tasks: Set<String>) {
        super.allowMeteredOnce(tasks)
        // The scheduler only re-checks on a state change, and a parked task's
        // state does not change when a permit arrives, so tick it here.
        scope.launch(Dispatchers.Default) { doYourWork() }
    }

    override fun restart(task: Task) {
        task.restartImpl()
    }

    override fun searchAgain(task: Task, query: String): Boolean {
        val current = taskStateMap[task] ?: return false
        // Only a terminal row may be re-searched: a new search replaces the
        // match the worker may still be using.
        if (!TaskTransitions.acceptsSearchAgain(current.downloadState)) return false
        val terms = query.trim().takeIf { it.isNotBlank() } ?: return false
        if (task.type !is TypeInfo.MetadataTrack) return false
        searchOverrides[task.id] = terms
        TaskAuditLog.append(task.id, "search again: query=$terms")
        // Idle, not restartTarget: this must re-run the catalogue search, never
        // re-download the info a previous pick cached.
        val queued = applyTransition(task, DownloadState.Idle) { it == current.downloadState }
        if (!queued) searchOverrides.remove(task.id)
        return queued
    }

    /**
     * Re-fetches only the candidate metadata, never the search. The scheduler
     * then re-runs exactly the swapped songs; the old file is deleted only once
     * its replacement finishes.
     *
     * Nothing here deletes: a swap that fails or is interrupted must leave the
     * old file and its history row intact. Deletion lives in
     * [cleanupSwappedFile], after the replacement is verified.
     */
    override suspend fun swapMatches(
        selections: List<Pair<Task, MatchCandidate>>
    ): List<SwapResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<SwapResult>()
            selections
                .distinctBy { it.first.id }
                .forEach { (task, candidate) ->
                    // One broken selection costs its own status line, never the
                    // batch: the caller renders one row per result and an
                    // exception here would escape into the UI coroutine.
                    results +=
                        runCatching { swapOne(task, candidate) }
                            .getOrElse { failure ->
                                // Cancellation is not a failed swap: let it
                                // unwind the caller's scope.
                                if (failure is CancellationException) throw failure
                                Log.w(
                                    TAG,
                                    "${task.id} swap failed unexpectedly: ${failure.message}",
                                    failure,
                                )
                                swapResult(
                                    task,
                                    candidate,
                                    SwapOutcome.FetchFailed,
                                    shortReason(failure),
                                )
                            }
                }
            Snapshot.sendApplyNotifications()
            results
        }

    /**
     * One selection, start to finish. Returns the outcome; throws only on a bug,
     * which [swapMatches] turns into a [SwapOutcome.FetchFailed] line.
     */
    private suspend fun swapOne(task: Task, candidate: MatchCandidate): SwapResult {
        val current = taskStateMap[task] ?: return swapResult(task, candidate, SwapOutcome.Missing)
        // A matching or downloading song must not have its state torn out from
        // under the worker.
        if (current.downloadState is FetchingInfo || current.downloadState is Running) {
            return swapResult(task, candidate, SwapOutcome.Busy)
        }
        val match = current.match ?: return swapResult(task, candidate, SwapOutcome.Missing)
        App.enginesReady.await()
        val fetched =
            DownloadUtil.fetchVideoInfoFromUrl(
                url = candidate.url,
                preferences = task.preferences,
                taskKey = task.id,
            )
        val failure = fetched.exceptionOrNull()
        if (failure != null) {
            Log.w(TAG, "${task.id} swap fetch failed: ${failure.message}")
            // The old pick and its file were never touched; make sure a
            // half-applied pending flag cannot survive.
            if (match.pending) task.match = match.cancelPending()
            return swapResult(task, candidate, SwapOutcome.FetchFailed, shortReason(failure))
        }
        val info = fetched.getOrThrow()
        // A pick that resolves to a stream with nothing to save is refused
        // before anything is parked or overwritten: the previous pick, its
        // file and a pending flag all stay exactly as they were.
        val refusal = liveRefusal(info.liveStatus)
        if (refusal != null) return swapResult(task, candidate, SwapOutcome.Refused, refusal.message)
        // Park the file this pick replaces before the new state can let the
        // scheduler overwrite history. An empty path means there was nothing on
        // disk to delete on success.
        pendingSwapCleanup[task.id] = (current.downloadState as? Completed)?.filePath.orEmpty()
        // An explicit pick is the one way out of a gate hold, but the hold is
        // cleared only by confirmSelection() after the file exists. If the
        // pick fails, cancelPending() keeps held=true and the review entry
        // (and its picker) survives.
        task.match = match.withSelection(candidate)
        task.info = info
        task.viewState = Task.ViewState.fromVideoInfo(info)
        task.setLiveNotice(info.liveStatus)
        // The fetch took seconds; a cancel during it wins over the pick.
        val queued =
            applyTransition(task, ReadyWithInfo) { it == current.downloadState }
        if (!queued) {
            pendingSwapCleanup.remove(task.id)
            task.match?.takeIf { it.pending }?.let { task.match = it.cancelPending() }
            return swapResult(task, candidate, SwapOutcome.Busy)
        }
        return swapResult(task, candidate, SwapOutcome.Queued)
    }

    /**
     * Non-blank text for [Task.DownloadState.Error.message]. Error surfaces
     * prefer this field when `throwable.message` is blank, so a throwable that
     * carries no message must not leave it empty. A failed SD-card move gets
     * the counts in plain words, then the folder the files are still in.
     */
    private fun errorText(throwable: Throwable, friendly: Throwable): String {
        if (friendly is DownloadErrorMessages.StorageMoveError) {
            return appContext.getString(
                    R.string.error_sdcard_move_kept,
                    friendly.copied,
                    friendly.copied + friendly.failed.size,
                ) + (if (friendly.retainedDir.isBlank()) "" else "\n" + friendly.retainedDir)
        }
        return friendly.message?.takeIf { it.isNotBlank() }
            ?: throwable.message?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.unknown_error)
    }

    /** One short, user-safe line; never a stack trace. */
    private fun shortReason(throwable: Throwable): String =
        DownloadErrorMessages.asFriendly(throwable)
            .message
            .orEmpty()
            .lineSequence()
            .first()
            .take(160)

    /** One status line for the review sheet; the title falls back to the URL. */
    private fun swapResult(
        task: Task,
        candidate: MatchCandidate,
        outcome: SwapOutcome,
        reason: String = "",
    ): SwapResult =
        SwapResult(
            taskId = task.id,
            uiKey = task.uiKey,
            title =
                (task.type as? TypeInfo.MetadataTrack)?.title?.takeIf { it.isNotBlank() }
                    ?: task.url,
            candidateUrl = candidate.url,
            candidateTitle = candidate.title,
            outcome = outcome,
            reason = reason,
        )

    override fun acknowledgeMatches(batchId: String) {
        super.acknowledgeMatches(batchId)
        taskStateMap.keys
            .mapNotNull { it.batch }
            .distinctBy { it.id }
            .firstOrNull { it.id == batchId }
            ?.let { updateBatchNotification(it) }
    }

    // All state accessors tolerate removal: a cancel/remove racing a running
    // task used to throw (taskStateMap[this]!!) and could crash the scheduler
    // collector. Reads fall back to a fresh Idle state without writing; writes
    // to a removed task are dropped instead of resurrecting it in the map.
    private val Task.state: Task.State
        get() = taskStateMap[this] ?: Task.State(Idle, null, Task.ViewState(url = url, title = url))

    private val Task.downloadState: DownloadState
        get() = state.downloadState

    /**
     * The one writer for [Task.DownloadState]. [accept] sees the state as it is
     * under the lock, so a worker's late result is judged against the state a
     * cancel actually left behind. A refused or illegal edge is dropped and
     * logged; an accepted one notifies through [syncNotifications].
     */
    private fun applyTransition(
        task: Task,
        next: DownloadState,
        accept: (DownloadState) -> Boolean,
    ): Boolean {
        synchronized(stateLock) {
            val current = taskStateMap[task] ?: return false
            val from = current.downloadState
            if (!accept(from)) return false
            if (!TaskTransitions.isLegal(from, next)) {
                Log.w(TAG, "${task.id} illegal transition ${from.label} -> ${next.label}")
                return false
            }
            taskStateMap[task] = current.copy(downloadState = next)
        }
        Snapshot.sendApplyNotifications()
        runCatching { syncNotifications(task, next) }
            .onFailure { Log.e(TAG, "notification sync failed", it) }
        return true
    }

    /** Applies [transform] to the newest state under the lock, never a stale copy. */
    private fun Task.updateState(transform: (Task.State) -> Task.State): Boolean {
        synchronized(stateLock) {
            val current = taskStateMap[this] ?: return false
            taskStateMap[this] = transform(current)
        }
        Snapshot.sendApplyNotifications()
        return true
    }

    private var Task.info: VideoInfo?
        get() = state.videoInfo
        set(value) {
            updateState { it.copy(videoInfo = value) }
        }

    private var Task.viewState: Task.ViewState
        get() = state.viewState
        set(value) {
            updateState { it.copy(viewState = value) }
        }

    private var Task.match: MatchResult?
        get() = state.match
        set(value) {
            updateState { it.copy(match = value) }
        }

    /**
     * Notifications are a projection of the state, not a side effect each
     * writer must remember. One notification per task, one per batch.
     */
    private fun syncNotifications(task: Task, next: DownloadState) {
        if (task.usesOwnNotification) {
            when (next) {
                is Running -> {
                    val percent =
                        if (next.progress < 0f) 0 else (next.progress * 100f).toInt()
                    val template = (task.type as? TypeInfo.CustomCommand)?.template
                    if (template != null) {
                        NotificationUtil.makeNotificationForCustomCommand(
                            notificationId = task.notificationId,
                            taskId = task.id,
                            progress = percent,
                            text = next.progressText,
                            templateName = template.name,
                            taskUrl = task.url,
                        )
                    } else {
                        NotificationUtil.notifyProgress(
                            notificationId = task.notificationId,
                            progress = percent,
                            text = next.progressText,
                            title = task.viewState.title,
                            taskId = task.id,
                        )
                    }
                }
                is Completed -> {
                    // The tap intent needs a suspend lookup; the state is final
                    // here, so the notification may land a moment later.
                    scope.launch {
                        val tapIntent =
                            PendingIntent.getActivity(
                                appContext,
                                0,
                                FileUtil.createNotificationTapIntent(next.filePath),
                                PendingIntent.FLAG_IMMUTABLE,
                            )
                        NotificationUtil.finishNotification(
                            task.notificationId,
                            title = task.viewState.title,
                            text =
                                appContext.getString(
                                    if (next.filePath == null) R.string.status_completed
                                    else R.string.download_finish_notification
                                ),
                            intent = tapIntent,
                        )
                    }
                }
                is Error -> {
                    scope.launch {
                        // The cookie file is measured off the main thread. The
                        // status is never cached: importing cookies while this
                        // task keeps its error must flip the action to Retry.
                        val cookies =
                            withContext(Dispatchers.IO) { with(FileUtil) { appContext.readCookieStatus() } }
                        // A restart can replace the error while the file is
                        // read; the newer state posts its own notification.
                        if (taskStateMap[task]?.downloadState !is Error) return@launch
                        val loginWalled =
                            next.throwable is DownloadErrorMessages.LoginRequiredError
                        NotificationUtil.notifyError(
                            title = task.viewState.title,
                            textId =
                                if (next.action == FetchInfo) R.string.fetch_info_error_msg
                                else R.string.download_error_msg,
                            notificationId = task.notificationId,
                            // The friendly wrapper keeps the raw extractor failure
                            // as its cause, so the report keeps full detail.
                            report = next.throwable.stackTraceToString(),
                            url = task.url,
                            withCookieRetry = loginWalled && cookies.usable,
                            withAddCookies = loginWalled && !cookies.usable,
                            withExtractorRetry =
                                next.throwable is DownloadErrorMessages.ExtractorBrokenError,
                            retryTaskId = task.id,
                        )
                    }
                }
                is Canceled ->
                    // A cancel must clear the task's own notification; a stale
                    // one would offer Cancel on work that no longer exists.
                    NotificationUtil.cancelNotification(task.notificationId)
                Idle, ReadyWithInfo, is FetchingInfo -> {}
            }
        }
        task.batch?.let { updateBatchNotification(it) }
    }

    /** Runtime evidence that the quick fence held (or that a user task uses cookies). */
    private fun auditCookieState(task: Task) {
        val line = task.cookieAudit
        Log.i(TAG, "${task.id} $line")
        if (TaskAuditLog.read(task.id).none { it.startsWith("cookies:") }) {
            TaskAuditLog.append(task.id, line)
        }
    }

    /** Deletes the file a swapped pick replaced, but only after the new file is complete. */
    private val pendingSwapCleanup = ConcurrentHashMap<String, String>()

    /**
     * Search terms the user typed for a "Search again" row, by task id. Process
     * local: they matter only while this app run is resolving that row.
     */
    private val searchOverrides = ConcurrentHashMap<String, String>()

    /** Suffix source for yt-dlp process ids; never reused within this process. */
    private val processSequence = AtomicLong()

    /**
     * A fresh yt-dlp process id for every attempt. The vendor keeps process ids
     * in a map and a dead-but-unreaped entry makes that id permanently unusable
     * for the rest of the app process, so a retry must never reuse it.
     */
    private fun nextProcessId(taskId: String): String =
        "$taskId:${processSequence.incrementAndGet()}"

    private val Task.notificationId: Int
        get() = id.hashCode()

    /**
     * One playlist notification per batch. Shown while any song is matching or
     * downloading, removed as soon as none is — including on stop and dismiss.
     */
    private fun updateBatchNotification(batch: BatchInfo) {
        val notificationId = batch.id.hashCode()
        val entries = BatchOps.entries(taskStateMap, batch.id)
        // Once the batch is done, a flagged pick replaces the progress
        // notification with the single review entry.
        if (MatchOps.reviewVisible(entries)) {
            NotificationUtil.notifyBatchReview(
                title = batch.title,
                text = appContext.getString(R.string.batch_notification_review, MatchOps.reviewCount(entries)),
                notificationId = notificationId,
                batchId = batch.id,
            )
            return
        }
        val state = BatchOps.notificationState(BatchOps.summary(entries))
        if (state == null) {
            NotificationUtil.cancelNotification(notificationId)
            return
        }
        NotificationUtil.notifyBatch(
            title = batch.title,
            text =
                appContext.getString(
                    R.string.batch_notification_progress,
                    state.completed,
                    state.total,
                ),
            progress = state.progress,
            notificationId = notificationId,
        )
    }

    private fun resolveMaxConcurrency(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return MAX_CONCURRENCY
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return maxConcurrencyForRam(memInfo.totalMem, am.isLowRamDevice)
    }

    private fun isDocumentOutputValid(uri: String): Boolean =
        with(FileUtil) { uri.getFileSize() } > 0L ||
            runCatching {
                    appContext.contentResolver
                        .openAssetFileDescriptor(Uri.parse(uri), "r")
                        ?.use { it.length > 0L } == true
                }
                .getOrDefault(false)

    /**
     * Called after a song completes: promote the pending pick to the real one
     * and drop the file (and history row) it replaced.
     *
     * Order is the contract: the replacement must exist and be non-empty first;
     * only then are the old row and file deleted, together. A swap that produced
     * no file cancels the pending pick and keeps both, so the user never loses
     * the download they already had.
     */
    private suspend fun cleanupSwappedFile(taskId: String, newPath: String?) {
        // Null means no swap was registered in this process (the pending pick
        // was restored after a restart): there is no parked path to delete,
        // but a verified replacement still promotes the pending pick.
        val oldPath = pendingSwapCleanup.remove(taskId)
        val task = taskStateMap.keys.firstOrNull { it.id == taskId }
        if (!isValidOutputPath(newPath, isDocumentValid = ::isDocumentOutputValid)) {
            Log.w(TAG, "swap for $taskId produced no file; keeping the old pick")
            task?.match?.takeIf { it.pending }?.let { task.match = it.cancelPending() }
            return
        }
        if (!oldPath.isNullOrBlank() && oldPath != newPath) {
            // Row first: a crash between the two leaves an orphan file (harmless)
            // instead of a history row pointing at a deleted file.
            DatabaseUtil.deleteInfoByPath(oldPath)
            FileUtil.deleteFile(oldPath)
                .onFailure { Log.w(TAG, "could not delete replaced file: ${it.message}") }
        }
        task?.match?.takeIf { it.pending }?.let { task.match = it.confirmSelection() }
    }

    /**
     * A swapped download failed: drop the parked path and put the old pick
     * back. The old file and its history row were never touched, so they stay.
     */
    private fun abandonSwappedFile(taskId: String) {
        if (pendingSwapCleanup.remove(taskId) == null) return
        val task = taskStateMap.keys.firstOrNull { it.id == taskId } ?: return
        task.match?.takeIf { it.pending }?.let { task.match = it.cancelPending() }
    }

    /**
     * The direct/CDN lane synthesizes [VideoInfo] before any byte is fetched,
     * so duration and exact size are unknown up to this point. Once the file
     * exists they are measurable — fill them in instead of leaving the card
     * on a "0.00 MB · 00:00" placeholder.
     *
     * @return the measured length in whole seconds, 0 when it could not be read
     */
    private fun Task.enrichWithCompletedFile(path: String): Int {
        // A SAF-folder finish line names a document, not a file path: probing
        // it through the resolver keeps the duration fill working there too.
        val isDocument = path.startsWith("content://")
        val length =
            if (isDocument) {
                runCatching {
                        appContext.contentResolver
                            .openAssetFileDescriptor(Uri.parse(path), "r")
                            ?.use { it.length }
                    }
                    .getOrNull()
                    ?.takeIf { it > 0 } ?: 0L
            } else {
                File(path).takeIf { it.isFile }?.length() ?: return 0
            }
        val probedDuration = probeSeconds(path, isDocument)
        viewState =
            viewState.copy(
                duration = viewState.duration.takeIf { it > 0 } ?: probedDuration,
                fileSizeApprox = length.takeIf { it > 0 }?.toDouble() ?: viewState.fileSizeApprox,
            )
        return probedDuration
    }

    private fun probeSeconds(path: String, isDocument: Boolean): Int =
        runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    if (isDocument) {
                        retriever.setDataSource(appContext, Uri.parse(path))
                    } else {
                        retriever.setDataSource(File(path).absolutePath)
                    }
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.div(1000L)
                        ?.toInt()
                } finally {
                    retriever.release()
                }
            }
            .getOrNull() ?: 0

    /**
     * Wakes the scheduler when a network becomes usable again. Calling
     * [doYourWork] directly is required: `Snapshot.sendApplyNotifications()`
     * alone does not re-emit `snapshotFlow` when the map did not change.
     */
    private fun kickFromNetwork() = doYourWork()

    /** Processor time of the last "parked on a metered network" log line. */
    @Volatile private var lastMeteredLogAt = 0L

    /** Processes pending tasks, prioritizing downloads. */
    private fun doYourWork() {
        // The one enforcement point for the metered gate. It sits here, above
        // the yt-dlp/aria2/Ghost dispatch, so every lane shares it and a
        // transfer already in flight is never interrupted mid-stream.
        if (!NetworkPolicy.canStartDownloads()) {
            val now = System.currentTimeMillis()
            if (now - lastMeteredLogAt > 30_000L) {
                lastMeteredLogAt = now
                Log.i(TAG, "queue parked: metered network, no permit")
            }
            return
        }
        if (taskStateMap.countRunning() >= maxConcurrency) return

        taskStateMap.entries
            .sortedBy { (_, state) -> state.downloadState.schedulerRank }
            .firstOrNull { (task, state) ->
                (state.downloadState == ReadyWithInfo || state.downloadState == Idle) &&
                    // A permit granted from one card names its tasks: another
                    // parked burst must not ride it.
                    NetworkPolicy.canStartTask(task.id)
            }
            ?.let { (task, state) ->
                // The picked state may already be stale (cancel/remove raced
                // the pick); prepare/download re-check and no-op in that case.
                when (state.downloadState) {
                    Idle -> task.prepare()
                    ReadyWithInfo -> task.download()
                    else -> {}
                }
            }
    }

    private fun Task.prepare() {
        // A cancel/remove can race the scheduler pick; a stale pick no-ops.
        if (downloadState != Idle) return
        auditCookieState(this)
        if (type is TypeInfo.CustomCommand) {
            execute()
            return
        }
        val track = type as? TypeInfo.MetadataTrack
        if (track != null) {
            if (track.needsSearch) {
                resolveMetadata(track)
            } else {
                // A split part carries the verified URL of one upload: there
                // is no catalogue entry to search and no page to sniff, so it
                // is extracted directly whatever the gallery preferences say.
                fetchInfo()
            }
            return
        }
        if (
            preferences.galleryDirect ||
            (DownloadUtil.isDirectMediaUrl(url) &&
                (!preferences.extractAudio || DownloadUtil.isDirectImageUrl(url)))
        ) {
            // The URL is already the media file (gallery/embed/sniff item, or
            // a bare CDN link whose generic round-trip only adds a failing
            // extraction before the same fallback below). Audio-extraction
            // requests on extractable video stay on yt-dlp: the ghost handoff
            // cannot remux.
            val job =
                scope.launch(Dispatchers.IO, CoroutineStart.LAZY) {
                    val runJob = coroutineContext[Job]
                    val synth =
                        DownloadUtil.buildDirectVideoInfo(
                            url = url,
                            // Blank lets the builder derive a real name from the URL
                            // path instead of showing the whole URL as the title.
                            title = preferences.newTitle,
                        )
                    if (synth != null) {
                        info = synth
                        viewState = Task.ViewState.fromVideoInfo(synth)
                        // No explicit download() here: writing ReadyWithInfo
                        // wakes doYourWork, which starts the download. Calling
                        // it from inside the job raced the scheduler's pick of
                        // the same ReadyWithInfo state and ran downloadVideo
                        // twice per task (two concurrent Ghost transfers, one
                        // orphaned and uncancellable — observed on hardware).
                        applyTransition(
                            this@prepare,
                            ReadyWithInfo,
                        ) { it is FetchingInfo && it.job === runJob }
                    } else {
                        // Fail closed when buildDirectVideoInfo rejects an
                        // unsafe URL instead of falling through to unguarded
                        // yt-dlp extraction.
                        val throwable = IllegalArgumentException("Blocked unsafe direct URL")
                        val friendly = DownloadErrorMessages.asFriendly(throwable)
                        applyTransition(
                            this@prepare,
                            Error(
                                throwable = friendly,
                                action = FetchInfo,
                                message = errorText(throwable, friendly),
                                reason = DownloadErrorMessages.errorReasonOf(friendly),
                            ),
                        ) { it is FetchingInfo && it.job === runJob }
                    }
                }
            // Only a task still waiting for its info may begin fetching;
            // a cancel that landed first leaves it untouched.
            if (
                applyTransition(
                    this,
                    FetchingInfo(job = job, taskId = id),
                ) { it == Idle }
            ) {
                job.start()
            }
            return
        }
        fetchInfo()
    }

    /**
     * A stream that cannot produce a file yet as a task state; null when the
     * item may proceed. Refused before any media request: the retry that
     * succeeds is the ordinary Error action, not a special one.
     */
    private fun liveRefusal(liveStatus: String?): Error? {
        val refuse = LiveGate.decide(liveStatus) as? LiveGate.Decision.Refuse ?: return null
        val error = DownloadErrorMessages.NotReadyError(refuse.reasonRes)
        return Error(
            throwable = error,
            action = FetchInfo,
            message = appContext.getString(refuse.reasonRes),
            reason = DownloadErrorMessages.errorReasonOf(error),
        )
    }

    /** The one-line notice a live capture carries while it saves what has aired. */
    private fun Task.setLiveNotice(liveStatus: String?) {
        val allow = LiveGate.decide(liveStatus) as? LiveGate.Decision.Allow ?: return
        allow.warningRes?.let { res -> updateState { it.copy(notice = appContext.getString(res)) } }
    }

    private fun Task.fetchInfo() {
        if (downloadState != Idle) return
        val task = this
        val taskInfo = task.type
        val playlistIndex = if (taskInfo is TypeInfo.Playlist) taskInfo.index else null
        val job =
            scope.launch(Dispatchers.IO, CoroutineStart.LAZY) {
                val runJob = coroutineContext[Job]
                val isThisAttempt = { state: DownloadState ->
                    state is FetchingInfo && state.job === runJob
                }
                App.enginesReady.await()
                DownloadUtil.fetchVideoInfoFromUrl(
                        url = url,
                        playlistIndex = playlistIndex,
                        preferences = preferences,
                        taskKey = id,
                    )
                    // Ahead of onSuccess on purpose: a throw inside onSuccess
                    // escapes the chain without reaching the chained onFailure.
                    .mapCatching { info ->
                        PlaylistPin.verify(state.expectedVideoId, info.id)
                        info
                    }
                    .onSuccess {
                        info = it
                        viewState = Task.ViewState.fromVideoInfo(it)
                        val refusal = liveRefusal(it.liveStatus)
                        if (refusal != null) {
                            applyTransition(task, refusal, isThisAttempt)
                        } else {
                            setLiveNotice(it.liveStatus)
                            // A cancel can land while extraction is finishing; the
                            // cancel wins and the late result is dropped.
                            applyTransition(task, ReadyWithInfo, isThisAttempt)
                        }
                    }
                    .onFailure { throwable ->
                        if (throwable is YoutubeDL.CanceledException) {
                            return@onFailure
                        }
                        if (preferences.galleryDirect) {
                            // gallery-dl/sniffed item: skip extraction, feed the
                            // raw media URL to the engine handoff pipeline.
                            val synth =
                                DownloadUtil.buildDirectVideoInfo(
                                    url = url,
                                    title = preferences.newTitle,
                                )
                            if (synth != null) {
                                info = synth
                                viewState = Task.ViewState.fromVideoInfo(synth)
                                // Same rule as prepare(): the ReadyWithInfo
                                // write is the trigger — doYourWork starts the
                                // download exactly once.
                                applyTransition(task, ReadyWithInfo, isThisAttempt)
                                return@onFailure
                            }
                        }
                        val friendly = DownloadErrorMessages.asFriendly(throwable)
                        applyTransition(
                            task,
                            Error(
                                throwable = friendly,
                                action = FetchInfo,
                                message = errorText(throwable, friendly),
                                reason = DownloadErrorMessages.errorReasonOf(friendly),
                            ),
                            isThisAttempt,
                        )
                    }
            }
        if (applyTransition(this, FetchingInfo(job = job, taskId = id)) { it == Idle }) {
            job.start()
        }
    }

    /**
     * Metadata songs resolve their YouTube counterpart here instead of in the
     * sheet. The lifecycle mirrors fetchInfo(): matching is FetchingInfo, and the
     * ReadyWithInfo write is what wakes the scheduler to start the download.
     */
    private fun Task.resolveMetadata(track: TypeInfo.MetadataTrack) {
        if (downloadState != Idle) return
        val task = this
        val job =
            scope.launch(Dispatchers.IO, CoroutineStart.LAZY) {
                val runJob = coroutineContext[Job]
                val isThisAttempt = { state: DownloadState ->
                    state is FetchingInfo && state.job === runJob
                }
                App.enginesReady.await()
                DownloadUtil.resolveMetadataTrack(
                        metadata = track,
                        taskKey = id,
                        preferences = preferences,
                        // A user-edited "Search again" query wins over the
                        // catalogue-built one for the rest of this run.
                        queryOverride = searchOverrides[id].orEmpty(),
                        // A cancel or removal flips this false, so matching stops
                        // before spawning another yt-dlp process.
                        isActive = {
                            (taskStateMap[task]?.downloadState as? FetchingInfo)?.job === runJob
                        },
                    )
                    .onSuccess { resolved ->
                        info = resolved.videoInfo
                        viewState = Task.ViewState.fromVideoInfo(resolved.videoInfo)
                        match = resolved.match
                        setLiveNotice(resolved.videoInfo.liveStatus)
                        // A held book finishes without ReadyWithInfo, so the
                        // scheduler never spends a byte on an unverified pick.
                        // A cancel that landed first wins and drops this.
                        applyTransition(
                            task,
                            MatchOps.resolvedState(resolved.match, resolved.videoInfo.liveStatus),
                            isThisAttempt,
                        )
                    }
                    .onFailure { throwable ->
                        if (throwable is YoutubeDL.CanceledException) return@onFailure
                        val friendly = DownloadErrorMessages.asFriendly(throwable)
                        applyTransition(
                            task,
                            Error(
                                throwable = friendly,
                                action = FetchInfo,
                                message = errorText(throwable, friendly),
                                reason = DownloadErrorMessages.errorReasonOf(friendly),
                            ),
                            isThisAttempt,
                        )
                    }
            }
        if (applyTransition(this, FetchingInfo(job = job, taskId = id)) { it == Idle }) {
            job.start()
        }
    }

    /**
     * Phase 1b safety net for an Audible book: measure the file that actually
     * landed against the catalogue runtime. Returns null for every other task;
     * there is nothing to prove.
     *
     * A mismatch writes the measured duration, re-opens the review entry through
     * the columns phase 1a already renders, and keeps the file: a short book the
     * user can still play beats deleting a download they waited for.
     *
     * [localDurationMs] is the measurement the finish line already took while
     * the file was local — the only one possible for an SD-card task, whose
     * published path is a `content://` URI no retriever can open.
     */
    private suspend fun Task.verifyAudibleDuration(
        path: String,
        localDurationMs: Long,
    ): VerifyResult? {
        val track = type as? TypeInfo.MetadataTrack ?: return null
        if (!track.provider.equals(AUDIBLE_PROVIDER, ignoreCase = true)) return null
        if (track.durationMs <= 0L) return null
        val result =
            when {
                localDurationMs > 0L -> AudioVerify.verifyMeasured(localDurationMs, track.durationMs)
                // Moved to the SD card and never measured there: nothing left
                // this process can read, so no verdict is better than a false one.
                path.startsWith("content://") -> return null
                else ->
                    withContext(Dispatchers.IO) {
                        AudioVerify.verifyDuration(appContext, File(path), track.durationMs)
                    }
            }
        val verdict = if (result.ok) "ok" else "mismatch"
        val measured = "${result.actualMs / 1000L}s of ${result.expectedMs / 1000L}s"
        val line = "verify: $verdict: ${result.reason.ifBlank { "within tolerance" }} ($measured)"
        Log.i(TAG, "$id $line")
        TaskAuditLog.append(id, line)
        match =
            if (result.ok) {
                match?.copy(actualDurationMs = result.actualMs)
            } else {
                match?.copy(
                    flagged = true,
                    // A failed check outranks a swap confirmation: the entry
                    // must stay visible until the user picks again.
                    acknowledged = false,
                    held = false,
                    actualDurationMs = result.actualMs,
                    gateReason = result.reason,
                )
            }
        return result
    }

    /**
     * Catalogue tags for the export, when the file came from a matched metadata
     * track. A blank field stays blank: an absent album is never filled with the
     * provider's name.
     */
    private fun TypeInfo.MetadataTrack.toM4bTagOverrides(): M4bTagOverrides =
        M4bTagOverrides(
            artist = artist.trim().ifBlank { null },
            albumArtist = authors.trim().ifBlank { null },
            album = album.trim().ifBlank { null },
            // The catalogue carries no track number; the position in the batch is
            // the only number there is. A lone book is not "track 1".
            track = if (index > 0) index + 1 else null,
        )

    private fun Task.download() {
        if (downloadState != ReadyWithInfo || info == null) return
        if (type is TypeInfo.CustomCommand) {
            execute()
            return
        }
        val task = this
        // LAZY + state-before-start: with eager launch the download body can
        // begin (and its first progress write can trigger a snapshot emission)
        // while this task still reads as ReadyWithInfo, and doYourWork then
        // launches a second downloadVideo for the same task.
        scope
            .launch(Dispatchers.IO, CoroutineStart.LAZY) {
                val runJob = coroutineContext[Job]
                // Cancel must win even when the state has already advanced to a
                // new attempt id: the job itself is the authority on liveness.
                val isActive = {
                    runJob?.isActive == true && (downloadState as? Running)?.job === runJob
                }
                // The finish line reports two things back before the card is
                // written: one local duration measurement (the Audible check
                // needs it) and any rider on an otherwise successful download.
                var localDurationMs = 0L
                val completedNotes = mutableListOf<CompletedNote>()
                val m4bTagOverrides = (type as? TypeInfo.MetadataTrack)?.toM4bTagOverrides()
                App.enginesReady.await()
                DownloadUtil.downloadVideo(
                        videoInfo = info,
                        taskId = id,
                        downloadPreferences = preferences,
                        processIdFactory = { nextProcessId(id) },
                        onProcessStarted = { processId ->
                            val current = downloadState
                            if (current is Running && current.taskId != processId) {
                                applyTransition(task, current.copy(taskId = processId)) {
                                    it is Running && it.job === runJob
                                }
                            }
                        },
                        isActive = isActive,
                        m4bTagOverrides = m4bTagOverrides,
                        onNote = { completedNotes += it },
                        onLocalDurationMs = { localDurationMs = it },
                        progressCallback = { progressPercentage, _, text ->
                            val progress = progressPercentage / 100f
                            val preState = downloadState
                            if (preState is Running) {
                                applyTransition(
                                    task,
                                    preState.copy(progress = progress, progressText = text),
                                ) { it is Running && it.job === runJob }
                            }
                        },
                    )
                    // aria2c is the optional accelerator, never a requirement:
                    // some hosts (generic direct links above all) fail under
                    // the external downloader, so the first aria2c failure
                    // retries once with yt-dlp's built-in one.
                    .recoverCatching { throwable ->
                        val retryable =
                            preferences.aria2c &&
                                throwable.message?.contains("aria2c", ignoreCase = true) == true
                        if (!retryable) throw throwable
                        Log.w(TAG, "aria2c failed, retrying with the built-in downloader: ${throwable.message?.take(120)}")
                        DownloadUtil.downloadVideo(
                                videoInfo = info,
                                taskId = id,
                                downloadPreferences = preferences.copy(aria2c = false),
                                processIdFactory = { nextProcessId(id) },
                                onProcessStarted = { processId ->
                                    val current = downloadState
                                    if (current is Running && current.taskId != processId) {
                                        applyTransition(task, current.copy(taskId = processId)) {
                                            it is Running && it.job === runJob
                                        }
                                    }
                                },
                                isActive = isActive,
                                m4bTagOverrides = m4bTagOverrides,
                                onNote = { completedNotes += it },
                                onLocalDurationMs = { localDurationMs = it },
                                progressCallback = { progressPercentage, _, text ->
                                    val progress = progressPercentage / 100f
                                    val preState = downloadState
                                    if (preState is Running) {
                                        applyTransition(
                                            task,
                                            preState.copy(
                                                progress = progress,
                                                progressText = text,
                                            ),
                                        ) { it is Running && it.job === runJob }
                                    }
                                },
                            )
                            .getOrThrow()
                    }
                    .onSuccess { pathList ->
                        val newPath = pathList.firstOrNull()
                        // Read before enrichment: it replaces an unknown (0)
                        // duration with the probe, which is the short number
                        // the comparison would then measure against itself.
                        val expectedMs = info?.duration?.toLong()?.times(1000L) ?: 0L
                        val live = state.notice != null
                        val probed = newPath?.let { enrichWithCompletedFile(it) } ?: 0
                        val truncation =
                            Truncation.warningRes(
                                probedSeconds = probed.toLong(),
                                expectedMs = expectedMs,
                                live = live,
                                clips = preferences.videoClips.isNotEmpty(),
                                split = preferences.splitByChapter,
                            )
                        // Post-download safety net first: the gate proves the
                        // pick, this proves the file that actually landed. A
                        // failed check must not delete the old file/row a swap
                        // was about to replace.
                        val verify = newPath?.let { verifyAudibleDuration(it, localDurationMs) }
                        if (verify != null && !verify.ok) {
                            // Keep the old pick/file/row; drop the parked path
                            // and make the short replacement a reviewable warning.
                            val previousPath = pendingSwapCleanup[id]
                            abandonSwappedFile(id)
                            val keepPath =
                                previousPath?.takeIf {
                                    isValidOutputPath(it, isDocumentValid = ::isDocumentOutputValid)
                                } ?: newPath
                            val warning =
                                if (keepPath != newPath) {
                                    "replacement rejected: ${verify.reason}; kept the previous file"
                                } else {
                                    verify.reason
                                }
                            match =
                                match?.copy(
                                    flagged = true,
                                    acknowledged = false,
                                    held = false,
                                    actualDurationMs = verify.actualMs,
                                    gateReason = verify.reason,
                                )
                            applyTransition(
                                task,
                                Completed(
                                    keepPath,
                                    warning = warning,
                                    notes = completedNotes.distinct(),
                                ),
                            ) { it is Running && it.job === runJob }
                        } else {
                            cleanupSwappedFile(id, newPath)
                            val warning =
                                truncation?.let {
                                    appContext.getString(
                                        it,
                                        probed.toDurationText(),
                                        (expectedMs / 1000L).toInt().toDurationText(),
                                    )
                                }.orEmpty()
                            applyTransition(
                                task,
                                Completed(
                                    newPath,
                                    warning = warning,
                                    notes = completedNotes.distinct(),
                                ),
                            ) { it is Running && it.job === runJob }
                        }
                    }
                    .onFailure { throwable ->
                        if (
                            throwable is YoutubeDL.CanceledException ||
                                throwable is CancellationException
                        ) {
                            return@onFailure
                        }
                        // A failed replacement keeps the old file and row; the
                        // pending pick must not stay "Replacing…" forever.
                        val previousPath = pendingSwapCleanup[id]
                        abandonSwappedFile(id)
                        val friendly = DownloadErrorMessages.asFriendly(throwable)
                        val keptPath =
                            previousPath?.takeIf {
                                isValidOutputPath(it, isDocumentValid = ::isDocumentOutputValid)
                            }
                        if (keptPath != null) {
                            // The file the user already had is still on disk.
                            // Reporting a bare failure would hide a download
                            // that exists, so this completes with a warning and
                            // stays reviewable: the failed pick is named, and
                            // the row and the batch count agree with the disk.
                            val reason = shortReason(throwable)
                            match =
                                match?.copy(
                                    flagged = true,
                                    acknowledged = false,
                                    gateReason = reason,
                                )
                            applyTransition(
                                task,
                                Completed(
                                    keptPath,
                                    warning = "replacement failed: $reason; kept the previous file",
                                    notes = completedNotes.distinct(),
                                ),
                            ) { it is Running && it.job === runJob }
                        } else {
                            applyTransition(
                                task,
                                Error(
                                    throwable = friendly,
                                    action = Download,
                                    message = errorText(throwable, friendly),
                                    reason = DownloadErrorMessages.errorReasonOf(friendly),
                                ),
                            ) { it is Running && it.job === runJob }
                        }
                    }
            }
            .also { job ->
                // Start only when this task is still waiting to download: a
                // cancel that raced the pick must not launch a worker whose
                // result the guard would then drop.
                if (
                    applyTransition(task, Running(job = job, taskId = id)) {
                        it is ReadyWithInfo
                    }
                ) {
                    job.start()
                }
            }
    }

    private fun Task.cancelImpl(): Boolean {
        val preState = downloadState
        val next = TaskTransitions.cancelTarget(preState) ?: return false
        // Any of the three workers may own this task: a yt-dlp process, a
        // Ghost engine transfer, or an N_m3u8DL-RE run. yt-dlp is keyed by the
        // current attempt id; the embedded engines are keyed by the stable
        // task id. Missing ones just report false; none may block the cancel.
        val cancelable = preState as? DownloadState.Cancelable
        val killed =
            cancelable != null &&
                (YoutubeDL.destroyProcessById(cancelable.taskId) ||
                    DownloadUtil.cancelGhostTask(id) ||
                    DownloadUtil.cancelNm3u8DlRe(id))
        cancelable?.job?.cancel()
        // Cancel every unfinished state, but keep what a worker already proved:
        // a job that finished between the snapshot and the kill owns a terminal
        // state the guard refuses to overwrite. A still-cancelable state of the
        // same run is ours even when its attempt id changed while the kill ran.
        applyTransition(this, next) { current ->
            when (current) {
                Idle, ReadyWithInfo -> true
                is DownloadState.Cancelable ->
                    killed || cancelable == null || current.job === cancelable.job
                else -> false
            }
        }
        return true
    }

    private fun Task.restartImpl() {
        val preState = downloadState
        // A held book has no verified full-length source. Restarting it would
        // download the upload the gate refused, so the explicit pick in the
        // review sheet is the only way out of a hold.
        if (
            preState is DownloadState.Restartable &&
                preState.action == Download &&
                match?.held == true
        ) {
            TaskAuditLog.append(
                id,
                "gate: restart refused — pick a candidate in Review matches",
            )
            // A silent no-op reads as a broken button; say where the hold can
            // be resolved.
            scope.launch {
                withContext(Dispatchers.Main) {
                    appContext.makeToast(R.string.match_held_resume_hint)
                }
            }
            return
        }
        // A double-tap on restart can race the first state change; the guard
        // refuses the stale second tap instead of overwriting the new state.
        val next = TaskTransitions.restartTarget(preState, type is TypeInfo.CustomCommand)
        if (next != null) applyTransition(this, next) { it == preState }
    }

    /**
     * Execute a custom command task
     *
     * @see Task.TypeInfo.CustomCommand
     */
    private fun Task.execute() {
        if (downloadState != Idle) return
        check(type is TypeInfo.CustomCommand)
        val template = type.template
        val task = this
        val job =
            scope
                .launch(Dispatchers.IO, CoroutineStart.LAZY) {
                    val runJob = coroutineContext[Job]
                    val isThisAttempt = { state: DownloadState ->
                        state is Running && state.job === runJob
                    }
                    App.enginesReady.await()
                    DownloadUtil.executeCustomCommandTask(url, id, template, preferences) {
                            progressPercentage,
                            _,
                            text ->
                            TaskOutputLog.append(id, text)
                            val preState = downloadState
                            if (preState is Running) {
                                applyTransition(
                                    task,
                                    preState.copy(
                                        progress = progressPercentage / 100f,
                                        progressText = text,
                                    ),
                                    isThisAttempt,
                                )
                            }
                        }
                        .onFailure { throwable ->
                            if (throwable is YoutubeDL.CanceledException) {
                                return@onFailure
                            }
                            // A failed command usually dies before printing the
                            // reason to stdout, so the log keeps the exception
                            // itself. The card still shows the friendly text.
                            TaskOutputLog.append(id, throwable.stackTraceToString())
                            val friendly = DownloadErrorMessages.asFriendly(throwable)
                            applyTransition(
                                task,
                                Error(
                                    throwable = friendly,
                                    action = Download,
                                    message = errorText(throwable, friendly),
                                    reason = DownloadErrorMessages.errorReasonOf(friendly),
                                ),
                                isThisAttempt,
                            )
                        }
                        .onSuccess { response ->
                            // yt-dlp printed nothing on this run (`--quiet`,
                            // a cached file): keep the command's own reply so
                            // the log is never silently empty.
                            if (TaskOutputLog.isEmpty(id)) {
                                TaskOutputLog.append(id, response.out + "\n" + response.err)
                            }
                            // The command lane talks to yt-dlp directly, so
                            // nothing else tells the gallery about the file.
                            preferences.commandDirectory
                                .takeIf { it.isNotEmpty() }
                                ?.let { FileUtil.scanDownloadDirectoryToMediaLibrary(it) }
                            applyTransition(task, Completed(null), isThisAttempt)
                        }
                }
        // State before start: the worker only acts while this attempt is the
        // Running state, so a cancel that landed first leaves it untouched.
        if (applyTransition(this, Running(job = job, taskId = id)) { it == Idle }) {
            job.start()
        }
    }
}
