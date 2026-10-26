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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Bridge to the embedded Selkie engine (Chaquopy + selkie_engine.py), which
 * follows the Ghost Downloader (GPL-3.0) task/step model. Every engine call
 * returns a JSON string, parsed here — no PyObject conversions cross the
 * boundary.
 */
object GhostEngine {
    private const val TAG = "GhostEngine"
    private val json = Json { ignoreUnknownKeys = true }

    /** Mirrors selkie_engine.py task states. */
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
        if (!Python.isStarted()) {
            val context =
                appContext ?: error("GhostEngine.init(context) was not called from Application")
            Python.start(AndroidPlatform(context))
            Log.i(TAG, "Embedded engine runtime started")
        }
    }

    private fun module() = Python.getInstance().getModule("selkie_engine")

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
                val taskId =
                    call("add_uri", url, mapOf(
                        "headers" to headers,
                        "outputDir" to (outputDir ?: ""),
                        "fileName" to (fileName ?: ""),
                        "subworkerCount" to subworkerCount,
                    )) { it["taskId"]!!.jsonPrimitive.content }
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
}
