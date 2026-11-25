package com.junkfood.seal.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.snapshots.Snapshot
import com.junkfood.seal.App
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.download.Task.TypeInfo
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.VideoInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MatchSwapCoordinator"

/** The provider the Audible gate and the post-download check both cover. */
internal const val AUDIBLE_PROVIDER = "Audible"

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

internal fun isDocumentOutputValid(appContext: Context, uri: String): Boolean =
    with(FileUtil) { uri.getFileSize() } > 0L ||
        runCatching {
                appContext.contentResolver
                    .openAssetFileDescriptor(Uri.parse(uri), "r")
                    ?.use { it.length > 0L } == true
            }
            .getOrDefault(false)

/**
 * Owns catalogue match replacement swaps ([swapMatches], [pendingSwapCleanup],
 * SAF-aware [cleanupSwappedFile], [abandonSwappedFile]) and Audible split-book
 * part expansion ([enqueueParts]).
 */
internal class MatchSwapCoordinator(
    private val store: TaskStore,
    private val scope: CoroutineScope,
    private val liveRefusal: (String?) -> Error?,
    private val setLiveNotice: (Task, String?) -> Unit,
    private val shortReason: (Throwable) -> String,
    private val isDocumentValid: (String) -> Boolean,
    private val isLocalValid: (String) -> Boolean = { p ->
        File(p).let { it.isFile && it.length() > 0L }
    },
    private val awaitEnginesReady: suspend () -> Unit = { App.enginesReady.await() },
    private val fetchVideoInfo:
        suspend (url: String, preferences: DownloadUtil.DownloadPreferences, taskKey: String) -> Result<VideoInfo> =
        { url, preferences, taskKey ->
            DownloadUtil.fetchVideoInfoFromUrl(
                url = url,
                preferences = preferences,
                taskKey = taskKey,
            )
        },
    private val deleteHistoryByPath: suspend (String) -> Unit = {
        DatabaseUtil.deleteInfoByPath(it)
    },
    private val deleteFile: (String) -> Result<Unit> = { path ->
        FileUtil.deleteFile(path).map {}
    },
    private val onEnqueuePart: (Task, Task.State) -> Unit,
    private val onRemoveTask: (Task) -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sendApplyNotifications: () -> Unit = { Snapshot.sendApplyNotifications() },
) {
    /** Deletes the file a swapped pick replaced, but only after the new file is complete. */
    internal val pendingSwapCleanup = ConcurrentHashMap<String, String>()

    fun parkedPathFor(taskId: String): String? = pendingSwapCleanup[taskId]

    fun parkSwapCleanup(taskId: String, oldPath: String) {
        pendingSwapCleanup[taskId] = oldPath
    }

    fun isValidOutput(path: String?): Boolean =
        isValidOutputPath(
            path = path,
            isLocalValid = isLocalValid,
            isDocumentValid = isDocumentValid,
        )

    /**
     * A swap in flight parks the file it would have replaced. Removing the task
     * drops every remaining claim on that file, so it must not survive as an
     * orphan: row first, then the file, exactly like a completed swap.
     */
    fun deleteOrphanedSwapAsync(taskId: String) {
        pendingSwapCleanup.remove(taskId)?.takeIf { it.isNotBlank() }?.let { orphaned ->
            scope.launch(ioDispatcher) {
                deleteHistoryByPath(orphaned)
                deleteFile(orphaned).onFailure {
                    runCatching {
                        Log.w(TAG, "could not delete orphaned swapped file: ${it.message}")
                    }
                }
            }
        }
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
    suspend fun swapMatches(selections: List<Pair<Task, MatchCandidate>>): List<SwapResult> =
        withContext(ioDispatcher) {
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
                                runCatching {
                                    Log.w(
                                        TAG,
                                        "${task.id} swap failed unexpectedly: ${failure.message}",
                                        failure,
                                    )
                                }
                                swapResult(
                                    task,
                                    candidate,
                                    SwapOutcome.FetchFailed,
                                    shortReason(failure),
                                )
                            }
                }
            sendApplyNotifications()
            results
        }

    /**
     * One selection, start to finish. Returns the outcome; throws only on a bug,
     * which [swapMatches] turns into a [SwapOutcome.FetchFailed] line.
     */
    private suspend fun swapOne(task: Task, candidate: MatchCandidate): SwapResult {
        val current = store.get(task) ?: return swapResult(task, candidate, SwapOutcome.Missing)
        // A matching or downloading song must not have its state torn out from
        // under the worker.
        if (current.downloadState is FetchingInfo || current.downloadState is Running) {
            return swapResult(task, candidate, SwapOutcome.Busy)
        }
        val match = current.match ?: return swapResult(task, candidate, SwapOutcome.Missing)
        awaitEnginesReady()
        val fetched = fetchVideoInfo(candidate.url, task.preferences, task.id)
        val failure = fetched.exceptionOrNull()
        if (failure != null) {
            runCatching { Log.w(TAG, "${task.id} swap fetch failed: ${failure.message}") }
            // The old pick and its file were never touched; make sure a
            // half-applied pending flag cannot survive.
            if (match.pending) store.setMatch(task, match.cancelPending())
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
        store.setMatch(task, match.withSelection(candidate))
        store.setVideoInfo(task, info)
        store.setViewState(task, Task.ViewState.fromVideoInfo(info))
        setLiveNotice(task, info.liveStatus)
        // The fetch took seconds; a cancel during it wins over the pick.
        val queued = store.applyTransition(task, ReadyWithInfo) { it == current.downloadState }
        if (!queued) {
            pendingSwapCleanup.remove(task.id)
            store.stateOf(task).match?.takeIf { it.pending }?.let {
                store.setMatch(task, it.cancelPending())
            }
            return swapResult(task, candidate, SwapOutcome.Busy)
        }
        return swapResult(task, candidate, SwapOutcome.Queued)
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
    suspend fun cleanupSwappedFile(taskId: String, newPath: String?) {
        // Null means no swap was registered in this process (the pending pick
        // was restored after a restart): there is no parked path to delete,
        // but a verified replacement still promotes the pending pick.
        val oldPath = pendingSwapCleanup.remove(taskId)
        val task = store.findById(taskId)
        if (!isValidOutput(newPath)) {
            runCatching { Log.w(TAG, "swap for $taskId produced no file; keeping the old pick") }
            task?.let { t ->
                store.stateOf(t).match?.takeIf { it.pending }?.let {
                    store.setMatch(t, it.cancelPending())
                }
            }
            return
        }
        if (!oldPath.isNullOrBlank() && oldPath != newPath) {
            // Row first: a crash between the two leaves an orphan file (harmless)
            // instead of a history row pointing at a deleted file.
            deleteHistoryByPath(oldPath)
            deleteFile(oldPath).onFailure {
                runCatching { Log.w(TAG, "could not delete replaced file: ${it.message}") }
            }
        }
        task?.let { t ->
            store.stateOf(t).match?.takeIf { it.pending }?.let {
                store.setMatch(t, it.confirmSelection())
            }
        }
    }

    /**
     * A swapped download failed: drop the parked path and put the old pick
     * back. The old file and its history row were never touched, so they stay.
     */
    fun abandonSwappedFile(taskId: String) {
        if (pendingSwapCleanup.remove(taskId) == null) return
        val task = store.findById(taskId) ?: return
        store.stateOf(task).match?.takeIf { it.pending }?.let {
            store.setMatch(task, it.cancelPending())
        }
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
    fun enqueueParts(task: Task, parts: List<MatchCandidate>): Int {
        if (parts.isEmpty()) return 0
        val book = task.type as? TypeInfo.MetadataTrack ?: return 0
        // A part set lives in the book's batch; without one there is no group
        // to report progress or completion against, so nothing is invented.
        val batch = task.batch ?: return 0
        // A fetch or download in flight must not be torn out from under its
        // worker: a split plan replaces a held book, which is never active.
        val state = store.get(task) ?: return 0
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
        partTasks.forEach { (partTask, partState) -> onEnqueuePart(partTask, partState) }
        onRemoveTask(task)
        return partTasks.size
    }

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
}
