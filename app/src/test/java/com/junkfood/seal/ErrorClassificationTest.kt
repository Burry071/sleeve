package com.junkfood.seal

import com.junkfood.seal.download.ErrorReason
import com.junkfood.seal.util.DownloadErrorMessages
import com.junkfood.seal.util.DownloadErrorMessages.ErrorFamily
import com.junkfood.seal.util.DownloadErrorMessages.NotReadyError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Failure text to family, and the typed errors that must survive the mapping
 * unchanged. `classify` is the pure half; the resource lookup that follows it
 * needs a running app and is exercised on device.
 */
class ErrorClassificationTest {

    @Test
    fun `login walls are recognised before anything else`() {
        assertEquals(
            ErrorFamily.LOGIN_REQUIRED,
            DownloadErrorMessages.classify("ERROR: use --cookies for the authentication"),
        )
        assertEquals(
            ErrorFamily.LOGIN_REQUIRED,
            DownloadErrorMessages.classify("Sign in to confirm your age"),
        )
        // A members-only notice that also mentions a format is still a login wall.
        assertEquals(
            ErrorFamily.LOGIN_REQUIRED,
            DownloadErrorMessages.classify("members-only: requested format is not available"),
        )
    }

    @Test
    fun `an unavailable format is not an engine fault`() {
        assertEquals(
            ErrorFamily.FORMAT_UNAVAILABLE,
            DownloadErrorMessages.classify("ERROR: requested format is not available"),
        )
    }

    @Test
    fun `rate limiting is its own family`() {
        assertEquals(ErrorFamily.RATE_LIMITED, DownloadErrorMessages.classify("HTTP 429"))
        assertEquals(ErrorFamily.RATE_LIMITED, DownloadErrorMessages.classify("Too Many Requests"))
        assertEquals(ErrorFamily.RATE_LIMITED, DownloadErrorMessages.classify("rate-limit hit"))
    }

    @Test
    fun `extractor breakage suggests an engine update`() {
        assertEquals(
            ErrorFamily.EXTRACTOR_BROKEN,
            DownloadErrorMessages.classify("Unable to extract initial data"),
        )
        assertEquals(
            ErrorFamily.EXTRACTOR_BROKEN,
            DownloadErrorMessages.classify("nsig extraction failed"),
        )
        assertEquals(
            ErrorFamily.EXTRACTOR_BROKEN,
            DownloadErrorMessages.classify("no supported JavaScript runtime"),
        )
    }

    @Test
    fun `sabr is matched as a word`() {
        assertEquals(
            ErrorFamily.EXTRACTOR_BROKEN,
            DownloadErrorMessages.classify("SABR streaming is required"),
        )
        assertEquals(ErrorFamily.OTHER, DownloadErrorMessages.classify("sabremetrics"))
    }

    @Test
    fun `unrecognised or empty text stays generic`() {
        assertEquals(ErrorFamily.OTHER, DownloadErrorMessages.classify(""))
        assertEquals(ErrorFamily.OTHER, DownloadErrorMessages.classify("boom"))
    }

    @Test
    fun `typed errors survive the friendly mapping untouched`() {
        val engine = DownloadErrorMessages.ExtractorBrokenError("update yt-dlp", Throwable())
        val move =
            DownloadErrorMessages.StorageMoveError(1, listOf("a.mp4"), "/tmp/abc")

        assertSame(engine, DownloadErrorMessages.asFriendly(engine))
        assertSame(move, DownloadErrorMessages.asFriendly(move))
    }

    @Test
    fun `a not-ready error persists the stream reason`() {
        assertEquals(
            ErrorReason.STREAM_ENDED,
            DownloadErrorMessages.errorReasonOf(NotReadyError(R.string.error_stream_just_ended)),
        )
        assertEquals(
            ErrorReason.STREAM_UPCOMING,
            DownloadErrorMessages.errorReasonOf(NotReadyError(R.string.error_stream_upcoming)),
        )
    }
}
