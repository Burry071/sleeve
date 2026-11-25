package com.junkfood.seal.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the file on disk measured against the catalogue runtime.
 *
 * [reason] is a plain English line, not a string resource: it is persisted in
 * [com.junkfood.seal.download.MatchResult.gateReason] and read back by the
 * review sheet long after the process that measured it is gone.
 */
data class VerifyResult(
    val ok: Boolean,
    val actualMs: Long,
    val expectedMs: Long,
    val reason: String,
)

/**
 * Post-download duration check for Audible books. The pre-download gate proves
 * the *pick*; this proves the *file* that actually landed, which is the only
 * thing the user will listen to.
 *
 * The tolerance is deliberately generous: a tail-silence trim or a codec
 * priming difference of a few seconds is not a wrong book. A wholesale
 * mismatch (a 40-minute summary for a 9-hour book) is.
 */
object AudioVerify {

    /** Two minutes of slack covers encoder padding and chapter-tail trims. */
    const val TOLERANCE_MS: Long = 120_000L

    /** Long books scale: 2% of 25 hours is 30 minutes, and still not a wrong book. */
    const val TOLERANCE_RATIO: Double = 0.02

    /**
     * `abs(actual - expected) <= max(2 min, 2% of expected)`.
     *
     * An unknown side (zero or negative) never passes: with nothing to compare
     * against, the check must fail closed rather than call every file complete.
     */
    fun withinTolerance(actualMs: Long, expectedMs: Long): Boolean {
        if (actualMs <= 0L || expectedMs <= 0L) return false
        val slack = max(TOLERANCE_MS.toDouble(), expectedMs * TOLERANCE_RATIO)
        return abs(actualMs - expectedMs) <= slack
    }

    /**
     * Reads [MediaMetadataRetriever.METADATA_KEY_DURATION] from [file] and
     * compares it with the catalogue runtime. Blocking; call off the main
     * thread.
     *
     * Fails closed for a missing/empty file, an unreadable container, and an
     * unknown expected runtime — each with the reason the review sheet shows.
     */
    fun verifyDuration(context: Context, file: File, expectedMs: Long): VerifyResult {
        if (expectedMs <= 0L) {
            return VerifyResult(
                ok = false,
                actualMs = 0L,
                expectedMs = expectedMs,
                reason = "catalogue runtime unknown",
            )
        }
        if (!file.isFile || file.length() <= 0L) {
            return VerifyResult(
                ok = false,
                actualMs = 0L,
                expectedMs = expectedMs,
                reason = "downloaded file is missing or empty",
            )
        }
        val actualMs = durationMs(context, file)
        if (actualMs <= 0L) {
            return VerifyResult(
                ok = false,
                actualMs = 0L,
                expectedMs = expectedMs,
                reason = "could not measure the downloaded file",
            )
        }
        return if (withinTolerance(actualMs, expectedMs)) {
            VerifyResult(ok = true, actualMs = actualMs, expectedMs = expectedMs, reason = "")
        } else {
            VerifyResult(
                ok = false,
                actualMs = actualMs,
                expectedMs = expectedMs,
                reason = mismatchReason(actualMs, expectedMs),
            )
        }
    }

    /**
     * Reads [MediaMetadataRetriever.METADATA_KEY_DURATION] from [file]; 0 when
     * the file is missing, empty, or the container cannot be read. Blocking.
     */
    fun durationMs(context: Context, file: File): Long {
        if (!file.isFile || file.length() <= 0L) return 0L
        return runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.fromFile(file))
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?: 0L
                } finally {
                    retriever.release()
                }
            }
            .getOrDefault(0L)
    }

    /**
     * Whether an export may take the source's place. A file within two seconds
     * of the source is the same audio; a shorter one lost content (and a
     * longer one — only possible with a bad measurement — is not trusted here).
     */
    fun canReplaceSource(sourceMs: Long, outputMs: Long): Boolean =
        sourceMs > 0L && outputMs > 0L && abs(outputMs - sourceMs) <= 2_000L

    /**
     * [verifyDuration]'s comparison without any file access, for callers that
     * already measured the file (the export measures its own scratch).
     */
    fun verifyMeasured(actualMs: Long, expectedMs: Long): VerifyResult {
        if (expectedMs <= 0L) {
            return VerifyResult(
                ok = false,
                actualMs = 0L,
                expectedMs = expectedMs,
                reason = "catalogue runtime unknown",
            )
        }
        if (actualMs <= 0L) {
            return VerifyResult(
                ok = false,
                actualMs = 0L,
                expectedMs = expectedMs,
                reason = "could not measure the downloaded file",
            )
        }
        return if (withinTolerance(actualMs, expectedMs)) {
            VerifyResult(ok = true, actualMs = actualMs, expectedMs = expectedMs, reason = "")
        } else {
            VerifyResult(
                ok = false,
                actualMs = actualMs,
                expectedMs = expectedMs,
                reason = mismatchReason(actualMs, expectedMs),
            )
        }
    }

    /** `downloaded file is 34% of the book` — same wording as the gate's reasons. */
    private fun mismatchReason(actualMs: Long, expectedMs: Long): String {
        val percent = (actualMs * 100.0 / expectedMs).roundToInt()
        return String.format(Locale.US, "downloaded file is %d%% of the book", percent)
    }
}
