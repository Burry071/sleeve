package com.junkfood.seal

import com.junkfood.seal.util.findURLsFromString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Share-text URL extraction is the one step quick download runs that an
 * in-app paste does not: a truncated or mangled URL here is exactly the
 * "share fails, paste works" bug class.
 */
class UrlExtractionTest {

    private fun one(s: String): String = findURLsFromString(s, firstMatchOnly = true).single()

    @Test
    fun `plain url survives`() {
        assertEquals("https://example.com/video", one("check this https://example.com/video"))
    }

    @Test
    fun `query parameters survive`() {
        val url = "https://www.youtube.com/watch?v=abc123&t=42s"
        assertEquals(url, one("look " + url + " now"))
    }

    @Test
    fun `plus in query survives (was truncated before)`() {
        val url = "https://example.com/search?q=one+two&x=1"
        assertEquals(url, one(url + " end"))
    }

    @Test
    fun `sentence punctuation at the end is trimmed`() {
        assertEquals("https://example.com/a", one("see https://example.com/a."))
        assertEquals("https://example.com/a", one("see https://example.com/a, and more"))
    }

    @Test
    fun `multiple urls are all found when not first-match-only`() {
        val urls =
            findURLsFromString("a https://x.com/1 b https://y.com/2?z=3")
        assertEquals(listOf("https://x.com/1", "https://y.com/2?z=3"), urls)
    }
}
