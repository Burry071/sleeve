package com.junkfood.seal.download

import android.app.PendingIntent
import android.content.Context
import com.junkfood.seal.R
import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.download.Task.RestartableAction.FetchInfo
import com.junkfood.seal.download.Task.TypeInfo
import com.junkfood.seal.util.DownloadErrorMessages
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.NotificationUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A cancelable task clears its notification inside cancel. Every other state
 * keeps a finished/failed notification whose actions would point at a task that
 * no longer exists after removal.
 */
internal fun clearsOwnNotificationOnRemoval(
    state: Task.State,
    usesOwnNotification: Boolean,
): Boolean = usesOwnNotification && state.downloadState !is DownloadState.Cancelable

internal val Task.notificationId: Int
    get() = id.hashCode()

/**
 * Projects [Task.DownloadState] transitions and batch summaries onto Android
 * notifications (one per standalone task, one per batch).
 */
internal class TaskNotificationSync(
    private val appContext: Context,
    private val store: TaskStore,
    private val scope: CoroutineScope,
) {
    /**
     * Notifications are a projection of the state, not a side effect each
     * writer must remember. One notification per task, one per batch.
     */
    fun syncNotifications(task: Task, next: DownloadState) {
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
                            title = store.stateOf(task).viewState.title,
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
                            title = store.stateOf(task).viewState.title,
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
                            withContext(Dispatchers.IO) {
                                with(FileUtil) { appContext.readCookieStatus() }
                            }
                        // A restart can replace the error while the file is
                        // read; the newer state posts its own notification.
                        if (store.get(task)?.downloadState !is Error) return@launch
                        val loginWalled =
                            next.throwable is DownloadErrorMessages.LoginRequiredError
                        NotificationUtil.notifyError(
                            title = store.stateOf(task).viewState.title,
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

    fun cancelOwnNotification(task: Task) {
        NotificationUtil.cancelNotification(task.notificationId)
    }

    fun refreshAllBatchNotifications() {
        // A restored batch is all Canceled/Idle, so no state write will ever
        // reach syncNotifications. Without this refresh the ongoing,
        // non-dismissable notification of the killed process would survive
        // with stale progress: cancel it when nothing is active, re-post it
        // when the restore left an active song.
        store.distinctBatches().forEach { updateBatchNotification(it) }
    }

    fun updateBatchNotificationForId(batchId: String) {
        store.distinctBatches().firstOrNull { it.id == batchId }?.let { updateBatchNotification(it) }
    }

    /**
     * One playlist notification per batch. Shown while any song is matching or
     * downloading, removed as soon as none is — including on stop and dismiss.
     */
    fun updateBatchNotification(batch: BatchInfo) {
        val notificationId = batch.id.hashCode()
        val entries = BatchOps.entries(store.taskStateMap, batch.id)
        // Once the batch is done, a flagged pick replaces the progress
        // notification with the single review entry.
        if (MatchOps.reviewVisible(entries)) {
            NotificationUtil.notifyBatchReview(
                title = batch.title,
                text =
                    appContext.getString(
                        R.string.batch_notification_review,
                        MatchOps.reviewCount(entries),
                    ),
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
}
