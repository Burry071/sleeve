package com.junkfood.seal.ui.page.downloadv2

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.junkfood.seal.R
import com.junkfood.seal.download.CompletedNote
import com.junkfood.seal.download.ErrorReason
import com.junkfood.seal.download.PostProcessing
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
 * The stored sentence the row shows when no short reason line applies — the
 * move-failure count or the mapped site message. Gated on the transient
 * throwable still carrying its own text: after a restore the throwable is a
 * bare [Throwable] with no message, so only the persisted [statusLine] reason
 * survives; the full sentence stays in the sheet and the copied report.
 */
fun statusText(state: DownloadState): String? {
    val error = state as? Error ?: return null
    if (error.reason != null) return null
    if (error.throwable.message == null) return null
    return error.message.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
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
 * What a finished card says about a secondary miss: the download succeeded, but
 * one thing about it did not. Separate from [statusText] so a note never
 * outranks a real warning.
 */
@Composable
fun completedNoteLine(state: DownloadState): String? {
    val completed = state as? Completed ?: return null
    return when {
        CompletedNote.M4B_NOT_EXPORTED in completed.notes ->
            stringResource(R.string.m4b_not_exported)
        else -> null
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

private val YT_DLP_PROGRESS_REGEX =
    Regex(
        """\[download]\s+(?:\d+(?:\.\d+)?%\s+of\s+~?\s*)?([0-9.]+\s*[KMGTP]?i?B)(?:\s+in\s+\S+)?(?:\s+at\s+([0-9.]+\s*[KMGTP]?i?B/s|Unknown\s+B/s))?(?:\s+ETA\s+(\S+))?""",
        RegexOption.IGNORE_CASE,
    )

private val ARIA2_PROGRESS_REGEX =
    Regex(
        """\[#[0-9a-f]+\s+([0-9.]+\s*[KMGTP]?i?B/[0-9.]+\s*[KMGTP]?i?B)\([^)]*\).*?DL:([0-9.]+\s*[KMGTP]?i?B)(?:\s+ETA:([^\]]+))?]""",
        RegexOption.IGNORE_CASE,
    )

private val NM3U8_STATS_REGEX =
    Regex(
        """([0-9.]+\s*[KMGTP]?i?B\s*/\s*[0-9.]+\s*[KMGTP]?i?B)(?:\s+([0-9.]+\s*[KMGTP]?i?B/s))?(?:\s+(\d{1,2}:\d{2}(?::\d{2})?))?""",
        RegexOption.IGNORE_CASE,
    )

/**
 * Crisp single-line transfer summary (engine, bytes, speed, ETA) extracted from
 * [DownloadState.Running.progressText]. Returns null for blank text,
 * post-processing log lines, or non-transfer chatter such as file paths.
 */
fun runningTransferLine(progressText: String): String? {
    val line = progressText.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: return null
    if (PostProcessing.inProgress(line)) return null

    if (line.startsWith("Ghost:", ignoreCase = true)) {
        return line.replace(" • ", " · ")
    }

    if (line.startsWith("N_m3u8DL-RE:", ignoreCase = true)) {
        val body = line.substringAfter(':').trim()
        val match = NM3U8_STATS_REGEX.find(body)
        if (match != null) {
            val bytes = match.groupValues[1].replace(Regex("\\s+"), "")
            val speed = match.groupValues[2].trim().takeIf { it.isNotEmpty() }
            val eta = match.groupValues[3].trim().takeIf { it.isNotEmpty() }
            return buildList {
                    add("N_m3u8DL-RE")
                    add(bytes)
                    if (speed != null) add(speed)
                    if (eta != null) add("ETA $eta")
                }
                .joinToString(" · ")
        }
        return if (body.isNotEmpty()) "N_m3u8DL-RE · $body" else null
    }

    ARIA2_PROGRESS_REGEX.find(line)?.let { match ->
        val bytes = match.groupValues[1].trim()
        val speed = "${match.groupValues[2].trim()}/s"
        val eta = match.groupValues[3].trim().takeIf { it.isNotEmpty() }
        return buildList {
                add("aria2c")
                add(bytes)
                add(speed)
                if (eta != null) add("ETA $eta")
            }
            .joinToString(" · ")
    }

    YT_DLP_PROGRESS_REGEX.find(line)?.let { match ->
        val size = match.groupValues[1].replace(Regex("\\s+"), "")
        val speed =
            match.groupValues[2]
                .replace(Regex("\\s+"), "")
                .takeIf { it.isNotEmpty() && !it.startsWith("Unknown", ignoreCase = true) }
        val eta =
            match.groupValues[3]
                .trim()
                .takeIf { it.isNotEmpty() && !it.equals("Unknown", ignoreCase = true) }
        return buildList {
                add("yt-dlp")
                add(size)
                if (speed != null) add(speed)
                if (eta != null) add("ETA $eta")
            }
            .joinToString(" · ")
    }

    return null
}
