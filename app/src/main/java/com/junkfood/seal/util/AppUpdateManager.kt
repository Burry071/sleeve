package com.junkfood.seal.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * In-app update flow against this repo's GitHub Releases:
 * check the latest tag, download the APK in-app with progress, then hand the
 * file to the system installer. No releases page detour.
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val REPO = "Burry071/sleeve"
    private const val APK_ASSET = "Sleeve-generic-release.apk"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases?per_page=30"

    // Anchored at the start but not the end: debug builds append "-debug"
    // (and F-Droid builds "-(F-Droid)"), which must still compare as beta.N
    // instead of falling back to 0.0.0 and flagging the same release as new.
    private val VERSION_RE =
        Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?(?:-([A-Za-z]+)\.?(\d+))?""")

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class Available(val version: String, val apkUrl: String) : State
        data class Downloading(val version: String, val progress: Int) : State
        data class Ready(val version: String, val file: File) : State
        data object UpToDate : State
        data class Failed(val message: String) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L
    @Volatile private var lastCheckAt = 0L

    private data class VersionTuple(
        val major: Long,
        val minor: Long,
        val patch: Long,
        val channelRank: Long,
        val build: Long,
    )

    /** Compares "2.0.0-beta.1"-style versions: numbers first, then channel rank. */
    fun isNewer(candidate: String, current: String): Boolean {
        val candidateTuple = parseVersionOrNull(candidate) ?: return false
        val currentTuple = parseVersionOrNull(current) ?: return false
        return compare(candidateTuple, currentTuple) > 0
    }

    private fun parseVersionOrNull(v: String): VersionTuple? {
        val match = VERSION_RE.find(v.trim()) ?: return null
        val (major, minor, patch, pre, build) = match.destructured
        val channelRank =
            when {
                pre.isEmpty() -> 4L
                pre.startsWith("rc", ignoreCase = true) -> 3L
                pre.startsWith("beta", ignoreCase = true) -> 2L
                else -> 1L
            }
        return VersionTuple(
            major.toLongOrNull() ?: return null,
            minor.toLongOrNull() ?: return null,
            patch.toLongOrNull() ?: 0L,
            channelRank,
            build.filter { it.isDigit() }.toLongOrNull() ?: 0L,
        )
    }

    private fun compare(a: VersionTuple, b: VersionTuple): Int =
        compareValuesBy(a, b, { it.major }, { it.minor }, { it.patch }, { it.channelRank }, { it.build })

    /**
     * Foreground check, throttled so returning to the app picks up a release
     * published while it was open without polling GitHub on every resume.
     */
    suspend fun checkIfStale(context: Context) {
        if (System.currentTimeMillis() - lastCheckAt < CHECK_INTERVAL_MS) return
        check(context)
    }

    suspend fun check(context: Context) {
        if (mutableState.value is State.Downloading || mutableState.value is State.Ready) return
        lastCheckAt = System.currentTimeMillis()
        mutableState.value = State.Checking
        withContext(Dispatchers.IO) {
            runCatching {
                val current =
                    context.packageManager
                        .getPackageInfo(context.packageName, 0)
                        .versionName.orEmpty()

                val conn = URL(API_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val code = conn.responseCode
                if (code !in 200..299) error("GitHub API returned $code")
                val body = conn.inputStream.use { it.readBytes().decodeToString() }
                val releases = Json.parseToJsonElement(body).jsonArray.map { it.jsonObject }

                // /releases/latest is a single pointer: a tag that is not a
                // version (or a release without the APK) silently hides newer
                // builds. Scan the list and keep the newest real release APK.
                val currentVersion =
                    parseVersionOrNull(current) ?: VersionTuple(0, 0, 0, 4, 0)
                val best =
                    releases
                        .asSequence()
                        .filter { it["draft"]?.jsonPrimitive?.content != "true" }
                        .mapNotNull { release ->
                            val tag =
                                release["tag_name"]
                                    ?.jsonPrimitive
                                    ?.content
                                    ?.removePrefix("v")
                                    ?: return@mapNotNull null
                            val version = parseVersionOrNull(tag) ?: return@mapNotNull null
                            val apkUrl =
                                release["assets"]
                                    ?.jsonArray
                                    ?.map { it.jsonObject }
                                    ?.firstOrNull {
                                        it["name"]?.jsonPrimitive?.content == APK_ASSET
                                    }
                                    ?.get("browser_download_url")
                                    ?.jsonPrimitive
                                    ?.content
                                    ?: return@mapNotNull null
                            Triple(tag, version, apkUrl)
                        }
                        .maxWithOrNull { a, b -> compare(a.second, b.second) }

                if (best != null && compare(best.second, currentVersion) > 0) {
                    State.Available(best.first, best.third)
                } else {
                    State.UpToDate
                }
            }.onSuccess { mutableState.value = it }
                .onFailure { mutableState.value = State.Failed(it.message ?: "update check failed") }
        }
    }

    suspend fun download(context: Context, version: String, apkUrl: String) {
        mutableState.value = State.Downloading(version, 0)
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
                val target = File(dir, APK_ASSET)
                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            output.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = ((read * 100) / total).toInt()
                                val prev = (mutableState.value as? State.Downloading)?.progress ?: -1
                                if (pct != prev) mutableState.value = State.Downloading(version, pct)
                            }
                        }
                    }
                }
                mutableState.value = State.Ready(version, target)
            }.onFailure {
                mutableState.value = State.Failed(it.message ?: "download failed")
            }
        }
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }
}
