package com.junkfood.seal.util

import android.util.Log
import com.junkfood.seal.ghostengine.GhostEngine.GalleryItem
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Anonymous Reddit resolver. Reddit's JSON API hard-blocks non-browser user
 * agents (yt-dlp gets the "Account authentication is required" page), so this
 * fetches the post JSON the way a browser would: real browser UA + headers,
 * over18/gated-subreddit cookies, an anonymous `loid` session bootstrapped
 * from old.reddit.com, and whatever reddit.com cookies the WebView database
 * happens to hold. None of that is a user login — cookies stay optional.
 *
 * Whatever media the post carries (v.redd.it video, i.redd.it images, media
 * galleries, crossposts) comes back as direct [GalleryItem]s for the
 * gallery/direct lane, where video manifests go through yt-dlp or
 * N_m3u8DL-RE and plain files go through the Ghost handoff.
 */
object RedditResolver {

    private const val TAG = "RedditResolver"

    private val json = Json { ignoreUnknownKeys = true }

    /** Any reddit host — used to present yt-dlp as a browser for these. */
    private val HOST_RE = Regex("""https?://(?:\w+\.)?(?:reddit\.com|redd\.it)""", RegexOption.IGNORE_CASE)

    /** Post URLs only: r/<sub>/comments/<id>[/slug] or bare redd.it/<id>. */
    private val POST_RE =
        Regex(
            """https?://(?:\w+\.)?reddit\.com/(?:(?:r|user)/[^/\s]+/)?comments/([A-Za-z0-9]+)(?:/[^/?#&\s]*(?:/([A-Za-z0-9]+))?)?""",
            RegexOption.IGNORE_CASE,
        )
    private val SHORT_RE = Regex("""https?://(?:www\.)?redd\.it/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)

    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** Same anonymous opt-ins yt-dlp sets: adult content + gated subreddits. */
    private const val ANON_COOKIES =
        "over18=1; _options=%7B%22pref_gated_sr_optin%22%3Atrue%7D"

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "avif")

    fun isRedditHost(url: String): Boolean = HOST_RE.containsMatchIn(url)

    /** Post URLs eligible for the JSON pre-route (not v.redd.it media links). */
    fun isPostUrl(url: String): Boolean = POST_RE.containsMatchIn(url) || SHORT_RE.containsMatchIn(url)

    private fun postPath(url: String): String {
        POST_RE.find(url)?.let { m ->
            val (id, slug) = m.destructured
            return "comments/$id" + if (slug.isNotEmpty()) "/$slug" else ""
        }
        SHORT_RE.find(url)?.let { return "comments/${it.groupValues[1]}" }
        throw IllegalArgumentException("not a reddit post url")
    }

    suspend fun resolve(url: String): Result<List<GalleryItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = postPath(url)
                val webViewCookies = redditCookieString()

                var noMediaSeen = false

                val attempts =
                    sequence {
                        val www = "https://www.reddit.com/$path.json?raw_json=1"
                        val old = "https://old.reddit.com/$path.json?raw_json=1"
                        yield(www to ANON_COOKIES)
                        if (webViewCookies.isNotEmpty()) yield(www to "$ANON_COOKIES; $webViewCookies")
                        val loid = bootstrapLoid()
                        if (loid.isNotEmpty()) yield(www to "$ANON_COOKIES; $loid")
                        yield(old to ANON_COOKIES)
                        if (webViewCookies.isNotEmpty()) yield(old to "$ANON_COOKIES; $webViewCookies")
                    }

                for ((endpoint, cookies) in attempts) {
                    val body = fetch(endpoint, cookies) ?: continue
                    val parsed =
                        runCatching { parsePostJson(body, url) }
                            .onFailure {
                                if (it.message == NO_MEDIA_MSG) noMediaSeen = true
                            }
                            .getOrNull()
                    if (parsed != null) {
                        Log.i(TAG, "reddit resolve ok: ${parsed.size} item(s) from $endpoint")
                        return@runCatching parsed
                    }
                    if (noMediaSeen) break
                }

                if (noMediaSeen) error(NO_MEDIA_MSG)
                error(
                    "reddit json api did not answer anonymously — add cookies for reddit to download",
                )
            }
        }

    private const val NO_MEDIA_MSG = "no downloadable media found on this post"

    /** GET old.reddit.com once to pick up the anonymous `loid` session cookie. */
    private fun bootstrapLoid(): String =
        runCatching {
            val conn = URL("https://old.reddit.com/").openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", BROWSER_UA)
            val setCookies =
                conn.headerFields.entries
                    .filter { it.key.equals("set-cookie", ignoreCase = true) }
                    .flatMap { it.value }
            conn.disconnect()
            setCookies
                .mapNotNull { it.substringBefore(';').takeIf { c -> c.startsWith("loid") } }
                .joinToString("; ")
        }.getOrNull().orEmpty()

    /** reddit.com cookies already in the WebView database, if any. */
    private fun redditCookieString(): String =
        runCatching {
            DownloadUtil.getCookieListFromDatabase()
                .getOrDefault(emptyList())
                .filter { it.domain?.contains("reddit", ignoreCase = true) == true }
                .joinToString("; ") { "${it.name}=${it.value}" }
        }.getOrDefault("")

    /** One anonymous GET; null unless the answer is a JSON listing array. */
    private fun fetch(endpoint: String, cookies: String): String? {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", BROWSER_UA)
            conn.setRequestProperty("Accept", "application/json,text/json;q=0.9,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.use { it.readBytes().decodeToString() }.trim()
            if (!body.startsWith("[")) return null
            body
        } catch (e: Exception) {
            Log.d(TAG, "fetch failed: $endpoint ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Extract direct media from a post listing. Static so it is unit-testable
     * on the host: takes the JSON text, returns gallery items.
     */
    fun parsePostJson(text: String, pageUrl: String): List<GalleryItem> {
        val root = json.parseToJsonElement(text.trim()).jsonArray
        val post =
            root.asSequence()
                .map { it.jsonObject }
                .mapNotNull { listing ->
                    listing["data"]
                        ?.jsonObject
                        ?.get("children")
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("data")
                        ?.jsonObject
                }
                .firstOrNull() ?: error("post data missing")

        val effective =
            (post["crosspost_parent_list"] as? JsonArray)
                ?.firstOrNull()
                ?.let { it.jsonObject }
                ?: post

        val title =
            sanitize(decodeAmp(effective.str("title") ?: post.str("title") ?: "reddit"))
        val subreddit =
            (effective.str("subreddit") ?: post.str("subreddit") ?: "reddit")
                .substringAfterLast('/')
                .take(40)
        val referer = effective.str("permalink")?.let { "https://www.reddit.com$it" } ?: pageUrl

        // v.redd.it video: prefer the HLS manifest (carries audio), then DASH,
        // then the video-only fallback mp4 as a last resort.
        val video =
            effective.obj("media")?.obj("reddit_video")
                ?: effective.obj("secure_media")?.obj("reddit_video")
                ?: effective.obj("preview")?.obj("reddit_video_preview")
        if (video != null) {
            val manifest = video.str("hls_url") ?: video.str("dash_url") ?: video.str("fallback_url")
                ?: error(NO_MEDIA_MSG)
            return listOf(
                GalleryItem(
                    url = manifest,
                    filename = "$subreddit-$title.mp4",
                    referer = referer,
                    category = "reddit",
                ),
            )
        }

        // Multi-image / mixed galleries, in the author's intended order.
        if (effective.bool("is_gallery")) {
            val meta = effective.obj("media_metadata") ?: error(NO_MEDIA_MSG)
            val order =
                effective.obj("gallery_data")
                    ?.get("items")
                    ?.jsonArray
                    ?.mapNotNull { it.jsonObject.str("media_id") }
                    ?: meta.keys.toList()
            val items =
                order.mapIndexedNotNull { index, id ->
                    val entry = meta[id]?.jsonObject ?: return@mapIndexedNotNull null
                    val s = entry.obj("s") ?: return@mapIndexedNotNull null
                    val raw = s.str("gif") ?: s.str("mp4") ?: s.str("u") ?: return@mapIndexedNotNull null
                    val url = decodeAmp(raw)
                    val ext =
                        url.substringBefore('?').substringAfterLast('.').lowercase()
                            .takeIf { it in IMAGE_EXTS || it == "mp4" } ?: "jpg"
                    GalleryItem(
                        url = url,
                        filename = "$subreddit-$title-${index + 1}.$ext",
                        referer = referer,
                        category = "reddit",
                    )
                }
            if (items.isNotEmpty()) return items
        }

        // Single direct destination (i.redd.it image/gif, i.imgur, ...).
        val dest = effective.str("url_overridden_by_dest") ?: effective.str("url")
        if (dest != null) {
            val clean = decodeAmp(dest)
            val ext = clean.substringBefore('?').substringAfterLast('.').lowercase()
            if (ext in IMAGE_EXTS || ext == "mp4") {
                return listOf(
                    GalleryItem(
                        url = clean,
                        filename = "$subreddit-$title.$ext",
                        referer = referer,
                        category = "reddit",
                    ),
                )
            }
        }

        // Last resort: the highest-resolution preview rendition.
        val preview =
            effective.obj("preview")
                ?.get("images")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.obj("source")
                ?.str("url")
        if (preview != null) {
            val url = decodeAmp(preview)
            val ext =
                url.substringBefore('?').substringAfterLast('.').lowercase()
                    .takeIf { it in IMAGE_EXTS } ?: "jpg"
            return listOf(
                GalleryItem(
                    url = url,
                    filename = "$subreddit-$title.$ext",
                    referer = referer,
                    category = "reddit",
                ),
            )
        }

        error(NO_MEDIA_MSG)
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.content == "true"

    private fun decodeAmp(url: String): String =
        url.replace("&amp;", "&").replace("&#38;", "&")

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(100)
}
