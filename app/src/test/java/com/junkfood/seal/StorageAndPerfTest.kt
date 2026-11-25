package com.junkfood.seal

import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.download.engine.subworkersForRam
import com.junkfood.seal.download.isValidOutputPath
import com.junkfood.seal.download.maxConcurrencyForRam
import com.junkfood.seal.util.filterNewTemplates
import com.junkfood.seal.util.isSdcardSidecar
import com.junkfood.seal.util.sdcardTempChildName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageAndPerfTest {

    @get:Rule val tmp = TemporaryFolder()

    private val gib = 1024L * 1024 * 1024

    @Test
    fun `filterNewTemplates imports unseen templates with reset ids and skips existing ones`() {
        val existing =
            listOf(
                CommandTemplate(id = 1, name = "MP3", template = "-x --audio-format mp3"),
                CommandTemplate(id = 2, name = "Best", template = "-f best"),
            )
        val incoming =
            listOf(
                CommandTemplate(id = 1, name = "MP3", template = "-x --audio-format mp3"),
                CommandTemplate(id = 99, name = "Best", template = "-f best"),
                CommandTemplate(id = 42, name = "Opus", template = "-x --audio-format opus"),
            )

        val result = filterNewTemplates(incoming = incoming, existing = existing)

        assertEquals(
            listOf(CommandTemplate(id = 0, name = "Opus", template = "-x --audio-format opus")),
            result,
        )
    }

    @Test
    fun `maxConcurrencyForRam scales down on 4GB or low-RAM devices`() {
        assertEquals(2, maxConcurrencyForRam(totalRamBytes = 2 * gib, isLowRamDevice = false))
        assertEquals(2, maxConcurrencyForRam(totalRamBytes = 4 * gib, isLowRamDevice = false))
        assertEquals(2, maxConcurrencyForRam(totalRamBytes = 8 * gib, isLowRamDevice = true))
        assertEquals(3, maxConcurrencyForRam(totalRamBytes = 6 * gib, isLowRamDevice = false))
        assertEquals(3, maxConcurrencyForRam(totalRamBytes = 8 * gib, isLowRamDevice = false))
        assertEquals(3, maxConcurrencyForRam(totalRamBytes = 0L, isLowRamDevice = false))
    }

    @Test
    fun `subworkersForRam caps Ghost connections on budget devices`() {
        assertEquals(4, subworkersForRam(totalRamBytes = 3 * gib, isLowRamDevice = false))
        assertEquals(4, subworkersForRam(totalRamBytes = 4 * gib, isLowRamDevice = false))
        assertEquals(4, subworkersForRam(totalRamBytes = 8 * gib, isLowRamDevice = true))
        assertEquals(8, subworkersForRam(totalRamBytes = 6 * gib, isLowRamDevice = false))
        assertEquals(8, subworkersForRam(totalRamBytes = 12 * gib, isLowRamDevice = false))
        assertEquals(8, subworkersForRam(totalRamBytes = 0L, isLowRamDevice = false))
    }

    @Test
    fun `imageMemoryCachePercentForRam trims Coil memory share on low-RAM devices`() {
        assertEquals(
            0.12,
            imageMemoryCachePercentForRam(totalRamBytes = 4 * gib, isLowRamDevice = false),
            0.0001,
        )
        assertEquals(
            0.12,
            imageMemoryCachePercentForRam(totalRamBytes = 8 * gib, isLowRamDevice = true),
            0.0001,
        )
        assertEquals(
            0.20,
            imageMemoryCachePercentForRam(totalRamBytes = 8 * gib, isLowRamDevice = false),
            0.0001,
        )
    }

    @Test
    fun `isValidOutputPath validates SAF content uris without treating them as local Files`() {
        val safUri =
            "content://com.android.externalstorage.documents/tree/1234-5678%3AMusic/document/1234-5678%3AMusic%2Ftrack.m4a"
        var localChecked = false

        assertTrue(
            isValidOutputPath(
                path = safUri,
                isLocalValid = {
                    localChecked = true
                    false
                },
                isDocumentValid = { it == safUri },
            )
        )
        assertFalse(localChecked)

        assertFalse(
            isValidOutputPath(
                path = safUri,
                isLocalValid = { true },
                isDocumentValid = { false },
            )
        )
        assertFalse(isValidOutputPath(path = null))
        assertFalse(isValidOutputPath(path = "   "))
    }

    @Test
    fun `isValidOutputPath checks local file existence and non-zero size`() {
        val realFile = tmp.newFile("clip.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val emptyFile = tmp.newFile("empty.mp4")

        assertTrue(isValidOutputPath(path = realFile.absolutePath, isDocumentValid = { false }))
        assertFalse(isValidOutputPath(path = emptyFile.absolutePath, isDocumentValid = { false }))
        assertFalse(
            isValidOutputPath(
                path = realFile.parentFile!!.resolve("missing.mp4").absolutePath,
                isDocumentValid = { false },
            )
        )
    }

    @Test
    fun `isSdcardSidecar filters thumbnails subtitles and metadata while keeping media`() {
        assertTrue(isSdcardSidecar("video.jpg"))
        assertTrue(isSdcardSidecar("video.PNG"))
        assertTrue(isSdcardSidecar("video.jpeg"))
        assertTrue(isSdcardSidecar("video.webp"))
        assertTrue(isSdcardSidecar("video.en.vtt"))
        assertTrue(isSdcardSidecar("video.srt"))
        assertTrue(isSdcardSidecar("video.ass"))
        assertTrue(isSdcardSidecar("video.lrc"))
        assertTrue(isSdcardSidecar("video.mp4.ytdl"))
        assertTrue(isSdcardSidecar("video.info.json"))
        assertTrue(isSdcardSidecar("video.description"))

        assertFalse(isSdcardSidecar("photo.jpg", keepImages = true))
        assertFalse(isSdcardSidecar("photo.PNG", keepImages = true))
        assertFalse(isSdcardSidecar("photo.jpeg", keepImages = true))
        assertFalse(isSdcardSidecar("photo.webp", keepImages = true))
        assertTrue(isSdcardSidecar("photo.vtt", keepImages = true))
        assertTrue(isSdcardSidecar("photo.info.json", keepImages = true))

        assertFalse(isSdcardSidecar("clip.mp4"))
        assertFalse(isSdcardSidecar("book.m4b"))
        assertFalse(isSdcardSidecar("track.opus"))
    }

    @Test
    fun `sdcardTempChildName produces deterministic 16-hex flat names safe from traversal`() {
        val hashed = sdcardTempChildName("../../etc/passwd")

        assertEquals(16, hashed.length)
        assertTrue(hashed.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(hashed, sdcardTempChildName("../../etc/passwd"))
        assertNotEquals(hashed, sdcardTempChildName("other-video-id"))
    }
}
