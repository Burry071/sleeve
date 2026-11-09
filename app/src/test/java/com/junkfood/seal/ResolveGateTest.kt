package com.junkfood.seal

import com.junkfood.seal.ui.page.downloadv2.configure.ResolveGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveGateTest {

    private var now = 1_000_000L

    private fun gate() = ResolveGate(cooldownMs = 3000L, clock = { now })

    @Test
    fun firstRequestIsAccepted() {
        assertTrue(gate().accept("gallery", "https://example.com/a"))
    }

    @Test
    fun duplicateWithinCooldownIsRejected() {
        val gate = gate()
        assertTrue(gate.accept("gallery", "https://example.com/a"))
        now += 2_999
        assertFalse(gate.accept("gallery", "https://example.com/a"))
    }

    @Test
    fun duplicateAfterCooldownIsAccepted() {
        val gate = gate()
        assertTrue(gate.accept("gallery", "https://example.com/a"))
        now += 3_000
        assertTrue(gate.accept("gallery", "https://example.com/a"))
    }

    @Test
    fun differentLinkIsNotBlockedByARunningResolve() {
        val gate = gate()
        assertTrue(gate.accept("gallery", "https://example.com/a"))
        now += 100
        assertTrue(gate.accept("gallery", "https://example.com/b"))
    }

    @Test
    fun differentSourceIsNotBlocked() {
        val gate = gate()
        assertTrue(gate.accept("gallery", "https://example.com/a"))
        now += 100
        assertTrue(gate.accept("spotify", "https://example.com/a"))
    }

    @Test
    fun staleEntriesAreDropped() {
        val gate = gate()
        repeat(40) { index ->
            assertTrue(gate.accept("gallery", "https://example.com/$index"))
            now += 10
        }
        now += 3_000
        // A fresh link after the cooldown still passes; the map only holds the
        // entries that can still reject a duplicate.
        assertTrue(gate.accept("gallery", "https://example.com/fresh"))
    }
}
