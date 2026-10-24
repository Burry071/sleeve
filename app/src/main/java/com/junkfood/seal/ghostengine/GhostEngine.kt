package com.junkfood.seal.ghostengine

import android.content.Context
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Bridge to the embedded Selkie engine (Chaquopy + selkie_engine.py), which
 * follows the Ghost Downloader (GPL-3.0) task/step model.
 */
object GhostEngine {
    private const val TAG = "GhostEngine"

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
                "OK — ${bytes / 1_048_576} MB in ${seconds}s (~${speedBps / 1_048_576} MB/s, $workers threads)"
            } else {
                "FAILED ($state): $error"
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

    private fun module(): com.chaquo.python.PyObject {
        ensureStarted()
        return Python.getInstance().getModule("selkie_engine")
    }

    fun isAvailable(): Boolean =
        runCatching { module(); true }.getOrElse { false }

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
                val result =
                    module()
                        .callAttr(
                            "add_uri",
                            url,
                            mapOf(
                                "headers" to headers,
                                "outputDir" to (outputDir ?: ""),
                                "fileName" to (fileName ?: ""),
                                "subworkerCount" to subworkerCount,
                            ),
                        )
                val taskId = result["taskId"].toString()
                progress.value = EngineProgress(taskId, TaskState.RUNNING, 0, 0, 0)
                taskId
            }
        }

    /** Poll engine progress into [progressFlow] until the task settles. */
    suspend fun watch(taskId: String) {
        withContext(Dispatchers.IO) {
            while (true) {
                val snap = runCatching { module().callAttr("progress", taskId) }.getOrNull()
                if (snap != null) {
                    val state =
                        runCatching { TaskState.valueOf(snap["state"].toString()) }
                            .getOrDefault(TaskState.ERROR)
                    val current =
                        EngineProgress(
                            taskId = taskId,
                            state = state,
                            downloadedBytes = (snap["downloaded"] as? Number)?.toLong() ?: 0,
                            totalBytes = (snap["total"] as? Number)?.toLong() ?: 0,
                            speedBps = (snap["speedBps"] as? Number)?.toLong() ?: 0,
                            error = snap["error"]?.toString() ?: "",
                        )
                    progress.value = current
                    if (state != TaskState.WAITING && state != TaskState.RUNNING) return@withContext
                } else {
                    return@withContext
                }
                delay(500)
            }
        }
    }

    fun cancel(taskId: String) {
        runCatching { module().callAttr("cancel", taskId) }
    }

    /** Settings action: download a test payload through the full segmented path. */
    suspend fun selfTest(context: Context): Result<SelfTestResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = context.cacheDir.resolve("engine-selftest").apply { mkdirs() }
                val raw = module().callAttr("self_test", dir.absolutePath)
                SelfTestResult(
                    ok = raw["ok"].toString() == "True",
                    bytes = (raw["bytes"] as? Number)?.toLong() ?: 0,
                    seconds = (raw["seconds"] as? Number)?.toDouble() ?: 0.0,
                    speedBps = (raw["speedBps"] as? Number)?.toLong() ?: 0,
                    workers = (raw["workers"] as? Number)?.toInt() ?: 0,
                    state = raw["state"]?.toString() ?: "",
                    error = raw["error"]?.toString() ?: "",
                )
            }
        }
}
