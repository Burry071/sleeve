package com.junkfood.seal

import com.junkfood.seal.download.CompletedNote
import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.util.AUDIO_FILE_EXTENSIONS
import com.junkfood.seal.util.AudioVerify
import com.junkfood.seal.util.DirectMediaRules
import com.junkfood.seal.util.M4bExportResult
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.runExportGuarded
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chapters arrive as float seconds in the resolved extractor's `chapters[]`;
 * the export reads them from there, never from a second catalogue probe.
 */
class ChaptersParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `start and end times decode from the extractor payload`() {
        val info =
            json.decodeFromString<VideoInfo>(
                """
                {
                  "id": "abc",
                  "title": "Book",
                  "vcodec": "none",
                  "chapters": [
                    {"title": "One", "start_time": 0.0, "end_time": 61.5},
                    {"title": "Two", "start_time": 61.5, "end_time": 3600.25}
                  ]
                }
                """
                    .trimIndent()
            )

        val chapters = info.chapters.orEmpty()
        assertEquals(2, chapters.size)
        assertEquals("One", chapters[0].title)
        assertEquals(61.5, chapters[0].endTime!!, 0.0)
        assertEquals(3600.25, chapters[1].endTime!!, 0.0)
        assertEquals("none", info.vcodec)
    }

    @Test
    fun `a payload without chapters stays empty and exportable-free`() {
        val info = json.decodeFromString<VideoInfo>("""{"id":"abc","title":"Song"}""")
        assertNull(info.chapters)
        assertTrue(info.chapters.isNullOrEmpty())
    }
}

class M4bExtensionListTest {

    @Test
    fun `m4b is audio everywhere the app classifies by container`() {
        assertTrue("m4b" in AUDIO_FILE_EXTENSIONS)
        assertTrue("m4b" in DirectMediaRules.MEDIA_EXTS)
        assertTrue(DirectMediaRules.isDirectMediaUrl("https://host/book.m4b"))
        assertFalse(DirectMediaRules.isDirectImageUrl("https://host/book.m4b"))
    }
}

class CompletedNotesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the export note survives a save and restore`() {
        val state = Completed("/tmp/book.m4b", notes = listOf(CompletedNote.M4B_NOT_EXPORTED))
        val restored =
            json.decodeFromString(
                DownloadState.serializer(),
                json.encodeToString(DownloadState.serializer(), state),
            ) as Completed

        assertEquals(listOf(CompletedNote.M4B_NOT_EXPORTED), restored.notes)
    }

    @Test
    fun `a state written before notes existed decodes to none`() {
        val restored =
            json.decodeFromString(
                DownloadState.serializer(),
                """{"type":"com.junkfood.seal.download.Task.DownloadState.Completed","filePath":"/tmp/a.m4a","warning":""}""",
            ) as Completed

        assertTrue(restored.notes.isEmpty())
    }
}

class CanReplaceSourceTest {

    @Test
    fun `an equal or slightly longer file may replace its source`() {
        assertTrue(AudioVerify.canReplaceSource(60_000L, 60_000L))
        assertTrue(AudioVerify.canReplaceSource(60_000L, 61_000L))
        // Container math differs by milliseconds; two seconds of slack absorbs it.
        assertTrue(AudioVerify.canReplaceSource(60_000L, 58_000L))
    }

    @Test
    fun `a measurably shorter file may not`() {
        assertFalse(AudioVerify.canReplaceSource(60_000L, 57_999L))
    }

    @Test
    fun `an unknown side never passes`() {
        assertFalse(AudioVerify.canReplaceSource(0L, 60_000L))
        assertFalse(AudioVerify.canReplaceSource(60_000L, 0L))
    }

    @Test
    fun `the measured comparison writes the same reasons as the file check`() {
        // Two minutes of absolute slack: 20 minutes of a 100-minute book is a
        // truncated download, not container padding.
        val tooShort = AudioVerify.verifyMeasured(actualMs = 20_000_000L, expectedMs = 100_000_000L)
        assertFalse(tooShort.ok)
        assertEquals("downloaded file is 20% of the book", tooShort.reason)

        assertEquals(
            "could not measure the downloaded file",
            AudioVerify.verifyMeasured(actualMs = 0L, expectedMs = 10_000L).reason,
        )
        assertEquals(
            "catalogue runtime unknown",
            AudioVerify.verifyMeasured(actualMs = 10_000L, expectedMs = 0L).reason,
        )

        val ok = AudioVerify.verifyMeasured(actualMs = 10_000L, expectedMs = 10_500L)
        assertTrue(ok.ok)
        assertEquals("", ok.reason)
    }
}

/**
 * The export is guarded process-wide and inside a non-cancellable region: a
 * cancel never runs the body half-way, and never leaves the mutex held.
 */
class M4bExportGuardTest {

    @Test
    fun `an inactive task never enters the export body`() = runBlocking {
        var ran = false
        val result =
            runExportGuarded(isActive = { false }) {
                ran = true
                M4bExportResult.Verified(File("x.m4b"))
            }

        assertEquals(M4bExportResult.Cancelled, result)
        assertFalse(ran)
    }

    @Test
    fun `a refusal releases the lock for the next export`() = runBlocking {
        runExportGuarded(isActive = { true }) { M4bExportResult.Failed }
        val second = runExportGuarded(isActive = { true }) { M4bExportResult.Failed }

        assertEquals(M4bExportResult.Failed, second)
    }

    @Test
    fun `a cancel mid-export does not abort the cleanup region`() = runBlocking {
        var cleaned = false
        val job =
            launch {
                runExportGuarded(isActive = { true }) {
                    // A cancellable suspension point inside the guard: with a
                    // plain withContext this would throw here and skip cleanup.
                    delay(300)
                    cleaned = true
                    M4bExportResult.Verified(File("x.m4b"))
                }
            }
        // The body suspends inside the guarded region well before this lands.
        delay(100)
        job.cancel()
        job.join()

        assertTrue(cleaned)
    }
}
