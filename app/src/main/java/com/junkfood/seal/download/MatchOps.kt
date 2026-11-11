package com.junkfood.seal.download

/**
 * Pure rules for catalogue matches: low-confidence flags and the audible
 * pre-download gate.
 *
 * A pick is flagged when the ranker's top score is below 75, or when the gap
 * between the top two candidates is below 15 points. Scores are the ranker's
 * 0-100 scale. Flagged picks surface a row badge and, once the batch finishes,
 * the single "Review matches" entry. A held pick is one the audible gate
 * refused; it is reviewable and can never start a download on its own.
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

    /**
     * A pick needs review when the ranker doubted it or the audible gate held
     * it. A held book must reach the review sheet even when the ranker was
     * confident: the gate, not the score, decides whether it may download.
     */
    fun reviewable(state: Task.State): Boolean =
        state.match?.let { (it.flagged || it.held) && !it.acknowledged } == true

    /**
     * Accept all may close a plain flagged pick, never a pending swap and never
     * a held book: a held book leaves the review entry only through an explicit
     * pick (the swap path), so its refusal cannot be cleared by a batch action.
     */
    fun acknowledgeable(state: Task.State): Boolean {
        val match = state.match ?: return false
        return reviewable(state) && !match.pending && !match.held
    }

    fun flaggedEntries(entries: List<Pair<Task, Task.State>>): List<Pair<Task, Task.State>> =
        entries.filter { reviewable(it.second) }

    fun reviewCount(entries: List<Pair<Task, Task.State>>): Int = flaggedEntries(entries).size

    /** The one review entry appears only after the batch has finished. */
    fun reviewVisible(entries: List<Pair<Task, Task.State>>): Boolean =
        BatchOps.summary(entries).unfinished == 0 && reviewCount(entries) > 0

    /**
     * The state a resolved metadata task enters. A held book must never become
     * [Task.DownloadState.ReadyWithInfo]: that is the write the scheduler reads
     * as "start downloading", and the whole point of the gate is to spend zero
     * bytes on an unverified pick. `Canceled` counts as finished, which is what
     * surfaces the existing review entry.
     */
    /**
     * 1-based label for a part, normalising the two numbering bases the gate
     * accepts. The parsed `index` is 1-based in the common case and 0-based for
     * zero-based sets; the sheet must say "Part 1" for both.
     */
    fun partDisplayNumber(match: MatchResult, part: MatchCandidate): Int {
        val base = match.parts.minOfOrNull { it.index } ?: 0
        return part.index - base + 1
    }

    fun resolvedState(match: MatchResult): Task.DownloadState =
        if (match.held) {
            Task.DownloadState.Canceled(Task.RestartableAction.Download)
        } else {
            Task.DownloadState.ReadyWithInfo
        }
}
