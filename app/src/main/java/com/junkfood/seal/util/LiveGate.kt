package com.junkfood.seal.util

import androidx.annotation.StringRes
import com.junkfood.seal.R

/**
 * What to do with a freshly fetched item according to its `live_status`.
 *
 * A live stream is allowed: whatever has aired is a valid recording, and the
 * card says so. A just-ended or upcoming stream cannot produce a file yet, so
 * it is refused at fetch time — before any media request — and retried later
 * through the ordinary `Error` path.
 *
 * Pure on purpose: the decision is a resource id, not a sentence, so the table
 * is testable without an Android context.
 */
internal object LiveGate {

    sealed interface Decision {
        /** Download it; [warningRes] is a card line, null for an ordinary item. */
        data class Allow(@StringRes val warningRes: Int?) : Decision

        data class Refuse(@StringRes val reasonRes: Int) : Decision
    }

    fun decide(liveStatus: String?): Decision =
        when (liveStatus) {
            "is_live" -> Decision.Allow(R.string.status_live_stream)
            "post_live" -> Decision.Refuse(R.string.error_stream_just_ended)
            "is_upcoming" -> Decision.Refuse(R.string.error_stream_upcoming)
            // was_live / not_live / none / absent are ordinary recordings.
            else -> Decision.Allow(null)
        }
}
