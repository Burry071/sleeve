package com.junkfood.seal.util

import android.util.Log
import androidx.annotation.CheckResult
import com.junkfood.seal.download.MatchCandidate
import com.junkfood.seal.download.MatchOps
import com.junkfood.seal.download.MatchResult
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.TaskAuditLog
import com.junkfood.seal.ghostengine.GhostEngine
import com.junkfood.seal.util.PreferenceUtil.getInt
import kotlinx.serialization.encodeToString
import kotlin.math.roundToInt

private const val TAG = DownloadUtil.TAG

/**
 * Catalogue-track matching: the query builders, the ranker hand-off, the
 * Audible precision gate and the one resolution pass that turns a metadata
 * track into a downloadable [VideoInfo] plus the candidates the review sheet
 * shows. Moved out of [DownloadUtil] unchanged; callers still enter through it.
 */
internal object MetadataMatch {

    /**
     * Normalized YouTube query for a catalogue track. Audible tracks get the
     * quoted-title form: their [GhostEngine.SpotifyTrack.artist] carries the
     * narrator roll ("Author (narrated by A, B, ... 12 names)"), which drags
     * the search away from the actual audiobook upload.
     */
    internal fun buildMetadataQuery(
        track: GhostEngine.SpotifyTrack,
        provider: String = "",
    ): String =
        if (provider.equals(AUDIBLE_PROVIDER, ignoreCase = true)) buildAudibleQuery(track)
        else
            listOf(track.artist, track.title, "audio")
                .filter { it.isNotBlank() }
                .joinToString(separator = " ")
                .let(::collapse)

    /** "<exact title>" <authors> audiobook — falls back to the quoted title. */
    internal fun buildAudibleQuery(track: GhostEngine.SpotifyTrack): String {
        val title =
            collapse(track.title.replace('"', ' ')).takeIf { it.isNotBlank() }?.let { "\"$it\"" }
        val authors = collapse(track.authors).takeIf { it.isNotBlank() }
        return listOfNotNull(title, authors, "audiobook").joinToString(separator = " ")
    }

    private fun collapse(value: String): String = Regex("\\s+").replace(value, " ").trim()

    private const val AUDIBLE_PROVIDER = "Audible"

    /**
     * The ranker scores candidates 0-100. Anything under 60 is not a confident
     * match, so the plain-YouTube pool is searched as well.
     */
    private const val WIDEN_BELOW_SCORE = 60.0

    /** A missing or weak YouTube Music match widens to a plain YouTube search. */
    internal fun shouldWidenSearch(bestScore: Double?): Boolean =
        bestScore == null || bestScore < WIDEN_BELOW_SCORE

    /**
     * Picks the winner between the YouTube Music pool and the plain-YouTube
     * pool, keeping the top [limit] candidates for the review sheet. A
     * floor-rejected music pool arrives here as an empty list, so the widening
     * leg still runs. [isActive] is polled before the second search and after
     * it, so a cancel never leaves that search running.
     */
    internal fun matchBestCandidates(
        isActive: () -> Boolean,
        musicSearch: () -> List<GhostEngine.RankedCandidate>,
        wideSearch: () -> List<GhostEngine.RankedCandidate>,
        limit: Int = 3,
    ): List<GhostEngine.RankedCandidate>? {
        val music = musicSearch().sortedByDescending { it.score }
        if (!isActive()) return null
        val pools =
            if (shouldWidenSearch(music.firstOrNull()?.score)) {
                val wide = wideSearch().sortedByDescending { it.score }
                if (!isActive()) return null
                music + wide
            } else {
                music
            }
        val merged =
            pools
                .sortedByDescending { it.score }
                .distinctBy { it.url.ifBlank { it.id } }
                .take(limit)
        return merged.ifEmpty { null }
    }

    /** Cancellation bail-out for [resolveMetadataTrack]; returned, never thrown. */
    private fun <T> matchCanceled(): Result<T> =
        Result.failure(IllegalStateException("match canceled"))

