package com.junkfood.seal

import com.junkfood.seal.download.MatchCandidate
import com.junkfood.seal.download.MatchOps
import com.junkfood.seal.download.MatchPhase
import com.junkfood.seal.download.MatchResult
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.RestartableAction
import com.junkfood.seal.download.isOpen
import com.junkfood.seal.download.phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One derived lifecycle replaces the four booleans every caller used to combine
 * by hand. The truth table pins both directions: every combination classifies,
 * and the predicates built on the phase equal the boolean rules they replaced
 * wherever a writer can produce the combination. The one deliberate change —
 * a hold outranks a stale acknowledgement — has its own test.
 */
class MatchPhaseTest {

    private fun match(
        flagged: Boolean = false,
        acknowledged: Boolean = false,
        held: Boolean = false,
        pending: Boolean = false,
    ): MatchResult =
        MatchResult(
            flagged = flagged,
            acknowledged = acknowledged,
            held = held,
            pending = pending,
        )

    private fun state(match: MatchResult?): Task.State =
        Task.State(Canceled(RestartableAction.Download), null, Task.ViewState(), match)

    private fun candidate(url: String): MatchCandidate = MatchCandidate(id = url, url = url)

    @Test
    fun noMatchIsNone() {
        assertEquals(MatchPhase.None, (null as MatchResult?).phase)
        assertFalse(MatchOps.reviewable(state(null)))
        assertFalse(MatchOps.acknowledgeable(state(null)))
    }

    @Test
    fun pendingWinsOverEveryOtherBoolean() {
        // A pending swap of a held book keeps held = true; the phase must read
        // as unfinished work, not as a hold the sheet could wave through.
        assertEquals(
            MatchPhase.SwapPending,
            match(flagged = true, held = true, pending = true).phase,
        )
        assertEquals(MatchPhase.SwapPending, match(held = true, pending = true).phase)
    }

    @Test
    fun heldOutranksAFlaggedScore() {
        assertEquals(MatchPhase.Held, match(flagged = true, held = true).phase)
    }

    @Test
    fun plainFlagNeedsReviewUntilAcknowledged() {
        assertEquals(MatchPhase.NeedsReview, match(flagged = true).phase)
        assertEquals(MatchPhase.Confirmed, match(flagged = true, acknowledged = true).phase)
        assertEquals(MatchPhase.Confirmed, match().phase)
    }

    @Test
    fun onlyTheThreeUnsettledPhasesAreOpen() {
        val open = MatchPhase.entries.filter { it.isOpen }.toSet()
        assertEquals(
            setOf(MatchPhase.NeedsReview, MatchPhase.Held, MatchPhase.SwapPending),
            open,
        )
    }

    @Test
    fun aStaleAcknowledgedHoldStaysReviewable() {
        // No writer acknowledges a held pick, but cached JSON from an older
        // build could carry held+acknowledged. The hold outranks the stale
        // flag: the entry stays open until a verified pick clears the hold.
        val stale = match(held = true, acknowledged = true)
        assertEquals(MatchPhase.Held, stale.phase)
        assertTrue(MatchOps.reviewable(state(stale)))
        assertFalse(MatchOps.acknowledgeable(state(stale)))
    }

    @Test
    fun predicatesMatchTheBooleanRulesTheyReplaced() {
        for (flagged in listOf(false, true))
            for (acknowledged in listOf(false, true))
                for (held in listOf(false, true))
                    for (pending in listOf(false, true)) {
                        val result = match(flagged, acknowledged, held, pending)
                        val at = "flagged=$flagged ack=$acknowledged held=$held pending=$pending"
                        assertEquals(
                            at,
                            result.held || result.pending || (result.flagged && !result.acknowledged),
                            result.phase.isOpen,
                        )
                        assertEquals(
                            at,
                            result.flagged &&
                                !result.acknowledged &&
                                !result.held &&
                                !result.pending,
                            result.phase == MatchPhase.NeedsReview,
                        )
                    }
    }

    @Test
    fun pendingSwapOfAHeldBookStaysBlockedForResume() {
        val result = match(held = true, pending = true)
        assertEquals(MatchPhase.SwapPending, result.phase)
        // The hold is a property of the stored pick, so it survives the phase
        // change: Resume must stay refused until the replacement verifies.
        assertTrue(MatchOps.held(state(result)))
        assertTrue(MatchOps.reviewable(state(result)))
        assertFalse(MatchOps.acknowledgeable(state(result)))
    }

    @Test
    fun aVerifiedSwapEndsTheHold() {
        val held = match(flagged = true, held = true)
        val pending = held.withSelection(candidate("https://example.com/b"))
        assertEquals(MatchPhase.SwapPending, pending.phase)

        val confirmed = pending.confirmSelection()
        assertEquals(MatchPhase.Confirmed, confirmed.phase)
        assertFalse(MatchOps.held(state(confirmed)))
    }

    @Test
    fun aFailedSwapFallsBackToTheHold() {
        val held = match(held = true)
        val pending = held.withSelection(candidate("https://example.com/b"))
        assertEquals(MatchPhase.SwapPending, pending.phase)

        val canceled = pending.cancelPending()
        assertEquals(MatchPhase.Held, canceled.phase)
        assertTrue(MatchOps.held(state(canceled)))
    }
}
