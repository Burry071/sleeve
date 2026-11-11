package com.junkfood.seal

import com.junkfood.seal.download.BatchInfo
import com.junkfood.seal.download.FakeDownloaderV2
import com.junkfood.seal.download.MatchResult
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.needsSearch
import com.junkfood.seal.ghostengine.GhostEngine
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 v1 split books: the gate's verified part set becomes a plan on
 * [MatchResult], and every part runs as its own direct-URL download.
 *
 * Everything here is pure. The queue side — removing the held book and
 * enqueueing N part tasks, each then fetching and downloading on its own — needs
 * a real [com.junkfood.seal.download.DownloaderV2] and is covered only by
 * instrumentation.
 */
class AudiblePartsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun ranked(index: Int) =
        GhostEngine.RankedCandidate(
            index = index,
            id = "id$index",
            url = "https://www.youtube.com/watch?v=id$index",
            title = "DUNE — Part ${index + 1}",
            score = 90.0 - index,
            duration = 42_840L,
        )

    private val rankedPool = (0 until 3).map { ranked(it) }

    /** Part `index` (1-based) is upload `id{index-1}`, as the gate reports it. */
    private fun part(index: Int, seconds: Long = 42_840L) =
        GhostEngine.AudiblePart(
            index = index,
            id = "id${index - 1}",
            url = "https://www.youtube.com/watch?v=id${index - 1}",
            title = "DUNE — Part $index",
            durationSeconds = seconds,
            channel = "Starlit Pages",
        )

    private fun splitGate() =
        GhostEngine.AudibleGateResult(
            verdict = GhostEngine.AudibleGateResult.VERDICT_SPLIT,
            reason = "complete 2-part set: 100% of the book",
            ratio = 1.0,
            expectedMs = 85_680_000L,
            bestId = "id1",
            bestRatio = 0.57,
            parts = listOf(part(1), part(2)),
            partCount = 2,
        )

    private fun holdGate() =
        GhostEngine.AudibleGateResult(
            verdict = GhostEngine.AudibleGateResult.VERDICT_HOLD,
            reason = "no full-length candidate - best match \"DUNE — Part 1\" is 34% of the book",
            expectedMs = 85_680_000L,
            bestId = "id1",
            bestRatio = 0.34,
        )

    private fun passGate() =
        GhostEngine.AudibleGateResult(
            verdict = GhostEngine.AudibleGateResult.VERDICT_PASS,
            reason = "full-length match: \"DUNE\" is 99% of the book",
            candidateId = "id2",
            ratio = 0.99,
            expectedMs = 85_680_000L,
            bestId = "id0",
            bestRatio = 0.98,
        )

    private fun bookTask(): Task =
        Task(
            url = "metadata://Audible/b/0",
            type =
                Task.TypeInfo.MetadataTrack(
                    index = 0,
                    provider = "Audible",
                    title = "Dune",
                    artist = "Frank Herbert",
                    album = "Dune",
                    durationMs = 85_680_000L,
                    authors = "Frank Herbert",
                    language = "english",
                ),
            preferences = DownloadPreferences.EMPTY,
            batch = BatchInfo(id = "b", title = "Dune", provider = "Audible"),
        )

    @Test
    fun `a split verdict holds the book and carries the plan`() {
        val decision = DownloadUtil.decideGatePick(splitGate(), rankedPool)

        assertTrue(decision.held)
        // `passed` stays reserved for a proven single upload.
        assertFalse(splitGate().passed)
        assertTrue(splitGate().splittable)
        assertEquals(listOf(1, 2), decision.parts.map { it.index })
        assertEquals(
            listOf("https://www.youtube.com/watch?v=id0", "https://www.youtube.com/watch?v=id1"),
            decision.parts.map { it.url },
        )
        // The display row still comes from best_id.
        assertEquals("id1", decision.pick.id)
    }

    @Test
    fun `a plain hold carries no plan`() {
        val decision = DownloadUtil.decideGatePick(holdGate(), rankedPool)

        assertTrue(decision.held)
        assertFalse(holdGate().splittable)
        assertTrue(decision.parts.isEmpty())
        assertEquals("id1", decision.pick.id)
    }

    @Test
    fun `a pass is not held and needs no plan`() {
        val decision = DownloadUtil.decideGatePick(passGate(), rankedPool)

        assertFalse(decision.held)
        assertTrue(decision.parts.isEmpty())
        // The gate names the pick, not the ranker.
        assertEquals("id2", decision.pick.id)
    }

    @Test
    fun `parts become candidates without a rank score`() {
        val candidates = with(DownloadUtil) { splitGate().parts.map { it.toMatchCandidate() } }

        assertEquals("https://www.youtube.com/watch?v=id0", candidates[0].url)
        assertEquals("DUNE — Part 1", candidates[0].title)
        assertEquals("Starlit Pages", candidates[0].channel)
        assertEquals("Starlit Pages", candidates[0].uploader)
        assertEquals(42_840L, candidates[0].duration)
        // A part is a set member, so it must never read as a ranking.
        assertEquals(0.0, candidates[0].score, 0.0)
        assertEquals("0.0%", candidates[0].scoreLabel)
    }

    @Test
    fun `the split plan survives match json`() {
        val parts = with(DownloadUtil) { splitGate().parts.map { it.toMatchCandidate() } }
        val match =
            MatchResult(
                candidates =
                    rankedPool.take(3).map { with(DownloadUtil) { it.toMatchCandidate() } },
                selectedUrl = "",
                selectedScore = 0.0,
                flagged = true,
                held = true,
                expectedDurationMs = 85_680_000L,
                gateReason = "complete 2-part set: 100% of the book",
                parts = parts,
            )

        val encoded = json.encodeToString(MatchResult.serializer(), match)
        val decoded = json.decodeFromString(MatchResult.serializer(), encoded)

        assertEquals(match, decoded)
        assertTrue(decoded.held)
        assertEquals(2, decoded.parts.size)
        assertEquals(parts, decoded.parts)
    }

    /** What beta.3 wrote: no `parts` key at all. */
    private val oldMatchJson =
        """{"candidates":[{"index":0,"id":"id0","url":"https://www.youtube.com/watch?v=id0","title":"candidate 0","score":62.0,"reasons":[],"uploader":"","channel":"","duration":0}],"selectedUrl":"","selectedScore":0.0,"flagged":true,"acknowledged":false,"pending":false,"previousSelectedUrl":"","previousSelectedScore":0.0,"held":true,"expectedDurationMs":75720000,"gateReason":"no full-length candidate"}"""

    @Test
    fun `a cached held match from before the plan decodes with no parts`() {
        val decoded = json.decodeFromString(MatchResult.serializer(), oldMatchJson)

        assertTrue(decoded.held)
        assertTrue(decoded.parts.isEmpty())
        assertEquals(75_720_000L, decoded.expectedDurationMs)
    }

    @Test
    fun `a part task is not a search task`() {
        val search =
            Task.TypeInfo.MetadataTrack(
                index = 0,
                provider = "Audible",
                title = "Dune",
                durationMs = 85_680_000L,
            )
        val part =
            search.copy(
                index = 0,
                title = "Dune — Part 1",
                durationMs = 42_840_000L,
                directUrl = true,
            )

        // prepare() reads exactly this to choose search vs. straight extraction.
        assertTrue(search.needsSearch)
        assertFalse(part.needsSearch)
        assertNotEquals(search, part)
    }

    @Test
    fun `a book task never needs a direct url`() {
        val book = bookTask().type as Task.TypeInfo.MetadataTrack

        assertFalse(book.directUrl)
        assertTrue(book.needsSearch)
    }

    @Test
    fun `the fake queue never enqueues a part set`() {
        val parts = with(DownloadUtil) { splitGate().parts.map { it.toMatchCandidate() } }

        assertEquals(0, FakeDownloaderV2.enqueueParts(bookTask(), emptyList()))
        assertEquals(0, FakeDownloaderV2.enqueueParts(bookTask(), parts))
        // The fake is a read-only stand-in: no part task may appear from it.
        assertTrue(FakeDownloaderV2.getTaskStateMap().isEmpty())
    }
}
