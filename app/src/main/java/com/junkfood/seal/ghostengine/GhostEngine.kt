package com.junkfood.seal.ghostengine

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Bridge to the embedded Sleeve engine (Chaquopy + sleeve_engine.py), which
 * follows the Ghost Downloader (GPL-3.0) task/step model. Every engine call
 * returns a JSON string, parsed here — no PyObject conversions cross the
 * boundary.
 */
object GhostEngine {
    private const val TAG = "GhostEngine"
    private val json = Json { ignoreUnknownKeys = true }

    /** Mirrors sleeve_engine.py task states. */
    enum class TaskState { WAITING, RUNNING, COMPLETED, ERROR, CANCELLED }

    data class EngineProgress(
        val taskId: String,
        val state: TaskState,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBps: Long,
        val error: String = "",
    )

    data class SelfTestResult(
        val ok: Boolean,
        val bytes: Long,
        val seconds: Double,
        val speedBps: Long,
        val workers: Int,
        val state: String,
        val error: String,
    ) {
        fun summary(): String =
            if (ok) {
                val mbps = "%.1f MB/s".format(speedBps / 1_048_576.0)
                "OK — ${bytes / 1_048_576} MB in ${seconds}s at $mbps ($workers connections)"
            } else {
                "Failed (${state.ifEmpty { "unknown" }})${error.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""}"
            }
    }

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun ensureStarted() {
        if (Python.isStarted()) return
        val context =
            appContext ?: error("GhostEngine.init(context) was not called from Application")
        Python.start(AndroidPlatform(context))
        // Chaquopy rewrites the process PYTHONPATH to its own 3.13 assets. The
        // yt-dlp subprocess (youtubedl-android's python 3.11, spawned with the
        // parent env) then fails its path configuration on the version-mixed
        // paths. The running interpreter built sys.path at start and does not
        // re-read PYTHONPATH — drop it so both runtimes keep working.
        runCatching {
            Python.getInstance()
                .getModule("os")
                .get("environ")
                ?.callAttr("pop", "PYTHONPATH", null)
        }.onFailure { Log.w(TAG, "PYTHONPATH scrub failed: ${it.message}") }
        Log.i(TAG, "Embedded engine runtime started")
    }

    private fun module() = Python.getInstance().getModule("sleeve_engine")

    /** Call an engine function and parse its JSON reply. */
    private inline fun <reified T> call(
        function: String,
        vararg args: Any?,
        parse: (kotlinx.serialization.json.JsonObject) -> T,
    ): T =
        ensureStarted().let {
            val reply = module().callAttr(function, *args).toString()
            parse(json.parseToJsonElement(reply).jsonObject)
        }

    fun isAvailable(): Boolean =
        runCatching { ensureStarted(); module() != null }.getOrDefault(false)

    private val progress = MutableStateFlow<EngineProgress?>(null)
    val progressFlow: StateFlow<EngineProgress?> = progress.asStateFlow()

