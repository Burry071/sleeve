package com.junkfood.seal

import com.junkfood.seal.util.Format
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * yt-dlp reports `"unknown"` and blank codec strings from some extractors, and
 * storyboards carry `none` for both. Classifying every non-null string as a
 * real codec filed storyboards under Audio and let "unknown" override the
 * container guess the generic extractor depends on.
 */
class FormatClassificationTest {

    private fun format(
        vcodec: String?,
        acodec: String?,
        ext: String? = "mp4",
        id: String = "1",
    ) = Format(formatId = id, vcodec = vcodec, acodec = acodec, ext = ext)

    @Test
    fun `a storyboard is neither audio nor video`() {
        val storyboard = format(vcodec = "none", acodec = "none", ext = "mhtml")
        assertFalse(storyboard.isAudioOnly())
        assertFalse(storyboard.containsAudio())
        assertFalse(storyboard.containsVideo())
    }

    @Test
    fun `placeholder codecs fall back to the container`() {
        assertTrue(format(vcodec = "unknown", acodec = "unknown", ext = "mp3").isAudioOnly())
        assertFalse(format(vcodec = "unknown", acodec = "unknown", ext = "mp4").isAudioOnly())
        assertTrue(format(vcodec = "", acodec = "", ext = "m4a").isAudioOnly())
    }

    @Test
    fun `a silent video stream is video only`() {
        val silent = format(vcodec = "avc1", acodec = "none")
        assertTrue(silent.isVideoOnly())
        assertTrue(silent.containsVideo())
        assertFalse(silent.containsAudio())
        assertFalse(silent.isAudioOnly())
    }

    @Test
    fun `an audio stream with unknown video codec stays audio`() {
        assertTrue(format(vcodec = "unknown", acodec = "opus", ext = "webm").isAudioOnly())
        assertTrue(format(vcodec = "none", acodec = "opus", ext = "webm").isAudioOnly())
    }

    @Test
    fun `a muxed stream is neither audio-only nor video-only`() {
        val muxed = format(vcodec = "avc1", acodec = "mp4a.40.2")
        assertFalse(muxed.isAudioOnly())
        assertFalse(muxed.isVideoOnly())
        assertTrue(muxed.containsVideo())
        assertTrue(muxed.containsAudio())
    }
}
