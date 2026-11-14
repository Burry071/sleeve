package com.junkfood.seal

import com.junkfood.seal.util.LiveGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `live_status` to a decision. The table is the whole contract: an allow with
 * a card line, or a refusal with the line the error state shows.
 */
class LiveGateTest {

    @Test
    fun `a live stream is allowed with its card line`() {
        val decision = LiveGate.decide("is_live")

        assertTrue(decision is LiveGate.Decision.Allow)
        assertEquals(
            R.string.status_live_stream,
            (decision as LiveGate.Decision.Allow).warningRes,
        )
    }

    @Test
    fun `a just-ended stream is refused`() {
        val decision = LiveGate.decide("post_live")

        assertTrue(decision is LiveGate.Decision.Refuse)
        assertEquals(
            R.string.error_stream_just_ended,
            (decision as LiveGate.Decision.Refuse).reasonRes,
        )
    }

    @Test
    fun `an upcoming stream is refused`() {
        assertEquals(
            R.string.error_stream_upcoming,
            (LiveGate.decide("is_upcoming") as LiveGate.Decision.Refuse).reasonRes,
        )
    }

    @Test
    fun `finished and ordinary items are allowed without a card line`() {
        for (status in listOf("was_live", "not_live", "none", null, "")) {
            val decision = LiveGate.decide(status)
            assertTrue(status.toString(), decision is LiveGate.Decision.Allow)
            assertNull(status.toString(), (decision as LiveGate.Decision.Allow).warningRes)
        }
    }
}
