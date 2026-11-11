package com.junkfood.seal.download

import android.app.PendingIntent
import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
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
import com.junkfood.seal.util.AudioVerify
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.DownloadErrorMessages
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.NetworkPolicy
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.VerifyResult
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.makeToast
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent

private const val TAG = "DownloaderV2"

private const val MAX_CONCURRENCY = 3

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

@OptIn(FlowPreview::class)
class DownloaderV2Impl(private val appContext: Context) : DownloaderV2, KoinComponent {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskStateMap = mutableStateMapOf<Task, Task.State>()
    private val snapshotFlow = snapshotFlow { taskStateMap.toMap() }

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
            scope.launch(Dispatchers.Default) { kickFromNetwork() }
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
                .distinctUntilChanged()
                .collect { PreferenceUtil.encodeTaskListBackup(it) }
        }
    }

    private fun enqueueFromBackup() {
        val taskList =
            PreferenceUtil.decodeTaskListBackup()
                .mapValues { (_, state) ->
                    val preState = state.downloadState
                    val downloadState =
                        when (preState) {
                            // Completed downloads keep their state and show
                            // as Downloaded cards after a restart.
                            is Completed -> preState
                            is FetchingInfo,
                            Idle -> {
                                Canceled(action = FetchInfo)
                            }
                            is Running -> {
                                Canceled(action = Download, progress = preState.progress)
                            }

                            ReadyWithInfo -> {
                                Canceled(action = Download, progress = null)
                            }
                            else -> {
                                preState
                            }
                        }
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
    }

    private fun Map<Task, Task.State>.countRunning(): Int = count { (_, state) ->
        state.downloadState is Running || state.downloadState is FetchingInfo
    }

    override fun getTaskStateMap(): SnapshotStateMap<Task, Task.State> {
        return taskStateMap
    }

    override fun enqueue(task: Task) {
        // A second share/confirm for a task already in flight must not reset it
        // to Idle: the scheduler would start a duplicate transfer over the
        // same output path while the first job keeps running.
        when (
            taskStateMap.entries
                .firstOrNull { (queued, _) -> queued.isSameWork(task) }
                ?.value
                ?.downloadState
        ) {
            is FetchingInfo,
            is Running,
            ReadyWithInfo -> return
            else -> {}
        }
        taskStateMap +=
            task to Task.State(Idle, null, Task.ViewState(url = task.url, title = task.url))
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
            pendingSwapCleanup.remove(task.id)
            Snapshot.sendApplyNotifications()
            task.batch?.let { updateBatchNotification(it) }
            return true
        }
        return false
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

    override fun cancel(task: Task): Boolean = task.cancelImpl()

    override fun restart(task: Task) {
        task.restartImpl()
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
        task.downloadState = ReadyWithInfo
        return swapResult(task, candidate, SwapOutcome.Queued)
    }

    /**
     * Non-blank text for [Task.DownloadState.Error.message]. Error surfaces
     * prefer this field when `throwable.message` is blank, so a throwable that
     * carries no message must not leave it empty.
     */
    private fun errorText(throwable: Throwable, friendly: Throwable): String =
        friendly.message?.takeIf { it.isNotBlank() }
            ?: throwable.message?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.unknown_error)

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
    private var Task.state: Task.State
        get() = taskStateMap[this] ?: Task.State(Idle, null, Task.ViewState(url = url, title = url))
        set(value) {
            if (taskStateMap.containsKey(this)) {
                taskStateMap[this] = value
                Snapshot.sendApplyNotifications()
            }
        }

    private var Task.downloadState: DownloadState
        get() = state.downloadState
        set(value) {
            if (taskStateMap.containsKey(this)) {
                val prevState = state
                taskStateMap[this] = prevState.copy(downloadState = value)
                Snapshot.sendApplyNotifications()
                batch?.let { updateBatchNotification(it) }
            }
        }

    private var Task.info: VideoInfo?
        get() = state.videoInfo
        set(value) {
            if (taskStateMap.containsKey(this)) {
                val prevState = state
                taskStateMap[this] = prevState.copy(videoInfo = value)
                Snapshot.sendApplyNotifications()
            }
        }

    private var Task.viewState: Task.ViewState
        get() = state.viewState
        set(value) {
            if (taskStateMap.containsKey(this)) {
                val prevState = state
                taskStateMap[this] = prevState.copy(viewState = value)
                Snapshot.sendApplyNotifications()
            }
        }

    private var Task.match: MatchResult?
        get() = state.match
        set(value) {
            if (taskStateMap.containsKey(this)) {
                val prevState = state
                taskStateMap[this] = prevState.copy(match = value)
                Snapshot.sendApplyNotifications()
            }
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
        val newFile = newPath?.let(::File)
        val task = taskStateMap.keys.firstOrNull { it.id == taskId }
        if (newFile == null || !newFile.isFile || newFile.length() <= 0L) {
            Log.w(TAG, "swap for $taskId produced no file; keeping the old pick")
            task?.match?.takeIf { it.pending }?.let { task.match = it.cancelPending() }
            return
        }
        if (!oldPath.isNullOrBlank() && oldPath != newPath) {
            // Row first: a crash between the two leaves an orphan file (harmless)
            // instead of a history row pointing at a deleted file.
            DatabaseUtil.deleteInfoByPath(oldPath)
            runCatching { File(oldPath).takeIf { it.isFile }?.delete() }
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
     */
    private fun Task.enrichWithCompletedFile(path: String) {
        val file = File(path)
        if (!file.isFile) return
        val length = file.length()
        val probedDuration =
            runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
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
        viewState =
            viewState.copy(
                duration = viewState.duration.takeIf { it > 0 } ?: probedDuration,
                fileSizeApprox = length.takeIf { it > 0 }?.toDouble() ?: viewState.fileSizeApprox,
            )
    }

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
        if (taskStateMap.countRunning() >= MAX_CONCURRENCY) return

        taskStateMap.entries
            .sortedBy { (_, state) -> state.downloadState }
            .firstOrNull { (_, state) ->
                state.downloadState == ReadyWithInfo || state.downloadState == Idle
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
            val synth =
                DownloadUtil.buildDirectVideoInfo(
                    url = url,
                    // Blank lets the builder derive a real name from the URL
                    // path instead of showing the whole URL as the title.
                    title = preferences.newTitle,
                )
            if (synth != null) {
                val job =
                    scope.launch(Dispatchers.Default, CoroutineStart.LAZY) {
                        info = synth
                        viewState = Task.ViewState.fromVideoInfo(synth)
                        // No explicit download() here: writing ReadyWithInfo
                        // wakes doYourWork, which starts the download. Calling
                        // it from inside the job raced the scheduler's pick of
                        // the same ReadyWithInfo state and ran downloadVideo
                        // twice per task (two concurrent Ghost transfers, one
                        // orphaned and uncancellable — observed on hardware).
                        downloadState = ReadyWithInfo
                    }
                downloadState = FetchingInfo(job = job, taskId = id)
                job.start()
                return
            }
        }
        fetchInfo()
    }

    private fun Task.fetchInfo() {
        if (downloadState != Idle) return
        val task = this
        val taskInfo = task.type
        val playlistIndex = if (taskInfo is TypeInfo.Playlist) taskInfo.index else null
        scope
            .launch(Dispatchers.Default) {
                DownloadUtil.fetchVideoInfoFromUrl(
                        url = url,
                        playlistIndex = playlistIndex,
                        preferences = preferences,
                        taskKey = id,
                    )
                    .onSuccess {
                        info = it
                        downloadState = ReadyWithInfo
                        viewState = Task.ViewState.fromVideoInfo(it)
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
                                downloadState = ReadyWithInfo
                                return@onFailure
                            }
                        }
                        val friendly = DownloadErrorMessages.asFriendly(throwable)
                        task.downloadState =
                            Error(
                                throwable = friendly,
                                action = FetchInfo,
                                message = errorText(throwable, friendly),
                            )
                        if (usesOwnNotification) {
                            NotificationUtil.notifyError(
                                title = viewState.title,
                                textId = R.string.fetch_info_error_msg,
                                notificationId = notificationId,
                                report = throwable.stackTraceToString(),
                                url = url,
                                withCookieRetry =
                                    friendly is DownloadErrorMessages.LoginRequiredError,
                            )
                        }
                    }
            }
            .also { job -> downloadState = FetchingInfo(job = job, taskId = id) }
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
            scope.launch(Dispatchers.Default, CoroutineStart.LAZY) {
                DownloadUtil.resolveMetadataTrack(
                        metadata = track,
                        taskKey = id,
                        preferences = preferences,
                        // A cancel or removal flips this false, so matching stops
                        // before spawning another yt-dlp process.
                        isActive = {
                            taskStateMap[task]?.downloadState is Task.DownloadState.FetchingInfo
                        },
                    )
                    .onSuccess { resolved ->
                        // A cancel can land while the search is finishing; the
                        // cancel wins and the late result is dropped.
                        if (downloadState !is FetchingInfo) return@onSuccess
                        info = resolved.videoInfo
                        viewState = Task.ViewState.fromVideoInfo(resolved.videoInfo)
                        match = resolved.match
                        // A held book finishes without ReadyWithInfo, so the
                        // scheduler never spends a byte on an unverified pick.
                        downloadState = MatchOps.resolvedState(resolved.match)
                    }
                    .onFailure { throwable ->
                        if (throwable is YoutubeDL.CanceledException) return@onFailure
                        if (downloadState !is FetchingInfo) return@onFailure
                        val friendly = DownloadErrorMessages.asFriendly(throwable)
                        downloadState =
                            Error(
                                throwable = friendly,
                                action = FetchInfo,
                                message = errorText(throwable, friendly),
                            )
                        if (usesOwnNotification) {
                            NotificationUtil.notifyError(
                                title = track.title,
                                textId = R.string.fetch_info_error_msg,
                                notificationId = notificationId,
                                report = throwable.stackTraceToString(),
                            )
                        }
                    }
            }
        downloadState = FetchingInfo(job = job, taskId = id)
        job.start()
    }

    /**
     * Phase 1b safety net for an Audible book: measure the file that actually
     * landed against the catalogue runtime. Returns null for every other task;
     * there is nothing to prove.
     *
     * A mismatch writes the measured duration, re-opens the review entry through
     * the columns phase 1a already renders, and keeps the file: a short book the
     * user can still play beats deleting a download they waited for.
     */
    private suspend fun Task.verifyAudibleDuration(path: String): VerifyResult? {
        val track = type as? TypeInfo.MetadataTrack ?: return null
        if (!track.provider.equals(AUDIBLE_PROVIDER, ignoreCase = true)) return null
        if (track.durationMs <= 0L) return null
        val result =
            withContext(Dispatchers.IO) {
                AudioVerify.verifyDuration(appContext, File(path), track.durationMs)
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

    private fun Task.download() {
        if (downloadState != ReadyWithInfo || info == null) return
        if (type is TypeInfo.CustomCommand) {
            execute()
            return
        }
        // LAZY + state-before-start: with eager launch the download body can
        // begin (and its first progress write can trigger a snapshot emission)
        // while this task still reads as ReadyWithInfo, and doYourWork then
        // launches a second downloadVideo for the same task.
        scope
            .launch(Dispatchers.Default, CoroutineStart.LAZY) {
                val runJob = coroutineContext[Job]
                // Cancel must win even when the state has already advanced to a
                // new attempt id: the job itself is the authority on liveness.
                val isActive = {
                    runJob?.isActive == true && (downloadState as? Running)?.job === runJob
                }
                DownloadUtil.downloadVideo(
                        videoInfo = info,
                        taskId = id,
                        downloadPreferences = preferences,
                        processIdFactory = { nextProcessId(id) },
                        onProcessStarted = { processId ->
                            val current = downloadState
                            if (current is Running && current.taskId != processId) {
                                downloadState = current.copy(taskId = processId)
                            }
                        },
                        isActive = isActive,
                        progressCallback = { progressPercentage, _, text ->
                            val progress = progressPercentage / 100f
                            when (val preState = downloadState) {
                                is Running -> {
                                    downloadState =
                                        preState.copy(progress = progress, progressText = text)
                                    if (usesOwnNotification) {
                                        NotificationUtil.notifyProgress(
                                            notificationId = notificationId,
                                            progress = progressPercentage.toInt(),
                                            text = text,
                                            title = viewState.title,
                                            taskId = id,
                                        )
                                    }
                                }
                                else -> {}
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
                                        downloadState = current.copy(taskId = processId)
                                    }
                                },
                                isActive = isActive,
                                progressCallback = { progressPercentage, _, text ->
                                    val progress = progressPercentage / 100f
                                    when (val preState = downloadState) {
                                        is Running -> {
                                            downloadState =
                                                preState.copy(progress = progress, progressText = text)
                                            if (usesOwnNotification) {
                                                NotificationUtil.notifyProgress(
                                                    notificationId = notificationId,
                                                    progress = progressPercentage.toInt(),
                                                    text = text,
                                                    title = viewState.title,
                                                    taskId = id,
                                                )
                                            }
                                        }

                                        else -> {}
                                    }
                                },
                            )
                            .getOrThrow()
                    }
                    .onSuccess { pathList ->
                        val newPath = pathList.firstOrNull()
                        newPath?.let { enrichWithCompletedFile(it) }
                        // Post-download safety net first: the gate proves the
                        // pick, this proves the file that actually landed. A
                        // failed check must not delete the old file/row a swap
                        // was about to replace.
                        val verify = newPath?.let { verifyAudibleDuration(it) }
                        if (verify != null && !verify.ok) {
                            // Keep the old pick/file/row; drop the parked path
                            // and make the short replacement a reviewable warning.
                            val previousPath = pendingSwapCleanup[id]
                            abandonSwappedFile(id)
                            val keepPath =
                                previousPath?.takeIf { File(it).isFile } ?: newPath
                            val warning =
                                if (keepPath != null && keepPath != newPath) {
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
                            downloadState = Completed(keepPath, warning = warning)
                        } else {
                            cleanupSwappedFile(id, newPath)
                            downloadState = Completed(newPath)
                        }

                        if (usesOwnNotification) {
                            val text =
                                appContext.getString(
                                    if (pathList.isEmpty()) R.string.status_completed
                                    else R.string.download_finish_notification
                                )
                            // Never a dead notification: the tap opens the file
                            // when possible and the app otherwise.
                            val tapIntent =
                                PendingIntent.getActivity(
                                    appContext,
                                    0,
                                    FileUtil.createNotificationTapIntent(pathList.firstOrNull()),
                                    PendingIntent.FLAG_IMMUTABLE,
                                )
                            NotificationUtil.finishNotification(
                                notificationId,
                                title = viewState.title,
                                text = text,
                                intent = tapIntent,
                            )
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
                        abandonSwappedFile(id)
                        val friendly = DownloadErrorMessages.asFriendly(throwable)
                        downloadState =
                            Error(
                                throwable = friendly,
                                action = Download,
                                message = errorText(throwable, friendly),
                            )
                        if (usesOwnNotification) {
                            NotificationUtil.notifyError(
                                title = viewState.title,
                                textId = R.string.download_error_msg,
                                notificationId = notificationId,
                                report = throwable.stackTraceToString(),
                                url = url,
                                withCookieRetry =
                                    friendly is DownloadErrorMessages.LoginRequiredError,
                            )
                        }
                    }
            }
            .also { job ->
                downloadState = Running(job = job, taskId = id)
                job.start()
            }
    }

    private fun Task.cancelImpl(): Boolean {
        when (val preState = downloadState) {
            is DownloadState.Cancelable -> {
                // Any of the three workers may own this task: a yt-dlp
                // process, a Ghost engine transfer, or an N_m3u8DL-RE run.
                // yt-dlp is keyed by the current attempt id; the embedded
                // engines are keyed by the stable task id. Missing ones just
                // report false; none may block the cancel.
                val killed =
                    YoutubeDL.destroyProcessById(preState.taskId) ||
                        DownloadUtil.cancelGhostTask(id) ||
                        DownloadUtil.cancelNm3u8DlRe(id)
                preState.job.cancel()
                if (usesOwnNotification) {
                    NotificationUtil.cancelNotification(notificationId)
                }
                // If the worker finished between the snapshot above and now,
                // keep its terminal state instead of overwriting it with
                // Canceled. A still-cancelable state of the same run is ours
                // even when its attempt id changed while the kill ran.
                val current = downloadState
                if (
                    killed ||
                        (current is DownloadState.Cancelable && current.job === preState.job)
                ) {
                    val progress = if (preState is Running) preState.progress else null
                    downloadState =
                        DownloadState.Canceled(action = preState.action, progress = progress)
                }
                return true
            }
            Idle -> {
                downloadState = DownloadState.Canceled(action = FetchInfo)
            }
            ReadyWithInfo -> {
                downloadState = DownloadState.Canceled(action = Download)
            }

            else -> {
                return false
            }
        }
        return true
    }

    private fun Task.restartImpl() {
        when (val preState = downloadState) {
            is DownloadState.Restartable -> {
                // A held book has no verified full-length source. Restarting it
                // would download the upload the gate refused, so the explicit
                // pick in the review sheet is the only way out of a hold.
                if (preState.action == Download && match?.held == true) {
                    TaskAuditLog.append(
                        id,
                        "gate: restart refused — pick a candidate in Review matches",
                    )
                    // A silent no-op reads as a broken button; say where the
                    // hold can be resolved.
                    scope.launch {
                        withContext(Dispatchers.Main) {
                            appContext.makeToast(R.string.match_held_resume_hint)
                        }
                    }
                    return
                }
                downloadState =
                    when (preState.action) {
                        Download -> ReadyWithInfo
                        FetchInfo -> Idle
                    }
            }
            // A double-tap on restart can race the first state change; a
            // stale second tap is a no-op, not a crash on the UI thread.
            else -> {}
        }
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
        scope
            .launch {
                DownloadUtil.executeCustomCommandTask(url, id, template, preferences) {
                        progressPercentage,
                        _,
                        text ->
                        val progress = progressPercentage / 100f
                        when (val preState = downloadState) {
                            is Running -> {
                                downloadState =
                                    preState.copy(progress = progress, progressText = text)
                                NotificationUtil.makeNotificationForCustomCommand(
                                    notificationId = notificationId,
                                    taskId = id,
                                    progress = progressPercentage.toInt(),
                                    templateName = template.name,
                                    taskUrl = url,
                                    text = text,
                                )
                            }
                            else -> {}
                        }
                    }
                    .onFailure { throwable ->
                        if (throwable is YoutubeDL.CanceledException) {
                            return@onFailure
                        }
                        val friendly = DownloadErrorMessages.asFriendly(throwable)
                        downloadState =
                            Error(
                                throwable = friendly,
                                action = Download,
                                message = errorText(throwable, friendly),
                            )
                        NotificationUtil.notifyError(
                            title = viewState.title,
                            textId = R.string.download_error_msg,
                            notificationId = notificationId,
                            report = throwable.stackTraceToString(),
                        )
                    }
                    .onSuccess {
                        downloadState = Completed(null)

                        val text = appContext.getString(R.string.status_completed)

                        NotificationUtil.finishNotification(
                            notificationId = notificationId,
                            title = viewState.title,
                            text = text,
                            intent = null,
                        )
                    }
            }
            .also { downloadState = Running(job = it, taskId = id) }
    }
}
