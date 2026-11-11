package com.junkfood.seal.util

import com.junkfood.seal.ghostengine.GhostEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ranker's candidate metadata is additive: the review sheet shows uploader,
 * channel and duration per alternative, so the mapping into [MatchCandidate]
 * must carry all three instead of dropping them at the bridge.
 */
class CandidateMappingTest {

    private fun ranked(url: String = "https://www.youtube.com/watch?v=abc") =
        GhostEngine.RankedCandidate(
            index = 2,
            id = "abc",
            url = url,
            title = "Candidate title",
            score = 74.6,
            reasons = listOf("name: 80", "duration: +5"),
            uploader = "Uploader name",
            channel = "Channel name",
            duration = 185L,
        )

    @Test
    fun `uploader channel and duration survive the mapping`() {
        val candidate = with(DownloadUtil) { ranked().toMatchCandidate() }

        assertEquals("Uploader name", candidate.uploader)
        assertEquals("Channel name", candidate.channel)
        assertEquals(185L, candidate.duration)
        assertEquals("3:05", candidate.durationText)
    }

    @Test
    fun `the scoring fields are carried unchanged`() {
        val candidate = with(DownloadUtil) { ranked().toMatchCandidate() }

        assertEquals(2, candidate.index)
        assertEquals("abc", candidate.id)
        assertEquals("Candidate title", candidate.title)
        assertEquals(74.6, candidate.score, 0.0)
        assertEquals(listOf("name: 80", "duration: +5"), candidate.reasons)
        assertEquals("74.6%", candidate.scoreLabel)
    }

    @Test
    fun `a candidate without a url falls back to the watch url`() {
        val candidate = with(DownloadUtil) { ranked(url = "").toMatchCandidate() }

        assertEquals("https://www.youtube.com/watch?v=abc", candidate.url)
    }
}
