package com.junkfood.seal

import com.junkfood.seal.util.ArchiveIndex
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveIndexTest {

    @get:Rule val tmp = TemporaryFolder()

    private val id = "dQw4w9WgXcQ"

    private fun archive(text: String): File =
        tmp.newFile().apply {
            writeText(text)
        }

    @Test
    fun `an exact whole line matches`() {
        val file = archive("youtube $id\n")
        assertTrue(ArchiveIndex.contains(file, "Youtube", id))
    }

    @Test
    fun `a prefix or suffix of the id does not match`() {
        val file = archive("youtube $id\n")
        assertFalse(ArchiveIndex.contains(file, "Youtube", "dQw4w9WgXc"))
        assertFalse(ArchiveIndex.contains(file, "Youtube", "dQw4w9WgXcQQ"))
    }

    @Test
    fun `a neighbouring field is not a match`() {
        val file = archive("youtube-id $id\nyoutube $id extra\nxyoutube $id\n")
        assertFalse(ArchiveIndex.contains(file, "Youtube", id))
    }

    @Test
    fun `the archive's lowercased extractor key is what matches`() {
        val lowercased = archive("youtube $id\n")
        assertTrue(ArchiveIndex.contains(lowercased, "Youtube", id))

        // A file whose line kept the display casing must not match: yt-dlp
        // writes the lowercased IE key.
        val verbatim = archive("Youtube $id\n")
        assertFalse(ArchiveIndex.contains(verbatim, "Youtube", id))
    }

    @Test
    fun `a length change rebuilds the index`() {
        val file = archive("youtube AAAAAAAAAAA\n")
        assertTrue(ArchiveIndex.contains(file, "Youtube", "AAAAAAAAAAA"))

        val stamp = file.lastModified()
        file.writeText("youtube AAAAAAAAAAA\nyoutube BBBBBBBBBBB\n")
        file.setLastModified(stamp)

        assertTrue(ArchiveIndex.contains(file, "Youtube", "BBBBBBBBBBB"))
    }

    @Test
    fun `an mtime change rebuilds the index`() {
        val file = archive("youtube AAAAAAAAAAA\n")
        assertTrue(ArchiveIndex.contains(file, "Youtube", "AAAAAAAAAAA"))

        val stamp = file.lastModified()
        // Same length, different line: only the timestamp can tell them apart.
        file.writeText("youtube BBBBBBBBBBB\n")
        file.setLastModified(stamp + 60_000L)

        assertTrue(ArchiveIndex.contains(file, "Youtube", "BBBBBBBBBBB"))
        assertFalse(ArchiveIndex.contains(file, "Youtube", "AAAAAAAAAAA"))
    }

    @Test
    fun `a missing file reads as empty`() {
        assertFalse(ArchiveIndex.contains(File(tmp.root, "absent.txt"), "Youtube", id))
    }
}
