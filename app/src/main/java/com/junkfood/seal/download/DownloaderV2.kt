package com.junkfood.seal.download

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
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
                .collect { if (it > 0) App.startService() else App.stopService() }
        }

        scope.launch(Dispatchers.IO) {
            // don't write before we read
            enqueueFromBackup()

            snapshotFlow
                .map { it.filter { it.value.downloadState !is Completed } }
                .distinctUntilChanged()
                .collect {
                    it.forEach { Log.d(TAG, it.value.viewState.title) }
                    PreferenceUtil.encodeTaskListBackup(it)
                }
        }
    }

    private fun enqueueFromBackup() {
        val taskList =
            PreferenceUtil.decodeTaskListBackup()
                .filter { it.value.downloadState !is Completed }
                .mapValues { (_, state) ->
                    val preState = state.downloadState
                    val downloadState =
                        when (preState) {
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
        taskList.forEach(::enqueue)
    }

    private fun Map<Task, Task.State>.countRunning(): Int = count { (_, state) ->
        state.downloadState is Running || state.downloadState is FetchingInfo
    }

    override fun getTaskStateMap(): SnapshotStateMap<Task, Task.State> {
        return taskStateMap
    }

    override fun enqueue(task: Task) {
        taskStateMap +=
            task to Task.State(Idle, null, Task.ViewState(url = task.url, title = task.url))
    }

    override fun enqueue(task: Task, state: Task.State) {
        taskStateMap += task to state
    }

    /**
     * Noted the caller is responsible for stopping the [task] before removing it
     *
     * @return true if the task was removed
     */
    override fun remove(task: Task): Boolean {
        if (taskStateMap.contains(task)) {
            taskStateMap.remove(task)
            return true
        }
        return false
    }

    override fun cancel(task: Task): Boolean = task.cancelImpl()

    override fun restart(task: Task) {
        task.restartImpl()
    }

    // All state accessors tolerate removal: a cancel/remove racing a running
    // task used to throw (taskStateMap[this]!!) and could crash the scheduler
    // collector. Reads fall back to a fresh Idle state without writing; writes
    // to a removed task are dropped instead of resurrecting it in the map.
    private var Task.state: Task.State
        get() = taskStateMap[this] ?: Task.State(Idle, null, Task.ViewState(url = url, title = url))
        set(value) {
            if (taskStateMap.containsKey(this)) taskStateMap[this] = value
        }

    private var Task.downloadState: DownloadState
        get() = state.downloadState
        set(value) {
            if (taskStateMap.containsKey(this)) {
                val prevState = state
                taskStateMap[this] = prevState.copy(downloadState = value)
            }
        }

    private var Task.info: VideoInfo?
        get() = state.videoInfo
        set(value) {
            if (taskStateMap.containsKey(this)) {
                val prevState = state
                taskStateMap[this] = prevState.copy(videoInfo = value)
            }
        }

    private var Task.viewState: Task.ViewState
        get() = state.viewState
        set(value) {
            if (taskStateMap.containsKey(this)) {
                val prevState = state
                taskStateMap[this] = prevState.copy(viewState = value)
            }
        }

    private val Task.notificationId: Int
        get() = id.hashCode()

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
        if (type is TypeInfo.CustomCommand) {
            execute()
            return
        }
        if (preferences.galleryDirect) {
            // The URL is already the media file (gallery/embed/sniff item);
            // running yt-dlp's generic extractor on it only adds a failing
            // round-trip before the same fallback below.
            val synth =
                DownloadUtil.buildDirectVideoInfo(
                    url = url,
                    title = preferences.newTitle.ifBlank { viewState.title },
                )
            if (synth != null) {
                val job =
                    scope.launch(Dispatchers.Default, CoroutineStart.LAZY) {
                        info = synth
                        viewState = Task.ViewState.fromVideoInfo(synth)
                        downloadState = ReadyWithInfo
                        download()
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
                                    title = viewState.title,
                                )
                            if (synth != null) {
                                info = synth
                                viewState = Task.ViewState.fromVideoInfo(synth)
                                downloadState = ReadyWithInfo
                                task.download()
                                return@onFailure
                            }
                        }
                        task.downloadState =
                            Error(
                                throwable = DownloadErrorMessages.asFriendly(throwable),
                                action = FetchInfo,
                            )
                        NotificationUtil.notifyError(
                            title = viewState.title,
                            textId = R.string.fetch_info_error_msg,
                            notificationId = notificationId,
                            report = throwable.stackTraceToString(),
                        )
                    }
            }
            .also { job -> downloadState = FetchingInfo(job = job, taskId = id) }
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
                                    NotificationUtil.notifyProgress(
                                        notificationId = notificationId,
                                        progress = progressPercentage.toInt(),
                                        text = text,
                                        title = viewState.title,
                                        taskId = id,
                                    )
                                }
                                else -> {}
                            }
                        },
                    )
                    .onSuccess { pathList ->
                        downloadState = Completed(pathList.firstOrNull())

                        val text =
                            appContext.getString(
                                if (pathList.isEmpty()) R.string.status_completed
                                else R.string.download_finish_notification
                            )
                        FileUtil.createIntentForOpeningFile(pathList.firstOrNull()).run {
                            NotificationUtil.finishNotification(
                                notificationId,
                                title = viewState.title,
                                text = text,
                                intent =
                                    if (this != null)
                                        PendingIntent.getActivity(
                                            appContext,
                                            0,
                                            this,
                                            PendingIntent.FLAG_IMMUTABLE,
                                        )
                                    else null,
                            )
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
                NotificationUtil.cancelNotification(notificationId)
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
