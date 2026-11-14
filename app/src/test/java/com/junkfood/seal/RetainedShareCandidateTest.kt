package com.junkfood.seal

import com.junkfood.seal.util.retainedShareCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetainedShareCandidateTest {

    private val dir = "/tmp/abc"

    private fun candidate(
        failed: List<String>,
        files: Set<String> = emptySet(),
        listing: List<String> = emptyList(),
    ): String? =
        retainedShareCandidate(
            retainedDir = dir,
            failed = failed,
            exists = { it in files },
            listing = { listing },
        )

    @Test
    fun `the first failed media name wins`() {
        assertEquals(
            "$dir/book.m4b",
            candidate(failed = listOf("thumb.png", "book.m4b"), files = setOf("book.m4b")),
        )
        assertEquals(
            "$dir/clip.mp4",
            candidate(failed = listOf("notes.txt", "clip.mp4"), files = setOf("notes.txt", "clip.mp4")),
        )
    }

    @Test
    fun `a media file in the directory is the second choice`() {
        assertEquals(
            "$dir/clip.mp4",
            candidate(
                failed = listOf("notes.txt"),
                files = setOf("notes.txt", "thumb.png", "clip.mp4"),
                listing = listOf("notes.txt", "thumb.png", "clip.mp4"),
            ),
        )
    }

    @Test
    fun `the retained directory's own name is never shared`() {
        assertEquals(null, candidate(failed = listOf("abc"), files = setOf("abc")))
    }

    @Test
    fun `scratch, ytdl and hidden names are never shared`() {
        assertNull(
            candidate(
                failed = listOf("a.mp4.part", "b.mp4.ytdl", ".hidden.mp4"),
                files = setOf("a.mp4.part", "b.mp4.ytdl", ".hidden.mp4"),
            )
        )
        assertNull(
            candidate(
                failed = emptyList(),
                files = setOf("y.mp4.part"),
                listing = listOf("y.mp4.part"),
            )
        )
    }

    @Test
    fun `a name with no file behind it is not a candidate`() {
        assertNull(candidate(failed = listOf("gone.mp4")))
    }

    @Test
    fun `the first failed name is the last resort`() {
        assertEquals("$dir/notes.txt", candidate(failed = listOf("notes.txt"), files = setOf("notes.txt")))
    }

    @Test
    fun `nothing qualifying hides the action`() {
        assertNull(candidate(failed = emptyList()))
        assertNull(
            candidate(
                failed = emptyList(),
                files = setOf("thumb.png"),
                listing = listOf("thumb.png"),
            )
        )
    }
}
