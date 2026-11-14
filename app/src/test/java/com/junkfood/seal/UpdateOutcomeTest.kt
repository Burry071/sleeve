package com.junkfood.seal

import com.junkfood.seal.util.UpdateOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The updater's return value: every branch is distinguishable, because the
 * three surfaces show a different line for each one.
 */
class UpdateOutcomeTest {

    @Test
    fun `a success carries the version the engine reported`() {
        val succeeded = UpdateOutcome.Succeeded("2026.11.14")
        assertEquals("2026.11.14", succeeded.version)
    }

    @Test
    fun `already up to date is a success with no version`() {
        // Nothing was installed, so there is no new version to show; this must
        // still be a success or the interval would restart forever.
        assertNull(UpdateOutcome.Succeeded(null).version)
    }

    @Test
    fun `a failure carries a short reason and the cause`() {
        val cause = java.io.IOException("boom")
        val failed = UpdateOutcome.Failed("network", cause)
        assertEquals("network", failed.reason)
        assertSame(cause, failed.cause)
    }

    @Test
    fun `busy is a singleton, not a failure`() {
        assertSame(UpdateOutcome.Busy, UpdateOutcome.Busy)
        assertTrue(UpdateOutcome.Busy !is UpdateOutcome.Failed)
    }
}
