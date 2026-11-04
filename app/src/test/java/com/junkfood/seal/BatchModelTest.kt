package com.junkfood.seal

import com.junkfood.seal.download.BatchInfo
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.TypeInfo
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchModelTest {

    private val json = Json {
        ignoreUnknownKeys = true
        allowStructuredMapKeys = true
    }

    private val batchA = BatchInfo(id = "batch-a", title = "Album A", provider = "Spotify")
    private val batchB = BatchInfo(id = "batch-b", title = "Album A", provider = "Spotify")

    private fun metadataTask(
        batch: BatchInfo,
        index: Int = 0,
        title: String = "Song",
        durationMs: Long = 200_000L,
    ) =
        Task(
            url = "metadata://Spotify/${batch.id}/$index",
            type =
                TypeInfo.MetadataTrack(
                    index = index,
                    provider = "Spotify",
                    title = title,
                    artist = "Artist",
                    album = "Album A",
                    durationMs = durationMs,
                ),
            preferences = DownloadPreferences.EMPTY,
            batch = batch,
        )

    @Test
    fun `identical tracks in different batches get different ids`() {
        assertNotEquals(metadataTask(batchA).id, metadataTask(batchB).id)
    }

    @Test
    fun `task id is stable for the same track in the same batch`() {
        assertEquals(metadataTask(batchA).id, metadataTask(batchA).id)
    }

    @Test
    fun `ui key disambiguates equal ids stamped with different batches`() {
        val single = Task(url = "https://youtu.be/x", preferences = DownloadPreferences.EMPTY)
        val first = single.copy(batch = batchA)
        val second = single.copy(batch = batchB)
        assertEquals(first.id, second.id)
        assertNotEquals(first.uiKey, second.uiKey)
        assertEquals("${single.id}#", single.uiKey)
    }

    @Test
    fun `single tasks post their own notification and batch tasks do not`() {
        assertTrue(
            Task(url = "https://youtu.be/x", preferences = DownloadPreferences.EMPTY)
                .usesOwnNotification
        )
        assertFalse(metadataTask(batchA).usesOwnNotification)
    }

    @Test
    fun `batch and metadata track survive a backup round trip`() {
        val task = metadataTask(batchA, index = 3)
        val backup = mapOf(task to Task.State(Idle, null, Task.ViewState(title = "Song")))
        val decoded =
            json.decodeFromString<Map<Task, Task.State>>(
                json.encodeToString<Map<Task, Task.State>>(backup)
            )
        assertEquals(task, decoded.keys.single())
        assertEquals(batchA, decoded.keys.single().batch)
    }

    @Test
    fun `a backup written before the batch field decodes with a null batch`() {
        val legacy =
            Task(
                url = "https://youtu.be/x",
                preferences = DownloadPreferences.EMPTY,
                // No batch: encoding omits default values, exactly like an old build.
            )
        val encoded =
            json.encodeToString<Map<Task, Task.State>>(
                mapOf(legacy to Task.State(Completed(null), null, Task.ViewState()))
            )
        assertFalse(encoded.contains("\"batch\""))
        val decoded = json.decodeFromString<Map<Task, Task.State>>(encoded)
        assertEquals(null, decoded.keys.single().batch)
        assertEquals(legacy.id, decoded.keys.single().id)
    }

    @Test
    fun `metadata type id is per index and does not include the batch`() {
        val first = metadataTask(batchA, index = 0)
        val sameIndexOtherBatch = metadataTask(batchB, index = 0)
        assertEquals(
            first.id.substringAfterLast('_'),
            sameIndexOtherBatch.id.substringAfterLast('_'),
        )
        assertNotEquals(first.id, sameIndexOtherBatch.id)
        assertTrue(first.id.endsWith("_metadata:0_${DownloadPreferences.EMPTY.hashCode()}"))
    }
}
