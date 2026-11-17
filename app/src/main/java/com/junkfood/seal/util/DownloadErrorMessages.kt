package com.junkfood.seal.util

import androidx.annotation.StringRes
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.download.ErrorReason

/**
 * One shared mapping from raw extractor failure text (yt-dlp, gallery-dl,
 * ghost) to short actionable messages. Task-level and sheet-level error
 * surfaces both render `throwable.message`, so this is applied where those
 * throwables are stored; the original throwable rides along as the cause so
 * copied error reports keep full detail.
 *
 * [classify] is the pure half: it reads the raw text and names the family.
 * [friendly] resolves that family to a string resource, which needs a running
 * app context — that is why the classification is separate and testable.
 */
object DownloadErrorMessages {

    /** Which actionable failure the raw text describes. Order is priority. */
    enum class ErrorFamily {
        LOGIN_REQUIRED,
        BOT_CHECK,
        ACCESS_DENIED,
        NOT_FOUND,
        FORMAT_UNAVAILABLE,
        RATE_LIMITED,
        EXTRACTOR_BROKEN,
        TIMEOUT,
        POSTPROCESSING,
        OTHER,
    }

    /**
     * Typed marker for login-walled extractions: error surfaces offer a
     * one-tap "Add cookies" deep link when they catch this type. The message
     * is the localized [R.string.site_login_required] guidance; the cause
     * keeps the raw extractor output for reports.
     */
    class LoginRequiredError(message: String, cause: Throwable) :
        IllegalStateException(message, cause)

    /**
     * The extractor itself is out of date or broken (signature/n-sig changes),
     * not the link. The recovery is an engine update, so error surfaces offer
     * "Update yt-dlp" for this type.
     */
    class ExtractorBrokenError(message: String, cause: Throwable) : Exception(message, cause)

