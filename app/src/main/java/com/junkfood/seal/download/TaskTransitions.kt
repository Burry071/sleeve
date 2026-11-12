package com.junkfood.seal.download

import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.download.Task.RestartableAction
import com.junkfood.seal.download.Task.RestartableAction.Download
import com.junkfood.seal.download.Task.RestartableAction.FetchInfo

/**
 * The legal edges of the download state machine, plus the decisions that used
 * to live as `else ->` branches in the engine. Everything here is pure so the
 * table is testable without Android; [DownloaderV2Impl.applyTransition] is the
 * only writer that consults it.
 */
internal object TaskTransitions {

    /**
     * Every edge the engine is allowed to take. A transition outside this table
     * is a bug in a writer, not user input: it is refused and logged, so a late
     * worker result can never overwrite a cancel, a completion, or a failure.
     */
    fun isLegal(from: DownloadState, to: DownloadState): Boolean =
        when (from) {
            Idle ->
                to is FetchingInfo || to is Running || to is Canceled || to is ReadyWithInfo
            is FetchingInfo ->
                to is ReadyWithInfo || to is Running || to is Canceled || to is Error
            // ReadyWithInfo -> ReadyWithInfo is a swapped pick landing on a song
            // that had not started its transfer yet.
            ReadyWithInfo ->
                to is Running || to is Canceled || to is Error || to is ReadyWithInfo
            is Running -> to is Running || to is Completed || to is Error || to is Canceled
            // A canceled/failed row is retried through Idle or straight to
            // ReadyWithInfo; a swap can also replace either pick.
            is Canceled -> to is Idle || to is ReadyWithInfo
            is Error -> to is Idle || to is ReadyWithInfo
            // A completed file can only be replaced by an explicit swap.
            is Completed -> to is ReadyWithInfo
        }

    /**
     * A process death cannot resume `yt-dlp`, a Ghost transfer, or a search.
     * Every in-flight state therefore restores as Canceled with the action that
     * would retry it; terminal states keep what they proved.
     */
    fun restore(from: DownloadState): DownloadState =
        when (from) {
            Idle, is FetchingInfo -> Canceled(action = FetchInfo)
            is Running -> Canceled(action = Download, progress = from.progress)
            ReadyWithInfo -> Canceled(action = Download, progress = null)
            is Canceled -> from
            is Error -> from
            is Completed -> from
        }

    /**
     * A duplicate enqueue must not reset work in flight: the scheduler would
     * start a second transfer over the same output path. Terminal work is a new
     * submission and starts over.
     */
    fun enqueueReplaces(existing: DownloadState?): Boolean =
        when (existing) {
            null -> true
            Idle -> true
            is Canceled, is Error, is Completed -> true
            is FetchingInfo, is Running, ReadyWithInfo -> false
        }

    /** Where Resume parks a resumable task, or null when it is not restartable. */
    fun restartTarget(from: DownloadState, isCustomCommand: Boolean): DownloadState? =
        when (from) {
            is Canceled -> retryState(from.action, isCustomCommand)
            is Error -> retryState(from.action, isCustomCommand)
            else -> null
        }

    /**
     * A custom command has no extracted info to download, and its worker only
     * accepts an Idle task: resuming it through ReadyWithInfo would park it
     * forever, so it re-enters through the Idle lane instead.
     */
    private fun retryState(action: RestartableAction, isCustomCommand: Boolean): DownloadState =
        when (action) {
            Download -> if (isCustomCommand) Idle else ReadyWithInfo
            FetchInfo -> Idle
        }

    /** The Canceled state a task of this kind enters, or null when not cancelable. */
    fun cancelTarget(from: DownloadState): DownloadState? =
        when (from) {
            Idle -> Canceled(action = FetchInfo)
            ReadyWithInfo -> Canceled(action = Download)
            is FetchingInfo -> Canceled(action = from.action)
            is Running -> Canceled(action = from.action, progress = from.progress)
            else -> null
        }
}
