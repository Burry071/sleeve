package com.junkfood.seal.download.engine

import com.junkfood.seal.util.AUDIO_FILE_EXTENSIONS
import com.junkfood.seal.util.Format
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.sanitizeSegment
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

private val DIRECT_AUDIO_EXTS = AUDIO_FILE_EXTENSIONS
private const val MAX_HEAD_REDIRECTS = 5

/** The direct-media lane: a raw URL is the artifact, so no extraction runs. */
internal object DirectMedia {

    /**
     * Best-effort content length for a direct media URL via a bounded HEAD
     * request; 0 means unknown, -1 means the URL (or a redirect target) was
     * rejected by [isSafeRemoteUrl]. Callers run on background dispatchers
     * (DownloaderV2.prepare launches on Dispatchers.Default). No fallback
     * guess is made when the server withholds Content-Length.
     */
    fun fetchContentLength(url: String): Long {
        if (!isSafeRemoteUrl(url)) return -1L
        var currentUrl = url
        var hops = 0
        while (hops <= MAX_HEAD_REDIRECTS) {
            if (!isSafeRemoteUrl(currentUrl)) return -1L
            var conn: HttpURLConnection? = null
            try {
                conn = URL(currentUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)")
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    runCatching { conn.inputStream?.close() }
                    if (location.isNullOrBlank() || hops >= MAX_HEAD_REDIRECTS) return 0L
                    val resolved =
                        runCatching { URI(currentUrl).resolve(location).toString() }.getOrNull()
                            ?: return -1L
                    if (!isSafeRemoteUrl(resolved)) return -1L
                    currentUrl = resolved
                    hops++
                    continue
                }
                val len = if (code in 200..299) conn.contentLengthLong else -1L
                if (code in 200..299) {
                    runCatching { conn.inputStream?.close() }
                } else {
                    runCatching { conn.errorStream?.close() }
                }
                return if (len > 0) len else 0L
            } catch (_: Throwable) {
                return 0L
            } finally {
                conn?.disconnect()
            }
        }
        return 0L
    }

    /**
     * Minimal VideoInfo for a raw media URL (gallery-dl item, sniffed page
     * asset): one synthetic "direct" format so the Ghost handoff transfers the
     * bytes without yt-dlp extraction. [contentLength] is injectable for
     * tests; production resolves it with a bounded HEAD only after
     * [isSafeRemoteUrl] passes, so the queue card shows the real size instead
     * of a defaulting "0.00 MB".
     */
    fun buildDirectVideoInfo(
        url: String,
        title: String,
        contentLength: Long? = null,
    ): VideoInfo? {
        if (!isSafeRemoteUrl(url)) return null
        val resolvedLength = contentLength ?: fetchContentLength(url)
        val cleanUrl = url.substringBefore('#').substringBefore('?').substringBefore('？')
        val path = cleanUrl.substringBefore('?').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 }
        // Format.isAudioOnly() treats a null vcodec as audio-only, which would
        // file every direct image/video under the audio dir; classify by the
        // file extension instead.
        val directVcodec = if (ext in DIRECT_AUDIO_EXTS) "none" else "h264"
        val baseName =
            title.ifBlank { path }.ifBlank { cleanUrl }.let { sanitizeSegment(it) }
        val fullName = if (ext != null && !baseName.endsWith(".$ext")) "$baseName.$ext" else baseName
        // requestedFormats = the chosen file: ViewState.videoFormats and the
        // ghost-handoff candidate read requestedFormats first, and an empty
        // list would label the task "audio" in the queue header.
        val directFormat =
            Format(
                formatId = "direct",
                ext = ext,
                url = url,
                vcodec = directVcodec,
            )
        return VideoInfo(
            id = Integer.toHexString(url.hashCode()),
            title = baseName,
            webpageUrl = url,
            originalUrl = url,
            extractor = "sleeve-direct",
            extractorKey = "SleeveDirect",
            ext = ext ?: "bin",
            filename = fullName,
            formats = listOf(directFormat),
            requestedFormats = listOf(directFormat),
            // Content-Length from the HEAD probe; 0 stays 0 (unknown) — the
            // size is never invented.
            fileSizeApprox = if (resolvedLength > 0) resolvedLength.toDouble() else .0,
        )
    }
}
