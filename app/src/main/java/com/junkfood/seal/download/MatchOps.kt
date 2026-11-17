package com.junkfood.seal.download

import com.junkfood.seal.util.DownloadErrorMessages
import com.junkfood.seal.util.LiveGate
import com.junkfood.seal.util.DownloadErrorMessages.NotReadyError

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
    fun reviewable(state: Task.State): Boolean = state.match.phase.isOpen

    /**
     * Accept all may close a plain flagged pick, never a pending swap and never
     * a held book: a held book leaves the review entry only through an explicit
     * pick (the swap path), so its refusal cannot be cleared by a batch action.
     */
    fun acknowledgeable(state: Task.State): Boolean = state.match.phase == MatchPhase.NeedsReview

    /**
     * Accept all stays available while any flagged pick is still open, even
     * next to held books: a batch that mixes the two would otherwise offer the
     * button and then refuse it.
     */
    fun acceptAllAvailable(entries: List<Pair<Task, Task.State>>): Boolean =
        entries.any { acknowledgeable(it.second) }

    /**
     * True whenever the gate refused this pick, including while its replacement
     * downloads. This is what blocks Resume: the hold is a property of the
     * stored pick, not of the review bucket it currently shows in.
     */
    fun held(state: Task.State): Boolean = state.match?.held == true

    fun flaggedEntries(entries: List<Pair<Task, Task.State>>): List<Pair<Task, Task.State>> =
        entries.filter { reviewable(it.second) }

    /**
     * The songs a batch swap will re-download: one entry per row whose stored
     * pick the user replaced, in row order. An unchanged pick is work the user
     * kept, and a pending replacement is skipped because a second swap on top
     * of it would orphan the first.
     */
    fun pendingSwaps(
        flagged: List<Pair<Task, Task.State>>,
        picks: Map<String, String>,
    ): List<Pair<Task, MatchCandidate>> =
        flagged.mapNotNull { (task, state) ->
            val match = state.match ?: return@mapNotNull null
            if (!reviewable(state) || match.phase == MatchPhase.SwapPending) {
                return@mapNotNull null
            }
            val chosen =
                match.candidates.firstOrNull { it.url == picks[task.uiKey] }
                    ?: return@mapNotNull null
            if (chosen.url == match.selectedUrl) null else task to chosen
        }

    /**
     * A held book that the gate resolved as a verified whole-book part set. The
     * hold is "nothing to download in one file", not "no source": the set covers
     * the book and the review sheet offers the parts.
     */
    fun isVerifiedSplit(match: MatchResult?): Boolean =
        match?.held == true && match.parts.isNotEmpty()

    /** Every reviewable entry in the batch is a verified split set. */
    fun splitOnly(entries: List<Pair<Task, Task.State>>): Boolean {
        val reviewable = flaggedEntries(entries)
        return reviewable.isNotEmpty() && reviewable.all { isVerifiedSplit(it.second.match) }
    }

    fun reviewCount(entries: List<Pair<Task, Task.State>>): Int = flaggedEntries(entries).size

    /**
     * The one review entry appears once the batch has finished — or earlier
     * when a pick is held: a held book is parked behind the review sheet until
     * the user picks a replacement, so hiding the entry until the rest of the
     * batch ends leaves a finished-looking row with no exit.
     */
    fun reviewVisible(entries: List<Pair<Task, Task.State>>): Boolean =
        (BatchOps.summary(entries).unfinished == 0 || entries.any { held(it.second) }) &&
            reviewCount(entries) > 0

    /**
     * 1-based label for a part, normalising the two numbering bases the gate
     * accepts. The parsed `index` is 1-based in the common case and 0-based for
     * zero-based sets; the sheet must say "Part 1" for both.
     */
    fun partDisplayNumber(match: MatchResult, part: MatchCandidate): Int {
        val base = match.parts.minOfOrNull { it.index } ?: 0
        return part.index - base + 1
    }

    /**
     * The state a resolved metadata task enters. A held book must never become
     * [Task.DownloadState.ReadyWithInfo]: that is the write the scheduler reads
     * as "start downloading", and the whole point of the gate is to spend zero
     * bytes on an unverified pick. `Canceled` counts as finished, which is what
     * surfaces the existing review entry.
     *
     * A live gate refusal arrives here instead of at fetch time only when the
     * match itself resolves the metadata; it must land on `Error` before the
     * media request, so the pick can be retried once the stream is ready.
     */
    fun resolvedState(match: MatchResult, liveStatus: String? = null): Task.DownloadState =
        when (val live = LiveGate.decide(liveStatus)) {
            is LiveGate.Decision.Refuse ->
                Task.DownloadState.Error(
                    NotReadyError(live.reasonRes),
                    Task.RestartableAction.FetchInfo,
                    reason = DownloadErrorMessages.errorReasonOf(NotReadyError(live.reasonRes)),
                )
            is LiveGate.Decision.Allow ->
                if (match.held) {
                    Task.DownloadState.Canceled(Task.RestartableAction.Download)
                } else {
                    Task.DownloadState.ReadyWithInfo
                }
        }
}
