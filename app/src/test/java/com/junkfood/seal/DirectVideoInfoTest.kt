package com.junkfood.seal

import com.junkfood.seal.util.DownloadUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The direct lane synthesizes VideoInfo without yt-dlp extraction. Its size
 * metadata must come only from the HEAD probe's Content-Length: a known
 * length maps onto fileSizeApprox (so the queue card stops showing a
 * defaulting "0.00 MB"), and an unknown length stays 0 — never invented.
 */
class DirectVideoInfoTest {

    private val url = "https://cdn.example/a/clip.mp4"

    @Test
    fun `known content length maps onto fileSizeApprox`() {
        val info = DownloadUtil.buildDirectVideoInfo(url, "clip", contentLength = 2_516_480L)
        assertNotNull(info)
        assertEquals(2_516_480L, (info!!.fileSizeApprox ?: 0.0).toLong())
        assertEquals("sleeve-direct", info.extractor)
        assertEquals("clip.mp4", info.filename)
        assertEquals("clip", info.title)
    }

    @Test
    fun `unknown content length stays zero and is never invented`() {
        val info = DownloadUtil.buildDirectVideoInfo(url, "clip", contentLength = 0L)
        assertNotNull(info)
        assertEquals(0.0, info!!.fileSizeApprox ?: 0.0, 0.0)
    }

    @Test
    fun `negative server length is treated as unknown`() {
        val info = DownloadUtil.buildDirectVideoInfo(url, "clip", contentLength = -1L)
        assertNotNull(info)
        assertEquals(0.0, info!!.fileSizeApprox ?: 0.0, 0.0)
    }
}