    /**
     * A stream that the extractor proved cannot be downloaded yet. The copy
     * comes from the resource this carries, so the card and the report say the
     * same thing without parsing the message.
     */
    class NotReadyError(@StringRes val reasonRes: Int) :
        IllegalStateException("stream not ready to download")

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
            is NotReadyError ->
                when (throwable.reasonRes) {
                    R.string.error_stream_just_ended -> ErrorReason.STREAM_ENDED
                    R.string.error_stream_upcoming -> ErrorReason.STREAM_UPCOMING
                    else -> null
                }
            else -> null
        }

    private val loginRequired =
        Regex(
            "account authentication is required|use --cookies|--cookies-from-browser" +
                "|login required|log in to confirm|sign in to confirm|members-only" +
                "|confirm your age|login page",
            RegexOption.IGNORE_CASE,
        )

    /**
     * Checked before [loginRequired]: YouTube's bot wall also names the cookie
     * flags, so priority alone would file it as an ordinary login wall.
     */
    private val botCheck =
        Regex(
            "not a bot|captcha|unusual traffic|automated queries",
            RegexOption.IGNORE_CASE,
        )

    private val accessDenied =
        Regex("http error 403|\\b403\\b.*forbidden|access denied", RegexOption.IGNORE_CASE)

    private val notFound =
        Regex(
            "http error 404|http error 410|video unavailable|private video" +
                "|has been removed|no longer available|does not exist|not available in your country",
            RegexOption.IGNORE_CASE,
        )

    private val timedOut =
        Regex("timed out|timeout|http error 504|connection reset", RegexOption.IGNORE_CASE)

    /** yt-dlp's merge/convert/tag steps run after the bytes are in; ffmpeg does them. */
    private val postProcessing =
        Regex("postprocessing|ffmpeg|conversion failed", RegexOption.IGNORE_CASE)

    private val formatUnavailable =
        Regex("requested format is not available", RegexOption.IGNORE_CASE)

    private val rateLimited =
        Regex("429|too many requests|rate.?limit", RegexOption.IGNORE_CASE)

    /**
     * Engine faults, not link faults: the extractor no longer matches the site.
     * `sabr` is a bare token in yt-dlp's output, so it is matched as a word to
     * keep unrelated prose ("sabremetrics") out.
     */
    private val extractorBroken =
        Regex(
            "unable to extract|no supported javascript runtime|nsig extraction failed" +
                "|\\bsabr\\b",
            RegexOption.IGNORE_CASE,
        )

    /** Pure: the family a raw failure text belongs to, with no resource lookup. */
    fun classify(text: String): ErrorFamily =
        when {
            text.isEmpty() -> ErrorFamily.OTHER
            botCheck.containsMatchIn(text) -> ErrorFamily.BOT_CHECK
            loginRequired.containsMatchIn(text) -> ErrorFamily.LOGIN_REQUIRED
            notFound.containsMatchIn(text) -> ErrorFamily.NOT_FOUND
            accessDenied.containsMatchIn(text) -> ErrorFamily.ACCESS_DENIED
            formatUnavailable.containsMatchIn(text) -> ErrorFamily.FORMAT_UNAVAILABLE
            rateLimited.containsMatchIn(text) -> ErrorFamily.RATE_LIMITED
            extractorBroken.containsMatchIn(text) -> ErrorFamily.EXTRACTOR_BROKEN
            timedOut.containsMatchIn(text) -> ErrorFamily.TIMEOUT
            postProcessing.containsMatchIn(text) -> ErrorFamily.POSTPROCESSING
            else -> ErrorFamily.OTHER
        }

    /** The user-facing text for a family, or the raw text when nothing maps. */
    fun friendly(throwable: Throwable): String {
        val raw = throwable.message ?: ""
        return when (classify(raw)) {
            ErrorFamily.BOT_CHECK -> App.context.getString(R.string.site_bot_check)
            ErrorFamily.LOGIN_REQUIRED -> App.context.getString(R.string.site_login_required)
            ErrorFamily.ACCESS_DENIED -> App.context.getString(R.string.site_access_denied)
            ErrorFamily.NOT_FOUND -> App.context.getString(R.string.site_media_not_found)
            ErrorFamily.FORMAT_UNAVAILABLE ->
                App.context.getString(R.string.format_unavailable_hint)
            ErrorFamily.RATE_LIMITED -> App.context.getString(R.string.site_rate_limited)
            ErrorFamily.EXTRACTOR_BROKEN -> App.context.getString(R.string.extractor_broken_hint)
            ErrorFamily.TIMEOUT -> App.context.getString(R.string.download_timed_out)
            ErrorFamily.POSTPROCESSING ->
                App.context.getString(R.string.post_processing_failed)
            ErrorFamily.OTHER -> raw
        }
    }

    /**
     * Same throwable when nothing maps; otherwise a rethrow of the typed family
     * with a user-facing message. Typed errors pass through untouched: their
     * fields are what the card, the sheet and the "Share kept file" action read.
     */
    fun asFriendly(throwable: Throwable): Throwable {
        // Already user-facing, and the failed names inside the message are
        // file names: remapping would drop the typed fields the card needs.
        if (throwable is StorageMoveError) return throwable
        if (throwable is ExtractorBrokenError) return throwable
        if (throwable is LoginRequiredError) return throwable
        val message = friendly(throwable)
        return when (classify(throwable.message ?: "")) {
            // The bot wall is cured the same way a login wall is: the account
            // the site wants is the one in the cookie file.
            ErrorFamily.BOT_CHECK,
            ErrorFamily.LOGIN_REQUIRED -> LoginRequiredError(message, throwable)
            ErrorFamily.EXTRACTOR_BROKEN -> ExtractorBrokenError(message, throwable)
            ErrorFamily.ACCESS_DENIED,
            ErrorFamily.NOT_FOUND,
            ErrorFamily.FORMAT_UNAVAILABLE,
            ErrorFamily.RATE_LIMITED,
            ErrorFamily.TIMEOUT,
            ErrorFamily.POSTPROCESSING -> IllegalStateException(message, throwable)
            ErrorFamily.OTHER -> throwable
        }
    }
}
