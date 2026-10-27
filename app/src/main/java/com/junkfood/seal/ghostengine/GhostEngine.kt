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
    )

    data class RankedCandidate(
        val index: Int,
        val id: String,
        val url: String,
        val title: String,
        val score: Double,
    )

    /**
     * Quality-ranked candidate picking (spotDL-adapted scoring): name/artist
     * fuzzy ratios, duration decay, forbidden-keyword penalties, "- Topic"
     * channel bonus. Candidates come from a flat ytsearch extraction.
     */
    fun rankCandidates(track: SpotifyTrack, candidatesJson: String): Result<RankedCandidate> =
        runCatching {
            ensureStarted()
            val trackJson = json.encodeToString(track)
            val reply =
                Python.getInstance()
                    .getModule("sleeve_spotify")
                    .callAttr("rank_candidates", trackJson, candidatesJson)
                    .toString()
            val obj = json.parseToJsonElement(reply).jsonObject
            if (obj["ok"]?.jsonPrimitive?.content != "true") {
                error(obj["error"]?.jsonPrimitive?.content ?: "no candidate passed")
            }
            RankedCandidate(
                index = obj["index"]!!.jsonPrimitive.content.toInt(),
                id = obj["id"]?.jsonPrimitive?.content ?: "",
                url = obj["url"]?.jsonPrimitive?.content ?: "",
                title = obj["title"]?.jsonPrimitive?.content ?: "",
                score = obj["score"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
            )
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
