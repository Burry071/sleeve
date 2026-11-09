package com.junkfood.seal

import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState
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
}
