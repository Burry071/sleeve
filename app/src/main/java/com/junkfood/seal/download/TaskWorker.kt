package com.junkfood.seal.download

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.download.Task.DownloadState
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
import com.junkfood.seal.util.DownloadErrorMessages
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.LiveGate
import com.junkfood.seal.util.M4bTagOverrides
import com.junkfood.seal.util.Truncation
import com.junkfood.seal.util.VerifyResult
import com.junkfood.seal.util.makeToast
import com.junkfood.seal.util.toDurationText
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TaskWorker"

/**
 * A metadata task normally resolves by searching the catalogue for its book or
 * track. A split part already carries the verified URL of one upload, so it
 * must go straight to extraction: re-running the search would gate that part as
 * if it were the whole book and hold it forever.
 */
internal val TypeInfo.MetadataTrack.needsSearch: Boolean
    get() = !directUrl

/**
 * Non-blank text for [Task.DownloadState.Error.message]. Error surfaces prefer
 * this field when `throwable.message` is blank, so a throwable that carries no
 * message must not leave it empty. A failed SD-card move gets the counts in
 * plain words, then the folder the files are still in.
 */
internal fun errorText(appContext: Context, throwable: Throwable, friendly: Throwable): String {
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
internal fun shortReason(throwable: Throwable): String =
    DownloadErrorMessages.asFriendly(throwable)
        .message
        .orEmpty()
        .lineSequence()
        .first()
        .take(160)

/**
 * A stream that cannot produce a file yet as a task state; null when the item
 * may proceed. Refused before any media request: the retry that succeeds is the
 * ordinary Error action, not a special one.
 */
internal fun liveRefusal(appContext: Context, liveStatus: String?): Error? {
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
internal fun setLiveNotice(
    appContext: Context,
    store: TaskStore,
    task: Task,
    liveStatus: String?,
) {
    val allow = LiveGate.decide(liveStatus) as? LiveGate.Decision.Allow ?: return
    allow.warningRes?.let { res -> store.setNotice(task, appContext.getString(res)) }
}

/**
 * Owns the `Dispatchers.IO` execution pipelines for [DownloaderV2Impl]:
 * [prepare], [fetchInfo], [resolveMetadata], [download] (including aria2c retry
 * and [App.enginesReady]), [execute], and [verifyAudibleDuration].
 */
internal class TaskWorker(
    private val appContext: Context,
    private val store: TaskStore,
    private val scope: CoroutineScope,
    private val swapCoordinator: MatchSwapCoordinator,
    private val auditCookieState: (Task) -> Unit,
    private val searchOverrideFor: (String) -> String,
) {
    /** Suffix source for yt-dlp process ids; never reused within this process. */
    private val processSequence = AtomicLong()

    /**
     * A fresh yt-dlp process id for every attempt. The vendor keeps process ids
     * in a map and a dead-but-unreaped entry makes that id permanently unusable
     * for the rest of the app process, so a retry must never reuse it.
     */
    private fun nextProcessId(taskId: String): String =
        "$taskId:${processSequence.incrementAndGet()}"

    fun prepare(task: Task) {
        // A cancel/remove can race the scheduler pick; a stale pick no-ops.
        if (store.downloadStateOf(task) != Idle) return
        auditCookieState(task)
        if (task.type is TypeInfo.CustomCommand) {
            execute(task)
            return
        }
        val track = task.type as? TypeInfo.MetadataTrack
        if (track != null) {
            if (track.needsSearch) {
                resolveMetadata(task, track)
            } else {
                // A split part carries the verified URL of one upload: there
                // is no catalogue entry to search and no page to sniff, so it
                // is extracted directly whatever the gallery preferences say.
                fetchInfo(task)
            }
            return
        }
        if (
            task.preferences.galleryDirect ||
                (DownloadUtil.isDirectMediaUrl(task.url) &&
                    (!task.preferences.extractAudio || DownloadUtil.isDirectImageUrl(task.url)))
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
                            url = task.url,
                            // Blank lets the builder derive a real name from the URL
                            // path instead of showing the whole URL as the title.
                            title = task.preferences.newTitle,
                        )
                    if (synth != null) {
                        store.setVideoInfo(task, synth)
                        store.setViewState(task, Task.ViewState.fromVideoInfo(synth))
                        // No explicit download() here: writing ReadyWithInfo
                        // wakes doYourWork, which starts the download. Calling
                        // it from inside the job raced the scheduler's pick of
                        // the same ReadyWithInfo state and ran downloadVideo
                        // twice per task (two concurrent Ghost transfers, one
                        // orphaned and uncancellable — observed on hardware).
                        store.applyTransition(task, ReadyWithInfo) {
                            it is FetchingInfo && it.job === runJob
                        }
                    } else {
                        // Fail closed when buildDirectVideoInfo rejects an
                        // unsafe URL instead of falling through to unguarded
                        // yt-dlp extraction.
                        val throwable = IllegalArgumentException("Blocked unsafe direct URL")
                        val friendly = DownloadErrorMessages.asFriendly(throwable)
                        store.applyTransition(
                            task,
                            Error(
                                throwable = friendly,
                                action = FetchInfo,
                                message = errorText(appContext, throwable, friendly),
                                reason = DownloadErrorMessages.errorReasonOf(friendly),
                            ),
                        ) { it is FetchingInfo && it.job === runJob }
                    }
                }
            // Only a task still waiting for its info may begin fetching;
            // a cancel that landed first leaves it untouched.
            if (
                store.applyTransition(task, FetchingInfo(job = job, taskId = task.id)) {
                    it == Idle
                }
            ) {
                job.start()
            }
            return
        }
        fetchInfo(task)
    }

    fun fetchInfo(task: Task) {
        if (store.downloadStateOf(task) != Idle) return
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
                        url = task.url,
                        playlistIndex = playlistIndex,
                        preferences = task.preferences,
                        taskKey = task.id,
                    )
                    // Ahead of onSuccess on purpose: a throw inside onSuccess
                    // escapes the chain without reaching the chained onFailure.
                    .mapCatching { info ->
                        PlaylistPin.verify(store.stateOf(task).expectedVideoId, info.id)
                        info
                    }
                    .onSuccess { fetchedInfo ->
                        store.setVideoInfo(task, fetchedInfo)
                        store.setViewState(task, Task.ViewState.fromVideoInfo(fetchedInfo))
                        val refusal = liveRefusal(appContext, fetchedInfo.liveStatus)
                        if (refusal != null) {
                            store.applyTransition(task, refusal, isThisAttempt)
                        } else {
                            setLiveNotice(appContext, store, task, fetchedInfo.liveStatus)
                            // A cancel can land while extraction is finishing; the
                            // cancel wins and the late result is dropped.
                            store.applyTransition(task, ReadyWithInfo, isThisAttempt)
                        }
                    }
                    .onFailure { throwable ->
                        if (throwable is YoutubeDL.CanceledException) {
                            return@onFailure
                        }
                        if (task.preferences.galleryDirect) {
                            // gallery-dl/sniffed item: skip extraction, feed the
                            // raw media URL to the engine handoff pipeline.
                            val synth =
                                DownloadUtil.buildDirectVideoInfo(
                                    url = task.url,
                                    title = task.preferences.newTitle,
                                )
                            if (synth != null) {
                                store.setVideoInfo(task, synth)
                                store.setViewState(task, Task.ViewState.fromVideoInfo(synth))
                                // Same rule as prepare(): the ReadyWithInfo
                                // write is the trigger — doYourWork starts the
                                // download exactly once.
                                store.applyTransition(task, ReadyWithInfo, isThisAttempt)
                                return@onFailure
                            }
                        }
                        val friendly = DownloadErrorMessages.asFriendly(throwable)
                        store.applyTransition(
                            task,
                            Error(
                                throwable = friendly,
                                action = FetchInfo,
                                message = errorText(appContext, throwable, friendly),
                                reason = DownloadErrorMessages.errorReasonOf(friendly),
                            ),
                            isThisAttempt,
                        )
                    }
            }
        if (store.applyTransition(task, FetchingInfo(job = job, taskId = task.id)) { it == Idle }) {
            job.start()
        }
    }

    /**
     * Metadata songs resolve their YouTube counterpart here instead of in the
     * sheet. The lifecycle mirrors [fetchInfo]: matching is [FetchingInfo], and
     * the [ReadyWithInfo] write is what wakes the scheduler to start the
     * download.
     */
    fun resolveMetadata(task: Task, track: TypeInfo.MetadataTrack) {
        if (store.downloadStateOf(task) != Idle) return
        val job =
            scope.launch(Dispatchers.IO, CoroutineStart.LAZY) {
                val runJob = coroutineContext[Job]
                val isThisAttempt = { state: DownloadState ->
                    state is FetchingInfo && state.job === runJob
                }
                App.enginesReady.await()
                DownloadUtil.resolveMetadataTrack(
                        metadata = track,
                        taskKey = task.id,
                        preferences = task.preferences,
                        // A user-edited "Search again" query wins over the
                        // catalogue-built one for the rest of this run.
                        queryOverride = searchOverrideFor(task.id),
                        // A cancel or removal flips this false, so matching stops
                        // before spawning another yt-dlp process.
                        isActive = {
                            (store.get(task)?.downloadState as? FetchingInfo)?.job === runJob
                        },
                    )
                    .onSuccess { resolved ->
                        store.setVideoInfo(task, resolved.videoInfo)
                        store.setViewState(task, Task.ViewState.fromVideoInfo(resolved.videoInfo))
                        store.setMatch(task, resolved.match)
                        setLiveNotice(appContext, store, task, resolved.videoInfo.liveStatus)
                        // A held book finishes without ReadyWithInfo, so the
                        // scheduler never spends a byte on an unverified pick.
                        // A cancel that landed first wins and drops this.
                        store.applyTransition(
                            task,
                            MatchOps.resolvedState(resolved.match, resolved.videoInfo.liveStatus),
                            isThisAttempt,
                        )
                    }
                    .onFailure { throwable ->
                        if (throwable is YoutubeDL.CanceledException) return@onFailure
                        val friendly = DownloadErrorMessages.asFriendly(throwable)
                        store.applyTransition(
                            task,
                            Error(
                                throwable = friendly,
                                action = FetchInfo,
                                message = errorText(appContext, throwable, friendly),
                                reason = DownloadErrorMessages.errorReasonOf(friendly),
                            ),
                            isThisAttempt,
                        )
                    }
            }
        if (store.applyTransition(task, FetchingInfo(job = job, taskId = task.id)) { it == Idle }) {
            job.start()
        }
    }

    fun download(task: Task) {
        if (store.downloadStateOf(task) != ReadyWithInfo || store.stateOf(task).videoInfo == null) {
            return
        }
        if (task.type is TypeInfo.CustomCommand) {
            execute(task)
            return
        }
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
                    runJob?.isActive == true &&
                        (store.downloadStateOf(task) as? Running)?.job === runJob
                }
                // The finish line reports two things back before the card is
                // written: one local duration measurement (the Audible check
                // needs it) and any rider on an otherwise successful download.
                var localDurationMs = 0L
                val completedNotes = mutableListOf<CompletedNote>()
                val m4bTagOverrides = (task.type as? TypeInfo.MetadataTrack)?.toM4bTagOverrides()
                App.enginesReady.await()
                DownloadUtil.downloadVideo(
                        videoInfo = store.stateOf(task).videoInfo,
                        taskId = task.id,
                        downloadPreferences = task.preferences,
                        processIdFactory = { nextProcessId(task.id) },
                        onProcessStarted = { processId ->
                            val current = store.downloadStateOf(task)
                            if (current is Running && current.taskId != processId) {
                                store.applyTransition(task, current.copy(taskId = processId)) {
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
                            val preState = store.downloadStateOf(task)
                            if (preState is Running) {
                                store.applyTransition(
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
                            task.preferences.aria2c &&
                                throwable.message?.contains("aria2c", ignoreCase = true) == true
                        if (!retryable) throw throwable
                        Log.w(
                            TAG,
                            "aria2c failed, retrying with the built-in downloader: ${throwable.message?.take(120)}",
                        )
                        DownloadUtil.downloadVideo(
                                videoInfo = store.stateOf(task).videoInfo,
                                taskId = task.id,
                                downloadPreferences = task.preferences.copy(aria2c = false),
                                processIdFactory = { nextProcessId(task.id) },
                                onProcessStarted = { processId ->
                                    val current = store.downloadStateOf(task)
                                    if (current is Running && current.taskId != processId) {
                                        store.applyTransition(
                                            task,
                                            current.copy(taskId = processId),
                                        ) { it is Running && it.job === runJob }
                                    }
                                },
                                isActive = isActive,
                                m4bTagOverrides = m4bTagOverrides,
                                onNote = { completedNotes += it },
                                onLocalDurationMs = { localDurationMs = it },
                                progressCallback = { progressPercentage, _, text ->
                                    val progress = progressPercentage / 100f
                                    val preState = store.downloadStateOf(task)
                                    if (preState is Running) {
                                        store.applyTransition(
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
                        val currentState = store.stateOf(task)
                        val expectedMs =
                            currentState.videoInfo?.duration?.toLong()?.times(1000L) ?: 0L
                        val live = currentState.notice != null
                        val probed = newPath?.let { enrichWithCompletedFile(task, it) } ?: 0
                        val truncation =
                            Truncation.warningRes(
                                probedSeconds = probed.toLong(),
                                expectedMs = expectedMs,
                                live = live,
                                clips = task.preferences.videoClips.isNotEmpty(),
                                split = task.preferences.splitByChapter,
                            )
                        // Post-download safety net first: the gate proves the
                        // pick, this proves the file that actually landed. A
                        // failed check must not delete the old file/row a swap
                        // was about to replace.
                        val verify =
                            newPath?.let { verifyAudibleDuration(task, it, localDurationMs) }
                        if (verify != null && !verify.ok) {
                            // Keep the old pick/file/row; drop the parked path
                            // and make the short replacement a reviewable warning.
                            val previousPath = swapCoordinator.parkedPathFor(task.id)
                            swapCoordinator.abandonSwappedFile(task.id)
                            val keepPath =
                                previousPath?.takeIf { swapCoordinator.isValidOutput(it) }
                                    ?: newPath
                            val warning =
                                if (keepPath != newPath) {
                                    "replacement rejected: ${verify.reason}; kept the previous file"
                                } else {
                                    verify.reason
                                }
                            store.stateOf(task).match?.let { existingMatch ->
                                store.setMatch(
                                    task,
                                    existingMatch.copy(
                                        flagged = true,
                                        acknowledged = false,
                                        held = false,
                                        actualDurationMs = verify.actualMs,
                                        gateReason = verify.reason,
                                    ),
                                )
                            }
                            store.applyTransition(
                                task,
                                Completed(
                                    keepPath,
                                    warning = warning,
                                    notes = completedNotes.distinct(),
                                ),
                            ) { it is Running && it.job === runJob }
                        } else {
                            swapCoordinator.cleanupSwappedFile(task.id, newPath)
                            val warning =
                                truncation
                                    ?.let {
                                        appContext.getString(
                                            it,
                                            probed.toDurationText(),
                                            (expectedMs / 1000L).toInt().toDurationText(),
                                        )
                                    }
                                    .orEmpty()
                            store.applyTransition(
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
                        val previousPath = swapCoordinator.parkedPathFor(task.id)
                        swapCoordinator.abandonSwappedFile(task.id)
                        val friendly = DownloadErrorMessages.asFriendly(throwable)
                        val keptPath = previousPath?.takeIf { swapCoordinator.isValidOutput(it) }
                        if (keptPath != null) {
                            // The file the user already had is still on disk.
                            // Reporting a bare failure would hide a download
                            // that exists, so this completes with a warning and
                            // stays reviewable: the failed pick is named, and
                            // the row and the batch count agree with the disk.
                            val reason = shortReason(throwable)
                            store.stateOf(task).match?.let { existingMatch ->
                                store.setMatch(
                                    task,
                                    existingMatch.copy(
                                        flagged = true,
                                        acknowledged = false,
                                        gateReason = reason,
                                    ),
                                )
                            }
                            store.applyTransition(
                                task,
                                Completed(
                                    keptPath,
                                    warning = "replacement failed: $reason; kept the previous file",
                                    notes = completedNotes.distinct(),
                                ),
                            ) { it is Running && it.job === runJob }
                        } else {
                            store.applyTransition(
                                task,
                                Error(
                                    throwable = friendly,
                                    action = Download,
                                    message = errorText(appContext, throwable, friendly),
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
                    store.applyTransition(task, Running(job = job, taskId = task.id)) {
                        it is ReadyWithInfo
                    }
                ) {
                    job.start()
                }
            }
    }

    /**
     * Execute a custom command task
     *
     * @see Task.TypeInfo.CustomCommand
     */
    fun execute(task: Task) {
        if (store.downloadStateOf(task) != Idle) return
        val type = task.type
        check(type is TypeInfo.CustomCommand)
        val template = type.template
        val job =
            scope.launch(Dispatchers.IO, CoroutineStart.LAZY) {
                val runJob = coroutineContext[Job]
                val isThisAttempt = { state: DownloadState ->
                    state is Running && state.job === runJob
                }
                App.enginesReady.await()
                DownloadUtil.executeCustomCommandTask(
                        task.url,
                        task.id,
                        template,
                        task.preferences,
                    ) { progressPercentage, _, text ->
                        TaskOutputLog.append(task.id, text)
                        val preState = store.downloadStateOf(task)
                        if (preState is Running) {
                            store.applyTransition(
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
                        TaskOutputLog.append(task.id, throwable.stackTraceToString())
                        val friendly = DownloadErrorMessages.asFriendly(throwable)
                        store.applyTransition(
                            task,
                            Error(
                                throwable = friendly,
                                action = Download,
                                message = errorText(appContext, throwable, friendly),
                                reason = DownloadErrorMessages.errorReasonOf(friendly),
                            ),
                            isThisAttempt,
                        )
                    }
                    .onSuccess { response ->
                        // yt-dlp printed nothing on this run (`--quiet`,
                        // a cached file): keep the command's own reply so
                        // the log is never silently empty.
                        if (TaskOutputLog.isEmpty(task.id)) {
                            TaskOutputLog.append(task.id, response.out + "\n" + response.err)
                        }
                        // The command lane talks to yt-dlp directly, so
                        // nothing else tells the gallery about the file.
                        task.preferences.commandDirectory
                            .takeIf { it.isNotEmpty() }
                            ?.let { FileUtil.scanDownloadDirectoryToMediaLibrary(it) }
                        store.applyTransition(task, Completed(null), isThisAttempt)
                    }
            }
        // State before start: the worker only acts while this attempt is the
        // Running state, so a cancel that landed first leaves it untouched.
        if (store.applyTransition(task, Running(job = job, taskId = task.id)) { it == Idle }) {
            job.start()
        }
    }

    fun cancel(task: Task): Boolean {
        val preState = store.downloadStateOf(task)
        val next = TaskTransitions.cancelTarget(preState) ?: return false
        // Any of the three workers may own this task: a yt-dlp process, a
        // Ghost engine transfer, or an N_m3u8DL-RE run. yt-dlp is keyed by the
        // current attempt id; the embedded engines are keyed by the stable
        // task id. Missing ones just report false; none may block the cancel.
        val cancelable = preState as? DownloadState.Cancelable
        val killed =
            cancelable != null &&
                (YoutubeDL.destroyProcessById(cancelable.taskId) ||
                    DownloadUtil.cancelGhostTask(task.id) ||
                    DownloadUtil.cancelNm3u8DlRe(task.id))
        cancelable?.job?.cancel()
        // Cancel every unfinished state, but keep what a worker already proved:
        // a job that finished between the snapshot and the kill owns a terminal
        // state the guard refuses to overwrite. A still-cancelable state of the
        // same run is ours even when its attempt id changed while the kill ran.
        store.applyTransition(task, next) { current ->
            when (current) {
                Idle, ReadyWithInfo -> true
                is DownloadState.Cancelable ->
                    killed || cancelable == null || current.job === cancelable.job
                else -> false
            }
        }
        return true
    }

    fun restart(task: Task) {
        val preState = store.downloadStateOf(task)
        // A held book has no verified full-length source. Restarting it would
        // download the upload the gate refused, so the explicit pick in the
        // review sheet is the only way out of a hold.
        if (
            preState is DownloadState.Restartable &&
                preState.action == Download &&
                store.stateOf(task).match?.held == true
        ) {
            TaskAuditLog.append(
                task.id,
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
        val next = TaskTransitions.restartTarget(preState, task.type is TypeInfo.CustomCommand)
        if (next != null) store.applyTransition(task, next) { it == preState }
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
    internal suspend fun verifyAudibleDuration(
        task: Task,
        path: String,
        localDurationMs: Long,
    ): VerifyResult? {
        val track = task.type as? TypeInfo.MetadataTrack ?: return null
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
        Log.i(TAG, "${task.id} $line")
        TaskAuditLog.append(task.id, line)
        val currentMatch = store.stateOf(task).match
        store.setMatch(
            task,
            if (result.ok) {
                currentMatch?.copy(actualDurationMs = result.actualMs)
            } else {
                currentMatch?.copy(
                    flagged = true,
                    // A failed check outranks a swap confirmation: the entry
                    // must stay visible until the user picks again.
                    acknowledged = false,
                    held = false,
                    actualDurationMs = result.actualMs,
                    gateReason = result.reason,
                )
            },
        )
        return result
    }

    /**
     * The direct/CDN lane synthesizes [VideoInfo] before any byte is fetched,
     * so duration and exact size are unknown up to this point. Once the file
     * exists they are measurable — fill them in instead of leaving the card on
     * a "0.00 MB · 00:00" placeholder.
     *
     * @return the measured length in whole seconds, 0 when it could not be read
     */
    private fun enrichWithCompletedFile(task: Task, path: String): Int {
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
        val currentView = store.stateOf(task).viewState
        store.setViewState(
            task,
            currentView.copy(
                duration = currentView.duration.takeIf { it > 0 } ?: probedDuration,
                fileSizeApprox = length.takeIf { it > 0 }?.toDouble() ?: currentView.fileSizeApprox,
            ),
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
}
