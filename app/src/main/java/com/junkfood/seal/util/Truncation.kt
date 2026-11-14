package com.junkfood.seal.util

import androidx.annotation.StringRes
import com.junkfood.seal.R

/**
 * The capture-length check that runs once per finished file.
 *
 * A live recording legitimately stops wherever the user stopped it, and a
 * clip or a split chapter set is short on purpose — all three are excluded.
 * Everything else that finished at well under its advertised duration was
 * most likely cut off mid-transfer (a dropped connection yt-dlp reported as
 * success) and deserves a one-line warning on the card.
 *
 * Pure so the thresholds are asserted directly; the caller supplies the
 * measured length and the duration the metadata advertised.
 */
internal object Truncation {

    /** Below 90% of the advertised duration is treated as cut short. */
    const val PROBED_RATIO = 0.9

    /**
     * @param probedSeconds what the finished file actually contains
     * @param expectedMs what the metadata advertised, 0 when unknown
     */
    fun warningRes(
        probedSeconds: Long,
        expectedMs: Long,
        live: Boolean,
        clips: Boolean,
        split: Boolean,
    ): Int? {
        if (live || clips || split) return null
        if (probedSeconds <= 0L || expectedMs <= 0L) return null
        val expectedSeconds = expectedMs / 1000L
        if (expectedSeconds <= 0L) return null
        return if (probedSeconds * 10L < expectedSeconds * 9L) {
            R.string.warning_truncated_capture
        } else {
            null
        }
    }
}
