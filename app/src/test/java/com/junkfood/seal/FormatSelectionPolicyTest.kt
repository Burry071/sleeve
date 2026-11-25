package com.junkfood.seal

import com.junkfood.seal.ui.page.downloadv2.configure.applySessionAudioConvertChip
import com.junkfood.seal.ui.page.downloadv2.configure.initialSuggestedSelected
import com.junkfood.seal.ui.page.downloadv2.configure.partitionFormats
import com.junkfood.seal.ui.page.downloadv2.configure.resolveSessionConvertAudio
import com.junkfood.seal.util.CONVERT_M4A
import com.junkfood.seal.util.CONVERT_MP3
import com.junkfood.seal.util.DownloadType
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.Format
import com.junkfood.seal.util.FormatSelectionPolicy
import com.junkfood.seal.util.FormatSelectionPolicy.Section
import com.junkfood.seal.util.VideoClip
import com.junkfood.seal.util.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatSelectionPolicyTest {

    private fun audio(id: String, abr: Double? = 128.0) =
        Format(formatId = id, acodec = "opus", vcodec = "none", ext = "webm", abr = abr)

    private fun muxed(id: String, height: Double, tbr: Double = 900.0) =
        Format(formatId = id, acodec = "aac", vcodec = "avc1", ext = "mp4", height = height, tbr = tbr)

    private fun silent(id: String, height: Double, tbr: Double = 1200.0) =
        Format(formatId = id, acodec = "none", vcodec = "avc1", ext = "mp4", height = height, tbr = tbr)

    // --- Download type -> audio-only ------------------------------------------------

    @Test
    fun onlyTheAudioTypeExtractsAudio() {
        assertTrue(FormatSelectionPolicy.audioOnly(DownloadType.Audio))
        assertFalse(FormatSelectionPolicy.audioOnly(DownloadType.Video))
        assertFalse(FormatSelectionPolicy.audioOnly(DownloadType.Playlist))
        assertFalse(FormatSelectionPolicy.audioOnly(DownloadType.Command))
    }

    // --- Per-session convert / split ------------------------------------------------

    @Test
    fun audioTypeKeepsTheSettingsConversionChoice() {
        val options = FormatSelectionPolicy.sessionOptions(DownloadType.Audio, settingsConvertAudio = true)
        assertTrue(options.convertAudio)
    }

    @Test
    fun audioTypeStartsOnOriginalWhenSettingsSayOriginal() {
        val options = FormatSelectionPolicy.sessionOptions(DownloadType.Audio, settingsConvertAudio = false)
        assertFalse(options.convertAudio)
    }

    @Test
    fun videoTypeNeverConvertsToMp3() {
        val options = FormatSelectionPolicy.sessionOptions(DownloadType.Video, settingsConvertAudio = true)
        assertFalse(options.convertAudio)
        assertFalse(FormatSelectionPolicy.showsAudioConversion(DownloadType.Video))
        assertTrue(FormatSelectionPolicy.showsAudioConversion(DownloadType.Audio))
    }

    @Test
    fun splitStartsOffForEveryNewSession() {
        assertFalse(FormatSelectionPolicy.sessionOptions(DownloadType.Audio, true).splitByChapter)
        assertFalse(FormatSelectionPolicy.sessionOptions(DownloadType.Video, true).splitByChapter)
    }

    @Test
    fun splitLabelNamesTracksOnlyForTheAudioType() {
        assertEquals(
            R.string.split_chapters_toggle,
            FormatSelectionPolicy.splitToggleLabel(DownloadType.Audio),
        )
        assertEquals(
            R.string.split_by_chapters_toggle,
            FormatSelectionPolicy.splitToggleLabel(DownloadType.Video),
        )
    }

    // --- Format section order -------------------------------------------------------

    @Test
    fun videoModeOrdersVideoAboveAudio() {
        val order =
            FormatSelectionPolicy.sectionOrder(
                audioOnly = false,
                hasVideoAudio = true,
                hasVideoOnly = true,
                hasAudioOnly = true,
            )
        assertEquals(listOf(Section.Video, Section.VideoWithoutAudio, Section.Audio), order)
    }

    @Test
    fun videoModeSkipsSectionsWithoutFormats() {
        val order =
            FormatSelectionPolicy.sectionOrder(
                audioOnly = false,
                hasVideoAudio = false,
                hasVideoOnly = true,
                hasAudioOnly = true,
            )
        assertEquals(listOf(Section.VideoWithoutAudio, Section.Audio), order)
    }

    @Test
    fun audioModeShowsOnlyTheAudioSection() {
        val order =
            FormatSelectionPolicy.sectionOrder(
                audioOnly = true,
                hasVideoAudio = true,
                hasVideoOnly = true,
                hasAudioOnly = true,
            )
        assertEquals(listOf(Section.Audio), order)
    }

    @Test
    fun audioModeFallsBackToMuxedVideoWhenNoAudioTrackExists() {
        val order =
            FormatSelectionPolicy.sectionOrder(
                audioOnly = true,
                hasVideoAudio = true,
                hasVideoOnly = true,
                hasAudioOnly = false,
            )
        assertEquals(listOf(Section.Video), order)
    }

    // --- Audio pairing --------------------------------------------------------------

    @Test
    fun silenceRiskPairingPicksTheBestAudioTrack() {
        val formats = listOf(audio("140", 128.0), audio("251", 160.0), audio("250", 70.0))
        assertEquals(1, FormatSelectionPolicy.audioPairIndex(emptyList(), formats))
    }

    @Test
    fun pairingKeepsAnAudioTrackTheUserAlreadyChose() {
        val formats = listOf(audio("140", 128.0), audio("251", 160.0))
        assertEquals(0, FormatSelectionPolicy.audioPairIndex(listOf(0), formats))
    }

    @Test
    fun pairingReportsNothingWhenNoAudioExists() {
        assertEquals(
            FormatSelectionPolicy.NOT_SELECTED,
            FormatSelectionPolicy.audioPairIndex(emptyList(), emptyList()),
        )
    }

    // --- Extraction decision (no silent flip for Video) -----------------------------

    @Test
    fun videoTypeWithAVideoFormatKeepsIt() {
        assertFalse(
            FormatSelectionPolicy.taskExtractsAudio(
                audioOnly = false,
                formatList = listOf(silent("137", 1080.0), audio("140", 128.0)),
            )
        )
        assertFalse(
            FormatSelectionPolicy.taskExtractsAudio(
                audioOnly = false,
                formatList = listOf(muxed("18", 720.0)),
            )
        )
    }

    @Test
    fun audioTypeAlwaysExtractsAudio() {
        assertTrue(
            FormatSelectionPolicy.taskExtractsAudio(
                audioOnly = true,
                formatList = listOf(muxed("18", 720.0)),
            )
        )
    }

    @Test
    fun anAudioOnlySelectionIsTheOnlyOtherExtractionReason() {
        assertTrue(
            FormatSelectionPolicy.taskExtractsAudio(
                audioOnly = false,
                formatList = listOf(audio("140", 128.0)),
            )
        )
    }

    // --- Suggested formats ----------------------------------------------------------

    @Test
    fun suggestedKeepsExplicitVideoAndAudioStreams() {
        val silentStream = silent("137", 1080.0)
        val audioStream = audio("140", 128.0)
        val info =
            VideoInfo(
                formats = listOf(silentStream, audioStream, muxed("18", 720.0)),
                requestedFormats = listOf(silentStream, audioStream),
            )
        val suggested = FormatSelectionPolicy.suggestedFormats(info, audioOnly = false)
        assertEquals(listOf("137", "140"), suggested.map { it.formatId })
    }

    @Test
    fun suggestedReplacesASingleDashAudioPickWithMuxedVideoInVideoMode() {
        val info =
            VideoInfo(
                formats = listOf(audio("140", 128.0), muxed("18", 720.0), muxed("22", 1080.0)),
                requestedFormats = listOf(audio("140", 128.0)),
            )
        val suggested = FormatSelectionPolicy.suggestedFormats(info, audioOnly = false)
        assertEquals(listOf("22"), suggested.map { it.formatId })
    }

    @Test
    fun suggestedPairsTheBestAudioWhenTheRequestHasVideoOnly() {
        val info =
            VideoInfo(
                formats = listOf(silent("137", 1080.0), audio("140", 128.0), audio("251", 160.0)),
                requestedFormats = listOf(silent("137", 1080.0)),
            )
        val suggested = FormatSelectionPolicy.suggestedFormats(info, audioOnly = false)
        assertEquals(listOf("137", "251"), suggested.map { it.formatId })
    }

    @Test
    fun suggestedFallsBackToBestVideoAndBestAudioWhenNothingIsRequested() {
        val info =
            VideoInfo(
                formats =
                    listOf(
                        silent("136", 720.0),
                        silent("137", 1080.0),
                        audio("140", 128.0),
                        audio("251", 160.0),
                    )
            )
        val suggested = FormatSelectionPolicy.suggestedFormats(info, audioOnly = false)
        assertEquals(listOf("137", "251"), suggested.map { it.formatId })
    }

    @Test
    fun suggestedPrefersOneMuxedStreamWhenNothingIsRequested() {
        val info =
            VideoInfo(
                formats = listOf(muxed("18", 720.0), muxed("22", 1080.0), audio("140", 128.0))
            )
        val suggested = FormatSelectionPolicy.suggestedFormats(info, audioOnly = false)
        assertEquals(listOf("22"), suggested.map { it.formatId })
    }

    @Test
    fun suggestedKeepsAnAudioOnlySourceAsAudio() {
        val info =
            VideoInfo(
                formats = listOf(audio("140", 128.0), audio("251", 160.0)),
                requestedFormats = listOf(audio("140", 128.0)),
            )
        val suggested = FormatSelectionPolicy.suggestedFormats(info, audioOnly = false)
        assertEquals(listOf("140"), suggested.map { it.formatId })
    }

    @Test
    fun suggestedUsesTheBestAudioInAudioMode() {
        val info =
            VideoInfo(
                formats = listOf(muxed("18", 720.0), audio("140", 128.0), audio("251", 160.0))
            )
        val suggested = FormatSelectionPolicy.suggestedFormats(info, audioOnly = true)
        assertEquals(listOf("251"), suggested.map { it.formatId })
    }

    @Test
    fun suggestedIsEmptyWithoutFormats() {
        assertTrue(FormatSelectionPolicy.suggestedFormats(VideoInfo(), audioOnly = false).isEmpty())
    }

    // --- Format id string -----------------------------------------------------------

    @Test
    fun suggestedLeavesTheFormatChoiceToYtDlp() {
        val formats = listOf(audio("140", 128.0), silent("137", 1080.0))
        assertEquals("", FormatSelectionPolicy.formatIdString(formats, suggested = true))
    }

    @Test
    fun explicitSelectionJoinsVideoBeforeAudio() {
        val formats = listOf(audio("251", 160.0), silent("137", 1080.0))
        assertEquals("137+251", FormatSelectionPolicy.formatIdString(formats, suggested = false))
    }

    @Test
    fun blankAndDuplicateIdsNeverReachTheFormatString() {
        val formats =
            listOf(
                muxed("18", 720.0),
                audio("140", 128.0),
                muxed("18", 720.0),
                Format(formatId = null, acodec = "aac", vcodec = "none", ext = "m4a"),
                Format(formatId = "  ", acodec = "aac", vcodec = "none", ext = "m4a"),
            )
        assertEquals("18+140", FormatSelectionPolicy.formatIdString(formats, suggested = false))
    }

    // --- Audio stream merging -------------------------------------------------------

    @Test
    fun aSilentVideoAndItsPairedAudioNeedNoMerge() {
        assertFalse(FormatSelectionPolicy.mergeAudioStream(listOf(silent("137", 1080.0), audio("140"))))
        assertFalse(FormatSelectionPolicy.mergeAudioStream(listOf(muxed("18", 720.0))))
    }

    @Test
    fun severalAudioStreamsOrAnExtraTrackOverMuxedVideoNeedAMerge() {
        assertTrue(FormatSelectionPolicy.mergeAudioStream(listOf(audio("140"), audio("251"))))
        assertTrue(FormatSelectionPolicy.mergeAudioStream(listOf(muxed("18", 720.0), audio("140"))))
    }

    // --- Type tab fallback ----------------------------------------------------------

    @Test
    fun aStoredTypeTheSheetDoesNotOfferFallsBackToItsFirstTab() {
        val entries = listOf(DownloadType.Video, DownloadType.Audio)
        assertEquals(DownloadType.Video, FormatSelectionPolicy.fallbackType(DownloadType.Command, entries))
        assertEquals(DownloadType.Audio, FormatSelectionPolicy.fallbackType(DownloadType.Audio, entries))
        assertEquals(null, FormatSelectionPolicy.fallbackType(DownloadType.Video, emptyList()))
    }

    // --- Clipping and chapter split -------------------------------------------------

    @Test
    fun clippingNeedsAPositiveDuration() {
        assertTrue(FormatSelectionPolicy.clippingAvailable(true, 61.0))
        assertFalse(FormatSelectionPolicy.clippingAvailable(true, 0.0))
        assertFalse(FormatSelectionPolicy.clippingAvailable(true, null))
        assertFalse(FormatSelectionPolicy.clippingAvailable(false, 61.0))
    }

    @Test
    fun clipsAndAChapterSplitNeverTravelTogether() {
        assertFalse(
            FormatSelectionPolicy.splitWithClips(
                splitSelected = true,
                clips = listOf(VideoClip(1, 2)),
            )
        )
        assertTrue(FormatSelectionPolicy.splitWithClips(splitSelected = true, clips = emptyList()))
        assertFalse(FormatSelectionPolicy.splitWithClips(splitSelected = false, clips = emptyList()))
    }

    // --- Format partitioning (Instagram DASH reels + absent codecs) -----------------

    @Test
    fun partitionFormatsRoutesInstagramVideoOnlyWithNullAcodecIntoVideoOnly() {
        val igVideoLow =
            Format(formatId = "ig-v-720", vcodec = "avc1.64001f", acodec = null, ext = "mp4")
        val igVideoHigh =
            Format(formatId = "ig-v-1080", vcodec = "avc1.64002a", acodec = null, ext = "mp4")
        val igAudio =
            Format(formatId = "ig-a-128", vcodec = null, acodec = "mp4a.40.2", ext = "m4a", abr = 128.0)
        val muxed = muxed("18", 720.0)
        val storyboard = Format(formatId = "sb0", vcodec = "none", acodec = "none", ext = "mhtml")

        val partitioned = partitionFormats(listOf(igVideoLow, igVideoHigh, igAudio, muxed, storyboard))

        assertEquals(listOf("ig-v-1080", "ig-v-720"), partitioned.videoOnly.map { it.formatId })
        assertEquals(listOf("ig-a-128"), partitioned.audioOnly.map { it.formatId })
        assertEquals(listOf("18"), partitioned.videoAudio.map { it.formatId })
        // Selecting the video-only reel track can now auto-pair with the audio track.
        assertEquals(0, FormatSelectionPolicy.audioPairIndex(emptyList(), partitioned.audioOnly))
    }

    // --- Audio -> Custom initial suggested state ------------------------------------

    @Test
    fun audioCustomNeverStartsWithSuggestedSelected() {
        val suggested = listOf(audio("251", 160.0))
        assertFalse(initialSuggestedSelected(audioOnly = true, suggestedFormats = suggested))
        assertTrue(initialSuggestedSelected(audioOnly = false, suggestedFormats = suggested))
        assertFalse(initialSuggestedSelected(audioOnly = false, suggestedFormats = emptyList()))
    }

    // --- Configure sheet convertAudio override + MP3 chip format --------------------

    @Test
    fun switchingFromAudioToVideoIgnoresStaleConvertAudioOverride() {
        assertTrue(
            resolveSessionConvertAudio(
                selectedType = DownloadType.Audio,
                override = true,
                settingsConvertAudio = false,
            )
        )
        assertFalse(
            resolveSessionConvertAudio(
                selectedType = DownloadType.Video,
                override = true,
                settingsConvertAudio = true,
            )
        )
        assertFalse(
            resolveSessionConvertAudio(
                selectedType = DownloadType.Playlist,
                override = true,
                settingsConvertAudio = true,
            )
        )
    }

    @Test
    fun tappingMp3ChipForcesMp3FormatEvenWhenSettingsDefaultToM4a() {
        val base = DownloadUtil.DownloadPreferences.EMPTY.copy(audioConvertFormat = CONVERT_M4A)
        val afterMp3 =
            applySessionAudioConvertChip(
                sessionPreferences = base,
                basePreferences = base,
                convertAudio = true,
            )
        assertEquals(CONVERT_MP3, afterMp3.audioConvertFormat)

        val afterOriginal =
            applySessionAudioConvertChip(
                sessionPreferences = afterMp3,
                basePreferences = base,
                convertAudio = false,
            )
        assertEquals(CONVERT_M4A, afterOriginal.audioConvertFormat)
    }
}