    /**
     * What a catalogue track resolves to when the search produced nothing worth
     * ranking. A dead end is still reviewable: the picker is where the user can
     * paste a link, search again or open the catalogue page, so this becomes a
     * held match with no candidates instead of a bare failed task. The row is
     * titled from the catalogue so it never shows the synthetic task URL.
     */
    internal fun noSourceResolution(
        metadata: Task.TypeInfo.MetadataTrack,
        reason: String,
    ): DownloadUtil.MetadataResolution =
        DownloadUtil.MetadataResolution(
            videoInfo =
                VideoInfo(
                    title = metadata.title,
                    uploader = metadata.authors.ifBlank { metadata.artist },
                    duration = metadata.durationMs.takeIf { it > 0 }?.div(1000.0),
                    // Empty, not a fake URL: every consumer falls back to the
                    // task URL and filters the synthetic metadata:// scheme out.
                    originalUrl = "",
                ),
            match =
                MatchResult(
                    held = true,
                    expectedDurationMs = metadata.durationMs,
                    gateReason = reason,
                ),
        )


    /**
     * One part of a split as the review sheet's candidate row. The set is not
     * ranked: `score` stays 0 so no part can win a sort or read as a
     * confidence figure. The channel doubles as uploader, because a part has
     * no separate owner in the gate's reply.
     */

    /** Logs every ranker score so the pick is auditable on the device later. */
    private fun logMatch(taskKey: String, track: GhostEngine.SpotifyTrack, ranked: List<MatchCandidate>, flagged: Boolean) {
        val header = "match: \"${track.title}\" by \"${track.artist}\" — ${ranked.size} candidate(s)"
        Log.i(TAG, "$taskKey $header")
        TaskAuditLog.append(taskKey, header)
        ranked.forEachIndexed { i, c ->
            val why = c.reasons.joinToString(", ")
            val line =
                "match: #${i + 1} ${c.percent}% \"${c.title}\"" + if (why.isBlank()) "" else " ($why)"
            Log.i(TAG, "$taskKey $line")
            TaskAuditLog.append(taskKey, line)
        }
        val verdict =
            if (flagged) {
                val top = ranked.first()
                val gap = if (ranked.size > 1) top.score - ranked[1].score else 100.0
                "match: flagged — top ${top.percent}%, gap ${gap.roundToInt()}% (review after the batch)"
            } else {
                "match: confident — top ${ranked.first().percent}%"
            }
        Log.i(TAG, "$taskKey $verdict")
        TaskAuditLog.append(taskKey, verdict)
    }

    /**
     * Runs the audible gate and records its verdict in the audit log. Returns
     * null when the gate itself failed: the caller then keeps the existing
     * ranker behaviour, because a broken gate must not block every Audible
     * download.
     */
    private fun audibleGate(
        taskKey: String,
        track: GhostEngine.SpotifyTrack,
        ranked: List<GhostEngine.RankedCandidate>,
    ): GhostEngine.AudibleGateResult? {
        val attempt = GhostEngine.gateAudibleCandidates(track, ranked)
        val gate = attempt.getOrNull()
        if (gate == null) {
            val reason =
                attempt.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: "unknown error"
            Log.w(TAG, "$taskKey audible gate unavailable: $reason")
            TaskAuditLog.append(taskKey, "gate: unavailable — $reason; using rank #1")
            return null
        }
        val line =
            "gate: ${gate.verdict} — ${gate.reason.ifBlank { "no reason" }}" +
                if (gate.passed) {
                    " (pick ${(gate.ratio * 100).roundToInt()}% of book)"
                } else {
                    " (best ${(gate.bestRatio * 100).roundToInt()}% of book)"
                }
        Log.i(TAG, "$taskKey $line")
        TaskAuditLog.append(taskKey, line)
        return gate
    }

