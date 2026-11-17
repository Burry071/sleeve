package com.junkfood.seal

import com.junkfood.seal.util.DownloadType
import com.junkfood.seal.util.Format
import com.junkfood.seal.util.FormatSelectionPolicy
import com.junkfood.seal.util.FormatSelectionPolicy.Section
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
}
