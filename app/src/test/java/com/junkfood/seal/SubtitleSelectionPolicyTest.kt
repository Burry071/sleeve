package com.junkfood.seal

import com.junkfood.seal.util.SubtitleFormat
import com.junkfood.seal.util.SubtitleSelectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSelectionPolicyTest {

    private fun track() = listOf(SubtitleFormat(ext = "vtt", url = "https://example.com/s.vtt"))

    private val manual = mapOf("en" to track(), "ja" to track())
    private val auto = mapOf("en-orig" to track(), "fr" to track())

    @Test
    fun `codes that only exist as automatic captions go to the auto list`() {
        assertTrue(SubtitleSelectionPolicy.goesToAutoCaptions(manual, auto, "en-orig"))
        assertTrue(SubtitleSelectionPolicy.goesToAutoCaptions(manual, auto, "fr"))
        assertFalse(SubtitleSelectionPolicy.goesToAutoCaptions(manual, auto, "en"))
        // A code yt-dlp offers as a real subtitle wins even if captions repeat it.
        assertFalse(SubtitleSelectionPolicy.goesToAutoCaptions(manual, manual + auto, "en"))
    }

    @Test
    fun `initial selection keeps manual and automatic codes apart`() {
        val selection =
            SubtitleSelectionPolicy.initialSelection(
                subtitles = manual,
                autoCaptions = auto,
                enabled = true,
                patterns = "en|en-orig|fr",
            )
        assertEquals(listOf("en"), selection.subtitles)
        assertEquals(listOf("en-orig", "fr"), selection.autoCaptions)
    }

    @Test
    fun `subtitle selection is empty when the sheet had it switched off`() {
        val selection =
            SubtitleSelectionPolicy.initialSelection(
                subtitles = manual,
                autoCaptions = auto,
                enabled = false,
                patterns = ".*",
            )
        assertTrue(selection.subtitles.isEmpty())
        assertTrue(selection.autoCaptions.isEmpty())
    }

    @Test
    fun `a trailing comma never selects every language`() {
        assertTrue(SubtitleSelectionPolicy.matchesAny("en", "en,"))
        assertFalse(SubtitleSelectionPolicy.matchesAny("ja", "en,"))
        assertFalse(SubtitleSelectionPolicy.matchesAny("ja", ""))
    }

    @Test
    fun `an invalid pattern falls back to a literal match`() {
        assertTrue(SubtitleSelectionPolicy.matchesAny("en[", "en["))
        assertFalse(SubtitleSelectionPolicy.matchesAny("en", "en["))
        // A broken group must not swallow the rest of the list.
        assertTrue(SubtitleSelectionPolicy.matchesAny("ja", "en[,ja"))
    }

    @Test
    fun `saving new languages never leaves a trailing comma`() {
        assertEquals("en,ja", SubtitleSelectionPolicy.buildLanguagePatterns("", listOf("en", "ja")))
        assertEquals("en,ja", SubtitleSelectionPolicy.buildLanguagePatterns("en,", listOf("ja", "en")))
        assertEquals("en", SubtitleSelectionPolicy.buildLanguagePatterns(" en , ", listOf("en")))
    }
}
