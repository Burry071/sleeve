package com.junkfood.seal

import com.junkfood.seal.download.CompletedNote
import com.junkfood.seal.util.Chapter
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.junkfood.seal.util.M4bExportResult
import com.junkfood.seal.util.M4bMetadata
import com.junkfood.seal.util.M4bPlan
import com.junkfood.seal.util.M4bTagOverrides
import com.junkfood.seal.util.M4bTags
import com.junkfood.seal.util.VideoClip
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.estimatedOutputBytes
import com.junkfood.seal.util.hasRoomFor
import com.junkfood.seal.util.tagArtist
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class M4bPlanTest {

    @get:Rule val tmp = TemporaryFolder()

    private val chapters = listOf(Chapter(title = "One", startTime = 0.0, endTime = 60.0))

    private fun plan(source: File, copyAudio: Boolean = true, ownedDir: Boolean = false) =
        M4bPlan(
            source = source,
            chapters = chapters,
            tags = M4bTags("T", "A", "", "", null),
            copyAudio = copyAudio,
            ownedDir = ownedDir,
        )

    private fun applies(
        exportM4b: Boolean = true,
        extractAudio: Boolean = true,
        chapters: List<Chapter>? = this.chapters,
        vcodec: String? = null,
        splitByChapter: Boolean = false,
        videoClips: Boolean = false,
    ): Boolean =
        M4bPlan.applies(
            VideoInfo(chapters = chapters, vcodec = vcodec),
            DownloadPreferences.EMPTY.copy(
                exportM4b = exportM4b,
                extractAudio = extractAudio,
                splitByChapter = splitByChapter,
                videoClips = if (videoClips) listOf(VideoClip(0, 10)) else emptyList(),
            ),
        )

    @Test
    fun `out and scratch sit beside the source and end in m4b`() {
        val dir = tmp.newFolder("book")
        val p = plan(File(dir, "Book.m4a"))

        assertEquals(File(dir, "Book.m4b"), p.out)
        assertEquals(File(dir, "Book.m4b.tmp"), p.scratch)
    }

    @Test
    fun `a taken name falls through to the next free one`() {
        val dir = tmp.newFolder("taken")
        val p = plan(File(dir, "Book.m4a"))
        p.out.writeText("existing")

        assertEquals(File(dir, "Book (2).m4b"), p.freeOut())

        File(dir, "Book (2).m4b").writeText("existing")
        assertEquals(File(dir, "Book (3).m4b"), p.freeOut())
    }

    @Test
    fun `an exhausted name space refuses instead of overwriting`() {
        val dir = tmp.newFolder("full")
        val p = plan(File(dir, "Book.m4a"))
        p.out.writeText("existing")
        (2..100).forEach { File(dir, "Book ($it).m4b").writeText("existing") }

        assertNull(p.freeOut())
    }

    @Test
    fun `the predicate needs the toggle, audio, chapters and no reshaping`() {
        assertTrue(applies())
        assertFalse("toggle off", applies(exportM4b = false))
        assertFalse("no chapters", applies(chapters = null))
        assertFalse("empty chapters", applies(chapters = emptyList()))
        assertFalse("split by chapter wins", applies(splitByChapter = true))

        // A video source is only audio when the switch extracts audio.
        assertFalse("video without extractAudio", applies(extractAudio = false))
        assertTrue("audio-only stream", applies(extractAudio = false, vcodec = "none"))
    }

    @Test
    fun `exactly one absolute file inside the folder is accepted`() {
        val dir = tmp.newFolder("lane")
        val produced = File(dir, "Book.m4a").apply { writeText("audio") }
        val report = File(dir, "report.txt").apply { writeText(produced.absolutePath + "\n") }

        assertEquals(produced.canonicalFile, M4bPlan.producedFrom(report, dir))
    }

    @Test
    fun `a multi-line report is refused`() {
        val dir = tmp.newFolder("multi")
        val first = File(dir, "a.m4a").apply { writeText("a") }
        val second = File(dir, "b.m4a").apply { writeText("b") }
        val report =
            File(dir, "report.txt").apply {
                writeText(first.absolutePath + "\n" + second.absolutePath + "\n")
            }

        assertNull(M4bPlan.producedFrom(report, dir))
    }

    @Test
    fun `a relative path, a missing file or a missing report is refused`() {
        val dir = tmp.newFolder("refused")

        assertNull(
            M4bPlan.producedFrom(File(dir, "r1.txt").apply { writeText("Book.m4a") }, dir)
        )
        assertNull(
            M4bPlan.producedFrom(
                File(dir, "r2.txt").apply { writeText(File(dir, "gone.m4a").absolutePath) },
                dir,
            )
        )
        assertNull(M4bPlan.producedFrom(File(dir, "absent.txt"), dir))
    }

    @Test
    fun `a file outside the lane folder is refused`() {
        val lane = tmp.newFolder("lane2")
        val elsewhere = tmp.newFolder("elsewhere")
        val stray = File(elsewhere, "Book.m4a").apply { writeText("audio") }
        val report = File(lane, "report.txt").apply { writeText(stray.absolutePath) }

        assertNull(M4bPlan.producedFrom(report, lane))
    }

    @Test
    fun `only a failed export carries the note`() {
        assertEquals(
            CompletedNote.M4B_NOT_EXPORTED,
            M4bExportResult.Failed.noteOnFailure(),
        )
        assertNull(M4bExportResult.Cancelled.noteOnFailure())
        assertNull(M4bExportResult.Verified(File("x.m4b")).noteOnFailure())
    }

    @Test
    fun `copying estimates the source plus a small margin`() {
        val source = tmp.newFile("s.m4a").apply { writeBytes(ByteArray(1_000_000)) }
        assertEquals(1_050_000L, estimatedOutputBytes(source, copyAudio = true, durationMs = 0L))
    }

    @Test
    fun `transcoding estimates by duration and refuses an unknown one`() {
        val source = tmp.newFile("t.mp3")
        // 128 kbit/s for 10 s, plus the 15% margin.
        assertEquals(184_000L, estimatedOutputBytes(source, copyAudio = false, durationMs = 10_000L))
        assertEquals(0L, estimatedOutputBytes(source, copyAudio = false, durationMs = 0L))
    }

    @Test
    fun `free space is only refused when it is known to be short`() {
        val dir = tmp.newFolder("space")
        assertTrue(hasRoomFor(dir, required = 0L))
        assertTrue(hasRoomFor(dir, required = 1L))
        assertFalse(hasRoomFor(dir, required = Long.MAX_VALUE))
    }

    @Test
    fun `a url task id yields one flat report file inside the cache dir`() {
        val cacheDir = tmp.newFolder("cache")
        val taskId = "https://www.audible.com/pd/Dune/B002V1OF70?qid=123__942405748"

        val report = M4bPlan.reportFile(cacheDir, taskId)

        assertEquals(cacheDir, report.parentFile)
        assertTrue(report.name.matches(Regex("""m4b_report_[0-9a-f]{24}\.txt""")))
        report.writeText("/sdcard/Download/Sleeve/Dune.m4a")
        assertEquals("/sdcard/Download/Sleeve/Dune.m4a", report.readText())
    }

    @Test
    fun `the same task always maps to the same report file`() {
        val cacheDir = tmp.newFolder("cache")
        val taskId = "https://example.com/a/b?c=d__1"

        assertEquals(M4bPlan.reportFile(cacheDir, taskId), M4bPlan.reportFile(cacheDir, taskId))
        assertNotEquals(
            M4bPlan.reportFile(cacheDir, taskId),
            M4bPlan.reportFile(cacheDir, taskId + "x"),
        )
    }
}

