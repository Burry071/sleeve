package com.junkfood.seal.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The post-download tolerance contract: `abs(actual - expected) <= max(2 min, 2%)`.
 *
 * The floor keeps a tail-silence trim or encoder padding from failing a good
 * book; the ratio keeps very long books scale-appropriate. Anything unknown
 * fails closed — a file nobody measured must not read as complete.
 */
class AudioVerifyTest {

    private val hour = 3_600_000L

    @Test
    fun `an exact match passes`() {
        assertTrue(AudioVerify.withinTolerance(hour, hour))
    }

    @Test
    fun `two minutes short passes at one hour`() {
        assertTrue(AudioVerify.withinTolerance(hour - 120_000L, hour))
    }

    @Test
    fun `one millisecond past the flat floor fails at one hour`() {
        assertFalse(AudioVerify.withinTolerance(hour - 120_001L, hour))
    }

    @Test
    fun `a longer file inside the floor passes`() {
        assertTrue(AudioVerify.withinTolerance(hour + 120_000L, hour))
    }

    @Test
    fun `two percent wins over the flat floor on a long book`() {
        val book = 25L * 60L * 60L * 1000L // 25 h; 2% = 1_800_000 ms
        assertTrue(AudioVerify.withinTolerance(book - 1_700_000L, book))
        assertFalse(AudioVerify.withinTolerance(book - 1_900_000L, book))
    }

    @Test
    fun `a summary fails against a nine hour book`() {
        assertFalse(AudioVerify.withinTolerance(40L * 60L * 1000L, 9L * 60L * 60L * 1000L))
    }

    @Test
    fun `unknown durations fail closed`() {
        assertFalse(AudioVerify.withinTolerance(0L, hour))
        assertFalse(AudioVerify.withinTolerance(hour, 0L))
        assertFalse(AudioVerify.withinTolerance(-1L, hour))
    }
}
