package com.junkfood.seal.pythonbridge

import android.util.Log
import com.junkfood.seal.ghostengine.GhostEngine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Catalogue metadata and candidate ranking through `sleeve_spotify.py`. */
object SpotifyBridge {
    private const val TAG = "GhostEngine"

    /**
     * Quality-ranked candidate picking (spotDL-adapted scoring): name/artist
     * fuzzy ratios, duration decay, forbidden-keyword penalties, "- Topic"
     * channel bonus. Candidates come from a flat ytsearch extraction.
     */
    fun rankCandidates(
        track: GhostEngine.SpotifyTrack,
        candidatesJson: String,
        strictness: Int = 1,
    ): Result<GhostEngine.RankedCandidate> =
        rankCandidatesWithAlternatives(track, candidatesJson, strictness).map { it.first() }

    /**
     * Same ranker, but keeps the top [topN] candidates with their reason
     * components so the task can cache them for the review sheet. Fails when
     * no candidate passes the floor, exactly like [rankCandidates].
     */
    fun rankCandidatesWithAlternatives(
        track: GhostEngine.SpotifyTrack,
        candidatesJson: String,
        strictness: Int = 1,
        topN: Int = 3,
    ): Result<List<GhostEngine.RankedCandidate>> =
        runCatching {
            ChaquopyGateway.ensureStarted()
            val trackJson = ChaquopyGateway.json.encodeToString(track)
            val reply =
                ChaquopyGateway.module(ChaquopyGateway.SPOTIFY_MODULE)
                    .callAttr("rank_candidates", trackJson, candidatesJson, strictness, topN)
                    .toString()
            val obj = ChaquopyGateway.json.parseToJsonElement(reply).jsonObject
            obj["rejected"]?.jsonArray?.forEach { el ->
                val r = el.jsonObject
                Log.i(
                    TAG,
                    "match rejected: ${r["title"]?.jsonPrimitive?.contentOrNull} — " +
                        r["reason"]?.jsonPrimitive?.contentOrNull,
                )
            }
            if (obj["ok"]?.jsonPrimitive?.content != "true") {
                error(obj["error"]?.jsonPrimitive?.content ?: "no candidate passed")
            }
            val ranked =
                (obj["candidates"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { el ->
                    runCatching {
                        val c = el.jsonObject
                        GhostEngine.RankedCandidate(
                            index = c["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                            id = c["id"]?.jsonPrimitive?.content ?: "",
                            url = c["url"]?.jsonPrimitive?.content ?: "",
                            title = c["title"]?.jsonPrimitive?.content ?: "",
                            score = c["score"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                            reasons =
                                c["reasons"]?.jsonArray?.mapNotNull {
                                    it.jsonPrimitive.contentOrNull
                                } ?: emptyList(),
                            // Absent on rankers older than the metadata
                            // contract; the defaults keep them decodable.
                            uploader = c["uploader"]?.jsonPrimitive?.contentOrNull ?: "",
                            channel = c["channel"]?.jsonPrimitive?.contentOrNull ?: "",
                            duration = c["duration"]?.jsonPrimitive?.longOrNull ?: 0L,
                        )
                    }.getOrNull()
                }
            if (ranked.isEmpty()) {
                error(obj["error"]?.jsonPrimitive?.content ?: "no candidate passed")
            }
            ranked
        }

    fun spotifyResolve(
        url: String,
        clientId: String,
        clientSecret: String,
    ): Result<Pair<String, List<GhostEngine.SpotifyTrack>>> =
        runCatching {
            ChaquopyGateway.ensureStarted()
            val reply =
                ChaquopyGateway.module(ChaquopyGateway.SPOTIFY_MODULE)
                    .callAttr("resolve", url, clientId, clientSecret)
                    .toString()
            val obj = ChaquopyGateway.json.parseToJsonElement(reply).jsonObject
            if (obj["ok"]?.jsonPrimitive?.content != "true") {
                error(obj["error"]?.jsonPrimitive?.content ?: "spotify resolve failed")
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
                        )
                    }.getOrNull()
                }
            obj["title"]?.jsonPrimitive?.content.orEmpty() to tracks
        }
}
