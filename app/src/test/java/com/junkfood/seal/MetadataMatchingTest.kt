package com.junkfood.seal

import com.junkfood.seal.ghostengine.GhostEngine.SpotifyTrack
import com.junkfood.seal.util.DownloadUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `low confidence music match widens to the wide pool`() {
        assertTrue(DownloadUtil.shouldWidenSearch(null))
        assertTrue(DownloadUtil.shouldWidenSearch(0.49))
        assertFalse(DownloadUtil.shouldWidenSearch(0.5))
        assertFalse(DownloadUtil.shouldWidenSearch(0.91))
    }
}
