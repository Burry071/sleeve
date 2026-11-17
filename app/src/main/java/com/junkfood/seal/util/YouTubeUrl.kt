package com.junkfood.seal.util

import java.net.URI

private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")
private val YOUTUBE_HOST =
    Regex(
        "(^|\\.)((youtube\\.com)|(youtu\\.be)|(youtube-nocookie\\.com))$",
        RegexOption.IGNORE_CASE,
    )

/** True for any http(s) URL on a YouTube host, video id or not. */
fun isYouTubeUrl(url: String): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
    val host = uri.host ?: return false
    return YOUTUBE_HOST.containsMatchIn(host)
}

/** The video id a YouTube URL names, or null when the URL names no video. */
fun youTubeIdFrom(url: String): String? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
    val host = uri.host ?: return null
    if (!YOUTUBE_HOST.containsMatchIn(host)) return null
    val path = uri.path.orEmpty()
    val id =
        when {
            host.endsWith("youtu.be", ignoreCase = true) ->
                path.trim('/').substringBefore('/')
            path.trimEnd('/') == "/watch" -> queryValue(uri.rawQuery, "v")
            path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore('/')
            path.startsWith("/live/") -> path.removePrefix("/live/").substringBefore('/')
            path.startsWith("/embed/") -> path.removePrefix("/embed/").substringBefore('/')
            path.startsWith("/v/") -> path.removePrefix("/v/").substringBefore('/')
            else -> null
        }
    return id?.takeIf { YOUTUBE_ID.matches(it) }
}

/** True for the bare 11-character id a flat playlist entry carries. */
fun isYouTubeId(value: String): Boolean = YOUTUBE_ID.matches(value)

private fun queryValue(rawQuery: String?, key: String): String? =
    rawQuery
        ?.split('&')
        ?.firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
