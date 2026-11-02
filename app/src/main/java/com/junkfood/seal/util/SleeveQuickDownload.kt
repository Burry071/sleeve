package com.junkfood.seal.util

import android.content.Context
import android.util.Log
import com.junkfood.seal.App
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.download.Task
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

    data class Outcome(val queued: Int, val source: String)

    /** Media found on a page, before anything is queued. */
    data class Resolved(val url: String, val items: List<GalleryItem>, val source: String)

    private fun cookiesFile(): String =
        runCatching {
            val content = DownloadUtil.getCookiesContentFromDatabase().getOrDefault("")
            if (content.isEmpty()) return@runCatching ""
            val file = with(FileUtil) { App.context.getCookiesFile() }
            FileUtil.writeContentToFile(content, file)
            file.absolutePath
        }.getOrDefault("")

    /** Extensions that mark a direct item as an extractable video. */
    private val DIRECT_VIDEO_EXTS = setOf("mp4", "webm", "mkv", "mov", "m4v")

    private fun directPreferences(
        item: GalleryItem,
        extractAudio: Boolean = false,
    ): DownloadUtil.DownloadPreferences {
        val ext =
            (item.filename.ifBlank { item.url })
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('/')
                .substringAfterLast('.')
                .lowercase()
        // Picking Audio means yt-dlp post-processing (-x), which the ghost
        // transfer lane does not do: direct video files take the stock
        // extraction route, images stay on the transfer lane unchanged. This
        // intentionally overrides the global EXTRACT_AUDIO preference, so the
        // dialog answer can't leak into unrelated quick-download items —
        // inheriting the global pref produced `-f direct` on the stock route.
        val wantsAudio = extractAudio && ext in DIRECT_VIDEO_EXTS
        return DownloadUtil.DownloadPreferences.createFromPreferences().copy(
            galleryDirect = true,
            extractAudio = wantsAudio,
            newTitle = item.filename.substringBeforeLast('.').ifBlank {
                item.url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
            },
            // "direct" is not a yt-dlp format id; it must never reach `-f`
            // when the audio-extraction route runs.
            formatIdString = if (wantsAudio) "" else "direct",
            referer = item.referer,
        )
    }

    /** Resolve via gallery → instagram embed → sniff. No enqueue; the caller
     * shows what was found and enqueues only after the user confirms. */
    suspend fun resolve(context: Context, url: String): Result<Resolved> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cookiesFile = cookiesFile()

                val (source, items) =
                    runCatching {
                        val (site, resolved) =
                            GhostEngine.galleryResolve(url, cookiesFile).getOrThrow()
                        if (resolved.isNotEmpty()) "gallery:$site" to resolved
                        else "sniff" to GhostEngine.sniffMedia(url).getOrDefault(emptyList())
                    }.recoverCatching { galleryError ->
                        if (url.contains("instagram.com")) {
                            val resolved = GhostEngine.instagramEmbed(url).getOrThrow()
                            if (resolved.isEmpty()) throw galleryError
                            "instagram-embed" to resolved
                        } else {
                            throw galleryError
                        }
                    }.getOrThrow()

                if (items.isEmpty()) error("no media found on this page")
                Resolved(url, items, source)
            }
        }

    /** Enqueue an already-resolved item list (the post-confirm step). */
    suspend fun enqueueResolved(
        downloader: DownloaderV2,
        resolved: Resolved,
        extractAudio: Boolean = false,
    ): Result<Outcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                var queued = 0
                resolved.items.forEach { item ->
                    runCatching {
                        downloader.enqueue(
                            Task(
                                url = item.url,
                                preferences = directPreferences(item, extractAudio),
                            )
                        )
                        queued++
                    }.onFailure { Log.e(TAG, "enqueue failed for ${item.url}: ${it.message}") }
                }
                if (queued == 0) error("could not queue any of the resolved items")
                Outcome(queued, resolved.source)
            }
        }

    /** Resolve via gallery → instagram embed → sniff, then enqueue each item. */
    suspend fun enqueue(context: Context, url: String, downloader: DownloaderV2): Result<Outcome> =
        resolve(context, url).mapCatching { enqueueResolved(downloader, it).getOrThrow() }

    /** Plain default-preferences enqueue for URLs no extractor claims. */
    suspend fun enqueueDefault(context: Context, url: String, downloader: DownloaderV2): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                downloader.enqueue(
                    Task(
                        url = url,
                        preferences = DownloadUtil.DownloadPreferences.createFromPreferences(),
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
        enqueue(context, url, downloader).recoverCatching {
            enqueueDefault(context, url, downloader).getOrThrow()
            Outcome(1, "default")
        }
}
