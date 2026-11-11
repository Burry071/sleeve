package com.junkfood.seal

import com.junkfood.seal.download.BatchOps
import com.junkfood.seal.download.BatchInfo
import com.junkfood.seal.download.MatchCandidate
import com.junkfood.seal.download.MatchOps
import com.junkfood.seal.download.MatchResult
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.download.Task.RestartableAction
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Audible hold contract: a gate refusal is a reviewable, non-downloadable pick.
 *
 * The gate runs before any media request (phase 1a), so a held book is finished as
 * `Canceled` and must still surface the existing review entry. Accept all must never
 * clear it; only an explicit candidate pick (or confirmSelection) lifts the hold.
 */
class MatchGateTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun candidate(index: Int, score: Double): MatchCandidate =
        MatchCandidate(
            index = index,
            id = "id$index",
            url = "https://www.youtube.com/watch?v=id$index",
            title = "candidate $index",
            score = score,
            duration = 26_028L,
        )

    /** Ranker was confident (not flagged); only the gate refuses the 34% upload. */
    private fun heldMatch(): MatchResult =
        MatchResult(
            candidates = listOf(candidate(0, 66.0), candidate(1, 40.0)),
            selectedUrl = "https://www.youtube.com/watch?v=id0",
            selectedScore = 66.0,
            flagged = false,
            held = true,
            expectedDurationMs = 75_720_000L,
            gateReason = "no full-length candidate - best match is 34% of the book",
        )

    private fun state(
        match: MatchResult?,
        downloadState: Task.DownloadState = Canceled(RestartableAction.Download),
    ): Task.State = Task.State(downloadState, null, Task.ViewState(), match)

    private fun task(index: Int): Task =
        Task(
            url = "metadata://Audible/b/$index",
            type =
                Task.TypeInfo.MetadataTrack(
                    index = index,
                    provider = "Audible",
                    title = "Dune",
                    durationMs = 75_720_000L,
                ),
            preferences = DownloadPreferences.EMPTY,
            batch = BatchInfo(id = "b", title = "Dune", provider = "Audible"),
        )

    @Test
    fun `a held book is reviewable even when the ranker was confident`() {
        val state = state(heldMatch())

        assertFalse(state.match!!.flagged)
        assertTrue(MatchOps.reviewable(state))
    }

    @Test
    fun `accept all cannot clear a held book`() {
        val state = state(heldMatch())

        assertTrue(MatchOps.reviewable(state))
        // The batch action must skip it; otherwise the only refusal signal disappears.
        assertFalse(MatchOps.acknowledgeable(state))
    }

    @Test
    fun `accept all still clears a plain flagged pick`() {
        val flagged =
            heldMatch().copy(
                held = false,
                expectedDurationMs = 0L,
                gateReason = "",
                flagged = true,
            )

        assertTrue(MatchOps.acknowledgeable(state(flagged)))
    }

    @Test
    fun `a pending held pick is not acknowledgeable either`() {
        val pending = heldMatch().withSelection(candidate(1, 40.0))

        assertTrue(pending.held)
        assertTrue(pending.pending)
        assertFalse(MatchOps.acknowledgeable(state(pending)))
    }

    @Test
    fun `withSelection alone does not lift the hold`() {
        // The hold is lifted only after the chosen file exists (swapOne on
        // success, then confirmSelection). A pending pick is not proof.
        assertTrue(heldMatch().withSelection(candidate(1, 40.0)).held)
    }

    @Test
    fun `confirmSelection clears held and keeps the gate reason as audit`() {
        val confirmed = heldMatch().withSelection(candidate(1, 40.0)).confirmSelection()

        assertFalse(confirmed.held)
        assertTrue(confirmed.acknowledged)
        assertFalse(confirmed.pending)
        // Why the book was held is still readable after the override.
        assertEquals("no full-length candidate - best match is 34% of the book", confirmed.gateReason)
        assertEquals(75_720_000L, confirmed.expectedDurationMs)
    }

    @Test
    fun `a held match resolves to canceled so no media request starts`() {
        val resolved = MatchOps.resolvedState(heldMatch())

        assertTrue(resolved is Canceled)
        assertEquals(RestartableAction.Download, (resolved as Canceled).action)
    }

    @Test
    fun `a passing match still resolves to ready with info`() {
        assertEquals(ReadyWithInfo, MatchOps.resolvedState(heldMatch().copy(held = false)))
    }

    @Test
    fun `the review entry appears for a batch that only holds`() {
        val entries =
            listOf(
                task(0) to state(heldMatch(), Canceled(RestartableAction.Download)),
                task(1) to state(heldMatch().copy(held = false), Completed("/tmp/dune.m4a")),
            )

        val summary = BatchOps.summary(entries)
        assertEquals(0, summary.unfinished)
        assertEquals(1, summary.completed)
        assertEquals(1, summary.canceled)
        assertEquals(1, MatchOps.reviewCount(entries))
        assertTrue(MatchOps.reviewVisible(entries))
    }

    @Test
    fun `a held task is not unfinished and not active`() {
        val state = state(heldMatch(), Canceled(RestartableAction.Download))

        assertFalse(BatchOps.isUnfinished(state.downloadState))
        assertFalse(BatchOps.isActive(state.downloadState))
        // Canceled is resumable, but restartImpl refuses a held pick; the review
        // sheet's explicit pick is the way forward.
        assertTrue(BatchOps.isResumable(state.downloadState))
    }

    @Test
    fun `a held match round-trips through json`() {
        val encoded = json.encodeToString(MatchResult.serializer(), heldMatch())
        val decoded = json.decodeFromString(MatchResult.serializer(), encoded)

        assertEquals(heldMatch(), decoded)
        assertTrue(decoded.held)
        assertEquals(75_720_000L, decoded.expectedDurationMs)
        assertEquals(heldMatch().gateReason, decoded.gateReason)
    }

    /** Exactly what the previous release wrote: no held/expected/gateReason keys. */
    private val oldMatchJson =
        """{"candidates":[{"index":0,"id":"id0","url":"https://www.youtube.com/watch?v=id0","title":"candidate 0","score":62.0,"reasons":["score: 62.0"],"uploader":"","channel":"","duration":0}],"selectedUrl":"https://www.youtube.com/watch?v=id0","selectedScore":62.0,"flagged":true,"acknowledged":false,"pending":false,"previousSelectedUrl":"","previousSelectedScore":0.0}"""

    @Test
    fun `part labels are one-based for both numbering bases`() {
        val oneBased =
            heldMatch().copy(
                parts =
                    listOf(
                        candidate(0, 0.0).copy(index = 1),
                        candidate(1, 0.0).copy(index = 2),
                    )
            )
        assertEquals(1, MatchOps.partDisplayNumber(oneBased, oneBased.parts[0]))
        assertEquals(2, MatchOps.partDisplayNumber(oneBased, oneBased.parts[1]))

        val zeroBased = oneBased.copy(parts = oneBased.parts.mapIndexed { i, p -> p.copy(index = i) })
        assertEquals(1, MatchOps.partDisplayNumber(zeroBased, zeroBased.parts[0]))
        assertEquals(2, MatchOps.partDisplayNumber(zeroBased, zeroBased.parts[1]))
    }

    @Test
    fun `a failed held override restores the hold and the picker`() {
        val pending = heldMatch().withSelection(candidate(1, 40.0))
        val cancelled = pending.cancelPending()

        assertTrue(cancelled.held)
        assertFalse(cancelled.acknowledged)
        assertTrue(MatchOps.reviewable(state(cancelled)))

        val restored = pending.restoreAfterProcessDeath(completed = false)
        assertTrue(restored.held)
        assertTrue(MatchOps.reviewable(state(restored)))
    }

    @Test
    fun `old match json decodes as not held`() {
        val decoded = json.decodeFromString(MatchResult.serializer(), oldMatchJson)

        assertFalse(decoded.held)
        assertEquals(0L, decoded.expectedDurationMs)
        assertEquals("", decoded.gateReason)
        // The old review path still works.
        assertTrue(MatchOps.reviewable(state(decoded)))
    }
}
