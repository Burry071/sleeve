package com.junkfood.seal.pythonbridge

import com.junkfood.seal.ghostengine.GhostEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Canonical bridge-layer aliases for the engine DTOs exposed on [GhostEngine].
 *
 * Kotlin does not permit nested `typealias` inside an `object`, so the concrete
 * data classes remain declared on [GhostEngine] for zero-breakage compatibility
 * across all callers while bridges under `pythonbridge` share one model and
 * pure-JSON parsing layer from here.
 */
typealias TaskState = GhostEngine.TaskState
typealias EngineProgress = GhostEngine.EngineProgress
typealias SelfTestResult = GhostEngine.SelfTestResult
typealias GalleryItem = GhostEngine.GalleryItem
typealias SpotifyTrack = GhostEngine.SpotifyTrack
typealias RankedCandidate = GhostEngine.RankedCandidate
typealias AudiblePart = GhostEngine.AudiblePart
typealias AudibleGateResult = GhostEngine.AudibleGateResult

/**
 * Parse a wire task state name from `sleeve_engine.py`. An unrecognised value
 * is a broken contract between the engine and this parser, so [TaskState.ERROR]
 * is the only safe verdict.
 */
internal fun parseTaskState(raw: String, onUnknown: ((String) -> Unit)? = null): TaskState =
    runCatching { GhostEngine.TaskState.valueOf(raw.trim().uppercase()) }
        .getOrElse {
            onUnknown?.invoke(raw)
            GhostEngine.TaskState.ERROR
        }

internal fun EngineProgress.isTerminal(): Boolean =
    state != GhostEngine.TaskState.WAITING && state != GhostEngine.TaskState.RUNNING

/**
 * Parse a `sleeve_engine.progress` JSON payload for [taskId].
 * Throws when `state` is missing or blank so callers treat truncated/corrupt
 * replies as unreadable polls instead of silent errors.
 */
internal fun parseEngineProgress(
    taskId: String,
    obj: JsonObject,
    onUnknownState: ((String) -> Unit)? = null,
): EngineProgress {
    val rawState =
        obj["state"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("missing engine state")
    return EngineProgress(
        taskId = taskId,
        state = parseTaskState(rawState, onUnknownState),
        downloadedBytes = obj["downloaded"]?.jsonPrimitive?.longOrNull ?: 0L,
        totalBytes = obj["total"]?.jsonPrimitive?.longOrNull ?: -1L,
        speedBps = obj["speedBps"]?.jsonPrimitive?.longOrNull ?: 0L,
        error = obj["error"]?.jsonPrimitive?.contentOrNull ?: "",
    )
}

/** Parse a `sleeve_engine.self_test` JSON payload. */
internal fun parseSelfTestResult(obj: JsonObject): SelfTestResult =
    SelfTestResult(
        ok = obj["ok"]?.jsonPrimitive?.contentOrNull == "true",
        bytes = obj["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
        seconds = obj["seconds"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
        speedBps = obj["speedBps"]?.jsonPrimitive?.longOrNull ?: 0L,
        workers = obj["workers"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
        state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "",
        error = obj["error"]?.jsonPrimitive?.contentOrNull ?: "",
    )

/**
 * Parse the `items` array of a gallery, embed, or sniff reply.
 * Malformed entries (such as items missing a non-blank `url`) are skipped
 * without discarding valid siblings. When [overrideCategory] is non-null, it
 * replaces any per-item `category` field.
 */
internal fun parseGalleryItems(
    obj: JsonObject,
    overrideCategory: String? = null,
): List<GalleryItem> =
    (obj["items"] as? JsonArray).orEmpty().mapNotNull { el ->
        runCatching {
            val t = el.jsonObject
            val url =
                t["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@runCatching null
            GalleryItem(
                url = url,
                filename = t["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                referer = t["referer"]?.jsonPrimitive?.contentOrNull ?: "",
                category =
                    overrideCategory ?: (t["category"]?.jsonPrimitive?.contentOrNull ?: ""),
            )
        }.getOrNull()
    }
