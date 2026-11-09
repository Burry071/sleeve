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
import com.junkfood.seal.util.DownloadErrorMessages
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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

    /** Mark every flagged pick in the batch as seen, clearing the review entry. */
    fun acknowledgeMatches(batchId: String) {
        val map = getTaskStateMap()
        BatchOps.entries(map, batchId).forEach { (task, state) ->
            val match = state.match ?: return@forEach
            if (match.flagged && !match.acknowledged) {
                map[task] = state.copy(match = match.copy(acknowledged = true))
            }
        }
        Snapshot.sendApplyNotifications()
    }

    /**
     * Re-download the songs whose picks the user swapped. Only the chosen
     * candidate is fetched — never a new search — and only that song's file is
     * produced. Returns how many songs were queued.
     */
    suspend fun swapMatches(selections: List<Pair<Task, MatchCandidate>>): Int = 0
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

@OptIn(FlowPreview::class)
class DownloaderV2Impl(private val appContext: Context) : DownloaderV2, KoinComponent {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskStateMap = mutableStateMapOf<Task, Task.State>()
    private val snapshotFlow = snapshotFlow { taskStateMap.toMap() }

    init {
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
                    state.copy(downloadState = downloadState)
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

    override fun cancel(task: Task): Boolean = task.cancelImpl()

    override fun restart(task: Task) {
        task.restartImpl()
    }

    /**
     * Re-fetches only the candidate metadata, never the search. The scheduler
     * then re-runs exactly the swapped songs; the old file is deleted only once
     * its replacement finishes.
     */
    override suspend fun swapMatches(selections: List<Pair<Task, MatchCandidate>>): Int =
        withContext(Dispatchers.IO) {
            var queued = 0
            selections
                .distinctBy { it.first.id }
                .forEach { (task, candidate) ->
                    val current = taskStateMap[task] ?: return@forEach
                    // A matching or downloading song must not have its state
                    // torn out from under the worker.
                    if (current.downloadState is FetchingInfo || current.downloadState is Running) {
                        return@forEach
                    }
                    val match = current.match ?: return@forEach
                    val fetched =
                        DownloadUtil.fetchVideoInfoFromUrl(
                            url = candidate.url,
                            preferences = task.preferences,
                            taskKey = task.id,
                        )
                    if (fetched.isFailure) {
                        Log.w(
                            TAG,
                            "${task.id} swap fetch failed: ${fetched.exceptionOrNull()?.message}",
                        )
                        return@forEach
                    }
                    val info = fetched.getOrThrow()
                    val oldPath = (current.downloadState as? Completed)?.filePath
                    if (!oldPath.isNullOrBlank()) pendingSwapCleanup[task.id] = oldPath
                    task.match = match.withSelection(candidate)
                    task.info = info
                    task.viewState = Task.ViewState.fromVideoInfo(info)
                    task.downloadState = ReadyWithInfo
                    queued++
                }
            Snapshot.sendApplyNotifications()
            queued
        }

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

    /** Called after a song completes: drop the file its swapped pick replaced. */
    private fun cleanupSwappedFile(taskId: String, newPath: String?) {
        val oldPath = pendingSwapCleanup.remove(taskId) ?: return
        if (oldPath == newPath) return
        runCatching { File(oldPath).takeIf { it.isFile }?.delete() }
            .onFailure { Log.w(TAG, "could not delete replaced file: ${it.message}") }
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

    /** Processes pending tasks, prioritizing downloads. */
    private fun doYourWork() {
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
        (type as? TypeInfo.MetadataTrack)?.let { track ->
            resolveMetadata(track)
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
                        task.downloadState = Error(throwable = friendly, action = FetchInfo)
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
                        downloadState = ReadyWithInfo
                    }
                    .onFailure { throwable ->
                        if (throwable is YoutubeDL.CanceledException) return@onFailure
                        if (downloadState !is FetchingInfo) return@onFailure
                        downloadState =
                            Error(
                                throwable = DownloadErrorMessages.asFriendly(throwable),
                                action = FetchInfo,
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
                DownloadUtil.downloadVideo(
                        videoInfo = info,
                        taskId = id,
                        downloadPreferences = preferences,
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
                        downloadState = Completed(newPath)
                        newPath?.let { enrichWithCompletedFile(it) }
                        cleanupSwappedFile(id, newPath)

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
                        if (throwable is YoutubeDL.CanceledException) {
                            return@onFailure
                        }
                        val friendly = DownloadErrorMessages.asFriendly(throwable)
                        downloadState = Error(throwable = friendly, action = Download)
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
                // Missing ones just report false; none may block the cancel.
                val killed =
                    YoutubeDL.destroyProcessById(preState.taskId) ||
                        DownloadUtil.cancelGhostTask(preState.taskId) ||
                        DownloadUtil.cancelNm3u8DlRe(preState.taskId)
                preState.job.cancel()
                if (usesOwnNotification) {
                    NotificationUtil.cancelNotification(notificationId)
                }
                // If the worker finished between the snapshot above and now,
                // keep its terminal state instead of overwriting it with
                // Canceled.
                if (killed || downloadState == preState) {
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
                        downloadState =
                            Error(
                                throwable = DownloadErrorMessages.asFriendly(throwable),
                                action = Download,
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
