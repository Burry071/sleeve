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

    private fun cookiesFile(): String =
        runCatching {
            val content = DownloadUtil.getCookiesContentFromDatabase().getOrDefault("")
            if (content.isEmpty()) return@runCatching ""
            val file = with(FileUtil) { App.context.getCookiesFile() }
            FileUtil.writeContentToFile(content, file)
            file.absolutePath
        }.getOrDefault("")

    private fun directPreferences(item: GalleryItem): DownloadUtil.DownloadPreferences =
        DownloadUtil.DownloadPreferences.createFromPreferences().copy(
            galleryDirect = true,
            newTitle = item.filename.substringBeforeLast('.').ifBlank {
                item.url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
            },
            formatIdString = "direct",
        )

    /** Resolve via gallery → instagram embed → sniff, then enqueue each item. */
    suspend fun enqueue(context: Context, url: String, downloader: DownloaderV2): Result<Outcome> =
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

                var queued = 0
                items.forEach { item ->
                    runCatching {
                        downloader.enqueue(
                            Task(url = item.url, preferences = directPreferences(item))
                        )
                        queued++
                    }.onFailure { Log.e(TAG, "enqueue failed for ${item.url}: ${it.message}") }
                }
                if (queued == 0) error("could not queue any of the resolved items")
                Outcome(queued, source)
            }
        }

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
