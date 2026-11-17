package com.junkfood.seal

import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.TaskKind
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskKindTest {

    private fun task(extractAudio: Boolean) =
        Task(
            url = "https://example.com/v",
            preferences = DownloadPreferences.EMPTY.copy(extractAudio = extractAudio),
        )

    private fun state(downloadState: DownloadState) =
        Task.State(
            downloadState = downloadState,
            videoInfo = null,
            viewState = Task.ViewState(url = "https://example.com/v", title = "title"),
        )

    @Test
    fun `a finished audio file counts as audio whatever was asked for`() {
        assertTrue(TaskKind.isAudio(task(extractAudio = false), state(DownloadState.Completed("/x/a.mp3"))))
        assertTrue(TaskKind.isAudio(task(extractAudio = false), state(DownloadState.Completed("/x/a.m4a"))))
    }

    @Test
    fun `a finished video file counts as video whatever was asked for`() {
        assertFalse(TaskKind.isAudio(task(extractAudio = true), state(DownloadState.Completed("/x/a.mp4"))))
    }

    @Test
    fun `an unfinished transfer follows the task's own intent`() {
        assertTrue(TaskKind.isAudio(task(extractAudio = true), state(DownloadState.Idle)))
        assertFalse(TaskKind.isAudio(task(extractAudio = false), state(DownloadState.Idle)))
    }

    @Test
    fun `a finished file with an unknown name follows the intent`() {
        val saf = "content://com.android.externalstorage.documents/document/primary%3Aa.mp3"
        assertTrue(TaskKind.isAudio(task(extractAudio = true), state(DownloadState.Completed(saf))))
        assertFalse(TaskKind.isAudio(task(extractAudio = false), state(DownloadState.Completed(saf))))
    }
}
