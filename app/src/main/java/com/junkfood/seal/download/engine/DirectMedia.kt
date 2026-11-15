package com.junkfood.seal.download.engine

import com.junkfood.seal.util.AUDIO_FILE_EXTENSIONS
import com.junkfood.seal.util.Format
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.sanitizeSegment
import java.net.HttpURLConnection
import java.net.URL

private val DIRECT_AUDIO_EXTS = AUDIO_FILE_EXTENSIONS

/** The direct-media lane: a raw URL is the artifact, so no extraction runs. */
internal object DirectMedia {

    /**
     * Best-effort content length for a direct media URL via a bounded HEAD
     * request; 0 means unknown. Callers run on background dispatchers
     * (DownloaderV2.prepare launches on Dispatchers.Default). No fallback
     * guess is made when the server withholds Content-Length.
     */
    fun fetchContentLength(url: String): Long =
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)")
            val len = if (conn.responseCode in 200..299) conn.contentLengthLong else -1L
            conn.disconnect()
            if (len > 0) len else 0L
        }.getOrDefault(0L)

    /**
     * Minimal VideoInfo for a raw media URL (gallery-dl item, sniffed page
     * asset): one synthetic "direct" format so the Ghost handoff transfers the
     * bytes without yt-dlp extraction. [contentLength] is injectable for
     * tests; production resolves it with a bounded HEAD so the queue card
     * shows the real size instead of a defaulting "0.00 MB".
     */
    fun buildDirectVideoInfo(
        url: String,
        title: String,
        contentLength: Long = fetchContentLength(url),
    ): VideoInfo? {
        if (!isSafeRemoteUrl(url)) return null
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
            fileSizeApprox = if (contentLength > 0) contentLength.toDouble() else .0,
        )
    }
}
