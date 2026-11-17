package com.junkfood.seal

import com.junkfood.seal.util.SplitHistoryPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A chapter split leaves the unsplit master beside its parts and the media
 * scanner still reports it (it was indexed before the delete), so the history
 * insert used to add the whole book next to every chapter.
 */
class SplitHistoryTest {

    private val master = "/storage/emulated/0/Download/Sleeve/Book/Book.mp3"
    private val first = "/storage/emulated/0/Download/Sleeve/Book/01 - Intro.mp3"
    private val second = "/storage/emulated/0/Download/Sleeve/Book/02 - End.mp3"

    @Test
    fun `the unsplit parent never reaches split history`() {
        assertEquals(listOf(first, second), SplitHistoryPolicy.chapterPaths(listOf(master, first, second), master))
    }

    @Test
    fun `without a named master every scanned path is a chapter`() {
        assertEquals(listOf(master, first, second), SplitHistoryPolicy.chapterPaths(listOf(master, first, second), null))
    }

    @Test
    fun `a master outside the list changes nothing`() {
        assertEquals(listOf(first, second), SplitHistoryPolicy.chapterPaths(listOf(first, second), master))
    }
}