class M4bTagsTest {

    @Test
    fun `the artist override is used and the narrator suffix dropped`() {
        val info = VideoInfo(title = "Book", uploader = "Uploader")
        val tags =
            M4bTags.from(
                info,
                M4bTagOverrides(
                    artist = "Tamsyn Muir (narrated by Moira Quirk)",
                    albumArtist = "Tamsyn Muir",
                    album = "",
                    track = 3,
                ),
            )

        assertEquals("Book", tags.title)
        assertEquals("Tamsyn Muir", tags.artist)
        assertEquals("Tamsyn Muir", tags.albumArtist)
        assertEquals("", tags.album)
        assertEquals(3, tags.track)
    }

    @Test
    fun `without overrides the uploader names the artist and album stays absent`() {
        val tags =
            M4bTags.from(VideoInfo(title = "Book", channel = "Channel"), overrides = null)

        assertEquals("Channel", tags.artist)
        assertEquals("", tags.albumArtist)
        assertEquals("", tags.album)
        assertNull(tags.track)
    }

    @Test
    fun `a blank override falls back to the uploader`() {
        val tags =
            M4bTags.from(
                VideoInfo(uploader = "Someone"),
                M4bTagOverrides(artist = "  "),
            )
        assertEquals("Someone", tags.artist)
    }

    @Test
    fun `only a trailing narrator suffix is stripped`() {
        assertEquals("Narrated", tagArtist("  Narrated  "))
        assertEquals("A", tagArtist("A (Narrated By Bob)"))
        assertEquals("A (narrated by Bob) and more", tagArtist("A (narrated by Bob) and more"))
        assertEquals("", tagArtist(null))
    }
}

class M4bMetadataTest {

    private fun text(chapters: List<Chapter>, durationMs: Long = 120_000L) =
        M4bMetadata.chaptersFile(chapters, durationMs)

    @Test
    fun `chapters become one FFMETADATA block each`() {
        val expected =
            """
            ;FFMETADATA1
            [CHAPTER]
            TIMEBASE=1/1000
            START=0
            END=60000
            title=Intro
            [CHAPTER]
            TIMEBASE=1/1000
            START=60000
            END=120000
            title=Two
            """
                .trimIndent() + "\n"

        assertEquals(
            expected,
            text(
                listOf(
                    Chapter("Intro", 0.0, 60.0),
                    Chapter("Two", 60.0, 120.0),
                )
            ),
        )
    }

    @Test
    fun `a missing end falls back to the next start, then the source length`() {
        val out =
            text(
                listOf(
                    Chapter("One", 0.0, null),
                    Chapter("Two", 30.0, null),
                ),
                durationMs = 45_000L,
            )

        assertTrue(out.contains("END=30000"))
        assertTrue(out.contains("END=45000"))
    }

    @Test
    fun `an anonymous chapter is numbered and special characters escaped`() {
        val out =
            text(listOf(Chapter(null, 0.0, 10.0), Chapter("A=B; C#D", 10.0, 20.0)))

        assertTrue(out.contains("title=Chapter 1"))
        assertTrue(out.contains("title=A\\=B\\; C\\#D"))
    }

    @Test
    fun `a chapter with nothing to say is dropped`() {
        val out =
            text(listOf(Chapter(null, null, null), Chapter("Kept", 0.0, 10.0)))

        assertFalse(out.contains("Chapter 1"))
        assertTrue(out.contains("title=Kept"))
    }
}
