package com.junkfood.seal

import com.junkfood.seal.download.ErrorReason
import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.RestartableAction
import com.junkfood.seal.ui.page.downloadv2.hasCookieAction
import com.junkfood.seal.ui.page.downloadv2.statusLine
import com.junkfood.seal.ui.page.downloadv2.statusText
import com.junkfood.seal.util.DownloadErrorMessages
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusLineTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun error(reason: ErrorReason?) =
        Error(
            throwable = IllegalStateException("boom"),
            action = RestartableAction.FetchInfo,
            message = "boom",
            reason = reason,
        )

    @Test
    fun `each persisted reason maps to its short line`() {
        assertEquals(
            R.string.status_playlist_shifted,
            statusLine(error(ErrorReason.PLAYLIST_SHIFTED)),
        )
        assertEquals(
            R.string.status_already_downloaded,
            statusLine(error(ErrorReason.ALREADY_DOWNLOADED)),
        )
        assertEquals(R.string.status_login_required, statusLine(error(ErrorReason.LOGIN_REQUIRED)))
    }

    @Test
    fun `a null reason keeps the pill generic`() {
        assertNull(statusLine(error(null)))
    }

    @Test
    fun `a non-error state has no line`() {
        assertNull(statusLine(Idle))
        assertNull(statusLine(Completed("/tmp/song.m4a")))
    }

    @Test
    fun `a restored error maps exactly as a live one`() {
        // The throwable is @Transient; the persisted reason is what a restore
        // still has, and the line must not vanish on the screen a user
        // returns to after the process died.
        val live = error(ErrorReason.LOGIN_REQUIRED)
        val restored = roundTrip(live)

        assertTrue(restored.throwable !is DownloadErrorMessages.LoginRequiredError)
        assertEquals(statusLine(live), statusLine(restored))
        assertEquals(R.string.status_login_required, statusLine(restored))
        assertTrue(hasCookieAction(restored))
    }

    @Test
    fun `the cookie action needs a login failure by either carrier`() {
        assertTrue(
            hasCookieAction(
                Error(
                    throwable = DownloadErrorMessages.LoginRequiredError("login", Throwable()),
                    action = RestartableAction.FetchInfo,
                )
            )
        )
        assertTrue(hasCookieAction(error(ErrorReason.LOGIN_REQUIRED)))
        assertFalse(hasCookieAction(error(ErrorReason.PLAYLIST_SHIFTED)))
        assertFalse(hasCookieAction(Error(Throwable("x"), RestartableAction.Download)))
        assertFalse(hasCookieAction(Idle))
        assertFalse(hasCookieAction(Completed("/tmp/song.m4a")))
    }

    @Test
    fun `a move failure shows its count sentence, not the retained path`() {
        val state =
            Error(
                throwable =
                    DownloadErrorMessages.StorageMoveError(
                        copied = 1,
                        failed = listOf("b.mp4"),
                        retainedDir = "/tmp/abc",
                    ),
                action = RestartableAction.Download,
                message = "Downloaded 1 of 2 file(s) to the chosen folder.\n/tmp/abc",
            )

        assertEquals("Downloaded 1 of 2 file(s) to the chosen folder.", statusText(state))
    }

    @Test
    fun `other errors and non-errors have no status text`() {
        assertNull(statusText(error(null)))
        assertNull(statusText(error(ErrorReason.LOGIN_REQUIRED)))
        assertNull(statusText(Idle))
        assertNull(statusText(Completed("/tmp/song.m4a")))
    }

    @Test
    fun `a restored move failure drops the sentence with its throwable`() {
        val live =
            Error(
                throwable =
                    DownloadErrorMessages.StorageMoveError(0, listOf("a.mp4"), "/tmp/abc"),
                action = RestartableAction.Download,
                message = "Downloaded 0 of 1 file(s) to the chosen folder.",
            )

        assertEquals("Downloaded 0 of 1 file(s) to the chosen folder.", statusText(live))
        assertNull(statusText(roundTrip(live)))
    }

    private fun roundTrip(state: DownloadState): Error {
        val text =
            json.encodeToString(
                DownloadState.serializer(),
                state,
            )
        return json.decodeFromString(DownloadState.serializer(), text) as Error
    }
}
