package com.junkfood.seal.ui.page.downloadv2.configure

import androidx.compose.runtime.Stable
import com.junkfood.seal.download.TaskFactory
import com.junkfood.seal.util.CONVERT_MP3
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.Format
import com.junkfood.seal.util.FormatSelectionPolicy
import com.junkfood.seal.util.SubtitleFormat
import com.junkfood.seal.util.SubtitleSelectionPolicy
import com.junkfood.seal.util.VideoClip
import com.junkfood.seal.util.VideoInfo

data class PartitionedFormats(
    val videoOnly: List<Format>,
    val audioOnly: List<Format>,
    val videoAudio: List<Format>,
)

/**
 * Splits [formats] using [Format.isAudioOnly], [Format.isVideoOnly], and
 * [Format.containsVideo] so DASH streams whose absent codec is `null` or
 * `"unknown"` (e.g., Instagram Reels video-only tracks) land in [PartitionedFormats.videoOnly]
 * and auto-pair with audio instead of being misclassified as muxed video.
 */
internal fun partitionFormats(formats: List<Format>): PartitionedFormats {
    val videoOnly = mutableListOf<Format>()
    val audioOnly = mutableListOf<Format>()
    val videoAudio = mutableListOf<Format>()
    for (format in formats) {
        when {
            format.isAudioOnly() -> audioOnly.add(format)
            format.containsVideo() && format.isVideoOnly() -> videoOnly.add(format)
            format.containsVideo() -> videoAudio.add(format)
        }
    }
    return PartitionedFormats(
        videoOnly = videoOnly.reversed(),
        audioOnly = audioOnly.reversed(),
        videoAudio = videoAudio.reversed(),
    )
}

internal fun initialSuggestedSelected(audioOnly: Boolean, suggestedFormats: List<Format>): Boolean =
    !audioOnly && suggestedFormats.isNotEmpty()

fun <T : Collection<String>> T.filterWithRegex(subtitleLanguageRegex: String): Set<String> =
    filter { SubtitleSelectionPolicy.matchesAny(it, subtitleLanguageRegex) }.toSet()

/**
 * Snapshot of everything [FormatPage] hands off to [TaskFactory.createWithConfigurations].
 */
data class FormatDownloadConfig(
    val videoInfo: VideoInfo,
    val formatList: List<Format>,
    val videoClips: List<VideoClip>,
    val splitByChapter: Boolean,
    val convertAudio: Boolean,
    val newTitle: String,
    val selectedSubtitles: List<String>,
    val selectedAutoCaptions: List<String>,
    val audioOnly: Boolean,
    val suggested: Boolean,
    val basePreferences: DownloadUtil.DownloadPreferences,
) {
    fun toTaskWithState(): TaskFactory.TaskWithState =
        TaskFactory.createWithConfigurations(
            videoInfo = videoInfo,
            formatList = formatList,
            videoClips = videoClips,
            splitByChapter = splitByChapter,
            convertAudio = convertAudio,
            newTitle = newTitle,
            selectedSubtitles = selectedSubtitles,
            selectedAutoCaptions = selectedAutoCaptions,
            audioOnly = audioOnly,
            suggested = suggested,
            basePreferences = basePreferences,
        )
}

/**
 * Pure, JVM-testable state holder for [FormatPage], keyed on
 * `(videoInfo, audioOnly, basePreferences)`. Encapsulates stream partitioning,
 * suggested vs. explicit selection, audio auto-pairing for video-only tracks,
 * subtitle/auto-caption routing, clipping/splitting mutual exclusion, and final
 * [FormatDownloadConfig] assembly.
 */
