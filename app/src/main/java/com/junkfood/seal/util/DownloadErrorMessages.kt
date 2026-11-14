package com.junkfood.seal.util

import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.download.ErrorReason

/**
 * One shared mapping from raw extractor failure text (yt-dlp, gallery-dl,
 * ghost) to short actionable messages. Task-level and sheet-level error
 * surfaces both render `throwable.message`, so this is applied where those
 * throwables are stored; the original throwable rides along as the cause so
 * copied error reports keep full detail.
 */
object DownloadErrorMessages {

    /**
     * Typed marker for login-walled extractions: error surfaces offer a
     * one-tap "Add cookies" deep link when they catch this type. The message
     * is the localized [R.string.site_login_required] guidance; the cause
     * keeps the raw extractor output for reports.
     */
    class LoginRequiredError(message: String, cause: Throwable) :
        IllegalStateException(message, cause)

    /**
     * The playlist item's index now resolves to a different video than the one
     * the user picked. The row copy comes from [R.string.status_playlist_shifted].
     */
    class PlaylistShiftedError(val expectedId: String, val actualId: String) :
        IllegalStateException("playlist shifted: $expectedId -> $actualId")

    /** yt-dlp's archive already lists this media. The row copy comes from [R.string.status_already_downloaded]. */
    class AlreadyDownloadedError(val extractorKey: String, val id: String, message: String) :
        IllegalStateException(message)

    /**
     * The move into the chosen SD-card folder failed for at least one file.
     * The typed fields drive both the card copy and the "Share kept file"
     * action, so neither parses the message. [cause] is nullable because a
     * provider can refuse a document without throwing anything.
     */
    class StorageMoveError(
        val copied: Int,
        val failed: List<String>,
        val retainedDir: String,
        cause: Throwable? = null,
    ) : Exception(
        "SD card copy failed for ${failed.size} of ${copied + failed.size} file(s): " +
            failed.joinToString(", "),
        cause,
    )

    /**
     * The reason to persist beside the message. Applied to [asFriendly]'s
     * result, not the raw throwable: [LoginRequiredError] is only ever
     * constructed inside [asFriendly], so a raw value never maps to it.
     */
    fun errorReasonOf(throwable: Throwable): ErrorReason? =
        when (throwable) {
            is PlaylistShiftedError -> ErrorReason.PLAYLIST_SHIFTED
            is AlreadyDownloadedError -> ErrorReason.ALREADY_DOWNLOADED
            is LoginRequiredError -> ErrorReason.LOGIN_REQUIRED
            else -> null
        }

    private val loginRequired =
        Regex(
            "account authentication is required|use --cookies|--cookies-from-browser" +
                "|login required|log in to confirm|sign in to confirm|members-only" +
                "|confirm your age|login page",
            RegexOption.IGNORE_CASE,
        )

    private val formatUnavailable =
        Regex("requested format is not available", RegexOption.IGNORE_CASE)

    private val rateLimited =
        Regex("429|too many requests|rate.?limit", RegexOption.IGNORE_CASE)

    fun friendly(throwable: Throwable): String {
        val raw = throwable.message ?: ""
        return when {
            raw.isEmpty() -> raw
            loginRequired.containsMatchIn(raw) ->
                App.context.getString(R.string.site_login_required)
            formatUnavailable.containsMatchIn(raw) ->
                App.context.getString(R.string.format_unavailable_hint)
            rateLimited.containsMatchIn(raw) ->
                App.context.getString(R.string.site_rate_limited)
            else -> raw
        }
    }

    /** Same throwable when nothing maps; otherwise a rethrow with a user-facing message. */
    fun asFriendly(throwable: Throwable): Throwable {
        // Already user-facing, and the failed names inside the message are
        // file names: remapping would drop the typed fields the card needs.
        if (throwable is StorageMoveError) return throwable
        val message = friendly(throwable)
        return when {
            message == throwable.message -> throwable
            loginRequired.containsMatchIn(throwable.message ?: "") ->
                LoginRequiredError(message, throwable)
            else -> IllegalStateException(message, throwable)
        }
    }
}
