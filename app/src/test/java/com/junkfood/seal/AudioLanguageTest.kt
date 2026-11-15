package com.junkfood.seal

import com.junkfood.seal.util.Format
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.audioLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gate holds a book only when it knows the audio is in another language,
 * so this helper must report unknown as empty rather than guess.
 */
class AudioLanguageTest {

    private fun audio(code: String?) = Format(acodec = "opus", vcodec = "none", language = code)

    @Test
    fun `the top-level language wins`() {
        val info = VideoInfo(language = "de", formats = listOf(audio("en")))
        assertEquals("de", info.audioLanguage())
    }

    @Test
    fun `one language shared by every audio format is the track language`() {
        val info =
            VideoInfo(
                formats =
                    listOf(
                        Format(acodec = "opus", vcodec = "none", language = "de"),
                        Format(acodec = "aac", vcodec = "none", language = "de"),
                    )
            )
        assertEquals("de", info.audioLanguage())
    }

    @Test
    fun `a video-only format never names the audio language`() {
        val info =
            VideoInfo(
                formats =
                    listOf(
                        Format(vcodec = "avc1", acodec = "none", language = "fr"),
                        audio("de"),
                    )
            )
        assertEquals("de", info.audioLanguage())
    }

    @Test
    fun `two audio languages are unknown, not a guess`() {
        val info = VideoInfo(formats = listOf(audio("de"), audio("en")))
        assertEquals("", info.audioLanguage())
    }

    @Test
    fun `an unlabelled or blank language is unknown`() {
        assertEquals("", VideoInfo().audioLanguage())
        assertEquals("", VideoInfo(language = "   ").audioLanguage())
        assertEquals("", VideoInfo(formats = listOf(audio(""), audio(null))).audioLanguage())
    }

    @Test
    fun `a blank top-level value falls back to the formats`() {
        val info = VideoInfo(language = " ", formats = listOf(audio("en-US")))
        assertEquals("en-US", info.audioLanguage())
    }
}
