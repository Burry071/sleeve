package com.junkfood.seal

import com.junkfood.seal.download.BatchInfo
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.TaskFactory
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistStampTest {

    @Test
    fun `stamping tags every entry and applies the chosen preferences`() {
        val batch = BatchInfo(id = "batch-yt", title = "Playlist", provider = "youtube")
        val entries =
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
        val preferences = DownloadPreferences.EMPTY.copy(extractAudio = true)

        val stamped = TaskFactory.stampBatch(entries, preferences, batch)

        assertTrue(stamped.all { it.task.batch == batch })
        assertTrue(stamped.all { it.task.preferences == preferences })
        assertEquals(entries.map { it.task.id }, stamped.map { it.task.id })
        assertEquals(2, stamped.map { it.task.uiKey }.distinct().size)
    }
}
