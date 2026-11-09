package com.junkfood.seal.download

/**
 * Pure rules for low-confidence catalogue matches.
 *
 * A pick is flagged when the ranker's top score is below 75, or when the gap
 * between the top two candidates is below 15 points. Scores are the ranker's
 * 0-100 scale. Flagged picks surface a row badge and, once the batch finishes,
 * the single "Review matches" entry.
 */
object MatchOps {

    const val FLAG_TOP_RATIO = 0.75
    const val FLAG_GAP_RATIO = 0.15

    fun isLowConfidence(candidates: List<MatchCandidate>): Boolean {
        val sorted = candidates.sortedByDescending { it.score }
        val top = sorted.firstOrNull() ?: return true
        if (top.score / 100.0 < FLAG_TOP_RATIO) return true
        val second = sorted.getOrNull(1) ?: return false
        return (top.score - second.score) / 100.0 < FLAG_GAP_RATIO
    }

    fun reviewable(state: Task.State): Boolean =
        state.match?.let { it.flagged && !it.acknowledged } == true

    fun flaggedEntries(entries: List<Pair<Task, Task.State>>): List<Pair<Task, Task.State>> =
        entries.filter { reviewable(it.second) }

    fun reviewCount(entries: List<Pair<Task, Task.State>>): Int = flaggedEntries(entries).size

    /** The one review entry appears only after the batch has finished. */
    fun reviewVisible(entries: List<Pair<Task, Task.State>>): Boolean =
        BatchOps.summary(entries).unfinished == 0 && reviewCount(entries) > 0

    /** Swapping one pick can re-download exactly the song it belongs to. */
    fun swapTargets(selections: List<Pair<Task, MatchCandidate>>): List<Task> =
        selections.map { it.first }.distinct()

    fun acknowledgeTargets(entries: List<Pair<Task, Task.State>>): List<Task> =
        flaggedEntries(entries).map { it.first }
}