    /** Enqueue a resolved direct URL. Returns the engine task id. */
    suspend fun download(
        url: String,
        headers: Map<String, String> = emptyMap(),
        outputDir: String? = null,
        fileName: String? = null,
        subworkerCount: Int = 8,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Options cross as a JSON string: Chaquopy's Java Map proxies are
                // not iterable from Python, and the JSON-string bridge is the
                // established contract for this boundary.
                val optionsJson =
                    """{"headers":${json.encodeToString<Map<String, String>>(headers)},"outputDir":${json.encodeToString<String>(outputDir ?: "")},"fileName":${json.encodeToString<String>(fileName ?: "")},"subworkerCount":$subworkerCount}"""
                val taskId =
                    call("add_uri", url, optionsJson) { it["taskId"]!!.jsonPrimitive.content }
                progress.value = EngineProgress(taskId, TaskState.RUNNING, 0, 0, 0)
                taskId
            }
        }

    /** Poll engine progress into [progressFlow] until the task settles. */
    suspend fun watch(taskId: String) {
        withContext(Dispatchers.IO) {
            while (true) {
                val snap =
                    runCatching {
                        call("progress", taskId) { obj ->
                            EngineProgress(
                                taskId = taskId,
                                state =
                                    runCatching {
                                        TaskState.valueOf(
                                            obj["state"]!!.jsonPrimitive.content.uppercase(),
                                        )
                                    }.getOrDefault(TaskState.ERROR),
                                downloadedBytes =
                                    obj["downloaded"]!!.jsonPrimitive.longOrNull ?: 0,
                                totalBytes = obj["total"]!!.jsonPrimitive.longOrNull ?: -1,
                                speedBps = obj["speedBps"]!!.jsonPrimitive.longOrNull ?: 0,
                                error = obj["error"]?.jsonPrimitive?.content ?: "",
                            )
                        }
                    }.getOrNull()
                if (snap == null) return@withContext
                progress.value = snap
                if (snap.state != TaskState.WAITING && snap.state != TaskState.RUNNING) {
                    return@withContext
                }
                delay(500)
            }
        }
    }

    fun cancel(taskId: String) {
        runCatching { ensureStarted(); module().callAttr("cancel", taskId) }
    }

    /** Settings action: download a test payload through the full segmented path. */
    suspend fun selfTest(context: Context): Result<SelfTestResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = context.cacheDir.resolve("engine-selftest").apply { mkdirs() }
                call("self_test", dir.absolutePath) { obj ->
                    SelfTestResult(
                        ok = obj["ok"]!!.jsonPrimitive.content == "true",
                        bytes = obj["bytes"]!!.jsonPrimitive.longOrNull ?: 0,
                        seconds = obj["seconds"]!!.jsonPrimitive.content.toDoubleOrNull() ?: 0.0,
                        speedBps = obj["speedBps"]!!.jsonPrimitive.longOrNull ?: 0,
                        workers = obj["workers"]!!.jsonPrimitive.content.toIntOrNull() ?: 0,
                        state = obj["state"]?.jsonPrimitive?.content ?: "",
                        error = obj["error"]?.jsonPrimitive?.content ?: "",
                    )
                }
            }
        }

    /**
     * Metadata track resolved from a public catalog (Spotify/Audible pattern):
     * metadata only — audio always comes from YouTube via yt-dlp.
     */
    @kotlinx.serialization.Serializable
    data class SpotifyTrack(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val authors: String = "",
    )

    data class RankedCandidate(
        val index: Int,
        val id: String,
        val url: String,
        val title: String,
        val score: Double,
        val reasons: List<String> = emptyList(),
    )

    data class GalleryItem(
        val url: String,
        val filename: String,
        val referer: String,
        val category: String,
    )

    /** True when a gallery-dl site module claims this URL. Loads lazily; call on IO. */
    fun galleryMatches(url: String): Boolean =
        runCatching {
            ensureStarted()
            module().callAttr("gallery_matches", url).toString() == "True"
        }.getOrDefault(false)

    /**
     * Preload the gallery-dl extractor registry once per install (cold import
     * of ~300 site modules) and surface any import error to the log.
     */
    fun preloadGallery() {
        runCatching {
            ensureStarted()
            val reply = module().callAttr("gallery_warmup").toString()
            val obj = json.parseToJsonElement(reply).jsonObject
            if (obj["ok"]?.jsonPrimitive?.content == "true") {
                Log.i(
                    "GhostEngine",
                    "gallery-dl registry warm in ${obj["seconds"]?.jsonPrimitive?.content}s",
                )
            } else {
                Log.w(
                    "GhostEngine",
                    "gallery-dl warmup failed: ${obj["error"]?.jsonPrimitive?.content}",
                )
            }
        }.onFailure { Log.w("GhostEngine", "gallery-dl warmup unavailable: ${it.message}") }
    }

    /** Resolve a page into direct media items (image sites, albums, sniffed pages). */
    fun galleryResolve(url: String, cookiesFile: String = ""): Result<Pair<String, List<GalleryItem>>> =
        runCatching {
            ensureStarted()
            val reply =
                module()
                    .callAttr("gallery_resolve", url, cookiesFile)
                    .toString()
            val obj = json.parseToJsonElement(reply).jsonObject
            if (obj["ok"]?.jsonPrimitive?.content != "true") {
                error(obj["error"]?.jsonPrimitive?.content ?: "gallery resolve failed")
            }
            val items =
                obj["items"]!!.jsonArray.mapNotNull { el ->
                    runCatching {
                        val t = el.jsonObject
                        GalleryItem(
                            url = t["url"]!!.jsonPrimitive.content,
                            filename = t["filename"]?.jsonPrimitive?.content ?: "",
                            referer = t["referer"]?.jsonPrimitive?.content ?: "",
                            category = t["category"]?.jsonPrimitive?.content ?: "",
                        )
                    }.getOrNull()
                }
            obj["category"]?.jsonPrimitive?.content.orEmpty() to items
        }

    /** Anonymous Instagram fallback: public post/reel embed page. */
    fun instagramEmbed(url: String): Result<List<GalleryItem>> =
        runCatching {
            ensureStarted()
            val reply = module().callAttr("instagram_embed", url).toString()
            val obj = json.parseToJsonElement(reply).jsonObject
            if (obj["ok"]?.jsonPrimitive?.content != "true") {
                error(obj["error"]?.jsonPrimitive?.content ?: "instagram embed failed")
            }
            obj["items"]!!.jsonArray.mapNotNull { el ->
                runCatching {
                    val t = el.jsonObject
                    GalleryItem(
                        url = t["url"]!!.jsonPrimitive.content,
                        filename = t["filename"]?.jsonPrimitive?.content ?: "",
                        referer = t["referer"]?.jsonPrimitive?.content ?: "",
                        category = "instagram-embed",
                    )
                }.getOrNull()
            }
        }

    /** Last-resort HTML pass: og:video / twitter:player:stream / video src / m3u8+mpd. */
    fun sniffMedia(url: String): Result<List<GalleryItem>> =
        runCatching {
            ensureStarted()
            val reply = module().callAttr("sniff_media", url).toString()
            val obj = json.parseToJsonElement(reply).jsonObject
            if (obj["ok"]?.jsonPrimitive?.content != "true") {
                error(obj["error"]?.jsonPrimitive?.content ?: "sniff failed")
            }
            obj["items"]!!.jsonArray.mapNotNull { el ->
                runCatching {
                    val t = el.jsonObject
                    GalleryItem(
                        url = t["url"]!!.jsonPrimitive.content,
                        filename = t["filename"]?.jsonPrimitive?.content ?: "",
                        referer = t["referer"]?.jsonPrimitive?.content ?: "",
                        category = "sniff",
                    )
                }.getOrNull()
            }
        }

    /**
     * Quality-ranked candidate picking (spotDL-adapted scoring): name/artist
     * fuzzy ratios, duration decay, forbidden-keyword penalties, "- Topic"
     * channel bonus. Candidates come from a flat ytsearch extraction.
     */
    fun rankCandidates(
        track: SpotifyTrack,
        candidatesJson: String,
        strictness: Int = 1,
    ): Result<RankedCandidate> =
        rankCandidatesWithAlternatives(track, candidatesJson, strictness)
            .map { it.first() }

    /**
     * Same ranker, but keeps the top [topN] candidates with their reason
     * components so the task can cache them for the review sheet. Fails when
     * no candidate passes the floor, exactly like [rankCandidates].
     */
    fun rankCandidatesWithAlternatives(
        track: SpotifyTrack,
        candidatesJson: String,
        strictness: Int = 1,
        topN: Int = 3,
    ): Result<List<RankedCandidate>> =
        runCatching {
            ensureStarted()
            val trackJson = json.encodeToString(track)
            val reply =
                Python.getInstance()
                    .getModule("sleeve_spotify")
                    .callAttr("rank_candidates", trackJson, candidatesJson, strictness, topN)
                    .toString()
            val obj = json.parseToJsonElement(reply).jsonObject
            obj["rejected"]?.jsonArray?.forEach { el ->
                val r = el.jsonObject
                Log.i(
                    "GhostEngine",
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
                        RankedCandidate(
                            index = c["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                            id = c["id"]?.jsonPrimitive?.content ?: "",
                            url = c["url"]?.jsonPrimitive?.content ?: "",
                            title = c["title"]?.jsonPrimitive?.content ?: "",
                            score = c["score"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                            reasons =
                                c["reasons"]?.jsonArray?.mapNotNull {
                                    it.jsonPrimitive.contentOrNull
                                } ?: emptyList(),
                        )
                    }.getOrNull()
                }
            if (ranked.isEmpty()) {
                error(obj["error"]?.jsonPrimitive?.content ?: "no candidate passed")
            }
            ranked
        }

    /** Audible catalog metadata (public API, Libation-derived — no account). */
    fun audibleResolve(url: String, keywords: String = ""): Result<Pair<String, List<SpotifyTrack>>> =
        runCatching {
            ensureStarted()
            val reply =
                Python.getInstance()
                    .getModule("sleeve_audible")
                    .callAttr("resolve", url, keywords)
                    .toString()
            val obj = json.parseToJsonElement(reply).jsonObject
            if (obj["ok"]?.jsonPrimitive?.content != "true") {
                error(obj["error"]?.jsonPrimitive?.content ?: "audible resolve failed")
            }
            val tracks =
                obj["tracks"]!!.jsonArray.mapNotNull { el ->
                    runCatching {
                        val t = el.jsonObject
                        SpotifyTrack(
                            title = t["title"]!!.jsonPrimitive.content,
                            artist = t["artist"]?.jsonPrimitive?.content ?: "",
                            album = t["album"]?.jsonPrimitive?.content ?: "",
                            durationMs = t["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                            authors = t["authors"]?.jsonPrimitive?.content ?: "",
                        )
                    }.getOrNull()
                }
            obj["title"]?.jsonPrimitive?.content.orEmpty() to tracks
        }

    fun spotifyResolve(
        url: String,
        clientId: String,
        clientSecret: String,
    ): Result<Pair<String, List<SpotifyTrack>>> =
        runCatching {
            ensureStarted()
            val reply =
                Python.getInstance()
                    .getModule("sleeve_spotify")
                    .callAttr("resolve", url, clientId, clientSecret)
                    .toString()
            val obj = json.parseToJsonElement(reply).jsonObject
            if (obj["ok"]?.jsonPrimitive?.content != "true") {
                error(obj["error"]?.jsonPrimitive?.content ?: "spotify resolve failed")
            }
            val tracks =
                obj["tracks"]!!.jsonArray.mapNotNull { el ->
                    runCatching {
                        val t = el.jsonObject
                        SpotifyTrack(
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
