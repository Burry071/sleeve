package com.junkfood.seal.download.engine

import android.os.Build
import android.util.Log
import com.junkfood.seal.App
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.isYouTubeUrl
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Transfer lanes keep the façade's log tag: device notes and bug reports quote
 * `DownloadUtil` lines, and those must keep matching after the split.
 */
internal const val TRANSFER_TAG = "DownloadUtil"

/**
 * Extraction rungs, cheapest first. yt-dlp's own client selection needs no JS
 * runtime and is not the set a bot check recognises; the TV/embedded pair
 * shares a challenge path with the web player, and visionOS is the quiet one.
 */
internal val YOUTUBE_CLIENT_LADDER: List<String?> =
    listOf(null, "tv,web_embedded", "visionos")

/** The winning rung first (it may be null), then every rung not yet tried. */
internal fun downloadClientLadder(recorded: String?, hasRecordedWin: Boolean): List<String?> =
    if (hasRecordedWin) listOf(recorded) + YOUTUBE_CLIENT_LADDER.filter { it != recorded }
    else YOUTUBE_CLIENT_LADDER

/** Only YouTube understands the client sets; other extractors keep yt-dlp's own. */
internal fun extractionLadder(url: String, recorded: String?, hasRecordedWin: Boolean): List<String?> =
    if (isYouTubeUrl(url)) downloadClientLadder(recorded, hasRecordedWin)
    else listOf(null)

/** Client set that last won extraction, per task key (YouTube ladder). */
internal object ExtractionClients {
    /** A null win (yt-dlp's default set) needs a value in the map to be remembered. */
    private const val DEFAULT_CLIENT = "\u0000"

    private val wins = ConcurrentHashMap<String, String>()

    fun record(taskKey: String, clients: String?) {
        wins[taskKey] = clients ?: DEFAULT_CLIENT
    }

    fun get(taskKey: String): String? = wins[taskKey]?.takeIf { it != DEFAULT_CLIENT }

    /** True once any rung won, including the null one. */
    fun hasWin(taskKey: String): Boolean = wins.containsKey(taskKey)

    fun clear(taskKey: String) {
        wins.remove(taskKey)
    }
}

private val NUMERIC_LABEL_RE = Regex("^(?:\\d+|0[xX][0-9a-fA-F]+)$")

/** Shared with the direct-lane resolvers: page content decides what gets fetched. */
internal fun isSafeRemoteUrl(url: String): Boolean {
    if (url.isBlank()) return false
    if (url.any { it.code < 0x20 || it.code == 0x7F }) return false

    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return false
    val scheme = url.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return false

    val afterScheme = url.substring(schemeEnd + 3)
    val authEnd =
        afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let {
            if (it == -1) afterScheme.length else it
        }
    val authority = afterScheme.substring(0, authEnd)
    // Reject empty authority, userinfo ("user@host"), or backslash parser tricks.
    if (authority.isEmpty() || '@' in authority || '\\' in authority) return false

    val uri = runCatching { URI("$scheme://$authority") }.getOrNull() ?: return false
    if (uri.scheme != scheme || uri.rawUserInfo != null || uri.rawAuthority != authority) {
        return false
    }
    val rawHost = uri.host ?: return false
    if (uri.port == 0 || uri.port > 65535) return false

    val lower = rawHost.lowercase()
    val startsBracket = lower.startsWith("[")
    val endsBracket = lower.endsWith("]")
    if (startsBracket != endsBracket) return false

    if (startsBracket || ':' in lower) {
        val ipv6Literal =
            (if (startsBracket) lower.substring(1, lower.length - 1) else lower)
                .substringBefore('%')
        val bytes = parseIpv6Bytes(ipv6Literal) ?: return false
        return !isBlockedIpv6(bytes)
    }

    val host = lower.trimEnd('.')
    if (host.isEmpty()) return false
    if (
        host == "localhost" ||
            host == "local" ||
            host == "ip6-localhost" ||
            host == "ip6-loopback" ||
            host == "metadata.google.internal" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local")
    ) {
        return false
    }

    val labels = host.split('.')
    if (labels.any { it.isEmpty() }) return false

    // Any numeric-only hostname (canonical dotted-quad IPv4 or alternative
    // inet_aton encodings like decimal 2130706433, hex 0x7f000001, octal
    // 0177.0.0.1, shorthand 127.1) is handled strictly without DNS.
    if (labels.all { NUMERIC_LABEL_RE.matches(it) }) {
        val quad = parseCanonicalIpv4(labels) ?: return false
        return !isBlockedIpv4(quad[0], quad[1], quad[2], quad[3])
    }

    // Reject single-label hostnames (e.g. "intranet", "metadata", "redis") and
    // all-numeric TLDs which are invalid on public DNS.
    if (labels.size < 2) return false
    if (NUMERIC_LABEL_RE.matches(labels.last())) return false

    return true
}

/** Parses strict canonical dotted-quad IPv4 (no leading-zero octal, no hex, no shorthand). */
private fun parseCanonicalIpv4(labels: List<String>): IntArray? {
    if (labels.size != 4) return null
    val out = IntArray(4)
    for (i in 0 until 4) {
        val part = labels[i]
        if (part.isEmpty() || part.length > 3) return null
        if (part.length > 1 && part[0] == '0') return null
        if (!part.all { it in '0'..'9' }) return null
        val value = part.toIntOrNull() ?: return null
        if (value !in 0..255) return null
        out[i] = value
    }
    return out
}

