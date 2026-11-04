package com.junkfood.seal

import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.TaskFactory
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataTaskFactoryTest {

    private val tracks =
        listOf(
            Task.TypeInfo.MetadataTrack(title = "First", artist = "Artist", durationMs = 180_000L),
            Task.TypeInfo.MetadataTrack(title = "Second", artist = "Artist", durationMs = 240_000L),
        )

    private fun create(batchId: String) =
        TaskFactory.createMetadataTasks(
            provider = "Spotify",
            playlistTitle = "Road Trip",
            batchId = batchId,
            tracks = tracks,
            preferences = DownloadPreferences.EMPTY,
        )

    @Test
    fun `one task per selected track with indexes and batch identity`() {
        val tasks = create("batch-1")
        assertEquals(2, tasks.size)
        assertEquals(listOf(0, 1), tasks.map { (it.task.type as Task.TypeInfo.MetadataTrack).index })
        assertTrue(tasks.all { it.task.batch?.id == "batch-1" })
        assertTrue(tasks.all { it.task.type is Task.TypeInfo.MetadataTrack })
    }

    @Test
    fun `synthetic urls include the batch id and are unique per track`() {
        val tasks = create("batch-1")
        assertTrue(tasks.all { it.task.url.startsWith("metadata://Spotify/batch-1/") })
        assertEquals(2, tasks.map { it.task.id }.distinct().size)
    }

    @Test
    fun `ids differ across batches and preferences stay audio-only`() {
        val first = create("batch-1")
        val second = create("batch-2")
        assertNotEquals(first.first().task.id, second.first().task.id)
        assertTrue(first.all { it.task.preferences.extractAudio })
    }

    @Test
    fun `initial view state shows the catalogue title before matching`() {
        val first = create("batch-1").first()
        assertEquals(Idle, first.state.downloadState)
        assertEquals("First", first.state.viewState.title)
        assertEquals("Artist", first.state.viewState.uploader)
        assertEquals(180, first.state.viewState.duration)
        assertEquals("", first.state.viewState.url)
    }
}
