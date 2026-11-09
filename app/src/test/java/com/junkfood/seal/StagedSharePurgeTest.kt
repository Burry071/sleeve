package com.junkfood.seal

import com.junkfood.seal.util.stagedShareDeletions
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StagedSharePurgeTest {

    private fun tempDir(): File = Files.createTempDirectory("staged-share").toFile()

    private fun stagedCopy(root: File, name: String, bytes: Int, modifiedAt: Long): File =
        File(root, name).apply {
            mkdirs()
            File(this, "video.mp4").writeBytes(ByteArray(bytes))
            setLastModified(modifiedAt)
        }

    @Test
    fun `only the newest two copies survive`() {
        val root = tempDir()
        val oldest = stagedCopy(root, "a", 1, 1_000)
        val middle = stagedCopy(root, "b", 1, 2_000)
        val newest = stagedCopy(root, "c", 1, 3_000)

        assertEquals(listOf(oldest), stagedShareDeletions(listOf(oldest, middle, newest)))
    }

    @Test
    fun `the size cap trims older copies`() {
        val root = tempDir()
        val oldest = stagedCopy(root, "a", 600, 1_000)
        val newest = stagedCopy(root, "b", 600, 2_000)

        assertEquals(
            listOf(oldest),
            stagedShareDeletions(listOf(oldest, newest), maxBytes = 1_000),
        )
    }

    @Test
    fun `the copy that just started is never deleted`() {
        val root = tempDir()
        val huge = stagedCopy(root, "a", 2_000, 1_000)
        val older = stagedCopy(root, "b", 1, 500)

        // Alone over budget, but deleting it would break the share in flight.
        assertEquals(emptyList<File>(), stagedShareDeletions(listOf(huge), maxBytes = 1_000))
        assertTrue(stagedShareDeletions(listOf(huge, older), maxBytes = 1_000).contains(older))
    }

    @Test
    fun `empty staging directory means nothing to delete`() {
        assertEquals(emptyList<File>(), stagedShareDeletions(emptyList()))
    }
}
