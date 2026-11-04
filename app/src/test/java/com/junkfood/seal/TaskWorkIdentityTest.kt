package com.junkfood.seal

import com.junkfood.seal.download.BatchInfo
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.TypeInfo
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskWorkIdentityTest {

    private fun metadataTask(batchId: String, index: Int = 0) =
        Task(
            url = "metadata://Spotify/$batchId/$index",
            type =
                TypeInfo.MetadataTrack(
                    index = index,
                    provider = "Spotify",
                    title = "Song",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 200_000L,
                ),
            preferences = DownloadPreferences.EMPTY,
            batch = BatchInfo(id = batchId, title = "Album", provider = "Spotify"),
        )

    @Test
    fun `same track from two playlist submissions is the same work`() {
        assertTrue(metadataTask("batch-a").isSameWork(metadataTask("batch-b")))
    }

    @Test
    fun `different track positions are different work`() {
        val first = metadataTask("batch-a", index = 0)
        val second = metadataTask("batch-b", index = 1)
        assertFalse(first.isSameWork(second))
    }

    @Test
    fun `playlist entries match by index across submissions`() {
        val url = "https://www.youtube.com/playlist?list=x"
        val first =
            Task(
                url = url,
                type = TypeInfo.Playlist(index = 2),
                preferences = DownloadPreferences.EMPTY,
                batch = BatchInfo(id = "batch-a", title = "List", provider = "YouTube"),
            )
        val second = first.copy(batch = BatchInfo(id = "batch-b", title = "List", provider = "YouTube"))
        val otherIndex = second.copy(type = TypeInfo.Playlist(index = 3))
        assertTrue(first.isSameWork(second))
        assertFalse(first.isSameWork(otherIndex))
    }

    @Test
    fun `different preferences are different work`() {
        val audio = metadataTask("batch-a")
        val video = audio.copy(preferences = DownloadPreferences.EMPTY.copy(extractAudio = true))
        assertFalse(audio.isSameWork(video))
    }
}
