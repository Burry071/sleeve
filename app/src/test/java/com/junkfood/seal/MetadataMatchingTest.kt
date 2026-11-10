package com.junkfood.seal

import com.junkfood.seal.ghostengine.GhostEngine.RankedCandidate
import com.junkfood.seal.ghostengine.GhostEngine.SpotifyTrack
import com.junkfood.seal.util.DownloadUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataMatchingTest {

    @Test
    fun `query joins artist title and audio and collapses whitespace`() {
        val query =
            DownloadUtil.buildMetadataQuery(
                SpotifyTrack(
                    title = "  Song   Name ",
                    artist = " The  Artist ",
                    album = "Album",
                    durationMs = 200_000L,
                )
            )
        assertEquals("The Artist Song Name audio", query)
    }

    @Test
    fun `query skips blank parts`() {
        val query =
            DownloadUtil.buildMetadataQuery(
                SpotifyTrack(title = "Song", artist = "", album = "", durationMs = 0L)
            )
        assertEquals("Song audio", query)
    }

    @Test
    fun `audible query quotes the title and drops the narrator roll`() {
        val query =
            DownloadUtil.buildMetadataQuery(
                SpotifyTrack(
                    title = "Dune",
                    artist = "Frank Herbert (narrated by Scott Brick, Orlagh Cassidy)",
                    album = "Audible",
                    durationMs = 75_876_000L,
                    authors = "Frank Herbert",
                ),
                provider = "Audible",
            )
        assertEquals("\"Dune\" Frank Herbert audiobook", query)
    }

    @Test
    fun `audible query never carries the share string or narrator names`() {
        val query =
            DownloadUtil.buildMetadataQuery(
                SpotifyTrack(
                    title = "Dune",
                    artist =
                        "Frank Herbert (narrated by Scott Brick) " +
                            "https://www.audible.com/pd/B0036ZK8LQ",
                    album = "Audible",
                    durationMs = 75_876_000L,
                    authors = "Frank Herbert",
                ),
                provider = "audible",
            )
        assertEquals("\"Dune\" Frank Herbert audiobook", query)
    }

    @Test
    fun `audible query falls back to the quoted title when authors are missing`() {
        val query =
            DownloadUtil.buildMetadataQuery(
                SpotifyTrack(
                    title = "Dune",
                    artist = "Frank Herbert (narrated by Scott Brick)",
                    album = "Audible",
                    durationMs = 75_876_000L,
                ),
                provider = "Audible",
            )
        assertEquals("\"Dune\" audiobook", query)
    }

    @Test
    fun `spotify query keeps the catalogue shape`() {
        val query =
            DownloadUtil.buildMetadataQuery(
                SpotifyTrack(
                    title = "Song Name",
                    artist = "The Artist",
                    album = "Album",
                    durationMs = 200_000L,
                ),
                provider = "Spotify",
            )
        assertEquals("The Artist Song Name audio", query)
    }

    @Test
    fun `low confidence music match widens to the wide pool`() {
        assertTrue(DownloadUtil.shouldWidenSearch(null))
        assertTrue(DownloadUtil.shouldWidenSearch(35.0))
        assertTrue(DownloadUtil.shouldWidenSearch(59.9))
        assertFalse(DownloadUtil.shouldWidenSearch(60.0))
        assertFalse(DownloadUtil.shouldWidenSearch(91.0))
    }

    @Test
    fun `a floor-rejected music pool still searches the wide pool`() {
        val wide = candidate(id = "wide", score = 88.0)
        val best =
            DownloadUtil.matchBestCandidate(
                isActive = { true },
                musicSearch = { null },
                wideSearch = { wide },
            )
        assertEquals(wide, best)
    }

    @Test
    fun `a confident music match skips the second search`() {
        var wideSearches = 0
        val music = candidate(id = "music", score = 85.0)
        val best =
            DownloadUtil.matchBestCandidate(
                isActive = { true },
                musicSearch = { music },
                wideSearch = {
                    wideSearches++
                    null
                },
            )
        assertEquals(music, best)
        assertEquals(0, wideSearches)
    }

    @Test
    fun `a weak music match yields to a better wide match`() {
        val music = candidate(id = "music", score = 40.0)
        val wide = candidate(id = "wide", score = 88.0)
        val best =
            DownloadUtil.matchBestCandidate(
                isActive = { true },
                musicSearch = { music },
                wideSearch = { wide },
            )
        assertEquals(wide, best)
    }

    @Test
    fun `a weak music match is kept when the wide pool is worse`() {
        val music = candidate(id = "music", score = 40.0)
        val wide = candidate(id = "wide", score = 30.0)
        val best =
            DownloadUtil.matchBestCandidate(
                isActive = { true },
                musicSearch = { music },
                wideSearch = { wide },
            )
        assertEquals(music, best)
    }

    @Test
    fun `no match in either pool returns null`() {
        assertNull(
            DownloadUtil.matchBestCandidate(
                isActive = { true },
                musicSearch = { null },
                wideSearch = { null },
            )
        )
    }

    @Test
    fun `a canceled match does not search the wide pool`() {
        var wideSearches = 0
        val best =
            DownloadUtil.matchBestCandidate(
                isActive = { false },
                musicSearch = { null },
                wideSearch = {
                    wideSearches++
                    candidate(id = "wide", score = 88.0)
                },
            )
        assertNull(best)
        assertEquals(0, wideSearches)
    }

    @Test
    fun `a cancel during the wide search discards its result`() {
        var activeChecks = 0
        val best =
            DownloadUtil.matchBestCandidate(
                isActive = { activeChecks++ < 1 },
                musicSearch = { candidate(id = "music", score = 40.0) },
                wideSearch = { candidate(id = "wide", score = 88.0) },
            )
        assertNull(best)
    }

    private fun candidate(id: String, score: Double) =
        RankedCandidate(
            index = 0,
            id = id,
            url = "https://www.youtube.com/watch?v=$id",
            title = "title-$id",
            score = score,
        )
}
