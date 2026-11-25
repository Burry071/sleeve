package com.junkfood.seal.pythonbridge

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Gallery extraction, anonymous Instagram embed fallback, and HTML media
 * sniffing through `sleeve_engine.py`. Kept separate from
 * [GhostSegmentedBridge] so page-resolution concerns never tangle with
 * segmented HTTP transfer state.
 */
object GalleryBridge {
    private const val TAG = "GhostEngine"

    /** True when a gallery-dl site module claims this URL. Loads lazily; call on IO. */
    fun galleryMatches(url: String): Boolean =
        runCatching {
            ChaquopyGateway.ensureStarted()
            ChaquopyGateway.module().callAttr("gallery_matches", url).toString() == "True"
        }.getOrDefault(false)

    /**
     * Preload the gallery-dl extractor registry once per install (cold import
     * of ~300 site modules) and surface any import error to the log.
     */
    fun preloadGallery() {
        runCatching {
            ChaquopyGateway.ensureStarted()
            val reply = ChaquopyGateway.module().callAttr("gallery_warmup").toString()
            val obj = ChaquopyGateway.json.parseToJsonElement(reply).jsonObject
            if (obj["ok"]?.jsonPrimitive?.contentOrNull == "true") {
                Log.i(
                    TAG,
                    "gallery-dl registry warm in ${obj["seconds"]?.jsonPrimitive?.contentOrNull}s",
                )
            } else {
                Log.w(
                    TAG,
                    "gallery-dl warmup failed: ${obj["error"]?.jsonPrimitive?.contentOrNull}",
                )
            }
        }.onFailure { Log.w(TAG, "gallery-dl warmup unavailable: ${it.message}") }
    }

    /** Resolve a page into direct media items (image sites, albums, sniffed pages). */
    fun galleryResolve(
        url: String,
        cookiesFile: String = "",
    ): Result<Pair<String, List<GalleryItem>>> =
        runCatching {
            val obj = callEngine("gallery_resolve", url, cookiesFile)
            if (obj["ok"]?.jsonPrimitive?.contentOrNull != "true") {
                error(obj["error"]?.jsonPrimitive?.contentOrNull ?: "gallery resolve failed")
            }
            val items = parseGalleryItems(obj)
            obj["category"]?.jsonPrimitive?.contentOrNull.orEmpty() to items
        }

    /** Anonymous Instagram fallback: public post/reel embed page. */
    fun instagramEmbed(url: String): Result<List<GalleryItem>> =
        runCatching {
            val obj = callEngine("instagram_embed", url)
            if (obj["ok"]?.jsonPrimitive?.contentOrNull != "true") {
                error(obj["error"]?.jsonPrimitive?.contentOrNull ?: "instagram embed failed")
            }
            parseGalleryItems(obj, overrideCategory = "instagram-embed")
        }

    /** Last-resort HTML pass: og:video / twitter:player:stream / video src / m3u8+mpd. */
    fun sniffMedia(url: String): Result<List<GalleryItem>> =
        runCatching {
            val obj = callEngine("sniff_media", url)
            if (obj["ok"]?.jsonPrimitive?.contentOrNull != "true") {
                error(obj["error"]?.jsonPrimitive?.contentOrNull ?: "sniff failed")
            }
            parseGalleryItems(obj, overrideCategory = "sniff")
        }

    private fun callEngine(function: String, vararg args: Any?): JsonObject =
        ChaquopyGateway.call(ChaquopyGateway.ENGINE_MODULE, function, *args) { it }
}
