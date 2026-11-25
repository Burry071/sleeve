package com.junkfood.seal

import com.junkfood.seal.download.engine.finalizePublishedPaths
import com.junkfood.seal.download.engine.removeSplitMaster
import com.junkfood.seal.download.engine.resolvePublishedPaths
import com.junkfood.seal.util.SplitHistoryPolicy
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chapter split leaves the unsplit master beside its parts and the media
 * scanner still reports it (it was indexed before the delete), so the history
 * insert used to add the whole book next to every chapter.
 */
class SplitHistoryTest {

    private val master = "/storage/emulated/0/Download/Sleeve/Book/Book.mp3"
    private val first = "/storage/emulated/0/Download/Sleeve/Book/01 - Intro.mp3"
    private val second = "/storage/emulated/0/Download/Sleeve/Book/02 - End.mp3"

    @Test
    fun `the unsplit parent never reaches split history`() {
        assertEquals(listOf(first, second), SplitHistoryPolicy.chapterPaths(listOf(master, first, second), master))
    }

    @Test
    fun `without a named master every scanned path is a chapter`() {
        assertEquals(listOf(master, first, second), SplitHistoryPolicy.chapterPaths(listOf(master, first, second), null))
    }

    @Test
    fun `a master outside the list changes nothing`() {
        assertEquals(listOf(first, second), SplitHistoryPolicy.chapterPaths(listOf(first, second), master))
    }

    @Test
    fun `single file finish line scans only produced file and skips directory walk`() {
        val dir = Files.createTempDirectory("sleeve-finish-isolated").toFile()
        try {
            val mine = File(dir, "video_a.mp4").apply { writeText("bytes-a") }
            val concurrent = File(dir, "video_b.mp4").apply { writeText("bytes-b") }
            var scannedExact: List<String> = emptyList()
            var fallbackInvoked = false

            val result =
                resolvePublishedPaths(
                    produced = mine,
                    exported = null,
                    splitByChapter = false,
                    scanExact = { scannedExact = it },
                    fallbackScan = {
                        fallbackInvoked = true
                        listOf(mine.absolutePath, concurrent.absolutePath)
                    },
                )

            assertEquals(listOf(mine.absolutePath), result)
            assertEquals(listOf(mine.absolutePath), scannedExact)
            assertFalse("Directory walk must not run for a named single output", fallbackInvoked)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `exported m4b wins over produced source without walking directory`() {
        val dir = Files.createTempDirectory("sleeve-finish-m4b").toFile()
        try {
            val exported = File(dir, "book.m4b").apply { writeText("m4b-bytes") }
            val deletedSource = File(dir, "book.m4a") // already deleted after rename
            var scannedExact: List<String> = emptyList()
            var fallbackInvoked = false

            val result =
                resolvePublishedPaths(
                    produced = deletedSource,
                    exported = exported,
                    splitByChapter = false,
                    scanExact = { scannedExact = it },
                    fallbackScan = {
                        fallbackInvoked = true
                        emptyList()
                    },
                )

            assertEquals(listOf(exported.absolutePath), result)
            assertEquals(listOf(exported.absolutePath), scannedExact)
            assertFalse(fallbackInvoked)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `chapter split and unnamed output fall back to directory scan`() {
        val dir = Files.createTempDirectory("sleeve-finish-split").toFile()
        try {
            val produced = File(dir, "master.mp3").apply { writeText("master") }
            var exactCalled = false

            val splitResult =
                resolvePublishedPaths(
                    produced = produced,
                    exported = null,
                    splitByChapter = true,
                    scanExact = { exactCalled = true },
                    fallbackScan = { listOf(first, second) },
                )
            assertEquals(listOf(first, second), splitResult)
            assertFalse(exactCalled)

            val unnamedResult =
                resolvePublishedPaths(
                    produced = null,
                    exported = null,
                    splitByChapter = false,
                    scanExact = { exactCalled = true },
                    fallbackScan = { listOf(first) },
                )
            assertEquals(listOf(first), unnamedResult)
            assertFalse(exactCalled)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `private mode excludes published paths from disk backfill before returning empty`() {
        val excluded = mutableListOf<String>()
        var historyInserted = false

        val result =
            finalizePublishedPaths(
                paths = listOf("/storage/emulated/0/Download/Sleeve/secret.mp4"),
                privateMode = true,
                splitByChapter = false,
                masterPath = null,
                excludeHistoryPaths = { excluded.addAll(it) },
                insertSplit = {
                    historyInserted = true
                    it
                },
                insertStandard = {
                    historyInserted = true
                    it
                },
                emptyError = { IllegalStateException("empty") },
            )

        assertTrue(result.isSuccess)
        assertEquals(emptyList<String>(), result.getOrNull())
        assertEquals(listOf("/storage/emulated/0/Download/Sleeve/secret.mp4"), excluded)
        assertFalse(historyInserted)
    }

    @Test
    fun `removeSplitMaster deletes unsplit master only when a sibling chapter landed`() {
        val dir = Files.createTempDirectory("sleeve-split-master").toFile()
        try {
            val startedAtMs = System.currentTimeMillis() - 10_000L
            val masterFile = File(dir, "Book.mp3").apply { writeText("full-book") }

            assertFalse(removeSplitMaster(masterFile, dir.absolutePath, startedAtMs))
            assertTrue(masterFile.isFile)

            val chapterFile = File(dir, "01 - Intro.mp3").apply { writeText("chapter-1") }
            chapterFile.setLastModified(System.currentTimeMillis())

            assertTrue(removeSplitMaster(masterFile, dir.absolutePath, startedAtMs))
            assertFalse(masterFile.exists())
            assertTrue(chapterFile.isFile)
        } finally {
            dir.deleteRecursively()
        }
    }
}
