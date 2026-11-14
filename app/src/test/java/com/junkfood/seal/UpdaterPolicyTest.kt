package com.junkfood.seal

import com.junkfood.seal.util.UpdateOutcome
import com.junkfood.seal.util.UpdaterPolicy
import com.junkfood.seal.util.YT_DLP_LAST_FAILURE
import com.junkfood.seal.util.YT_DLP_LAST_FAILURE_REASON
import com.junkfood.seal.util.YT_DLP_UPDATE_BUILD
import com.junkfood.seal.util.YT_DLP_UPDATE_FAILURES
import com.junkfood.seal.util.YT_DLP_UPDATE_TIME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The updater's pure half: when the launch check may run, what an attempt
 * records, and that a busy attempt records nothing at all.
 */
class UpdaterPolicyTest {

    private val now = 1_000_000_000_000L
    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour

    @Test
    fun `no running transfer permits an engine swap`() {
        assertTrue(UpdaterPolicy.canAttempt(0))
        assertFalse(UpdaterPolicy.canAttempt(1))
        assertFalse(UpdaterPolicy.canAttempt(3))
    }

    @Test
    fun `a success stamps time and build and clears the failure streak`() {
        val keys = UpdaterPolicy.bookkeeping(UpdateOutcome.Succeeded("2026.11.14"), 240, now, 4)

        assertEquals(now, keys[YT_DLP_UPDATE_TIME])
        assertEquals(240, keys[YT_DLP_UPDATE_BUILD])
        assertEquals(0, keys[YT_DLP_UPDATE_FAILURES])
        // Present with a null value means "delete": MMKV has no null write.
        assertTrue(keys.containsKey(YT_DLP_LAST_FAILURE))
        assertNull(keys[YT_DLP_LAST_FAILURE])
        assertTrue(keys.containsKey(YT_DLP_LAST_FAILURE_REASON))
        assertNull(keys[YT_DLP_LAST_FAILURE_REASON])
    }

    @Test
    fun `an already-up-to-date success counts as a success`() {
        // The outcome carries no version because nothing was installed; the
        // bookkeeping is still a success, so the interval restarts.
        val keys = UpdaterPolicy.bookkeeping(UpdateOutcome.Succeeded(null), 240, now)

        assertEquals(now, keys[YT_DLP_UPDATE_TIME])
        assertEquals(0, keys[YT_DLP_UPDATE_FAILURES])
    }

    @Test
    fun `a failure counts up and keeps its reason`() {
        val keys =
            UpdaterPolicy.bookkeeping(
                UpdateOutcome.Failed("network", java.io.IOException("boom")),
                240,
                now,
                failureCount = 2,
            )

        assertEquals(now, keys[YT_DLP_LAST_FAILURE])
        assertEquals("network", keys[YT_DLP_LAST_FAILURE_REASON])
        assertEquals(3, keys[YT_DLP_UPDATE_FAILURES])
        assertEquals(240, keys[YT_DLP_UPDATE_BUILD])
        // A failure must not claim a success.
        assertFalse(keys.containsKey(YT_DLP_UPDATE_TIME))
    }

    @Test
    fun `a busy attempt writes nothing`() {
        assertTrue(UpdaterPolicy.bookkeeping(UpdateOutcome.Busy, 240, now, 3).isEmpty())
    }

    @Test
    fun `manual reset clears the streak but keeps the success time`() {
        val keys = UpdaterPolicy.manualReset()

        assertNull(keys[YT_DLP_LAST_FAILURE])
        assertNull(keys[YT_DLP_LAST_FAILURE_REASON])
        assertEquals(0, keys[YT_DLP_UPDATE_FAILURES])
        assertFalse(keys.containsKey(YT_DLP_UPDATE_TIME))
    }

    @Test
    fun `the first run is due`() {
        assertTrue(due(lastSuccess = 0, lastFailure = 0))
    }

    @Test
    fun `inside the interval it is not due`() {
        assertFalse(due(lastSuccess = now - day, lastFailure = 0))
    }

    @Test
    fun `after the interval it is due again`() {
        assertTrue(due(lastSuccess = now - 8 * day, lastFailure = 0))
    }

    @Test
    fun `an automatic check stands down once opted out`() {
        assertFalse(due(lastSuccess = now - 30 * day, lastFailure = 0, autoUpdate = false))
        // Opting out never blocks a check that was already due on a fresh install.
        assertTrue(due(lastSuccess = 0, lastFailure = 0, autoUpdate = false))
    }

    @Test
    fun `a failed attempt waits six hours before the next automatic try`() {
        val lastFailure = now - 2 * hour
        assertFalse(due(lastSuccess = now - 30 * day, lastFailure = lastFailure))
        assertTrue(due(lastSuccess = now - 30 * day, lastFailure = now - 7 * hour))
    }

    @Test
    fun `five consecutive failures stop the automatic check`() {
        assertFalse(due(lastSuccess = now - 30 * day, lastFailure = now - 7 * hour, count = 5))
        assertTrue(due(lastSuccess = now - 30 * day, lastFailure = now - 7 * hour, count = 4))
    }

    @Test
    fun `an upgraded build is due immediately`() {
        // A new build ships a new engine, so the stored timestamps describe
        // software that no longer exists.
        assertTrue(due(lastSuccess = now - hour, lastFailure = 0, sameBuild = false))
        assertTrue(due(lastSuccess = 0, lastFailure = now - hour, sameBuild = false))
        // The stand-down streak still wins: a build with five recorded
        // failures does not get a sixth automatic attempt.
        assertFalse(due(lastSuccess = 0, lastFailure = now - hour, sameBuild = false, count = 5))
    }

    private fun due(
        lastSuccess: Long,
        lastFailure: Long,
        count: Int = 0,
        autoUpdate: Boolean = true,
        sameBuild: Boolean = true,
    ) =
        UpdaterPolicy.isDue(
            now = now,
            lastSuccess = lastSuccess,
            lastFailure = lastFailure,
            intervalMs = 7 * day,
            failureCount = count,
            autoUpdate = autoUpdate,
            sameBuild = sameBuild,
        )
}
