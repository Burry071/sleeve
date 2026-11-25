package com.junkfood.seal.pythonbridge

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.junkfood.seal.ghostengine.GhostEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Transfers and page resolution through `sleeve_engine.py`, which follows the
 * Ghost Downloader (GPL-3.0) task/step model.
 */
object GhostSegmentedBridge {
    private const val TAG = "GhostEngine"

    /** Poll interval while a transfer is live. */
    private const val POLL_INTERVAL_MS = 500L

    /** Gap between retries of a poll that could not be parsed. */
    private const val RETRY_DELAY_MS = 500L

    /** How many times one unreadable poll is retried before [watch] gives up. */
    private const val MAX_POLL_RETRIES = 3

    fun isAvailable(): Boolean = ChaquopyGateway.engineAvailable()

    private val progress = MutableStateFlow<GhostEngine.EngineProgress?>(null)
    val progressFlow: StateFlow<GhostEngine.EngineProgress?> = progress.asStateFlow()

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
                    """{"headers":${ChaquopyGateway.json.encodeToString<Map<String, String>>(headers)},"outputDir":${ChaquopyGateway.json.encodeToString<String>(outputDir ?: "")},"fileName":${ChaquopyGateway.json.encodeToString<String>(fileName ?: "")},"subworkerCount":$subworkerCount}"""
                val taskId =
                    call("add_uri", url, optionsJson) { it["taskId"]!!.jsonPrimitive.content }
                progress.value =
                    GhostEngine.EngineProgress(
                        taskId,
                        GhostEngine.TaskState.RUNNING,
                        0,
                        0,
                        0,
                    )
                taskId
            }
        }

    /**
     * Poll engine progress into [progressFlow] until the task settles, and
     * return that terminal snapshot.
     *
     * The return value — never [progressFlow] — is the caller's verdict. When
     * the engine cannot be read after [MAX_POLL_RETRIES] retries, an ERROR
     * snapshot for [taskId] is returned instead of null: a caller must never
     * have to guess which task a shared flow currently holds.
     */
    suspend fun watch(taskId: String): GhostEngine.EngineProgress? =
        withContext<GhostEngine.EngineProgress?>(Dispatchers.IO) {
            var lastGood: GhostEngine.EngineProgress? = null
            var consecutiveFailures = 0
            var terminal: GhostEngine.EngineProgress? = null
            try {
                pollLoop@ while (true) {
                    ensureActive()
                    val snap = poll(taskId)
                    if (snap == null) {
                        // One unreadable poll is not a verdict: the engine can be
                        // busy rewriting its state file. Retry a bounded number of
                        // times, then report only what was actually observed.
                        if (consecutiveFailures >= MAX_POLL_RETRIES) {
                            // Retries exhausted: report a verdict for THIS task.
                            // Returning null would push the caller back onto the
                            // shared flow, where a newer task's snapshot decides.
                            terminal =
                                lastGood?.takeIf { it.isTerminal() }
                                    ?: unreadableSnapshot(taskId, lastGood)
                            break@pollLoop
                        }
                        consecutiveFailures++
                        delay(RETRY_DELAY_MS)
                        continue@pollLoop
                    }
                    consecutiveFailures = 0
                    lastGood = snap
                    // UI-only mirror: callers use the returned snapshot, not this.
                    progress.value = snap
                    if (snap.isTerminal()) {
                        terminal = snap
                        break@pollLoop
                    }
                    delay(POLL_INTERVAL_MS)
                }
            } catch (ce: CancellationException) {
                cancel(taskId)
                throw ce
            }
            terminal
        }

    /**
     * Terminal verdict for a task whose progress stayed unreadable past the
     * retry budget. The id is carried through so the caller can never mistake
     * it for another transfer's snapshot; observed bytes are kept for the log.
     */
    fun unreadableSnapshot(
        taskId: String,
        lastGood: GhostEngine.EngineProgress?,
    ): GhostEngine.EngineProgress =
        GhostEngine.EngineProgress(
            taskId = taskId,
            state = GhostEngine.TaskState.ERROR,
            downloadedBytes = lastGood?.downloadedBytes ?: 0L,
            totalBytes = lastGood?.totalBytes ?: -1L,
            speedBps = 0L,
            error = "engine progress unavailable",
        )

    /** One read of the engine; null when this poll could not be parsed. */
    private fun poll(taskId: String): GhostEngine.EngineProgress? =
        runCatching {
                call("progress", taskId) { obj ->
                    val rawState = obj["state"]!!.jsonPrimitive.content
                    GhostEngine.EngineProgress(
                        taskId = taskId,
                        state = parseState(rawState, taskId),
                        downloadedBytes = obj["downloaded"]!!.jsonPrimitive.longOrNull ?: 0,
                        totalBytes = obj["total"]!!.jsonPrimitive.longOrNull ?: -1,
                        speedBps = obj["speedBps"]!!.jsonPrimitive.longOrNull ?: 0,
                        error = obj["error"]?.jsonPrimitive?.content ?: "",
                    )
                }
            }
            .getOrNull()

    /**
     * An unrecognised state is a broken contract between the engine and this
     * parser, but ERROR is the only safe verdict. Name the value that arrived
     * so the log says what the engine sent instead of only what we assumed.
     */
    private fun parseState(raw: String, taskId: String): GhostEngine.TaskState =
        runCatching { GhostEngine.TaskState.valueOf(raw.uppercase()) }
            .getOrElse {
                Log.w(TAG, "unknown engine state '$raw' for task $taskId; treating as ERROR")
                GhostEngine.TaskState.ERROR
            }

    private fun GhostEngine.EngineProgress.isTerminal(): Boolean =
        state != GhostEngine.TaskState.WAITING && state != GhostEngine.TaskState.RUNNING

    fun cancel(taskId: String) {
        runCatching { ChaquopyGateway.ensureStarted(); ChaquopyGateway.module().callAttr("cancel", taskId) }
    }

    /** Settings action: download a test payload through the full segmented path. */
    suspend fun selfTest(context: Context): Result<GhostEngine.SelfTestResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = context.cacheDir.resolve("engine-selftest").apply { mkdirs() }
                call("self_test", dir.absolutePath) { obj ->
                    GhostEngine.SelfTestResult(
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

    private fun <T> call(
        function: String,
        vararg args: Any?,
        parse: (kotlinx.serialization.json.JsonObject) -> T,
    ): T = ChaquopyGateway.call(ChaquopyGateway.ENGINE_MODULE, function, *args, parse = parse)

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
    fun galleryResolve(
        url: String,
        cookiesFile: String = "",
    ): Result<Pair<String, List<GhostEngine.GalleryItem>>> =
        runCatching {
            ChaquopyGateway.ensureStarted()
            val reply =
                ChaquopyGateway.module()
                    .callAttr("gallery_resolve", url, cookiesFile)
                    .toString()
            val obj = ChaquopyGateway.json.parseToJsonElement(reply).jsonObject
            if (obj["ok"]?.jsonPrimitive?.content != "true") {
                error(obj["error"]?.jsonPrimitive?.content ?: "gallery resolve failed")
            }
            val items =
                obj["items"]!!.jsonArray.mapNotNull { el ->
                    runCatching {
                        val t = el.jsonObject
                        GhostEngine.GalleryItem(
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
    fun instagramEmbed(url: String): Result<List<GhostEngine.GalleryItem>> =
        runCatching {
            ChaquopyGateway.ensureStarted()
            val reply = ChaquopyGateway.module().callAttr("instagram_embed", url).toString()
            val obj = ChaquopyGateway.json.parseToJsonElement(reply).jsonObject
            if (obj["ok"]?.jsonPrimitive?.content != "true") {
                error(obj["error"]?.jsonPrimitive?.content ?: "instagram embed failed")
            }
            obj["items"]!!.jsonArray.mapNotNull { el ->
                runCatching {
                    val t = el.jsonObject
                    GhostEngine.GalleryItem(
                        url = t["url"]!!.jsonPrimitive.content,
                        filename = t["filename"]?.jsonPrimitive?.content ?: "",
                        referer = t["referer"]?.jsonPrimitive?.content ?: "",
                        category = "instagram-embed",
                    )
                }.getOrNull()
            }
        }

    /** Last-resort HTML pass: og:video / twitter:player:stream / video src / m3u8+mpd. */
    fun sniffMedia(url: String): Result<List<GhostEngine.GalleryItem>> =
        runCatching {
            ChaquopyGateway.ensureStarted()
            val reply = ChaquopyGateway.module().callAttr("sniff_media", url).toString()
            val obj = ChaquopyGateway.json.parseToJsonElement(reply).jsonObject
            if (obj["ok"]?.jsonPrimitive?.content != "true") {
                error(obj["error"]?.jsonPrimitive?.content ?: "sniff failed")
            }
            obj["items"]!!.jsonArray.mapNotNull { el ->
                runCatching {
                    val t = el.jsonObject
                    GhostEngine.GalleryItem(
                        url = t["url"]!!.jsonPrimitive.content,
                        filename = t["filename"]?.jsonPrimitive?.content ?: "",
                        referer = t["referer"]?.jsonPrimitive?.content ?: "",
                        category = "sniff",
                    )
                }.getOrNull()
            }
        }
}
