package com.junkfood.seal

import com.junkfood.seal.util.sanitizeSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class SanitizeSegmentTest {

    @Test
    fun `every FAT32-illegal character and the backslash are replaced`() {
        assertEquals("a_b_c_d_e_f_g_h_i_", sanitizeSegment("a/b\\c:d*e?f\"g<h>i|"))
    }

    @Test
    fun `C0 controls are replaced`() {
        assertEquals("line_break_tab_ctl", sanitizeSegment("line\nbreak\ttab\u0001ctl"))
    }

    @Test
    fun `an emoji-only name is kept`() {
        assertEquals("🎧 audiobook", sanitizeSegment("🎧 audiobook"))
    }

    @Test
    fun `CJK is kept`() {
        assertEquals("有声书 第1章", sanitizeSegment("有声书 第1章"))
    }

    @Test
    fun `the cap is honoured`() {
        assertEquals(120, sanitizeSegment("x".repeat(200)).length)
        assertEquals("abcdefghij", sanitizeSegment("abcdefghijklmno", maxLength = 10))
    }

    @Test
    fun `a clean name is unchanged`() {
        assertEquals("My Video - Part 1.mp4", sanitizeSegment("My Video - Part 1.mp4"))
    }

    @Test
    fun `defaults do not rename existing downloads`() {
        // A double space, a leading space and a trailing dot are legal on the
        // card, so the shared function must leave them alone.
        assertEquals("a  b. ", sanitizeSegment("a  b. "))
    }

    @Test
    fun `collapse and trim are opt-in`() {
        assertEquals("a b c", sanitizeSegment("a  b c", collapseWhitespace = true))
        assertEquals("a b", sanitizeSegment("  a b  ", trimWhitespace = true))
        assertEquals("a b", sanitizeSegment("  a  b  ", collapseWhitespace = true, trimWhitespace = true))
    }

    @Test
    fun `the Reddit settings match its old inline rule`() {
        assertEquals(
            "My Video_Part_1",
            sanitizeSegment(
                "My  Video/Part\n1",
                maxLength = 100,
                collapseWhitespace = true,
                trimWhitespace = true,
            ),
        )
        assertEquals(
            100,
            sanitizeSegment(
                "y".repeat(140),
                maxLength = 100,
                collapseWhitespace = true,
                trimWhitespace = true,
            ).length,
        )
    }
}
