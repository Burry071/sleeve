package com.junkfood.seal

import com.junkfood.seal.download.engine.DirectMedia
import com.junkfood.seal.download.engine.isSafeRemoteUrl
import com.junkfood.seal.download.engine.replaceFileSafely
import com.junkfood.seal.util.DownloadUtil
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `isSafeRemoteUrl blocks ipv6 loopback private link-local and ipv4-mapped bypasses`() {
        val blocked =
            listOf(
                "http://[::1]:8080/a.mp4",
                "http://[::1]/a.mp4",
                "http://[0:0:0:0:0:0:0:1]/a.mp4",
                "http://[::]/a.mp4",
                "http://[fc00::1]/a.mp4",
                "http://[fd12:3456:789a::1]/a.mp4",
                "http://[fe80::1]/a.mp4",
                "http://[fe80::1%25eth0]/a.mp4",
                "http://[::ffff:127.0.0.1]/a.mp4",
                "http://[::ffff:7f00:1]/a.mp4",
                "http://[::ffff:10.0.0.1]/a.mp4",
                "http://[::ffff:169.254.169.254]/latest/meta-data",
                "http://[::127.0.0.1]/a.mp4",
                "http://[64:ff9b::127.0.0.1]/a.mp4",
                "http://[2002:7f00:0001::1]/a.mp4",
            )
        for (candidate in blocked) {
            assertFalse("Expected IPv6 SSRF URL to be blocked: $candidate", isSafeRemoteUrl(candidate))
        }
    }

    @Test
    fun `isSafeRemoteUrl blocks userinfo localhost private cgnat and numeric ip encodings`() {
        val blocked =
            listOf(
                "http://safe.example@127.0.0.1/a.mp4",
                "http://user:pass@cdn.example/a.mp4",
                "http://127.0.0.1\\@safe.example/a.mp4",
                "http://localhost/a.mp4",
                "http://localhost:8080/a.mp4",
                "http://localhost./a.mp4",
                "http://sub.localhost/a.mp4",
                "http://printer.local/a.mp4",
                "http://metadata.google.internal/computeMetadata/v1/",
                "http://intranet/a.mp4",
                "http://127.0.0.1/a.mp4",
                "http://10.0.0.1/a.mp4",
                "http://172.16.0.1/a.mp4",
                "http://172.31.255.254/a.mp4",
                "http://192.168.1.1/a.mp4",
                "http://169.254.169.254/latest/meta-data",
                "http://0.0.0.0/a.mp4",
                "http://100.64.0.1/a.mp4",
                "http://100.127.255.254/a.mp4",
                "http://2130706433/a.mp4",
                "http://0x7f000001/a.mp4",
                "http://0177.0.0.1/a.mp4",
                "http://127.1/a.mp4",
                "http://127.0.1/a.mp4",
                "http://0/a.mp4",
                "file:///etc/passwd",
                "ftp://cdn.example/a.mp4",
                "javascript:alert(1)",
                "",
                "http://[invalid",
            )
        for (candidate in blocked) {
            assertFalse("Expected unsafe URL to be blocked: $candidate", isSafeRemoteUrl(candidate))
        }
    }

    @Test
    fun `isSafeRemoteUrl allows legitimate public domains and public ips without dns`() {
        val allowed =
            listOf(
                "https://cdn.example/a/clip.mp4",
                "http://example.com:8080/video.mp4",
                "https://cdn.example/a.mp4?sig=foo|bar&x=1",
                "https://1.1.1.1/a.mp4",
                "https://93.184.216.34/a.mp4",
                "https://172.32.0.1/a.mp4",
                "https://100.63.0.1/a.mp4",
                "https://100.128.0.1/a.mp4",
                "https://[2606:4700:4700::1111]/a.mp4",
                "https://[::ffff:8.8.8.8]/a.mp4",
            )
        for (candidate in allowed) {
            assertTrue("Expected public URL to be allowed: $candidate", isSafeRemoteUrl(candidate))
        }
    }

    @Test
    fun `buildDirectVideoInfo and fetchContentLength never hit network for unsafe urls`() {
        val hits = AtomicInteger(0)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 500
        val listener =
            Thread {
                while (!server.isClosed) {
                    runCatching {
                        server.accept().use { hits.incrementAndGet() }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        try {
            val localUrl = "http://127.0.0.1:${server.localPort}/secret.mp4"
            assertEquals(-1L, DirectMedia.fetchContentLength(localUrl))
            assertNull(DirectMedia.buildDirectVideoInfo(localUrl, "secret"))
            assertNull(DownloadUtil.buildDirectVideoInfo(localUrl, "secret"))
            assertEquals(0, hits.get())
        } finally {
            server.close()
            listener.join(1000)
        }
    }

    @Test
    fun `replaceFileSafely replaces valid files and preserves original on empty or missing source`() {
        val dir = Files.createTempDirectory("sleeve-replace-test").toFile()
        try {
            val target = File(dir, "clip.mp4").apply { writeText("original-bytes") }
            val missing = File(dir, "missing.mp4")
            assertFalse(replaceFileSafely(missing, target))
            assertTrue(target.exists())
            assertEquals("original-bytes", target.readText())

            val empty = File(dir, "empty.mp4").apply { writeBytes(ByteArray(0)) }
            assertFalse(replaceFileSafely(empty, target))
            assertTrue(target.exists())
            assertEquals("original-bytes", target.readText())

            val updated = File(dir, "clip.meta_tmp.mp4").apply { writeText("updated-with-metadata") }
            assertTrue(replaceFileSafely(updated, target))
            assertTrue(target.exists())
            assertEquals("updated-with-metadata", target.readText())
            assertFalse(updated.exists())
            assertEquals(listOf("clip.mp4", "empty.mp4"), dir.list()?.sorted())
        } finally {
            dir.deleteRecursively()
        }
    }
}
