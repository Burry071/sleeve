package com.junkfood.seal

import com.junkfood.seal.ui.page.downloadv2.configure.ConfigureSessionState
import com.junkfood.seal.ui.page.downloadv2.configure.FormatPickerState
import com.junkfood.seal.util.CONVERT_M4A
import com.junkfood.seal.util.CONVERT_MP3
import com.junkfood.seal.util.Chapter
import com.junkfood.seal.util.DownloadType
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.Format
import com.junkfood.seal.util.SubtitleFormat
import com.junkfood.seal.util.VideoClip
import com.junkfood.seal.util.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatPickerStateTest {

    private fun audio(id: String, abr: Double = 128.0) =
        Format(formatId = id, acodec = "opus", vcodec = "none", ext = "webm", abr = abr)

    private fun muxed(id: String, height: Double = 720.0) =
        Format(formatId = id, acodec = "aac", vcodec = "avc1", ext = "mp4", height = height)

    // --- Instagram Reels video-only DASH (acodec = null) ----------------------------

    @Test
    fun instagramReelVideoOnlyWithNullAcodecAutoPairsBestAudio() {
        val igVideoLow =
            Format(formatId = "ig-v-720", vcodec = "avc1.64001f", acodec = null, ext = "mp4")
        val igVideoHigh =
            Format(formatId = "ig-v-1080", vcodec = "avc1.64002a", acodec = null, ext = "mp4")
        val igAudioLow =
            Format(formatId = "ig-a-64", vcodec = null, acodec = "mp4a.40.2", ext = "m4a", abr = 64.0)
        val igAudioHigh =
            Format(
                formatId = "ig-a-128",
                vcodec = null,
                acodec = "mp4a.40.2",
                ext = "m4a",
                abr = 128.0,
            )
        val info =
            VideoInfo(
                id = "reel-1",
                title = "Reel",
                originalUrl = "https://www.instagram.com/reel/C123456/",
                formats = listOf(igVideoLow, igVideoHigh, igAudioLow, igAudioHigh),
                duration = 30.0,
            )

        val initial = FormatPickerState.create(videoInfo = info, audioOnly = false)
        assertEquals(listOf("ig-v-1080", "ig-v-720"), initial.videoOnlyFormats.map { it.formatId })
        assertTrue(initial.videoAudioFormats.isEmpty())
        assertEquals(listOf("ig-a-128", "ig-a-64"), initial.audioOnlyFormats.map { it.formatId })

        val picked = initial.toggleVideoOnly(0)
        assertFalse(picked.isSuggestedFormatSelected)
        assertEquals(0, picked.selectedVideoOnlyFormat)
        // Auto-paired with highest-bitrate audio track (index 0 after partition reverse).
        assertEquals(listOf(0), picked.selectedAudioOnlyFormats)
        assertTrue(picked.isMergingVideoAndAudio)

        val config = picked.buildDownloadConfig()
        assertFalse(config.suggested)
        assertEquals(setOf("ig-v-1080", "ig-a-128"), config.formatList.map { it.formatId }.toSet())

        val taskWithState = config.toTaskWithState()
        assertEquals("ig-v-1080+ig-a-128", taskWithState.task.preferences.formatIdString)
        assertFalse(taskWithState.task.preferences.extractAudio)
    }

    @Test
    fun outOfBoundsFormatIndicesAreSafelyIgnored() {
        val info =
            VideoInfo(
                id = "v-bounds",
                formats = listOf(muxed("18"), audio("140")),
                duration = 45.0,
            )
        val state = FormatPickerState.create(videoInfo = info, audioOnly = false)
        assertEquals(state, state.toggleVideoAudio(-1))
        assertEquals(state, state.toggleVideoAudio(99))
        assertEquals(state, state.toggleVideoOnly(0))
        assertEquals(state, state.toggleAudioOnly(5))
    }

    // --- Audio -> Custom initial state & track toggling -----------------------------

    @Test
    fun audioCustomDisablesSuggestedPreSelectsBestAudioAndTogglesCleanly() {
        val a64 = audio("249", abr = 64.0)
        val a160 = audio("251", abr = 160.0)
        val a128 = audio("140", abr = 128.0)
        val info =
            VideoInfo(
                id = "song-1",
                title = "Track",
                originalUrl = "https://www.youtube.com/watch?v=aaaaaaaaaaa",
                formats = listOf(muxed("18"), a64, a160, a128),
                requestedFormats = listOf(a160),
                duration = 210.0,
            )
        val basePrefs =
            DownloadUtil.DownloadPreferences.EMPTY.copy(audioConvertFormat = CONVERT_M4A)

        val initial =
            FormatPickerState.create(
                videoInfo = info,
                audioOnly = true,
                basePreferences = basePrefs,
            )

        assertFalse(initial.isSuggestedFormatAvailable)
        assertFalse(initial.isSuggestedFormatSelected)
        // Reversed order: [140 (idx 0), 251 (idx 1), 249 (idx 2)] -> best is 251 (idx 1).
        assertEquals(listOf("140", "251", "249"), initial.audioOnlyFormats.map { it.formatId })
        assertEquals(listOf(1), initial.selectedAudioOnlyFormats)
        assertEquals(listOf("251"), initial.selectedFormats.map { it.formatId })

        // selectSuggested is a no-op when audioOnly == true.
        assertFalse(initial.selectSuggested().isSuggestedFormatSelected)

        // Switching to another audio track replaces previous pick and keeps suggested false.
        val switched = initial.toggleAudioOnly(0)
        assertFalse(switched.isSuggestedFormatSelected)
        assertEquals(listOf(0), switched.selectedAudioOnlyFormats)
        assertEquals(listOf("140"), switched.selectedFormats.map { it.formatId })

        // Deselecting active track empties selection without falling back to suggested.
        val cleared = switched.toggleAudioOnly(0)
        assertFalse(cleared.isSuggestedFormatSelected)
        assertTrue(cleared.selectedAudioOnlyFormats.isEmpty())
        assertFalse(cleared.canDownload)

        // Tapping MP3 chip forces CONVERT_MP3 in built Task preferences.
        val mp3State = switched.setConvertAudio(convert = true, fromMp3Chip = true)
        val mp3Task = mp3State.buildDownloadConfig().toTaskWithState().task
        assertTrue(mp3Task.preferences.extractAudio)
        assertTrue(mp3Task.preferences.convertAudio)
        assertEquals(CONVERT_MP3, mp3Task.preferences.audioConvertFormat)
        assertEquals("140", mp3Task.preferences.formatIdString)
    }

    // --- Auto-caption-only video subtitle routing -----------------------------------

    @Test
    fun autoCaptionOnlyVideoPopulatesSelectedAutoCaptionsAndKeepsSubtitlesEmpty() {
        val info =
            VideoInfo(
                id = "auto-cap-1",
                title = "Talk",
                originalUrl = "https://www.youtube.com/watch?v=bbbbbbbbbbb",
                formats = listOf(muxed("22", height = 1080.0)),
                subtitles = emptyMap(),
                automaticCaptions =
                    mapOf(
                        "en-orig" to
                            listOf(SubtitleFormat(ext = "vtt", url = "", name = "English (auto)")),
                        "es" to
                            listOf(SubtitleFormat(ext = "vtt", url = "", name = "Spanish (auto)")),
                    ),
                duration = 120.0,
            )
        val basePrefs =
            DownloadUtil.DownloadPreferences.EMPTY.copy(
                downloadSubtitle = true,
                subtitleLanguage = "en.*",
            )

        val initial =
            FormatPickerState.create(
                videoInfo = info,
                audioOnly = false,
                basePreferences = basePrefs,
            )

        assertTrue(initial.selectedSubtitles.isEmpty())
        assertEquals(listOf("en-orig"), initial.selectedAutoCaptions)
        assertTrue(initial.isSubtitleSelected("en-orig"))

        // Toggling off and back on keeps the code strictly in selectedAutoCaptions.
        val toggledOff = initial.toggleSubtitle("en-orig")
        assertTrue(toggledOff.selectedSubtitles.isEmpty())
        assertTrue(toggledOff.selectedAutoCaptions.isEmpty())

        val toggledOn = toggledOff.toggleSubtitle("en-orig")
        assertTrue(toggledOn.selectedSubtitles.isEmpty())
        assertEquals(listOf("en-orig"), toggledOn.selectedAutoCaptions)

        // Even if a caller passes an auto-caption code in the subtitles list, updateSubtitles routes it safely.
        val updated = toggledOff.updateSubtitles(subtitles = listOf("en-orig"), autoCaptions = emptyList())
        assertTrue(updated.selectedSubtitles.isEmpty())
        assertEquals(listOf("en-orig"), updated.selectedAutoCaptions)

        val task = updated.buildDownloadConfig().toTaskWithState().task
        assertTrue(task.preferences.downloadSubtitle)
        assertTrue(task.preferences.autoSubtitle)
        assertEquals("en-orig", task.preferences.subtitleLanguage)
    }

    // --- Clipping, splitting, and rename --------------------------------------------

    @Test
    fun clippingAndSplittingAreMutuallyExclusiveAndFlowIntoDownloadConfig() {
        val info =
            VideoInfo(
                id = "clip-1",
                title = "Original Title",
                originalUrl = "https://www.youtube.com/watch?v=ccccccccccc",
                formats = listOf(muxed("22", height = 1080.0)),
                chapters = listOf(Chapter("Intro", 0.0, 30.0), Chapter("Main", 30.0, 90.0)),
                duration = 90.0,
            )

        val state =
            FormatPickerState.create(
                videoInfo = info,
                audioOnly = false,
                initialSplitByChapter = true,
            )
        assertTrue(state.isSplittingVideo)
        assertFalse(state.isClippingVideo)

        val clipped = state.toggleClipping(true).updateClipRange(10f..45f).updateRename("Clipped Cut")
        assertTrue(clipped.isClippingVideo)
        assertFalse(clipped.isSplittingVideo)

        val config = clipped.buildDownloadConfig()
        assertEquals(listOf(VideoClip(10, 45)), config.videoClips)
        assertFalse(config.splitByChapter)
        assertEquals("Clipped Cut", config.newTitle)

        val task = config.toTaskWithState().task
        assertEquals(listOf(VideoClip(10, 45)), task.preferences.videoClips)
        assertFalse(task.preferences.splitByChapter)
        assertEquals("Clipped Cut", task.preferences.newTitle)
    }

    // --- ConfigureSessionState reducers ---------------------------------------------

    @Test
    fun switchingFromAudioMp3ToVideoClearsConvertAudioOverrideAndRestoresFormat() {
        val base =
            DownloadUtil.DownloadPreferences.EMPTY.copy(
                convertAudio = false,
                audioConvertFormat = CONVERT_M4A,
            )
        val audioMp3 =
            ConfigureSessionState(
                    basePreferences = base,
                    selectedType = DownloadType.Audio,
                    useFormatSelection = false,
                    sessionPreferences = base,
                )
                .setConvertAudio(true)

        assertEquals(true, audioMp3.convertAudioOverride)
        assertTrue(audioMp3.convertAudio)
        assertEquals(CONVERT_MP3, audioMp3.sessionPreferences.audioConvertFormat)
        assertEquals(CONVERT_MP3, audioMp3.effectivePreferences().audioConvertFormat)
        assertTrue(audioMp3.effectivePreferences().extractAudio)
        assertTrue(audioMp3.effectivePreferences().convertAudio)

        val switchedToVideo = audioMp3.selectType(DownloadType.Video)
        assertNull(switchedToVideo.convertAudioOverride)
        assertFalse(switchedToVideo.convertAudio)
        assertEquals(CONVERT_M4A, switchedToVideo.sessionPreferences.audioConvertFormat)
        assertFalse(switchedToVideo.effectivePreferences().extractAudio)
        assertFalse(switchedToVideo.effectivePreferences().convertAudio)
    }

    @Test
    fun cookiesChipStaysSessionScopedAndResetForPicksUpFreshBasePreferences() {
        val base =
            DownloadUtil.DownloadPreferences.EMPTY.copy(
                cookies = false,
                downloadSubtitle = false,
                convertAudio = false,
            )
        val edited =
            ConfigureSessionState(
                    basePreferences = base,
                    selectedType = DownloadType.Audio,
                    useFormatSelection = true,
                    sessionPreferences = base,
                )
                .toggleCookies()
                .toggleDownloadSubtitle()
                .toggleSplitByChapter()
                .setConvertAudio(true)

        assertTrue(edited.sessionPreferences.cookies)
        assertFalse(edited.basePreferences.cookies)
        assertTrue(edited.sessionPreferences.downloadSubtitle)
        assertTrue(edited.splitByChapter)
        assertTrue(edited.effectivePreferences().cookies)
        assertTrue(edited.effectivePreferences().splitByChapter)

        // Switching to Playlist clears custom format selection and audio conversion override.
        val playlist = edited.selectType(DownloadType.Playlist)
        assertFalse(playlist.useFormatSelection)
        assertNull(playlist.convertAudioOverride)

        // Resetting for a new sheet opening picks up fresh Settings defaults without leaks.
        val freshBase =
            DownloadUtil.DownloadPreferences.EMPTY.copy(
                cookies = false,
                videoResolution = 2,
                convertAudio = true,
            )
        val reset = edited.resetFor(freshBase)
        assertEquals(freshBase, reset.basePreferences)
        assertEquals(freshBase, reset.sessionPreferences)
        assertFalse(reset.sessionPreferences.cookies)
        assertFalse(reset.splitByChapter)
        assertNull(reset.convertAudioOverride)
        assertTrue(reset.convertAudio)
    }

    @Test
    fun withBasePreferencesMergesSavedPresetWhilePreservingSessionOverrides() {
        val initialBase =
            DownloadUtil.DownloadPreferences.EMPTY.copy(
                cookies = false,
                videoResolution = 0,
                audioConvertFormat = CONVERT_M4A,
            )
        val session =
            ConfigureSessionState(
                    basePreferences = initialBase,
                    selectedType = DownloadType.Audio,
                    sessionPreferences = initialBase,
                )
                .toggleCookies(true)
                .setConvertAudio(true)

        val updatedBase = initialBase.copy(videoResolution = 3, audioQuality = 2)
        val merged = session.withBasePreferences(updatedBase)

        assertEquals(updatedBase, merged.basePreferences)
        assertEquals(3, merged.sessionPreferences.videoResolution)
        assertEquals(2, merged.sessionPreferences.audioQuality)
        assertTrue(merged.sessionPreferences.cookies)
        assertEquals(CONVERT_MP3, merged.sessionPreferences.audioConvertFormat)
    }
}
