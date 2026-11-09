package com.junkfood.seal

import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.clearsOwnNotificationOnRemoval
import com.junkfood.seal.download.needsCancelBeforeRemoval
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRemovalTest {

    private fun state(downloadState: DownloadState) =
        Task.State(
            downloadState = downloadState,
            videoInfo = null,
            viewState = Task.ViewState(url = "https://example.com/v", title = "title"),
        )

    @Test
    fun deletingARunningTaskStopsItsWorker() {
        assertTrue(needsCancelBeforeRemoval(state(DownloadState.Running(job = Job(), taskId = "t"))))
    }

    @Test
    fun deletingAFetchingTaskStopsItsWorker() {
        assertTrue(needsCancelBeforeRemoval(state(DownloadState.FetchingInfo(job = Job(), taskId = "t"))))
    }

    @Test
    fun deletingIdleOrFinishedHistoryCancelsNothing() {
        assertFalse(needsCancelBeforeRemoval(state(DownloadState.Idle)))
        assertFalse(needsCancelBeforeRemoval(state(DownloadState.ReadyWithInfo)))
        assertFalse(needsCancelBeforeRemoval(state(DownloadState.Completed(filePath = null))))
        assertFalse(
            needsCancelBeforeRemoval(
                state(DownloadState.Canceled(action = Task.RestartableAction.Download))
            )
        )
    }

    @Test
    fun deletingATerminalTaskTakesItsOwnNotificationWithIt() {
        // Failed/completed tasks keep a notification with Retry/Open actions;
        // after removal nothing could answer those actions.
        assertTrue(
            clearsOwnNotificationOnRemoval(
                state(DownloadState.Error(action = Task.RestartableAction.Download)),
                usesOwnNotification = true,
            )
        )
        assertTrue(
            clearsOwnNotificationOnRemoval(
                state(DownloadState.Completed(filePath = "/tmp/a.mp4")),
                usesOwnNotification = true,
            )
        )
        assertTrue(
            clearsOwnNotificationOnRemoval(
                state(DownloadState.Canceled(action = Task.RestartableAction.Download)),
                usesOwnNotification = true,
            )
        )
    }

    @Test
    fun cancelableAndBatchTasksDoNotNeedTheExtraClear() {
        assertFalse(
            clearsOwnNotificationOnRemoval(
                state(DownloadState.Running(job = Job(), taskId = "t")),
                usesOwnNotification = true,
            )
        )
        assertFalse(
            clearsOwnNotificationOnRemoval(
                state(DownloadState.Error(action = Task.RestartableAction.Download)),
                usesOwnNotification = false,
            )
        )
    }
}
