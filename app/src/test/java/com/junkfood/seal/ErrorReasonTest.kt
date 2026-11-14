package com.junkfood.seal

import com.junkfood.seal.download.ErrorReason
import com.junkfood.seal.util.DownloadErrorMessages
import com.junkfood.seal.util.DownloadErrorMessages.AlreadyDownloadedError
import com.junkfood.seal.util.DownloadErrorMessages.PlaylistShiftedError
import com.yausername.youtubedl_android.YoutubeDLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ErrorReasonTest {

    @Test
    fun `the three typed failures map to their reasons`() {
        assertEquals(
            ErrorReason.LOGIN_REQUIRED,
            DownloadErrorMessages.errorReasonOf(
                DownloadErrorMessages.LoginRequiredError("login", Throwable())
            ),
        )
        assertEquals(
            ErrorReason.PLAYLIST_SHIFTED,
            DownloadErrorMessages.errorReasonOf(PlaylistShiftedError("a", "b")),
        )
        assertEquals(
            ErrorReason.ALREADY_DOWNLOADED,
            DownloadErrorMessages.errorReasonOf(AlreadyDownloadedError("Youtube", "id", "done")),
        )
    }

    @Test
    fun `an untyped failure maps to no reason`() {
        assertNull(DownloadErrorMessages.errorReasonOf(YoutubeDLException("boom")))
        assertNull(DownloadErrorMessages.errorReasonOf(IllegalStateException("boom")))
        assertNull(DownloadErrorMessages.errorReasonOf(Throwable()))
    }

    @Test
    fun `asFriendly stores the typed failures unchanged`() {
        // The reason is derived from asFriendly's result, so these types must
        // survive it or LOGIN_REQUIRED could never be persisted.
        val login = DownloadErrorMessages.LoginRequiredError("login", Throwable())
        val shifted = PlaylistShiftedError("a", "b")
        val archived = AlreadyDownloadedError("Youtube", "id", "done")
        assertSame(login, DownloadErrorMessages.asFriendly(login))
        assertSame(shifted, DownloadErrorMessages.asFriendly(shifted))
        assertSame(archived, DownloadErrorMessages.asFriendly(archived))
    }
}
