package com.junkfood.seal

import com.junkfood.seal.download.TaskFactory
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.junkfood.seal.util.Format
import com.junkfood.seal.util.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskFactoryConfigTest {

    private val silent = Format(formatId = "137", acodec = "none", vcodec = "avc1", ext = "mp4")
    private val audio = Format(formatId = "251", acodec = "opus", vcodec = "none", ext = "webm")
    private val muxed = Format(formatId = "18", acodec = "aac", vcodec = "avc1", ext = "mp4")

    private val info =
        VideoInfo(
            id = "abc",
            title = "Clip",
            formats = listOf(silent, audio, muxed),
            requestedFormats = listOf(silent, audio),
            originalUrl = "https://youtube.com/watch?v=abc",
        )

    private val base =
        DownloadPreferences.EMPTY.copy(
            downloadSubtitle = true,
            subtitleLanguage = "en",
            outputTemplate = "%(title)s.%(ext)s",
        )

    private fun create(
        formatList: List<Format>,
        suggested: Boolean = false,
        splitByChapter: Boolean = false,
        newTitle: String = "",
        subtitles: List<String> = emptyList(),
        autoCaptions: List<String> = emptyList(),
        audioOnly: Boolean = false,
    ) =
        TaskFactory.createWithConfigurations(
            videoInfo = info,
            formatList = formatList,
            videoClips = emptyList(),
            splitByChapter = splitByChapter,
            convertAudio = false,
            newTitle = newTitle,
            selectedSubtitles = subtitles,
            selectedAutoCaptions = autoCaptions,
            audioOnly = audioOnly,
            suggested = suggested,
            basePreferences = base,
        )

    @Test
    fun `suggested keeps the format choice live for yt-dlp but still carries every override`() {
        val stamped = create(formatList = listOf(silent, audio), suggested = true, splitByChapter = true)

        assertEquals("", stamped.task.preferences.formatIdString)
        assertTrue(stamped.task.preferences.splitByChapter)
        assertEquals(listOf("137"), stamped.state.viewState.videoFormats.orEmpty().map { it.formatId })
        assertEquals(listOf("251"), stamped.state.viewState.audioOnlyFormats.orEmpty().map { it.formatId })
    }

    @Test
    fun `an explicit pick joins video before audio`() {
        val stamped = create(formatList = listOf(audio, silent))

        assertEquals("137+251", stamped.task.preferences.formatIdString)
    }

    @Test
    fun `a missing format id never becomes the literal null`() {
        val noId = Format(formatId = null, acodec = "opus", vcodec = "none", ext = "webm")
        val stamped = create(formatList = listOf(noId, muxed))

        assertEquals("18", stamped.task.preferences.formatIdString)
    }

    @Test
    fun `empty subtitle lists stop the global subtitle preference from leaking`() {
        val stamped = create(formatList = listOf(muxed))

        assertFalse(stamped.task.preferences.downloadSubtitle)
        assertFalse(stamped.task.preferences.autoSubtitle)
        assertEquals("", stamped.task.preferences.subtitleLanguage)
    }

    @Test
    fun `automatic captions are marked as such and kept apart from subtitles`() {
        val stamped = create(formatList = listOf(muxed), subtitles = listOf("en"), autoCaptions = listOf("en-orig"))

        assertTrue(stamped.task.preferences.downloadSubtitle)
        assertTrue(stamped.task.preferences.autoSubtitle)
        assertEquals("en,en-orig", stamped.task.preferences.subtitleLanguage)
    }

    @Test
    fun `an automatic caption alone is still a subtitle download`() {
        val stamped = create(formatList = listOf(muxed), autoCaptions = listOf("en-orig"))

        assertTrue(stamped.task.preferences.downloadSubtitle)
        assertTrue(stamped.task.preferences.autoSubtitle)
    }

    @Test
    fun `an unchanged title is not written as a rename`() {
        val stamped = create(formatList = listOf(muxed), newTitle = "Clip")

        assertEquals("", stamped.task.preferences.newTitle)
        assertEquals("Clip", stamped.state.videoInfo?.title)
    }

    @Test
    fun `a changed title reaches both the task and the card`() {
        val stamped = create(formatList = listOf(muxed), newTitle = "Better")

        assertEquals("Better", stamped.task.preferences.newTitle)
        assertEquals("Better", stamped.state.videoInfo?.title)
    }

    @Test
    fun `merging audio is only asked for when a stream would otherwise be lost`() {
        assertFalse(create(formatList = listOf(silent, audio)).task.preferences.mergeAudioStream)
        assertTrue(create(formatList = listOf(audio, audio.copy(formatId = "140"))).task.preferences.mergeAudioStream)
    }
}
