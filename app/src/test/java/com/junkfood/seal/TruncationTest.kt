package com.junkfood.seal

import com.junkfood.seal.util.Truncation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The post-download length check. Only an ordinary, non-live, non-clip,
 * non-split file that finished well under its advertised length warns.
 */
class TruncationTest {

    private fun warning(
        probedSeconds: Long,
        expectedMs: Long,
        live: Boolean = false,
        clips: Boolean = false,
        split: Boolean = false,
    ) =
        Truncation.warningRes(
            probedSeconds = probedSeconds,
            expectedMs = expectedMs,
            live = live,
            clips = clips,
            split = split,
        )

    @Test
    fun `a file at full length does not warn`() {
        assertNull(warning(probedSeconds = 600, expectedMs = 600_000))
    }

    @Test
    fun `a file just under the threshold does not warn`() {
        // 540s of 600s is exactly 90%; the check only fires below that.
        assertNull(warning(probedSeconds = 540, expectedMs = 600_000))
    }

    @Test
    fun `a file well under its length warns`() {
        assertEquals(
            R.string.warning_truncated_capture,
            warning(probedSeconds = 120, expectedMs = 600_000),
        )
    }

    @Test
    fun `a live capture never warns`() {
        // The user stopped the recording on purpose.
        assertNull(warning(probedSeconds = 120, expectedMs = 7_200_000, live = true))
    }

    @Test
    fun `a clip never warns`() {
        assertNull(warning(probedSeconds = 30, expectedMs = 7_200_000, clips = true))
    }

    @Test
    fun `a split chapter set never warns`() {
        assertNull(warning(probedSeconds = 30, expectedMs = 7_200_000, split = true))
    }

    @Test
    fun `an unmeasured file or an unknown duration does not warn`() {
        assertNull(warning(probedSeconds = 0, expectedMs = 600_000))
        assertNull(warning(probedSeconds = 120, expectedMs = 0))
        assertNull(warning(probedSeconds = 120, expectedMs = 500))
    }
}
