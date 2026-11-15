package com.junkfood.seal.pythonbridge

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The single door to the embedded Python runtime. Every bridge in this
 * package starts Chaquopy here, resolves its module here and parses replies
 * through one [Json]; no [com.chaquo.python.PyObject] ever leaves a bridge.
 */
object ChaquopyGateway {
    private const val TAG = "ChaquopyGateway"

    const val ENGINE_MODULE = "sleeve_engine"
    const val SPOTIFY_MODULE = "sleeve_spotify"
    const val AUDIBLE_MODULE = "sleeve_audible"
    const val AUDIBLE_GATE_MODULE = "sleeve_audible_gate"

    val json = Json { ignoreUnknownKeys = true }

    /**
     * Candidate payload for the audible gate. Defaults are encoded so a
     * candidate with an unknown duration still reaches Python as `0` instead
     * of disappearing; the gate treats 0 as unknown and holds.
     */
    val gateJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun ensureStarted() {
        if (Python.isStarted()) return
        val context =
            appContext ?: error("ChaquopyGateway.init(context) was not called from Application")
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

    fun module(name: String = ENGINE_MODULE) = Python.getInstance().getModule(name)

    fun engineAvailable(): Boolean =
        runCatching {
                ensureStarted()
                module() != null
            }
            .getOrDefault(false)

    /** Call a function and parse the JSON object it returns. */
    fun <T> call(
        moduleName: String,
        function: String,
        vararg args: Any?,
        parse: (JsonObject) -> T,
    ): T {
        ensureStarted()
        val reply = module(moduleName).callAttr(function, *args).toString()
        return parse(json.parseToJsonElement(reply).jsonObject)
    }
}
