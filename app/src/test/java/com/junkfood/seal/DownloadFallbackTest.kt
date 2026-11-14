package com.junkfood.seal

import com.junkfood.seal.ghostengine.GhostEngine.GalleryItem
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.SleeveQuickDownload
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The N_m3u8DL-RE lane must never replace a file another app owns. */
class UniqueFileNameTest {

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a free name is returned unchanged`() {
        val dir = folder.newFolder().absolutePath
        assertEquals("clip.mp4", DownloadUtil.uniqueFileName(dir, "clip.mp4"))
    }

    @Test
    fun `an existing name gets a numbered sibling`() {
        val dir = folder.newFolder()
        File(dir, "clip.mp4").writeText("x")

        assertEquals("clip (1).mp4", DownloadUtil.uniqueFileName(dir.absolutePath, "clip.mp4"))
    }

    @Test
    fun `numbering skips every name already taken`() {
        val dir = folder.newFolder()
        File(dir, "clip.mp4").writeText("x")
        File(dir, "clip (1).mp4").writeText("x")

        assertEquals("clip (2).mp4", DownloadUtil.uniqueFileName(dir.absolutePath, "clip.mp4"))
    }

    @Test
    fun `a name without an extension still gets a suffix`() {
        val dir = folder.newFolder()
        File(dir, "download").writeText("x")

        assertEquals("download (1)", DownloadUtil.uniqueFileName(dir.absolutePath, "download"))
    }

    @Test
    fun `an unrelated file does not change the answer`() {
        val dir = folder.newFolder()
        File(dir, "other.mp4").writeText("x")

        assertEquals("clip.mp4", DownloadUtil.uniqueFileName(dir.absolutePath, "clip.mp4"))
    }
}

/**
 * gallery-dl has a module for a minority of sites; the sniffer is the only lane
 * that can see a bare `<video src>`. A gallery miss on a generic page must try
 * it before the caller drops to yt-dlp.
 */
class QuickDownloadResolveTest {

    private val image = GalleryItem("https://cdn.example.com/a.jpg", "a.jpg", "", "sniff")

    private fun galleryMiss() =
        Result.failure<Pair<String, List<GalleryItem>>>(
            IllegalStateException("no site module matched")
        )

    @Test
    fun `a gallery miss on a generic page reaches the sniffer`() = runBlocking {
        var sniffed = 0
        val (source, items) =
            SleeveQuickDownload.resolveItems(
                url = "https://example.com/post/1",
                cookiesFile = "",
                gallery = { _, _ -> galleryMiss() },
                embed = { error("instagram embed must not run for a generic host") },
                sniff = {
                    sniffed++
                    Result.success(listOf(image))
                },
            )

        assertEquals("sniff", source)
        assertEquals(listOf(image), items)
        assertEquals(1, sniffed)
    }

    @Test
    fun `a gallery hit wins and the sniffer is never asked`() = runBlocking {
        val galleryItem = GalleryItem("https://cdn.example.com/g.jpg", "g.jpg", "", "pixiv")
        var sniffed = 0
        val (source, items) =
            SleeveQuickDownload.resolveItems(
                url = "https://www.pixiv.net/artworks/1",
                cookiesFile = "",
                gallery = { _, _ -> Result.success("pixiv" to listOf(galleryItem)) },
                embed = { error("unused") },
                sniff = {
                    sniffed++
                    Result.success(emptyList())
                },
            )

        assertEquals("gallery:pixiv", source)
        assertEquals(listOf(galleryItem), items)
        assertEquals(0, sniffed)
    }

    @Test
    fun `a gallery hit with no items still falls to the sniffer`() = runBlocking {
        val (source, items) =
            SleeveQuickDownload.resolveItems(
                url = "https://example.com/post/2",
                cookiesFile = "",
                gallery = { _, _ -> Result.success("example" to emptyList()) },
                embed = { error("unused") },
                sniff = { Result.success(listOf(image)) },
            )

        assertEquals("sniff", source)
        assertEquals(listOf(image), items)
    }

    @Test
    fun `an instagram gallery miss goes to the anonymous embed`() = runBlocking {
        val embedItem =
            GalleryItem("https://cdn.example.com/i.jpg", "i.jpg", "", "instagram-embed")
        var sniffed = 0
        val (source, items) =
            SleeveQuickDownload.resolveItems(
                url = "https://www.instagram.com/p/abc/",
                cookiesFile = "",
                gallery = { _, _ -> galleryMiss() },
                embed = { Result.success(listOf(embedItem)) },
                sniff = {
                    sniffed++
                    Result.success(emptyList())
                },
            )

        assertEquals("instagram-embed", source)
        assertEquals(listOf(embedItem), items)
        assertEquals(0, sniffed)
    }

    @Test
    fun `when both gallery and sniffer miss the gallery error survives`() = runBlocking {
        val result =
            runCatching {
                SleeveQuickDownload.resolveItems(
                    url = "https://example.com/post/3",
                    cookiesFile = "",
                    gallery = { _, _ -> galleryMiss() },
                    embed = { error("unused") },
                    sniff = { Result.success(emptyList()) },
                )
            }

        assertEquals("no site module matched", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a failing sniffer falls back to the gallery error`() = runBlocking {
        val result =
            runCatching {
                SleeveQuickDownload.resolveItems(
                    url = "https://example.com/post/4",
                    cookiesFile = "",
                    gallery = { _, _ -> galleryMiss() },
                    embed = { error("unused") },
                    sniff = { Result.failure(IllegalStateException("sniff timed out")) },
                )
            }

        // An empty or failed sniff keeps the gallery error, so the caller's
        // fallback reports why the resolver stopped.
        assertEquals("no site module matched", result.exceptionOrNull()?.message)
    }

    @Test
    fun `an instagram embed miss keeps the gallery error, not the embed one`() = runBlocking {
        val result =
            runCatching {
                SleeveQuickDownload.resolveItems(
                    url = "https://www.instagram.com/reel/abc/",
                    cookiesFile = "",
                    gallery = { _, _ -> galleryMiss() },
                    embed = { Result.success(emptyList()) },
                    sniff = { Result.success(listOf(image)) },
                )
            }

        assertEquals("no site module matched", result.exceptionOrNull()?.message)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }
}