@Stable
data class FormatPickerState(
    val videoInfo: VideoInfo,
    val audioOnly: Boolean,
    val basePreferences: DownloadUtil.DownloadPreferences,
    val mergeAudioStream: Boolean,
    val partitioned: PartitionedFormats,
    val suggestedFormats: List<Format>,
    val isSuggestedFormatAvailable: Boolean,
    val isSuggestedFormatSelected: Boolean,
    val selectedVideoAudioFormat: Int = NOT_SELECTED,
    val selectedVideoOnlyFormat: Int = NOT_SELECTED,
    val selectedAudioOnlyFormats: List<Int> = emptyList(),
    val selectedSubtitles: List<String> = emptyList(),
    val selectedAutoCaptions: List<String> = emptyList(),
    val convertAudio: Boolean = false,
    val mp3ChipTapped: Boolean = false,
    val isClippingVideo: Boolean = false,
    val isSplittingVideo: Boolean = false,
    val clipRange: ClosedFloatingPointRange<Float> = 0f..0f,
    val rename: String = "",
) {
    val videoOnlyFormats: List<Format>
        get() = partitioned.videoOnly

    val audioOnlyFormats: List<Format>
        get() = partitioned.audioOnly

    val videoAudioFormats: List<Format>
        get() = partitioned.videoAudio

    val duration: Double
        get() = videoInfo.duration ?: 0.0

    val audioFallback: Boolean
        get() = audioOnly && audioOnlyFormats.isEmpty()

    val isSplitByChapterAvailable: Boolean
        get() = !videoInfo.chapters.isNullOrEmpty()

    val videoDurationRange: ClosedFloatingPointRange<Float>
        get() = 0f..(videoInfo.duration?.toFloat()?.coerceAtLeast(0f) ?: 0f)

    val suggestedSubtitleMap: Map<String, List<SubtitleFormat>>
        get() =
            videoInfo.subtitles.takeIf { it.isNotEmpty() }
                ?: videoInfo.automaticCaptions.filterKeys { it.endsWith("-orig") }

    val otherSubtitleMap: Map<String, List<SubtitleFormat>>
        get() = videoInfo.subtitles + videoInfo.automaticCaptions - suggestedSubtitleMap.keys

    val selectedFormats: List<Format>
        get() =
            buildList {
                if (isSuggestedFormatSelected) {
                    addAll(suggestedFormats)
                } else {
                    selectedAudioOnlyFormats.forEach { index ->
                        audioOnlyFormats.getOrNull(index)?.let(::add)
                    }
                    videoAudioFormats.getOrNull(selectedVideoAudioFormat)?.let(::add)
                    videoOnlyFormats.getOrNull(selectedVideoOnlyFormat)?.let(::add)
                }
            }

    val canDownload: Boolean
        get() = isSuggestedFormatSelected || selectedFormats.isNotEmpty()

    val isMergingVideoAndAudio: Boolean
        get() =
            !isSuggestedFormatSelected &&
                selectedVideoOnlyFormat != NOT_SELECTED &&
                selectedAudioOnlyFormats.isNotEmpty()

    val expectedBytes: Double
        get() = selectedFormats.sumOf { it.fileSize ?: it.fileSizeApprox ?: 0.0 }

    fun selectSuggested(): FormatPickerState {
        if (!isSuggestedFormatAvailable) return this
        return copy(
            isSuggestedFormatSelected = true,
            selectedAudioOnlyFormats = emptyList(),
            selectedVideoAudioFormat = NOT_SELECTED,
            selectedVideoOnlyFormat = NOT_SELECTED,
        )
    }

    fun toggleAudioOnly(index: Int): FormatPickerState {
        if (index !in audioOnlyFormats.indices) return this
        if (index in selectedAudioOnlyFormats) {
            return copy(selectedAudioOnlyFormats = selectedAudioOnlyFormats - index)
        }
        val nextAudio =
            if (mergeAudioStream) selectedAudioOnlyFormats + index else listOf(index)
        // An audio track replaces a muxed pick; keeping both would ask yt-dlp
        // to merge a stream that already carries audio.
        return copy(
            selectedAudioOnlyFormats = nextAudio,
            selectedVideoAudioFormat = NOT_SELECTED,
            isSuggestedFormatSelected = false,
        )
    }

    fun toggleVideoOnly(index: Int): FormatPickerState {
        if (index !in videoOnlyFormats.indices) return this
        if (selectedVideoOnlyFormat == index) {
            return copy(selectedVideoOnlyFormat = NOT_SELECTED)
        }
        // A silent video needs an audio track; pair one so the task cannot
        // silently save as video-only.
        val pairIndex =
            FormatSelectionPolicy.audioPairIndex(selectedAudioOnlyFormats, audioOnlyFormats)
        val nextAudio =
            if (pairIndex != NOT_SELECTED && pairIndex !in selectedAudioOnlyFormats) {
                if (mergeAudioStream) selectedAudioOnlyFormats + pairIndex else listOf(pairIndex)
            } else {
                selectedAudioOnlyFormats
            }
        return copy(
            selectedVideoOnlyFormat = index,
            selectedVideoAudioFormat = NOT_SELECTED,
            isSuggestedFormatSelected = false,
            selectedAudioOnlyFormats = nextAudio,
        )
    }

    fun toggleVideoAudio(index: Int): FormatPickerState {
        if (index !in videoAudioFormats.indices) return this
        if (selectedVideoAudioFormat == index) {
            return copy(selectedVideoAudioFormat = NOT_SELECTED)
        }
        return copy(
            selectedVideoAudioFormat = index,
            selectedAudioOnlyFormats = emptyList(),
            selectedVideoOnlyFormat = NOT_SELECTED,
            isSuggestedFormatSelected = false,
        )
    }

    fun setConvertAudio(
        convert: Boolean,
        fromMp3Chip: Boolean = convert,
    ): FormatPickerState =
        copy(
            convertAudio = convert,
            mp3ChipTapped = convert && fromMp3Chip,
        )

    fun toggleClipping(enabled: Boolean = !isClippingVideo): FormatPickerState =
        if (enabled) {
            copy(
                isClippingVideo = true,
                isSplittingVideo = false,
                clipRange = videoDurationRange,
            )
        } else {
            copy(
                isClippingVideo = false,
                clipRange = videoDurationRange,
            )
        }

    fun updateClipRange(range: ClosedFloatingPointRange<Float>): FormatPickerState {
        val bounds = videoDurationRange
        val start = range.start.coerceIn(bounds.start, bounds.endInclusive)
        val end = range.endInclusive.coerceIn(start, bounds.endInclusive)
        return copy(clipRange = start..end)
    }

    fun toggleSplitting(enabled: Boolean = !isSplittingVideo): FormatPickerState =
        if (enabled) {
            copy(isSplittingVideo = true, isClippingVideo = false)
        } else {
            copy(isSplittingVideo = false)
        }

    fun updateRename(newTitle: String): FormatPickerState = copy(rename = newTitle)

    fun isSubtitleSelected(code: String): Boolean =
        if (
            SubtitleSelectionPolicy.goesToAutoCaptions(
                videoInfo.subtitles,
                videoInfo.automaticCaptions,
                code,
            )
        ) {
            code in selectedAutoCaptions
        } else {
            code in selectedSubtitles
        }

    fun toggleSubtitle(code: String): FormatPickerState =
        if (
            SubtitleSelectionPolicy.goesToAutoCaptions(
                videoInfo.subtitles,
                videoInfo.automaticCaptions,
                code,
            )
        ) {
            val next =
                if (code in selectedAutoCaptions) selectedAutoCaptions - code
                else selectedAutoCaptions + code
            copy(selectedAutoCaptions = next)
        } else {
            val next =
                if (code in selectedSubtitles) selectedSubtitles - code
                else selectedSubtitles + code
            copy(selectedSubtitles = next)
        }

    fun updateSubtitles(
        subtitles: List<String>,
        autoCaptions: List<String>,
    ): FormatPickerState {
        val resolvedSubs = mutableListOf<String>()
        val resolvedAuto = mutableListOf<String>()
        subtitles.distinct().forEach { code ->
            if (
                SubtitleSelectionPolicy.goesToAutoCaptions(
                    videoInfo.subtitles,
                    videoInfo.automaticCaptions,
                    code,
                )
            ) {
                resolvedAuto.add(code)
            } else {
                resolvedSubs.add(code)
            }
        }
        autoCaptions.distinct().forEach { code ->
            if (code !in resolvedAuto) resolvedAuto.add(code)
        }
        return copy(
            selectedSubtitles = resolvedSubs,
            selectedAutoCaptions = resolvedAuto,
        )
    }

    fun diffSubtitleLanguages(subtitleLanguageRegex: String): Set<String> =
        (selectedSubtitles + selectedAutoCaptions)
            .run { this - this.filterWithRegex(subtitleLanguageRegex) }
            .toSet()

    fun buildDownloadConfig(): FormatDownloadConfig {
        val clips = if (isClippingVideo) listOf(VideoClip(clipRange)) else emptyList()
        val effectivePreferences =
            if (convertAudio && mp3ChipTapped) {
                basePreferences.copy(audioConvertFormat = CONVERT_MP3)
            } else {
                basePreferences
            }
        return FormatDownloadConfig(
            videoInfo = videoInfo,
            formatList = selectedFormats,
            videoClips = clips,
            // A clip and a chapter split write different files; the clip wins
            // because the slider shows what the user picked.
            splitByChapter = FormatSelectionPolicy.splitWithClips(isSplittingVideo, clips),
            convertAudio = convertAudio,
            newTitle = rename,
            selectedSubtitles = selectedSubtitles,
            selectedAutoCaptions = selectedAutoCaptions,
            audioOnly = audioOnly,
            suggested = isSuggestedFormatSelected,
            basePreferences = effectivePreferences,
        )
    }

    companion object {
        const val NOT_SELECTED = FormatSelectionPolicy.NOT_SELECTED

        fun create(
            videoInfo: VideoInfo,
            audioOnly: Boolean = false,
            basePreferences: DownloadUtil.DownloadPreferences =
                DownloadUtil.DownloadPreferences.EMPTY,
            initialSplitByChapter: Boolean = basePreferences.splitByChapter,
            initialConvertAudio: Boolean = basePreferences.convertAudio,
            mergeAudioStream: Boolean = !audioOnly && basePreferences.mergeAudioStream,
            subtitleLanguagePatterns: String = basePreferences.subtitleLanguage,
            downloadSubtitle: Boolean = basePreferences.downloadSubtitle,
        ): FormatPickerState {
            val partitioned = partitionFormats(videoInfo.formats.orEmpty())
            val suggestedFormats = FormatSelectionPolicy.suggestedFormats(videoInfo, audioOnly)
            val isSuggestedAvailable = initialSuggestedSelected(audioOnly, suggestedFormats)
            val initialAudioSelection =
                if (audioOnly) {
                    listOfNotNull(
                        FormatSelectionPolicy.bestAudioIndex(partitioned.audioOnly)
                            .takeIf { it != NOT_SELECTED }
                    )
                } else {
                    emptyList()
                }
            val initialSubtitles =
                SubtitleSelectionPolicy.initialSelection(
                    subtitles = videoInfo.subtitles,
                    autoCaptions = videoInfo.automaticCaptions,
                    enabled = downloadSubtitle,
                    patterns = subtitleLanguagePatterns,
                )
            val durationRange = 0f..(videoInfo.duration?.toFloat()?.coerceAtLeast(0f) ?: 0f)

            return FormatPickerState(
                videoInfo = videoInfo,
                audioOnly = audioOnly,
                basePreferences = basePreferences,
                mergeAudioStream = !audioOnly && mergeAudioStream,
                partitioned = partitioned,
                suggestedFormats = suggestedFormats,
                isSuggestedFormatAvailable = isSuggestedAvailable,
                // Starts on the suggested set whenever the suggested card is actually shown,
                // never in Audio -> Custom mode where the audio list owns the initial pick.
                isSuggestedFormatSelected = isSuggestedAvailable,
                selectedVideoAudioFormat = NOT_SELECTED,
                selectedVideoOnlyFormat = NOT_SELECTED,
                selectedAudioOnlyFormats = initialAudioSelection,
                selectedSubtitles = initialSubtitles.subtitles,
                selectedAutoCaptions = initialSubtitles.autoCaptions,
                convertAudio = initialConvertAudio,
                mp3ChipTapped = false,
                isClippingVideo = false,
                isSplittingVideo = initialSplitByChapter,
                clipRange = durationRange,
                rename = "",
            )
        }
    }
}
