package com.junkfood.seal.ui.page.downloadv2

import androidx.annotation.StringRes
import com.junkfood.seal.R
import com.junkfood.seal.download.ErrorReason
import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.util.DownloadErrorMessages

/**
 * One mapping for both surfaces that show why a task failed. Reads the
 * persisted [Error.reason], never the throwable: the throwable is
 * `@Transient`, so a restored error has a blank one.
 */
@StringRes
fun statusLine(state: DownloadState): Int? {
    val error = state as? Error ?: return null
    return when (error.reason) {
        ErrorReason.PLAYLIST_SHIFTED -> R.string.status_playlist_shifted
        ErrorReason.ALREADY_DOWNLOADED -> R.string.status_already_downloaded
        ErrorReason.LOGIN_REQUIRED -> R.string.status_login_required
        null -> null
    }
}

/**
 * Whether the cookie recovery action applies. Shared by the sheet and the
 * card footer so the two conditions cannot drift: the persisted reason keeps
 * the action reachable after a restore, where the throwable is blank.
 */
fun hasCookieAction(state: DownloadState): Boolean =
    state is Error &&
        (state.reason == ErrorReason.LOGIN_REQUIRED ||
            state.throwable is DownloadErrorMessages.LoginRequiredError)
