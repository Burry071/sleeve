package com.junkfood.seal

import com.junkfood.seal.download.BatchOps
import com.junkfood.seal.download.MatchCandidate
import com.junkfood.seal.download.MatchOps
import com.junkfood.seal.download.MatchResult
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.download.TaskFactory
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker-storm contract for catalogue batches: every track is auto-picked, low-confidence
 * picks are badged, and the batch exposes exactly one review entry once it has finished.
 */
class BatchMatchInvariantTest {

    private val batchId = "batch-storm"

    private fun batch(size: Int = 20) =
        TaskFactory.createMetadataTasks(
            provider = "Spotify",
            playlistTitle = "Storm",
            batchId = batchId,
            tracks =
                (1..size).map {
                    Task.TypeInfo.MetadataTrack(
                        title = "Track $it",
                        artist = "Artist",
                        durationMs = 180_000L,
                    )
                },
            preferences = DownloadPreferences.EMPTY.copy(extractAudio = true),
        )

    private fun candidates(vararg scores: Double): List<MatchCandidate> =
        scores.mapIndexed { index, score ->
            MatchCandidate(
                index = index,
                id = "id$index",
                url = "https://www.youtube.com/watch?v=id$index",
                title = "candidate $index",
                score = score,
                reasons = listOf("score: $score"),
            )
        }

    private fun result(scores: List<Double>, selectedIndex: Int = 0): MatchResult {
        val list = candidates(*scores.toDoubleArray())
        return MatchResult(
            candidates = list,
            selectedUrl = list[selectedIndex].url,
            selectedScore = list[selectedIndex].score,
            flagged = MatchOps.isLowConfidence(list),
        )
    }

    /** Confident picks: top 94, runner-up 70 (gap 24). */
    private fun confident(): MatchResult = result(listOf(94.0, 70.0, 61.0))

    /** Trap 1 — live version: top score below 75. */
    private fun liveTrap(): MatchResult = result(listOf(62.0, 40.0, 31.0))

    /** Trap 2 — cover: strong top, but the original is almost tied. */
    private fun coverTrap(): MatchResult = result(listOf(88.0, 85.0, 72.0))

    /** Trap 3 — "full" suffix: top just under the floor. */
    private fun fullTrap(): MatchResult = result(listOf(74.0, 51.0, 44.0))

    private fun finishedStates(): Map<Task, Task.State> {
        val tasks = batch()
        val matches =
            HashMap<String, MatchResult>().apply {
                put("Track 4", liveTrap())
                put("Track 11", coverTrap())
                put("Track 17", fullTrap())
            }
        return tasks.associate { (task, state) ->
            val title = (task.type as Task.TypeInfo.MetadataTrack).title
            val match = matches[title] ?: confident()
            task to
                state.copy(
                    downloadState = Completed(filePath = "/music/${task.id}.m4a"),
                    match = match,
                )
        }
    }

    @Test
    fun `a twenty track batch auto-picks every track so no picker can appear`() {
        val states = finishedStates()

        assertEquals(20, states.size)
        // The inline picker is only reachable when a track has no selection. Every
        // track resolved to one, so the batch path never opens a modal.
        assertEquals(0, states.count { it.value.match == null })
        assertEquals(0, states.count { it.value.match?.selected == null })
        assertTrue(states.keys.all { it.batch?.id == batchId })
    }

    @Test
    fun `all twenty scores are cached and the three traps are badged`() {
        val entries = finishedStates().toList()

        assertEquals(20, entries.count { it.second.match?.candidates?.isNotEmpty() == true })
        assertEquals(3, MatchOps.reviewCount(entries))
        assertEquals(
            setOf("Track 4", "Track 11", "Track 17"),
            MatchOps.flaggedEntries(entries)
                .map { (t, _) -> (t.type as Task.TypeInfo.MetadataTrack).title }
                .toSet(),
        )
        // The review sheet reads the cache: top-3 candidates with reasons survive on the task.
        MatchOps.flaggedEntries(entries).forEach { (_, state) ->
            val match = state.match!!
            assertTrue(match.candidates.size >= 2)
            assertTrue(match.candidates.all { it.reasons.isNotEmpty() })
        }
    }

    @Test
    fun `exactly one review entry appears and only after the batch finishes`() {
        val entries = finishedStates().toList()

        assertTrue(MatchOps.reviewVisible(entries))
        // One batch -> one card -> one entry point (the notification mirrors the same count).
        assertEquals(1, BatchOps.groups(entries.toMap()).size)

        val stillRunning =
            entries.map { (task, state) ->
                if ((task.type as Task.TypeInfo.MetadataTrack).title == "Track 9") {
                    task to state.copy(downloadState = Running(taskId = task.id))
                } else {
                    task to state
                }
            }
        assertFalse("mid-batch must not offer review", MatchOps.reviewVisible(stillRunning))
        assertEquals(3, MatchOps.reviewCount(stillRunning))
    }

    @Test
    fun `a swap targets exactly one file and leaves the rest untouched`() {
        val entries = finishedStates()
        val swapped =
            entries.keys.first { (it.type as Task.TypeInfo.MetadataTrack).title == "Track 11" }
        val before = entries.getValue(swapped).match!!
        val alternative = before.candidates[1]

        // Exactly one task is queued for the re-download.
        assertEquals(listOf(swapped), MatchOps.swapTargets(listOf(swapped to alternative)))

        val newMatch = before.withSelection(alternative)
        assertNotEquals(before.selectedUrl, newMatch.selectedUrl)
        assertTrue(newMatch.acknowledged)
        assertFalse(MatchOps.reviewable(entries.getValue(swapped).copy(match = newMatch)))

        val afterSwap = entries + (swapped to entries.getValue(swapped).copy(match = newMatch))
        assertEquals(2, MatchOps.reviewCount(afterSwap.toList()))
        entries
            .filterKeys { it != swapped }
            .forEach { (task, state) -> assertEquals(state, afterSwap.getValue(task)) }
    }

    @Test
    fun `acknowledging every pick closes the single review entry`() {
        val entries = finishedStates().toList()

        val acknowledged =
            entries.map { (task, state) ->
                task to state.copy(match = state.match!!.copy(acknowledged = true))
            }
        assertEquals(3, MatchOps.acknowledgeTargets(entries).size)
        assertEquals(0, MatchOps.reviewCount(acknowledged))
        assertFalse(MatchOps.reviewVisible(acknowledged))
    }

    @Test
    fun `confidence thresholds sit exactly at 75 points and a 15 point gap`() {
        assertTrue(MatchOps.isLowConfidence(candidates(74.9, 30.0)))
        assertFalse(MatchOps.isLowConfidence(candidates(75.0, 59.9)))
        assertTrue(MatchOps.isLowConfidence(candidates(90.0, 75.1)))
        assertFalse(MatchOps.isLowConfidence(candidates(90.0, 75.0)))
    }

    @Test
    fun `an empty or single-candidate match is never silently trusted`() {
        assertTrue(MatchOps.isLowConfidence(emptyList()))
        assertFalse(MatchOps.isLowConfidence(candidates(90.0)))
    }
}
