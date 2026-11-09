package com.junkfood.seal

import com.junkfood.seal.util.migrateLegacyCookieFile
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieFileMigrationTest {

    private fun tempDir(): File = Files.createTempDirectory("cookie-migration").toFile()

    @Test
    fun `legacy cache copy is moved into filesDir`() {
        val root = tempDir()
        val legacy = File(root, "cache/cookies.txt").apply {
            parentFile?.mkdirs()
            writeText("session=abc")
        }
        val target = File(root, "files/cookies.txt").apply { parentFile?.mkdirs() }

        assertTrue(migrateLegacyCookieFile(legacy, target))
        assertEquals("session=abc", target.readText())
        assertFalse(legacy.exists())
    }

    @Test
    fun `an existing filesDir copy wins and the legacy file is dropped`() {
        val root = tempDir()
        val legacy = File(root, "cache/cookies.txt").apply {
            parentFile?.mkdirs()
            writeText("stale=old")
        }
        val target = File(root, "files/cookies.txt").apply {
            parentFile?.mkdirs()
            writeText("session=new")
        }

        assertFalse(migrateLegacyCookieFile(legacy, target))
        assertEquals("session=new", target.readText())
        assertFalse(legacy.exists())
    }

    @Test
    fun `nothing happens when there is no legacy file`() {
        val root = tempDir()
        val target = File(root, "files/cookies.txt")

        assertFalse(migrateLegacyCookieFile(File(root, "cache/cookies.txt"), target))
        assertFalse(target.exists())
    }

    @Test
    fun `a failed copy keeps the legacy file usable`() {
        val root = tempDir()
        val legacy = File(root, "cache/cookies.txt").apply {
            parentFile?.mkdirs()
            writeText("session=abc")
        }
        // Target parent is a regular file, so the copy cannot land.
        val blocker = File(root, "files").apply { writeText("not a directory") }
        val target = File(blocker, "cookies.txt")

        assertFalse(migrateLegacyCookieFile(legacy, target))
        assertTrue(legacy.exists())
        assertEquals("session=abc", legacy.readText())
        assertFalse(target.exists())
    }
}
