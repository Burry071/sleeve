package com.junkfood.seal.util

/**
 * The direct-media routing table: which URLs are already the media file and
 * can bypass yt-dlp's generic extractor (which adds a failing round-trip and
 * thumbnail-postprocessing failures on CDN links — the user's `.heic` Instagram
 * share was missing from this table and fell into that path). Pure and
 * host-testable; [DownloadUtil] delegates here.
 */
object DirectMediaRules {

    val MEDIA_EXTS =
        setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "jfif",
            "heic", "heif", "tif", "tiff",
            "mp4", "m4v", "webm", "mkv", "mov", "avi", "3gp", "ts", "flv",
            "mp3", "m4a", "aac", "opus", "wav", "flac", "oga", "wma",
        )

    val IMAGE_EXTS =
        setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "jfif", "heic", "heif", "tif", "tiff")

    /** File extension of the URL path, query/fragment stripped, lowercased. */
    fun extensionOf(url: String): String =
        url.substringBefore('#')
            .substringBefore('?')
            .substringBefore('\uff1f')
            .substringAfterLast('/')
            .substringAfterLast('.')
            .lowercase()

    fun isDirectMediaUrl(url: String): Boolean {
        val segment = url.substringBefore('#')
            .substringBefore('?')
            .substringBefore('\uff1f')
            .substringAfterLast('/')
        // A filename carries its extension after a dot; "site.com/jpg" is a
        // page named "jpg", not a file — the dot must exist in the segment.
        if (!segment.contains('.')) return false
        return segment.substringAfterLast('.').lowercase() in MEDIA_EXTS
    }

    fun isDirectImageUrl(url: String): Boolean {
        val segment = url.substringBefore('#')
            .substringBefore('?')
            .substringBefore('\uff1f')
            .substringAfterLast('/')
        if (!segment.contains('.')) return false
        return segment.substringAfterLast('.').lowercase() in IMAGE_EXTS
    }
}
