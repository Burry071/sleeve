package com.junkfood.seal

import com.junkfood.seal.download.BatchInfo
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.TaskFactory
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistStampTest {

    private fun entries() =
        listOf(
            TaskFactory.TaskWithState(
                Task(
                    url = "https://youtube.com/playlist?list=1",
                    type = Task.TypeInfo.Playlist(1),
                    preferences = DownloadPreferences.EMPTY,
                ),
                Task.State(Idle, null, Task.ViewState()),
            ),
            TaskFactory.TaskWithState(
                Task(
                    url = "https://youtube.com/playlist?list=1",
                    type = Task.TypeInfo.Playlist(2),
                    preferences = DownloadPreferences.EMPTY,
                ),
                Task.State(Idle, null, Task.ViewState()),
            ),
        )

    @Test
    fun `stamping tags every entry and applies the chosen preferences`() {
        val batch = BatchInfo(id = "batch-yt", title = "Playlist", provider = "youtube")
        val preferences = DownloadPreferences.EMPTY.copy(extractAudio = true)

        val stamped = TaskFactory.stampBatch(entries(), preferences, batch)

        assertTrue(stamped.all { it.task.batch == batch })
        assertTrue(stamped.all { it.task.preferences == preferences })
        assertEquals(2, stamped.map { it.task.uiKey }.distinct().size)
    }

    @Test
    fun `stamping recomputes ids so batches with different settings never collide`() {
        val audioBatch = BatchInfo(id = "batch-a", title = "Playlist", provider = "youtube")
        val videoBatch = BatchInfo(id = "batch-b", title = "Playlist", provider = "youtube")
        val asAudio = DownloadPreferences.EMPTY.copy(extractAudio = true)
        val asVideo = DownloadPreferences.EMPTY.copy(extractAudio = false, splitByChapter = true)

        val first = TaskFactory.stampBatch(entries(), asAudio, audioBatch)
        val second = TaskFactory.stampBatch(entries(), asVideo, videoBatch)

        first.zip(second).forEach { (one, other) ->
            assertNotEquals(one.task.id, other.task.id)
        }
        assertEquals(2, first.map { it.task.id }.distinct().size)
    }
}
