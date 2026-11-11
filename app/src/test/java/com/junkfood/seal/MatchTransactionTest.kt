package com.junkfood.seal

import com.junkfood.seal.download.MatchCandidate
import com.junkfood.seal.download.MatchResult
import com.junkfood.seal.download.SwapOutcome
import com.junkfood.seal.download.SwapResult
import com.junkfood.seal.download.Task
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pending-selection contract for catalogue swaps: a new pick is not trusted until the
 * replacement file is confirmed, and backing out restores the previous pick.
 *
 * Also pins the additive-field promise: a beta.11 payload (no `pending`/`previousSelected*`, no
 * candidate metadata, no `Error.message`) must still decode.
 */
class MatchTransactionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun candidate(index: Int, score: Double): MatchCandidate =
        MatchCandidate(
            index = index,
            id = "id$index",
            url = "https://www.youtube.com/watch?v=id$index",
            title = "candidate $index",
            score = score,
            reasons = listOf("score: $score"),
            uploader = "Uploader $index",
            channel = "Channel $index",
            duration = 185L + index,
        )

    /** A flagged pick whose top candidate is the 62-point live version. */
    private fun original(): MatchResult =
        MatchResult(
            candidates = listOf(candidate(0, 62.0), candidate(1, 40.0)),
            selectedUrl = "https://www.youtube.com/watch?v=id0",
            selectedScore = 62.0,
            flagged = true,
        )

    @Test
    fun `withSelection marks the new pick pending and keeps the old one parked`() {
        val before = original()
        val alternative = before.candidates[1]

        val after = before.withSelection(alternative)

        assertEquals(alternative.url, after.selectedUrl)
        assertEquals(alternative.score, after.selectedScore, 0.0)
        assertTrue(after.pending)
        assertFalse(after.acknowledged)
        assertEquals(before.selectedUrl, after.previousSelectedUrl)
        assertEquals(before.selectedScore, after.previousSelectedScore, 0.0)
    }

    @Test
    fun `confirmSelection accepts the pick and clears the parking slot`() {
        val confirmed = original().withSelection(candidate(1, 40.0)).confirmSelection()

        assertFalse(confirmed.pending)
        assertTrue(confirmed.acknowledged)
        assertEquals("", confirmed.previousSelectedUrl)
        assertEquals(0.0, confirmed.previousSelectedScore, 0.0)
        // The confirmed pick itself is untouched.
        assertEquals("https://www.youtube.com/watch?v=id1", confirmed.selectedUrl)
        assertEquals(40.0, confirmed.selectedScore, 0.0)
    }

    @Test
    fun `cancelPending restores the original selection and reopens the review entry`() {
        val before = original()

        val cancelled = before.withSelection(before.candidates[1]).cancelPending()

        assertEquals(before.selectedUrl, cancelled.selectedUrl)
        assertEquals(before.selectedScore, cancelled.selectedScore, 0.0)
        assertFalse(cancelled.pending)
        assertFalse(cancelled.acknowledged)
        assertEquals("", cancelled.previousSelectedUrl)
        assertEquals(0.0, cancelled.previousSelectedScore, 0.0)
        // Flagged and unacknowledged again, exactly as before the swap was requested.
        assertEquals(before.flagged, cancelled.flagged)
        assertEquals(before.candidates, cancelled.candidates)
    }

    @Test
    fun `the pending window survives a json round-trip`() {
        val pending = original().withSelection(candidate(1, 40.0))

        val encoded = json.encodeToString(MatchResult.serializer(), pending)
        val decoded = json.decodeFromString(MatchResult.serializer(), encoded)

        assertEquals(pending, decoded)
        assertTrue(decoded.pending)
        assertFalse(decoded.acknowledged)
        assertEquals(pending.previousSelectedUrl, decoded.previousSelectedUrl)
        assertEquals(pending.previousSelectedScore, decoded.previousSelectedScore, 0.0)
    }

    @Test
    fun `a pending swap restored after process death falls back unless it completed`() {
        val pending = original().withSelection(candidate(1, 40.0))

        // The parked old path is process-local. A restore that did not finish
        // must return to the previous pick, not stay stuck on "Replacing…".
        val unfinished = pending.restoreAfterProcessDeath(completed = false)
        assertEquals(original().selectedUrl, unfinished.selectedUrl)
        assertFalse(unfinished.pending)
        assertFalse(unfinished.acknowledged)

        // A completed restore already has the replacement file; promote it.
        val completed = pending.restoreAfterProcessDeath(completed = true)
        assertEquals(pending.selectedUrl, completed.selectedUrl)
        assertFalse(completed.pending)
        assertTrue(completed.acknowledged)
    }

    /** Exactly what beta.11 wrote: no `pending`, no `previousSelected*`, no candidate metadata. */
    private val beta11MatchJson =
        """{"candidates":[{"index":0,"id":"id0","url":"https://www.youtube.com/watch?v=id0","title":"candidate 0","score":62.0,"reasons":["score: 62.0"]}],"selectedUrl":"https://www.youtube.com/watch?v=id0","selectedScore":62.0,"flagged":true,"acknowledged":false}"""

    @Test
    fun `a beta 11 match json without the new keys decodes as not pending`() {
        val decoded = json.decodeFromString(MatchResult.serializer(), beta11MatchJson)

        assertFalse(decoded.pending)
        assertFalse(decoded.acknowledged)
        assertTrue(decoded.flagged)
        assertEquals("", decoded.previousSelectedUrl)
        assertEquals(0.0, decoded.previousSelectedScore, 0.0)
        assertEquals("https://www.youtube.com/watch?v=id0", decoded.selectedUrl)
        assertEquals(62.0, decoded.selectedScore, 0.0)

        val only = decoded.candidates.single()
        assertEquals("candidate 0", only.title)
        assertEquals("", only.uploader)
        assertEquals("", only.channel)
        assertEquals(0L, only.duration)
        assertEquals("", only.durationText)
    }

    @Test
    fun `scoreLabel keeps one decimal instead of rounding to a whole percent`() {
        assertEquals("74.6%", candidate(0, 74.6).scoreLabel)
        assertEquals("75.0%", candidate(0, 75.0).scoreLabel)
        assertEquals("0.0%", candidate(0, 0.0).scoreLabel)
        assertEquals("100.0%", candidate(0, 100.0).scoreLabel)
    }

    @Test
    fun `durationText renders m colon ss and hides an unknown duration`() {
        val base = candidate(0, 62.0)

        assertEquals("3:05", base.copy(duration = 185L).durationText)
        assertEquals("0:59", base.copy(duration = 59L).durationText)
        assertEquals("60:00", base.copy(duration = 3600L).durationText)
        assertEquals("", base.copy(duration = 0L).durationText)
        assertEquals("", base.copy(duration = -5L).durationText)
    }

    @Test
    fun `a swap result round-trips and defaults its reason to blank`() {
        val result =
            SwapResult(
                taskId = "task-1",
                uiKey = "task-1#batch-storm",
                title = "Track 11",
                candidateUrl = "https://www.youtube.com/watch?v=id1",
                candidateTitle = "candidate 1",
                outcome = SwapOutcome.Queued,
            )

        val decoded =
            json.decodeFromString(
                SwapResult.serializer(),
                json.encodeToString(SwapResult.serializer(), result),
            )

        assertEquals(result, decoded)
        assertEquals("", decoded.reason)
        assertEquals(SwapOutcome.Queued, decoded.outcome)
    }

    @Test
    fun `an error without a message decodes with a blank message`() {
        val error = Task.DownloadState.Error(action = Task.RestartableAction.Download)
        val encoded =
            json.encodeToJsonElement(Task.DownloadState.Error.serializer(), error).jsonObject
        val legacy = JsonObject(encoded - "message")

        val decoded =
            json.decodeFromJsonElement(Task.DownloadState.Error.serializer(), legacy)

        assertEquals("", decoded.message)
        assertEquals(Task.RestartableAction.Download, decoded.action)
    }

    @Test
    fun `an error message survives a json round-trip`() {
        val error =
            Task.DownloadState.Error(
                action = Task.RestartableAction.Download,
                message = "Video unavailable",
            )

        val decoded =
            json.decodeFromString(
                Task.DownloadState.Error.serializer(),
                json.encodeToString(Task.DownloadState.Error.serializer(), error),
            )

        assertEquals("Video unavailable", decoded.message)
    }
}
