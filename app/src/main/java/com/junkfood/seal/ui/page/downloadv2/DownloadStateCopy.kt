package com.junkfood.seal.ui.page.downloadv2

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.junkfood.seal.R
import com.junkfood.seal.download.ErrorReason
import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.util.ContainerLabel
import com.junkfood.seal.util.CookieFileStatus
import com.junkfood.seal.util.DownloadErrorMessages
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences

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
        ErrorReason.STREAM_ENDED -> R.string.error_stream_just_ended
        ErrorReason.STREAM_UPCOMING -> R.string.error_stream_upcoming
        null -> null
    }
}

/**
 * The move-failure sentence, which carries counts and so cannot be a string
 * resource. Gated on the same transient throwable as the share action, so the
 * two appear and disappear together; the full message, directory included,
 * stays in the copied report.
 */
fun statusText(state: DownloadState): String? {
    val error = state as? Error ?: return null
    if (error.throwable !is DownloadErrorMessages.StorageMoveError) return null
    return error.message.lineSequence().firstOrNull()
}

/**
 * What the finished card names about the file itself: the audio target when conversion
 * replaced the source stream, otherwise the container the merge produced. Both are read
 * from the finished name, so a private-mode null path and a SAF `content://` URI show
 * nothing.
 */
@Composable
fun completedFormatLine(state: DownloadState, preferences: DownloadPreferences): String? {
    val completed = state as? Completed ?: return null
    val container = ContainerLabel.of(completed.filePath) ?: return null
    return if (preferences.extractAudio) {
        if (!preferences.convertAudio) return null
        val target = if (preferences.audioConvertFormat == 0) "MP3" else "M4A"
        stringResource(R.string.converted_to, target)
    } else {
        stringResource(R.string.container_format, container)
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

/**
 * Which line the cookies row shows. The entry count is passed through
 * unconditionally by the caller: the no-arg resources simply ignore it.
 */
@StringRes
fun cookieRowRes(status: CookieFileStatus): Int =
    when {
        !status.exists || status.entryCount <= 0 -> R.string.cookies_row_missing
        !status.parseable -> R.string.cookies_row_unparseable
        else -> R.string.cookies_row_entries
    }
