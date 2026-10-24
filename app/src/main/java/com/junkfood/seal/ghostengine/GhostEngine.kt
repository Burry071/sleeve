package com.junkfood.seal.ghostengine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Bridge to the Ghost Downloader engine (GPL-3.0, XiaoYouChR/Ghost-Downloader-3).
 *
 * Stage A (this branch): availability probe and API surface only. Every download
 * keeps using the yt-dlp path until the Chaquopy runtime is wired per
 * engine-ghost/README.md. Availability is detected through reflection so this
 * file compiles without the Chaquopy dependency.
 */
object GhostEngine {
    private const val TAG = "GhostEngine"
    private const val CHAQUO_PYTHON_CLASS = "com.chaquo.python.Python"

    /** Mirrors the Ghost task lifecycle from app/models/task.py. */
    enum class TaskState { IDLE, RUNNING, PAUSED, COMPLETED, ERROR }

    data class EngineProgress(
        val taskId: String,
        val state: TaskState,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBps: Long,
    )

    private val available: Boolean by lazy {
        try {
            Class.forName(CHAQUO_PYTHON_CLASS)
            true
        } catch (_: ClassNotFoundException) {
            Log.i(TAG, "Chaquopy runtime not present; Ghost engine unavailable")
            false
        }
    }

    fun isAvailable(): Boolean = available

    private val progress = MutableStateFlow<EngineProgress?>(null)
    val progressFlow: StateFlow<EngineProgress?> = progress.asStateFlow()

    /**
     * Enqueue a resolved direct URL with the Ghost engine. Returns null while the
     * engine is unavailable so callers fall back to YoutubeDL.execute(); returns a
     * failed Result if the engine is present but the bridge call breaks.
     *
     * Stage B implements the PyProxy call into ghost_bridge.addUri().
     */
    suspend fun download(
        url: String,
        headers: Map<String, String> = emptyMap(),
        outputDir: String? = null,
        fileName: String? = null,
    ): Result<Unit>? {
        if (!available) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                // TODO(stage-b): Python.getInstance().getModule("ghost_bridge")
                //   .callAttr("add_uri", url, headers, outputDir, fileName)
                error("Ghost engine bridge not wired yet")
            }
        }
    }
}
