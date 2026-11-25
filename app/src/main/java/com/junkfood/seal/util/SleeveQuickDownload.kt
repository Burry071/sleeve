package com.junkfood.seal.util

import android.content.Context
import android.util.Log
import com.junkfood.seal.App
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.TaskOrigin
import com.junkfood.seal.ghostengine.GhostEngine
import com.junkfood.seal.ghostengine.GhostEngine.GalleryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared resolution + enqueue path for links that should land in the queue
 * with as little ceremony as possible: the share-sheet "Quick download"
 * target and the configure dialog's gallery route both run this chain.
 *
 * Order: gallery-dl (with the captured cookie session) → anonymous Instagram
 * embed → generic page sniffing. Whatever survives is enqueued on the direct
 * lane; plain video URLs fall back to a default-preferences task.
 */
object SleeveQuickDownload {

    private const val TAG = "SleeveQuickDownload"

    private val INSTAGRAM_VIDEO_URL = Regex("instagram\\.com/(?:reel|reels|tv)/")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    data class Outcome(val queued: Int, val source: String)

    private fun cookiesFile(): String =
        runCatching {
            val content = DownloadUtil.getCookiesContentFromDatabase().getOrDefault("")
            if (content.isEmpty()) return@runCatching ""
            val file = with(FileUtil) { App.context.getCookieFile() }
            FileUtil.writeContentToFile(content, file)
            file.absolutePath
        }.getOrDefault("")

    private fun preferences(anonymous: Boolean): DownloadUtil.DownloadPreferences =
        DownloadUtil.DownloadPreferences.createFromPreferences()
            .let { if (anonymous) it.withoutCookies() else it }

    /** Quick mode is the only fence; every other caller is a user task. */
    private fun origin(anonymous: Boolean): TaskOrigin =
        if (anonymous) TaskOrigin.Quick else TaskOrigin.User

    internal fun applyDirectPreferences(
        base: DownloadUtil.DownloadPreferences,
        item: GalleryItem,
    ): DownloadUtil.DownloadPreferences =
        base.copy(
            galleryDirect = true,
            // A direct item is the final artifact: no audio extraction and no
            // conversion postprocessors on a file that is already what was
            // asked for.
            extractAudio = false,
            convertAudio = false,
            newTitle = item.filename.substringBeforeLast('.').ifBlank {
                item.url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
            },
            formatIdString = "direct",
            referer = item.referer.trim(),
        )

    private fun directPreferences(
        item: GalleryItem,
        anonymous: Boolean,
    ): DownloadUtil.DownloadPreferences =
        applyDirectPreferences(preferences(anonymous), item)

    /**
     * The resolver ladder, injectable so the fallback order is testable without
     * the embedded engine: gallery-dl → (Instagram) anonymous embed → page
     * sniff. A gallery miss on any other host must reach the sniffer: most
     * pages have no gallery-dl module at all, and the old code jumped straight
     * to yt-dlp, which cannot see a bare `<video src>` on a generic page.
     */
    internal suspend fun resolveItems(
        url: String,
        cookiesFile: String,
        gallery: suspend (String, String) -> Result<Pair<String, List<GalleryItem>>>,
        embed: suspend (String) -> Result<List<GalleryItem>>,
        sniff: suspend (String) -> Result<List<GalleryItem>>,
    ): Pair<String, List<GalleryItem>> {
        val galleryResult = gallery(url, cookiesFile)
        if (galleryResult.isSuccess) {
            val (site, resolved) = galleryResult.getOrThrow()
            if (resolved.isNotEmpty()) return "gallery:$site" to resolved
            return "sniff" to sniff(url).getOrDefault(emptyList())
        }
        val galleryError = galleryResult.exceptionOrNull() ?: error("gallery resolve failed")

        if (url.contains("instagram.com")) {
            val resolved = embed(url).getOrThrow()
            if (resolved.isEmpty()) throw galleryError
            return "instagram-embed" to resolved
        }
        // No module matched: read the page's own tags before giving up. An
        // empty sniff keeps the gallery error so the caller's fallback reports
        // why the resolver stopped, not a misleading "no media".
        val sniffed = sniff(url).getOrDefault(emptyList())
        if (sniffed.isEmpty()) throw galleryError
        return "sniff" to sniffed
    }

    /** Resolve via gallery → instagram embed → sniff, then enqueue each item. */
    suspend fun enqueue(
        context: Context,
        url: String,
        downloader: DownloaderV2,
        anonymous: Boolean = false,
    ): Result<Outcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Quick mode is a fence, not a login: no captured session.
                val cookiesFile = if (anonymous) "" else cookiesFile()

                val (source, items) =
                    resolveItems(
                        url = url,
                        cookiesFile = cookiesFile,
                        gallery = { u, c -> GhostEngine.galleryResolve(u, c) },
                        embed = { GhostEngine.instagramEmbed(it) },
                        sniff = { GhostEngine.sniffMedia(it) },
                    )

                if (items.isEmpty()) error("no media found on this page")

                // A reel/tv link is a video. The anonymous embed sometimes only
                // exposes the poster image (embed naming is
                // instagram_<shortcode>.jpg); saving that would "succeed" with
                // the wrong content. Send the page URL to yt-dlp instead, which
                // owns the logged-out/login-wall error.
                val videoFirst = INSTAGRAM_VIDEO_URL.containsMatchIn(url)
                val posterOnly =
                    items.all { item ->
                        item.category == "instagram-embed" &&
                            item.filename.substringAfterLast('.', "").lowercase() in
                                IMAGE_EXTENSIONS
                    }
                if (videoFirst && posterOnly) {
                    Log.i(TAG, "video-first URL resolved to a poster image; routing to yt-dlp")
                    downloader.enqueue(
                        Task(
                            url = url,
                            preferences = preferences(anonymous),
                            origin = origin(anonymous),
                        )
                    )
                    return@runCatching Outcome(1, "default")
                }

                var queued = 0
                items.forEach { item ->
                    runCatching {
                        downloader.enqueue(
                            Task(
                                url = item.url,
                                preferences = directPreferences(item, anonymous),
                                origin = origin(anonymous),
                            )
                        )
                        queued++
                    }.onFailure { Log.e(TAG, "enqueue failed for ${item.url}: ${it.message}") }
                }
                if (queued == 0) error("could not queue any of the resolved items")
                Outcome(queued, source)
            }
        }

    /** Plain default-preferences enqueue for URLs no extractor claims. */
    suspend fun enqueueDefault(
        context: Context,
        url: String,
        downloader: DownloaderV2,
        anonymous: Boolean = false,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                downloader.enqueue(
                    Task(
                        url = url,
                        preferences = preferences(anonymous),
                        origin = origin(anonymous),
                    )
                )
            }
        }

    /** Full quick-download routing: gallery/sniff when possible, default otherwise. */
    suspend fun quickDownload(
        context: Context,
        url: String,
        downloader: DownloaderV2,
    ): Result<Outcome> =
        enqueue(context, url, downloader, anonymous = true).recoverCatching {
            enqueueDefault(context, url, downloader, anonymous = true).getOrThrow()
            Outcome(1, "default")
        }
}
