package com.junkfood.seal

import com.junkfood.seal.download.ErrorReason
import com.junkfood.seal.download.MatchCandidate
import com.junkfood.seal.download.MatchOps
import com.junkfood.seal.download.MatchResult
import com.junkfood.seal.download.SwapOutcome
import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.RestartableAction
import com.junkfood.seal.util.DownloadErrorMessages.NotReadyError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A swap whose candidate turns out to be a stream that cannot be downloaded
 * yet. The refusal has its own outcome, the task lands on `Error` instead of
 * `ReadyWithInfo`, and the pick is rolled back without losing the hold state.
 */
class SwapRefuseTest {

    private fun candidate(index: Int) =
        MatchCandidate(
            index = index,
            id = "id$index",
            url = "https://www.youtube.com/watch?v=id$index",
            title = "candidate $index",
            score = 70.0,
            duration = 3_600L,
        )

    private fun passingMatch(): MatchResult =
        MatchResult(
            candidates = listOf(candidate(0), candidate(1)),
            selectedUrl = "https://www.youtube.com/watch?v=id0",
            selectedScore = 70.0,
            flagged = false,
        )

    @Test
    fun `a refusal is its own swap outcome`() {
        // The sheet shows a different line than a failed fetch, so the two
        // cannot share an enum constant.
        assertNotEquals(SwapOutcome.FetchFailed, SwapOutcome.Refused)
        assertNotEquals(SwapOutcome.Missing, SwapOutcome.Refused)
        assertEquals("Refused", SwapOutcome.Refused.name)
    }

    @Test
    fun `a refused stream resolves to an error, never to ready`() {
        val resolved = MatchOps.resolvedState(passingMatch(), "post_live")

        assertTrue(resolved is DownloadState.Error)
        resolved as DownloadState.Error
        assertTrue(resolved.throwable is NotReadyError)
        assertEquals(R.string.error_stream_just_ended, (resolved.throwable as NotReadyError).reasonRes)
        assertEquals(RestartableAction.FetchInfo, resolved.action)
        assertEquals(ErrorReason.STREAM_ENDED, resolved.reason)
    }

    @Test
    fun `an upcoming stream resolves to its own error`() {
        val resolved = MatchOps.resolvedState(passingMatch(), "is_upcoming") as DownloadState.Error

        assertEquals(R.string.error_stream_upcoming, (resolved.throwable as NotReadyError).reasonRes)
        assertEquals(ErrorReason.STREAM_UPCOMING, resolved.reason)
    }

    @Test
    fun `a rollback restores the previous pick and closes the pending state`() {
        val pending = passingMatch().withSelection(candidate(1))
        val restored = pending.cancelPending()

        assertEquals("https://www.youtube.com/watch?v=id0", restored.selectedUrl)
        assertEquals(70.0, restored.selectedScore, 0.0)
        assertFalse(restored.pending)
        assertFalse("https://www.youtube.com/watch?v=id1" == restored.selectedUrl)
    }

    @Test
    fun `a rollback keeps a held book held`() {
        val held =
            passingMatch().copy(
                held = true,
                expectedDurationMs = 7_200_000L,
                gateReason = "no full-length candidate",
            )
        val restored = held.withSelection(candidate(1)).cancelPending()

        assertTrue(restored.held)
        assertEquals("no full-length candidate", restored.gateReason)
    }
}