private fun isBlockedIpv4(a: Int, b: Int, c: Int, d: Int): Boolean =
    a == 0 || // 0.0.0.0/8 unspecified
        a == 10 || // 10.0.0.0/8 private
        (a == 100 && b in 64..127) || // 100.64.0.0/10 carrier-grade NAT
        a == 127 || // 127.0.0.0/8 loopback
        (a == 169 && b == 254) || // 169.254.0.0/16 link-local / cloud metadata
        (a == 172 && b in 16..31) || // 172.16.0.0/12 private
        (a == 192 && b == 0 && c == 0) || // 192.0.0.0/24 IETF protocol assignments
        (a == 192 && b == 168) || // 192.168.0.0/16 private
        (a == 198 && b in 18..19) || // 198.18.0.0/15 benchmarking
        a >= 224 // 224.0.0.0/4 multicast, 240.0.0.0/4 reserved, 255.255.255.255 broadcast

/** Pure, zero-DNS parser for IPv6 literals (including embedded IPv4 tails). */
private fun parseIpv6Bytes(raw: String): ByteArray? {
    if (raw.isEmpty() || ":::" in raw) return null
    val normalized =
        if ('.' in raw) {
            val lastColon = raw.lastIndexOf(':')
            if (lastColon == -1) return null
            val prefix = raw.substring(0, lastColon + 1)
            val v4 = parseCanonicalIpv4(raw.substring(lastColon + 1).split('.')) ?: return null
            val hi = (v4[0] shl 8) or v4[1]
            val lo = (v4[2] shl 8) or v4[3]
            "$prefix${hi.toString(16)}:${lo.toString(16)}"
        } else {
            raw
        }

    val halves = normalized.split("::")
    if (halves.size > 2) return null
    val groups =
        if (halves.size == 1) {
            val parts = normalized.split(':')
            if (parts.size != 8) return null
            parts
        } else {
            val left = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
            val right = if (halves[1].isEmpty()) emptyList() else halves[1].split(':')
            val missing = 8 - (left.size + right.size)
            if (missing < 1) return null
            left + List(missing) { "0" } + right
        }

    val bytes = ByteArray(16)
    for (i in 0 until 8) {
        val g = groups[i]
        if (g.length !in 1..4) return null
        if (!g.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val value = g.toInt(16)
        bytes[i * 2] = (value ushr 8).toByte()
        bytes[i * 2 + 1] = (value and 0xFF).toByte()
    }
    return bytes
}

private fun isBlockedIpv6(b: ByteArray): Boolean {
    val allZero = b.all { it == 0.toByte() }
    if (allZero) return true // :: unspecified

    val isLoopback = (0 until 15).all { b[it] == 0.toByte() } && b[15] == 1.toByte()
    if (isLoopback) return true // ::1 loopback

    val b0 = b[0].toInt() and 0xFF
    val b1 = b[1].toInt() and 0xFF
    if ((b0 and 0xFE) == 0xFC) return true // fc00::/7 unique local (private)
    if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true // fe80::/10 link-local
    if (b0 == 0xFE && (b1 and 0xC0) == 0xC0) return true // fec0::/10 site-local
    if (b0 == 0xFF) return true // ff00::/8 multicast

    // ::ffff:0:0/96 IPv4-mapped IPv6
    val isV4Mapped =
        (0 until 10).all { b[it] == 0.toByte() } &&
            b[10] == 0xFF.toByte() &&
            b[11] == 0xFF.toByte()
    if (isV4Mapped) {
        return isBlockedIpv4(
            b[12].toInt() and 0xFF,
            b[13].toInt() and 0xFF,
            b[14].toInt() and 0xFF,
            b[15].toInt() and 0xFF,
        )
    }

    // ::/96 deprecated IPv4-compatible IPv6
    val isV4Compatible = (0 until 12).all { b[it] == 0.toByte() }
    if (isV4Compatible) {
        return isBlockedIpv4(
            b[12].toInt() and 0xFF,
            b[13].toInt() and 0xFF,
            b[14].toInt() and 0xFF,
            b[15].toInt() and 0xFF,
        )
    }

    // 64:ff9b::/96 NAT64 well-known prefix
    val isNat64 =
        b0 == 0x00 &&
            b1 == 0x64 &&
            (b[2].toInt() and 0xFF) == 0xFF &&
            (b[3].toInt() and 0xFF) == 0x9B &&
            (4 until 12).all { b[it] == 0.toByte() }
    if (isNat64) {
        return isBlockedIpv4(
            b[12].toInt() and 0xFF,
            b[13].toInt() and 0xFF,
            b[14].toInt() and 0xFF,
            b[15].toInt() and 0xFF,
        )
    }

    // 2002::/16 6to4 embeds IPv4 in bytes 2..5
    if (b0 == 0x20 && b1 == 0x02) {
        return isBlockedIpv4(
            b[2].toInt() and 0xFF,
            b[3].toInt() and 0xFF,
            b[4].toInt() and 0xFF,
            b[5].toInt() and 0xFF,
        )
    }

    return false
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
            if (replaceFileSafely(tmpOut, inFile)) {
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

/**
 * Replaces [target] with [source] without deleting [target] first: moves
 * [target] to a sibling backup, renames [source] into place, and restores
 * the backup if replacement fails so the original media file is never lost.
 */
internal fun replaceFileSafely(source: File, target: File): Boolean {
    if (!source.isFile || source.length() <= 0L) return false
    if (!target.exists()) {
        return source.renameTo(target)
    }
    val backup = File(target.parentFile, "${target.name}.bak_${System.nanoTime()}")
    if (!target.renameTo(backup)) {
        return false
    }
    if (source.renameTo(target)) {
        backup.delete()
        return true
    }
    backup.renameTo(target)
    return false
}
