package com.junkfood.seal.pythonbridge

import com.junkfood.seal.ghostengine.GhostEngine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Audible catalogue resolution and the identity gate (pure Python side). */
object AudibleBridge {

    /**
     * Deterministic audible identity gate: title F1 plus a single full-length
     * duration test, before any media request. Kept separate from
     * [SpotifyBridge.rankCandidatesWithAlternatives] so a fuzzy score never
     * decides identity.
     */
    fun gateAudibleCandidates(
        track: GhostEngine.SpotifyTrack,
        candidates: List<GhostEngine.RankedCandidate>,
    ): Result<GhostEngine.AudibleGateResult> =
        runCatching {
            ChaquopyGateway.ensureStarted()
            val reply =
                ChaquopyGateway.module(ChaquopyGateway.AUDIBLE_GATE_MODULE)
                    .callAttr(
                        "gate_audible",
                        ChaquopyGateway.gateJson.encodeToString(track),
                        ChaquopyGateway.gateJson.encodeToString(candidates),
                    )
                    .toString()
            val obj = ChaquopyGateway.json.parseToJsonElement(reply).jsonObject
            if (obj["ok"]?.jsonPrimitive?.content != "true") {
                error(obj["error"]?.jsonPrimitive?.contentOrNull ?: "audible gate failed")
            }
            // Only a split carries `parts`; pass/hold/no_source keep the
            // phase-1 shape, so an absent array simply leaves the plan empty.
            val parts =
                (obj["parts"] as? JsonArray).orEmpty().mapNotNull { el ->
                    runCatching {
                        val part = el.jsonObject
                        GhostEngine.AudiblePart(
                            index = part["part_index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                            id = part["id"]?.jsonPrimitive?.contentOrNull ?: "",
                            url = part["url"]?.jsonPrimitive?.contentOrNull ?: "",
                            title = part["title"]?.jsonPrimitive?.contentOrNull ?: "",
                            durationSeconds = part["duration"]?.jsonPrimitive?.longOrNull ?: 0L,
                            channel = part["channel"]?.jsonPrimitive?.contentOrNull ?: "",
                        )
                    }.getOrNull()
                }
            GhostEngine.AudibleGateResult(
                verdict =
                    obj["verdict"]?.jsonPrimitive?.contentOrNull
                        ?: GhostEngine.AudibleGateResult.VERDICT_HOLD,
                reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: "",
                candidateId = obj["candidate_id"]?.jsonPrimitive?.contentOrNull ?: "",
                ratio = obj["ratio"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                expectedMs = track.durationMs,
                bestId = obj["best_id"]?.jsonPrimitive?.contentOrNull ?: "",
                bestRatio = obj["best_ratio"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                parts = parts,
                partCount =
                    obj["part_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: parts.size,
            )
        }

    /** Audible catalog metadata (public API, Libation-derived — no account). */
    fun audibleResolve(
        url: String,
        keywords: String = "",
    ): Result<Pair<String, List<GhostEngine.SpotifyTrack>>> =
        runCatching {
            ChaquopyGateway.ensureStarted()
            val reply =
                ChaquopyGateway.module(ChaquopyGateway.AUDIBLE_MODULE)
                    .callAttr("resolve", url, keywords)
                    .toString()
            val obj = ChaquopyGateway.json.parseToJsonElement(reply).jsonObject
            if (obj["ok"]?.jsonPrimitive?.content != "true") {
                error(obj["error"]?.jsonPrimitive?.content ?: "audible resolve failed")
            }
            val tracks =
                obj["tracks"]!!.jsonArray.mapNotNull { el ->
                    runCatching {
                        val t = el.jsonObject
                        GhostEngine.SpotifyTrack(
                            title = t["title"]!!.jsonPrimitive.content,
                            artist = t["artist"]?.jsonPrimitive?.content ?: "",
                            album = t["album"]?.jsonPrimitive?.content ?: "",
                            durationMs = t["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                            authors = t["authors"]?.jsonPrimitive?.content ?: "",
                            language = t["language"]?.jsonPrimitive?.content ?: "",
                        )
                    }.getOrNull()
                }
            obj["title"]?.jsonPrimitive?.content.orEmpty() to tracks
        }
}
