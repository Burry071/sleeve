package com.junkfood.seal.download

import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * One ranker candidate kept after a catalogue match, with the reason
 * components the ranker used. Cached in [Task.State] so the review sheet never
 * re-runs a search.
 *
 * The metadata fields are additive: a cached match written by beta.3 decodes
 * with them empty, and an unknown duration stays `0` so the UI can hide it
 * instead of rendering `0:00`.
 */
@Serializable
data class MatchCandidate(
    val index: Int = 0,
    val id: String = "",
    val url: String = "",
    val title: String = "",
    val score: Double = 0.0,
    val reasons: List<String> = emptyList(),
    val uploader: String = "",
    val channel: String = "",
    val duration: Long = 0L,
) {
    val percent: Int
        get() = score.roundToInt()

    /** One decimal, so a 74.6 never renders as a rounded 75. */
    val scoreLabel: String
        get() = String.format(Locale.US, "%.1f%%", score)

    /** `m:ss`; empty when the ranker reported no duration. */
    val durationText: String
        get() {
            if (duration <= 0L) return ""
            val minutes = duration / 60L
            val seconds = duration % 60L
            return String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
}

/**
 * Everything the queue keeps about one catalogue match.
 *
 * A new pick is [pending] until the replacement file is verified: the review
 * entry stays visible while pending, so a failed swap falls back to the original
 * selection instead of silently losing it.
 */
@Serializable
data class MatchResult(
    val candidates: List<MatchCandidate> = emptyList(),
    val selectedUrl: String = "",
    val selectedScore: Double = 0.0,
    val flagged: Boolean = false,
    val acknowledged: Boolean = false,
    val pending: Boolean = false,
    val previousSelectedUrl: String = "",
    val previousSelectedScore: Double = 0.0,
    /**
     * Audible pre-download gate held this pick: no candidate proved full
     * length, so nothing may download until the user picks explicitly.
     * Defaults keep cached JSON from older versions decodable.
     */
    val held: Boolean = false,
    /** Audible runtime the gate compared against; 0 when not gated. */
    val expectedDurationMs: Long = 0L,
    /** Why the gate held; empty for a pass or a non-Audible task. */
    val gateReason: String = "",
    /**
     * Duration the downloaded file actually measured, in ms. `0` when the
     * post-download check never ran or could not measure the file. Defaulted
     * so match JSON written before phase 1b still decodes.
     */
    val actualDurationMs: Long = 0L,
    /**
     * The split plan: when no single upload carries the book but a complete
     * part set does, these are the uploads to download, in part order, each as
     * its own file. Empty for a single pick. `score` is 0 on purpose — a part
     * is a set member, not a ranking. Defaulted so cached match JSON from
     * earlier versions still decodes.
     */
    val parts: List<MatchCandidate> = emptyList(),
) {
    val selected: MatchCandidate?
        get() = candidates.firstOrNull { it.url == selectedUrl } ?: candidates.firstOrNull()

    val percent: Int
        get() = selectedScore.roundToInt()

    /**
     * Records a new pick without trusting it yet: it stays unacknowledged and
     * pending, and the old selection is remembered for [cancelPending].
     */
    fun withSelection(candidate: MatchCandidate): MatchResult =
        copy(
            selectedUrl = candidate.url,
            selectedScore = candidate.score,
            acknowledged = false,
            pending = true,
            previousSelectedUrl = selectedUrl,
            previousSelectedScore = selectedScore,
        )

    /** Called only once the replacement file exists and is non-empty. */
    fun confirmSelection(): MatchResult =
        copy(
            pending = false,
            previousSelectedUrl = "",
            previousSelectedScore = 0.0,
            acknowledged = true,
            // The picked candidate is now the verified source: it is no longer
            // a held book. The reason is kept as the audit trail.
            held = false,
        )

    /** Restores the pre-swap selection and re-opens the review entry. */
    fun cancelPending(): MatchResult =
        copy(
            selectedUrl = previousSelectedUrl,
            selectedScore = previousSelectedScore,
            pending = false,
            previousSelectedUrl = "",
            previousSelectedScore = 0.0,
            acknowledged = false,
        )

    /**
     * Resolve a pending swap that outlived the process that parked its old
     * path. The old path is intentionally not persisted, so a non-completed
     * restore must fall back to the previous pick; a completed restore has
     * already produced the replacement file and can be confirmed.
     */
    fun restoreAfterProcessDeath(completed: Boolean): MatchResult =
        when {
            !pending -> this
            completed -> confirmSelection()
            else -> cancelPending()
        }
}

/** How one requested swap ended. One [SwapResult] is reported per task. */
@Serializable
enum class SwapOutcome {
    Queued,
    Busy,
    Missing,
    FetchFailed,
}

/** One user-safe status line for the review sheet; never carries a stack trace. */
@Serializable
data class SwapResult(
    val taskId: String,
    val uiKey: String,
    val title: String,
    val candidateUrl: String,
    val candidateTitle: String,
    val outcome: SwapOutcome,
    val reason: String = "",
)