    /**
     * Maps a gate verdict onto the candidate this task will use. A pass may
     * name a candidate the ranker did not put first: the gate, not the score,
     * decides identity. A hold still fetches metadata for display, but from
     * the closest candidate instead of the ranker's favourite. A split is a
     * hold with a plan: no single upload may download, but the complete part
     * set the gate verified is carried along so it can be queued.
     */
    internal fun decideGatePick(
        gate: GhostEngine.AudibleGateResult,
        ranked: List<GhostEngine.RankedCandidate>,
    ): DownloadUtil.GateDecision {
        val fallback = ranked.first()
        if (!gate.passed) {
            val display = ranked.firstOrNull { it.id == gate.bestId } ?: fallback
            val reason = gate.reason.ifBlank { "gate held the pick" }
            return DownloadUtil.GateDecision(
                pick = display,
                held = true,
                reason = reason,
                // Only a split names the uploads that together are the book;
                // a plain hold must never look like a plan.
                parts = if (gate.splittable) gate.parts else emptyList(),
            )
        }
        val passing =
            ranked.firstOrNull { it.id == gate.candidateId }
                ?: return DownloadUtil.GateDecision(
                    fallback,
                    held = true,
                    reason = "gate returned an unknown candidate",
                )
        return DownloadUtil.GateDecision(passing, held = false)
    }

