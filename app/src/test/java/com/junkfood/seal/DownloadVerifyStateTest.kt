package com.junkfood.seal

import com.junkfood.seal.download.MatchResult
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Completed
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1b keeps the cached queue decodable: the `warning` on a completed
 * download and the `actualDurationMs` on a match are both additive defaults, so
 * a state written by beta.4 must still load — and a fresh write must survive a
 * round trip.
 */
class DownloadVerifyStateTest {

    // encodeDefaults makes the "old" payload's missing key explicit: a real
    // beta.4 write simply never emitted it, because the field did not exist.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun completedState(warning: String) =
        Task.State(
            downloadState = Completed("/tmp/dune.m4a", warning = warning),
            videoInfo = null,
            viewState = Task.ViewState(),
        )

    @Test
    fun `a completed warning round-trips`() {
        val reason = "downloaded file is 34% of the book"
        val encoded = json.encodeToString(Task.State.serializer(), completedState(reason))
        val decoded = json.decodeFromString(Task.State.serializer(), encoded)

        val state = decoded.downloadState as Completed
        assertEquals("/tmp/dune.m4a", state.filePath)
        assertEquals(reason, state.warning)
    }

    @Test
    fun `completed json without a warning decodes to empty`() {
        val current = json.encodeToString(Task.State.serializer(), completedState(""))
        val legacy = current.replace(",\"warning\":\"\"", "")
        assertTrue(legacy.contains("\"filePath\":\"/tmp/dune.m4a\""))

        val state =
            json.decodeFromString(Task.State.serializer(), legacy).downloadState as Completed
        assertEquals("/tmp/dune.m4a", state.filePath)
        assertEquals("", state.warning)
    }

    @Test
    fun `a plain completed state still round-trips`() {
        val encoded = json.encodeToString(Task.State.serializer(), completedState(""))
        val decoded = json.decodeFromString(Task.State.serializer(), encoded)

        assertEquals(completedState(""), decoded)
    }

    /** Exactly what beta.4 wrote for a low-confidence pick. */
    private val legacyMatchJson =
        """{"candidates":[],"selectedUrl":"https://www.youtube.com/watch?v=id0","selectedScore":62.0,"flagged":true,"acknowledged":false,"pending":false,"previousSelectedUrl":"","previousSelectedScore":0.0}"""

    @Test
    fun `old match json decodes with no measured duration`() {
        val decoded = json.decodeFromString(MatchResult.serializer(), legacyMatchJson)

        assertEquals(0L, decoded.actualDurationMs)
        assertTrue(decoded.flagged)
        // Phase 1a columns keep their old defaults on the same payload.
        assertEquals(false, decoded.held)
        assertEquals("", decoded.gateReason)
    }

    @Test
    fun `a measured duration round-trips`() {
        val match =
            MatchResult(
                selectedUrl = "https://www.youtube.com/watch?v=id0",
                flagged = true,
                actualDurationMs = 2_400_000L,
                expectedDurationMs = 75_720_000L,
                gateReason = "downloaded file is 3% of the book",
            )
        val decoded =
            json.decodeFromString(
                MatchResult.serializer(),
                json.encodeToString(MatchResult.serializer(), match),
            )

        assertEquals(2_400_000L, decoded.actualDurationMs)
        assertEquals(match, decoded)
    }
}
