package com.junkfood.seal

import com.junkfood.seal.download.engine.Nm3u8TransferEngine
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Nm3u8WorkDirTest {

    @Test
    fun `workDirForTask isolates path traversal payloads under cacheDir`() {
        val cacheDir = Files.createTempDirectory("sleeve-nm3re-cache").toFile()
        try {
            val base = cacheDir.canonicalPath + File.separator
            val payloads =
                listOf(
                    "http://x/../../../files",
                    "../../shared_prefs/secret",
                    "../..",
                    "a/b/c/../../../outside",
                    "foo\\..\\..\\bar",
                    "https://example.com/watch?v=abc&list=1__123",
                    "",
                )
            for (taskId in payloads) {
                val dir = Nm3u8TransferEngine.workDirForTask(cacheDir, taskId)
                assertTrue(
                    "Expected $dir to stay inside $base for taskId=$taskId",
                    dir.canonicalPath.startsWith(base),
                )
                assertEquals(cacheDir.canonicalFile, dir.parentFile?.canonicalFile)
                assertTrue(dir.name.matches(Regex("^nm3re_[0-9a-f]{64}$")))
            }
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `workDirForTask is deterministic and collision free for distinct task ids`() {
        val cacheDir = Files.createTempDirectory("sleeve-nm3re-hash").toFile()
        try {
            val first = Nm3u8TransferEngine.workDirForTask(cacheDir, "https://example.com/a.m3u8")
            val repeat = Nm3u8TransferEngine.workDirForTask(cacheDir, "https://example.com/a.m3u8")
            val second = Nm3u8TransferEngine.workDirForTask(cacheDir, "https://example.com/b.m3u8")
            assertEquals(first.canonicalPath, repeat.canonicalPath)
            assertNotEquals(first.canonicalPath, second.canonicalPath)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `copyAtomically copies bytes cleanly and preserves target on empty source`() {
        val root = Files.createTempDirectory("sleeve-nm3re-copy").toFile()
        try {
            val source = File(root, "media.mp4").apply { writeText("muxed-hls-stream") }
            val target = File(root, "out/final.mp4")

            assertTrue(Nm3u8TransferEngine.copyAtomically(source, target))
            assertTrue(target.isFile)
            assertEquals("muxed-hls-stream", target.readText())
            assertFalse(File(target.parentFile, "${target.name}.tmp").exists())

            val emptySource = File(root, "empty.mp4").apply { writeBytes(ByteArray(0)) }
            assertFalse(Nm3u8TransferEngine.copyAtomically(emptySource, target))
            assertEquals("muxed-hls-stream", target.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `buildHeaderArgs formats referer and user agent strips crlf and skips blanks`() {
        assertEquals(
            listOf(
                "--header",
                "Referer: https://example.com/watch",
                "--header",
                "User-Agent: Mozilla/5.0",
            ),
            Nm3u8TransferEngine.buildHeaderArgs(
                referer = "https://example.com/watch",
                userAgent = "Mozilla/5.0",
            ),
        )
        assertEquals(
            listOf(
                "--header",
                "Referer: https://example.com/watchX-Evil: 1",
                "--header",
                "User-Agent: Mozilla/5.0Injected: true",
            ),
            Nm3u8TransferEngine.buildHeaderArgs(
                referer = " https://example.com/watch\r\nX-Evil: 1 ",
                userAgent = "Mozilla/5.0\r\nInjected: true\n",
            ),
        )
        assertEquals(
            listOf("--header", "User-Agent: Mozilla/5.0"),
            Nm3u8TransferEngine.buildHeaderArgs(referer = "   \r\n  ", userAgent = "Mozilla/5.0"),
        )
        assertEquals(
            emptyList<String>(),
            Nm3u8TransferEngine.buildHeaderArgs(referer = "", userAgent = "  "),
        )
    }
}