    /**
     * spotDL-pattern matching for one catalogue track: rank a YouTube Music pool,
     * widen when confidence is low, then resolve the winner through the normal
     * extractor so the download stage reuses the winning client.
     *
     * [isActive] is polled between the legs. A cancel destroys the in-flight
     * yt-dlp process, which reads as a failed (empty) search here; without the
     * poll the widening leg and the extraction would spawn two more processes
     * that no later cancel can reach.
     */
    @CheckResult
    fun resolveMetadataTrack(
        metadata: Task.TypeInfo.MetadataTrack,
        taskKey: String,
        preferences: DownloadUtil.DownloadPreferences,
        isActive: () -> Boolean = { true },
        /** User-edited terms that replace the catalogue-built query. */
        queryOverride: String = "",
    ): Result<DownloadUtil.MetadataResolution> =
        runCatching {
            val track =
                GhostEngine.SpotifyTrack(
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album,
                    durationMs = metadata.durationMs,
                    authors = metadata.authors,
                    language = metadata.language,
                )
            val query =
                queryOverride.trim().takeIf { it.isNotBlank() }
                    ?: buildMetadataQuery(track, metadata.provider)
            Log.i(TAG, "match query: $query")

            fun search(pool: String): List<PlaylistEntry> =
                DownloadUtil.getPlaylistOrVideoInfo(
                        playlistURL = "$pool:$query",
                        downloadPreferences = preferences,
                        silent = true,
                        processId = taskKey,
                    )
                    .map { it as? PlaylistResult }
                    .getOrElse { null }
                    ?.entries
                    .orEmpty()

            var rankError: Throwable? = null
            // Audible must see the whole search pool: a full-book upload can
            // sit below rank 3, and a 12-part set needs more than 10 rows. The
            // gate supports up to 20 parts; 30 leaves room for duplicates.
            val audible = metadata.provider.equals("audible", ignoreCase = true)
            val poolLimit = if (audible) 30 else 3
            val widePool = if (audible) "ytsearch30" else "ytsearch10"

            // A floor rejection is a failed ranking, not a fatal error: the
            // plain-YouTube pool still needs its chance before the task fails.
            fun ranked(pool: String): List<GhostEngine.RankedCandidate> {
                val candidates = search(pool)
                if (candidates.isEmpty()) return emptyList()
                return GhostEngine.rankCandidatesWithAlternatives(
                        track = track,
                        candidatesJson = DownloadUtil.jsonFormat.encodeToString(candidates),
                        strictness = RANKING_STRICTNESS.getInt(),
                        topN = poolLimit,
                    )
                    .onFailure { rankError = it }
                    .getOrElse { emptyList() }
            }

            val ranked =
                matchBestCandidates(
                    isActive = isActive,
                    musicSearch = { ranked("ytmsearch5") },
                    wideSearch = { ranked(widePool) },
                    limit = poolLimit,
                )

            // A canceled first leg degrades to an empty pool, so stop before
            // extraction spawns a process no later cancel can reach.
            if (!isActive()) return matchCanceled()

            if (ranked.isNullOrEmpty()) {
                // Nothing to rank is a reviewable hold, not a terminal error:
                // the review sheet offers paste-link, search-again and the
                // catalogue page for exactly this case.
                val reason =
                    "no confident match for \"${track.title}\" by \"${track.artist}\"" +
                        (rankError?.message?.let { ": $it" } ?: "")
                Log.i(TAG, "$taskKey gate: no_source — $reason")
                TaskAuditLog.append(taskKey, "gate: no_source — $reason")
                return@runCatching noSourceResolution(metadata, reason)
            }

            val best = ranked.first()

            // Precision gate: an Audible book must prove its identity and its
            // full length before any media request. Other providers keep the
            // ranker-only path exactly as before. A gate failure falls back to
            // the ranker and leaves a warning in the audit log.
            val gate =
                if (metadata.provider.equals("audible", ignoreCase = true)) {
                    audibleGate(taskKey, track, ranked)
                } else {
                    null
                }
            var decision = gate?.let { DownloadUtil.decideGatePick(it, ranked) }
            var pick = decision?.pick ?: best
            var pickUrl = pick.url.ifBlank { "https://www.youtube.com/watch?v=" + pick.id }

            // A held book still fetches the closest candidate's metadata (for
            // display and the review sheet), but the caller must not write
            // ReadyWithInfo, so no media bytes are requested.
            var resolved =
                DownloadUtil.fetchVideoInfoFromUrl(
                        url = pickUrl,
                        preferences = preferences,
                        taskKey = taskKey,
                    )
                    .getOrThrow()
            if (!isActive()) return matchCanceled()

            // The flat search pool carries no track language; only the full
            // extraction of the pick names one. An English book matched to a
            // German dub must hold here, before the download stage is told to
            // start, even though the first gate could not see the difference.
            if (gate?.passed == true) {
                val audioLanguage = resolved.audioLanguage()
                if (audioLanguage.isNotBlank()) {
                    val enriched =
                        ranked.map { candidate ->
                            if (candidate.id == pick.id) {
                                candidate.copy(audioLanguage = audioLanguage)
                            } else {
                                candidate
                            }
                        }
                    val second = audibleGate(taskKey, track, enriched)
                    if (second != null && !second.passed) {
                        val held2 = DownloadUtil.decideGatePick(second, enriched)
                        decision = held2
                        if (held2.pick.id != pick.id) {
                            pick = held2.pick
                            pickUrl =
                                pick.url.ifBlank { "https://www.youtube.com/watch?v=" + pick.id }
                            resolved =
                                DownloadUtil.fetchVideoInfoFromUrl(
                                        url = pickUrl,
                                        preferences = preferences,
                                        taskKey = taskKey,
                                    )
                                    .getOrThrow()
                            if (!isActive()) return matchCanceled()
                        } else {
                            pick = held2.pick
                        }
                    }
                }
            }

            val held = decision?.held == true
            // Keep the passing pick visible even when it was below rank 3.
            val displayed =
                (listOf(pick) + ranked)
                    .distinctBy { it.url.ifBlank { it.id } }
                    .take(3)
            val matchCandidates = displayed.map { with(DownloadUtil) { it.toMatchCandidate() } }
            val flagged = MatchOps.isLowConfidence(matchCandidates)
            logMatch(taskKey, track, matchCandidates, flagged)
            // The split plan the gate verified. Top-3 display above stays the
            // same; these are the uploads that together are the book, so they
            // are kept apart from the ranked rows instead of competing with
            // them for a slot.
            val partPlan =
                if (held) decision?.parts.orEmpty().map { with(DownloadUtil) { it.toMatchCandidate() } } else emptyList()
            val match =
                MatchResult(
                        candidates = matchCandidates,
                        // A held book has no pick yet. The review sheet reads
                        // `selectedUrl` as "already chosen" and disables Apply
                        // for that row, so preselecting the displayed candidate
                        // would make the only sensible override impossible.
                        // Any row the user taps is then an explicit choice.
                        selectedUrl = if (held) "" else pickUrl,
                        selectedScore = if (held) 0.0 else pick.score,
                        flagged = flagged,
                    )
                    .let { base ->
                        if (!held) {
                            base
                        } else {
                            base.copy(
                                held = true,
                                expectedDurationMs = metadata.durationMs,
                                gateReason = decision?.reason.orEmpty(),
                                parts = partPlan,
                            )
                        }
                    }
            DownloadUtil.MetadataResolution(resolved, match)
        }
}
