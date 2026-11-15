package com.junkfood.seal.download.engine

import android.os.Build
import android.util.Log
import com.junkfood.seal.App
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.VideoInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Transfer lanes keep the façade's log tag: device notes and bug reports quote
 * `DownloadUtil` lines, and those must keep matching after the split.
 */
internal const val TRANSFER_TAG = "DownloadUtil"

/** Client set that last won extraction, per task key (YouTube ladder). */
internal object ExtractionClients {
    private val wins = ConcurrentHashMap<String, String>()

    fun record(taskKey: String, clients: String) {
        wins[taskKey] = clients
    }

    fun get(taskKey: String): String? = wins[taskKey]

    fun clear(taskKey: String) {
        wins.remove(taskKey)
    }
}

/** Remote-URL guard shared by engine and N_m3u8DL-RE routes. */
private val PRIVATE_HOST_RE =
    Regex(
        "^(localhost|127\\.|10\\.|0\\.|169\\.254\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\." +
            "|\\[::1\\]|\\[fc|\\[fd|\\[fe80)",
        RegexOption.IGNORE_CASE,
    )

/** Shared with the direct-lane resolvers: page content decides what gets fetched. */
internal fun isSafeRemoteUrl(url: String): Boolean {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return false
    val rest = url.substringAfter("://")
    // Bracketed IPv6 hosts must not be split on ':' (would leave "[" and
    // bypass the blocklist).
    val hostPart =
        if (rest.startsWith("["))
            rest.substringBefore(']')
        else
            rest.substringBefore('/').substringBefore(':')
    // Match the pattern against the token as-is: IPv6 alternatives in the
    // blocklist are written bracketed ("[::1]", "[fc", "[fd").
    return hostPart.length > 2 && !PRIVATE_HOST_RE.containsMatchIn(hostPart)
}

private val ELF_MACHINES =
    mapOf(
        "arm64-v8a" to 183L, // EM_AARCH64
        "x86_64" to 62L, // EM_X86_64
    )

/** True when the bundled ELF binary matches one of the device's ABIs. */
internal fun canExecuteElf(file: File): Boolean {
    if (!file.exists()) return false
    return runCatching {
        val header = file.inputStream().use { it.readNBytes(20) }
        if (header.size < 20 || header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte())
            return false
        val machine = ((header[19].toLong() and 0xFF) shl 8) or (header[18].toLong() and 0xFF)
        val abi =
            Build.SUPPORTED_ABIS.firstOrNull { ELF_MACHINES.containsKey(it) }
                ?: return false
        ELF_MACHINES[abi] == machine
    }.getOrDefault(false)
}

internal fun nativeBinary(name: String): File? {
    val dir = App.context.applicationInfo.nativeLibraryDir ?: return null
    val f = File(dir, name)
    return if (f.exists()) f else null
}

/**
 * First free "name.ext", "name (1).ext", … inside [dir]. Files under
 * Download/Sleeve survive re-installs, and a name owned by another app UID
 * cannot be replaced (EPERM), so never plan to overwrite one.
 */
internal fun uniqueFileName(dir: String, name: String): String {
    if (!File(dir, name).exists()) return name
    val stem = name.substringBeforeLast('.', name)
    val suffix = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var index = 1
    while (true) {
        val candidate = "$stem ($index)$suffix"
        if (!File(dir, candidate).exists()) return candidate
        index++
    }
}

/**
 * Best-effort metadata embed for engine-transferred files, restoring the
 * embedMetadata post-processing the stock yt-dlp path would have run.
 * Stream-copies (-c copy) so the transfer bytes are untouched; any ffmpeg
 * failure keeps the original file.
 */
internal fun embedMetadataWithFfmpeg(
    filePath: String,
    videoInfo: VideoInfo,
    preferences: DownloadUtil.DownloadPreferences,
) {
    val ffmpeg =
        File(App.context.applicationInfo.nativeLibraryDir ?: return, "libffmpeg.so")
            .takeIf { it.exists() } ?: return
    val inFile = File(filePath)
    if (!inFile.exists()) return
    // Keep the real extension on the temp file so ffmpeg can pick the muxer.
    val tmpOut =
        File(
            inFile.parentFile,
            inFile.nameWithoutExtension + ".meta_tmp." + inFile.extension,
        )

    val title = preferences.newTitle.ifEmpty { videoInfo.title }
    val artist = videoInfo.uploader ?: videoInfo.channel ?: ""
    val args =
        mutableListOf(
            ffmpeg.absolutePath,
            "-y",
            "-i",
            inFile.absolutePath,
            "-map",
            "0",
            "-c",
            "copy",
            "-metadata",
            "title=$title",
            "-metadata",
            "artist=$artist",
            "-metadata",
            "album_artist=$artist",
            "-metadata",
            "comment=Sleeve",
            tmpOut.absolutePath,
        )
    runCatching {
        val proc =
            ProcessBuilder(args)
                .apply {
                    // libc++_shared.so and the libav* libs live in the
                    // extracted ffmpeg package, where yt-dlp's own ffmpeg
                    // invocations also load them from.
                    environment()["LD_LIBRARY_PATH"] =
                        File(
                            App.context.noBackupFilesDir,
                            "youtubedl-android/packages/ffmpeg/usr/lib",
                        )
                            .absolutePath
                    redirectErrorStream(true)
                }
                .start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code == 0 && tmpOut.exists() && tmpOut.length() > 0) {
            if (inFile.delete() && tmpOut.renameTo(inFile)) {
                Log.i(TRANSFER_TAG, "ffmpeg: metadata embedded into ${inFile.name}")
            } else {
                Log.w(TRANSFER_TAG, "ffmpeg: could not replace original")
                tmpOut.delete()
            }
        } else {
            Log.w(TRANSFER_TAG, "ffmpeg: exit $code ${output.takeLast(300)}")
            tmpOut.delete()
        }
    }.onFailure {
        Log.w(TRANSFER_TAG, "ffmpeg: embed failed: ${it.message}")
        tmpOut.delete()
    }
}
