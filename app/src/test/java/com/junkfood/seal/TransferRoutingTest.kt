package com.junkfood.seal

import com.junkfood.seal.download.engine.outputIsAudio
import com.junkfood.seal.download.engine.selectedFormats
import com.junkfood.seal.download.engine.singleFormatHandOffAllowed
import com.junkfood.seal.util.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The embedded engines only ever write one progressive stream. Handing them a
 * silent video strand loses the audio yt-dlp would have muxed, and an .mkv
 * remux is something they cannot do at all, so both must stay on yt-dlp.
 */
class TransferRoutingTest {

    private fun audio(id: String) =
        Format(formatId = id, acodec = "opus", vcodec = "none", ext = "webm")

    private fun silent(id: String) = Format(formatId = id, acodec = "none", vcodec = "avc1", ext = "mp4")

    private fun muxed(id: String) = Format(formatId = id, acodec = "aac", vcodec = "avc1", ext = "mp4")

    @Test
    fun `a single progressive stream is eligible`() {
        assertTrue(singleFormatHandOffAllowed(muxed("18"), mergeToMkv = false))
        assertTrue(singleFormatHandOffAllowed(audio("140"), mergeToMkv = false))
    }

    @Test
    fun `a video-only stream stays on yt-dlp`() {
        assertFalse(singleFormatHandOffAllowed(silent("137"), mergeToMkv = false))
    }

    @Test
    fun `an mkv remux stays on yt-dlp`() {
        assertFalse(singleFormatHandOffAllowed(muxed("18"), mergeToMkv = true))
    }

    @Test
    fun `nothing resolved means no handoff`() {
        assertFalse(singleFormatHandOffAllowed(null, mergeToMkv = false))
    }

    @Test
    fun `the format string resolves back to the picked formats`() {
        val formats = listOf(silent("137"), audio("251"), muxed("18"))

        assertEquals(listOf("137", "251"), selectedFormats("137+251", formats).map { it.formatId })
        assertEquals(listOf("18"), selectedFormats("18", formats).map { it.formatId })
        assertTrue(selectedFormats("", formats).isEmpty())
        assertTrue(selectedFormats("999", formats).isEmpty())
    }

    @Test
    fun `the audio folder follows the picked formats not the type flag alone`() {
        assertTrue(outputIsAudio(extractAudio = false, selected = listOf(audio("140"))))
        assertTrue(outputIsAudio(extractAudio = true, selected = emptyList()))
    }

    @Test
    fun `a video stream keeps the video folder even when it carries audio`() {
        assertFalse(outputIsAudio(extractAudio = false, selected = listOf(muxed("18"))))
        assertFalse(outputIsAudio(extractAudio = false, selected = listOf(silent("137"), audio("140"))))
        assertFalse(outputIsAudio(extractAudio = false, selected = emptyList()))
    }
}
