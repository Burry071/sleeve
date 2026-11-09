package com.junkfood.seal.download

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * One ranker candidate kept after a catalogue match, with the reason
 * components the ranker used. Cached in [Task.State] so the review sheet never
 * re-runs a search.
 */
@Serializable
data class MatchCandidate(
    val index: Int = 0,
    val id: String = "",
    val url: String = "",
    val title: String = "",
    val score: Double = 0.0,
    val reasons: List<String> = emptyList(),
) {
    val percent: Int
        get() = score.roundToInt()
}

/** Everything the queue keeps about one catalogue match. */
@Serializable
data class MatchResult(
    val candidates: List<MatchCandidate> = emptyList(),
    val selectedUrl: String = "",
    val selectedScore: Double = 0.0,
    val flagged: Boolean = false,
    val acknowledged: Boolean = false,
) {
    val selected: MatchCandidate?
        get() = candidates.firstOrNull { it.url == selectedUrl } ?: candidates.firstOrNull()

    val percent: Int
        get() = selectedScore.roundToInt()

    fun withSelection(candidate: MatchCandidate): MatchResult =
        copy(
            selectedUrl = candidate.url,
            selectedScore = candidate.score,
            acknowledged = true,
        )
}
