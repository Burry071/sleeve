package com.junkfood.seal

import com.junkfood.seal.util.CookieFileStatus
import com.junkfood.seal.ui.page.downloadv2.cookieRowRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cookie file's usability, which is what decides whether the recovery copy
 * offers "Retry with cookies" or "Add cookies".
 */
class CookieFileStatusTest {

    private val header = "# Netscape HTTP Cookie File"
    private val youtube = ".youtube.com\tTRUE\t/\tTRUE\t1893456000\tSID\tsecret"
    private val spotify = "#HttpOnly_.spotify.com\tTRUE\t/\tTRUE\t1893456000\tsp_dc\tsecret"

    @Test
    fun `a missing file is unusable and reported as missing`() {
        val status = CookieFileStatus.Missing

        assertFalse(status.exists)
        assertFalse(status.usable)
        assertEquals(R.string.cookies_row_missing, cookieRowRes(status))
    }

    @Test
    fun `header-only file has no entries and is unusable`() {
        val status = CookieFileStatus.fromLines(listOf(header, "", "# just a comment"))

        assertTrue(status.exists)
        assertEquals(0, status.entryCount)
        assertFalse(status.parseable)
        assertFalse(status.usable)
        assertEquals(R.string.cookies_row_missing, cookieRowRes(status))
    }

    @Test
    fun `http-only rows are data, not comments`() {
        val status = CookieFileStatus.fromLines(listOf(header, youtube, spotify))

        assertEquals(2, status.entryCount)
        assertTrue(status.parseable)
        assertTrue(status.usable)
        assertEquals(R.string.cookies_row_entries, cookieRowRes(status))
    }

    @Test
    fun `a malformed row makes the file unusable even beside good rows`() {
        val status = CookieFileStatus.fromLines(listOf(header, youtube, ".bad.com\tTRUE\t/"))

        assertTrue(status.exists)
        assertEquals(1, status.entryCount)
        assertFalse(status.parseable)
        assertFalse(status.usable)
        assertEquals(R.string.cookies_row_unparseable, cookieRowRes(status))
    }

    @Test
    fun `crlf and a byte order mark do not break a row`() {
        val status =
            CookieFileStatus.fromLines(listOf("\uFEFF$header\r", "$youtube\r", "\r\n"))

        assertEquals(1, status.entryCount)
        assertTrue(status.parseable)
        assertTrue(status.usable)
    }

    @Test
    fun `blank domains are malformed`() {
        val status = CookieFileStatus.fromLines(listOf(header, "\tTRUE\t/\tTRUE\t1\tname\tvalue"))

        assertFalse(status.parseable)
        assertFalse(status.usable)
    }

    @Test
    fun `usable needs exists, parseable and at least one row`() {
        assertTrue(CookieFileStatus(exists = true, entryCount = 1, parseable = true).usable)
        assertFalse(CookieFileStatus(exists = true, entryCount = 0, parseable = true).usable)
        assertFalse(CookieFileStatus(exists = true, entryCount = 1, parseable = false).usable)
        assertFalse(CookieFileStatus(exists = false, entryCount = 1, parseable = true).usable)
    }
}
