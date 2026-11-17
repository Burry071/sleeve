package com.junkfood.seal.util

import com.junkfood.seal.R

/**
 * Pure decisions behind the configure sheet and the format picker. Kept out of the composables so
 * the rules that regressed on device — the download type winning over sticky preferences, the
 * section order, and what "Suggested" resolves to — are covered by unit tests.
 */
object FormatSelectionPolicy {

    const val NOT_SELECTED = -1

    /** Format sections in the order the picker renders them. */
    enum class Section {
        Video,
        VideoWithoutAudio,
        Audio,
    }

    /** What a freshly opened configure sheet starts with, before the user touches a chip. */
    data class SessionOptions(val convertAudio: Boolean, val splitByChapter: Boolean)

    fun audioOnly(selectedType: DownloadType?): Boolean = selectedType == DownloadType.Audio

    fun showsAudioConversion(selectedType: DownloadType?): Boolean = audioOnly(selectedType)

    /**
     * A new sheet always starts the split toggle off and only inherits the Settings conversion
     * choice for the Audio type. The chips change this session's task, never the Settings value.
     */
    fun sessionOptions(selectedType: DownloadType?, settingsConvertAudio: Boolean): SessionOptions =
        SessionOptions(
            convertAudio = audioOnly(selectedType) && settingsConvertAudio,
            splitByChapter = false,
        )

    /** Audio split yields tracks; a video split yields files, so the chip says so. */
    fun splitToggleLabel(selectedType: DownloadType?): Int =
        if (audioOnly(selectedType)) R.string.split_chapters_toggle
        else R.string.split_by_chapters_toggle

    fun sectionOrder(
        audioOnly: Boolean,
        hasVideoAudio: Boolean,
        hasVideoOnly: Boolean,
        hasAudioOnly: Boolean,
    ): List<Section> =
        if (audioOnly) {
            when {
                hasAudioOnly -> listOf(Section.Audio)
                hasVideoAudio -> listOf(Section.Video)
                else -> emptyList()
            }
        } else {
            buildList {
                if (hasVideoAudio) add(Section.Video)
                if (hasVideoOnly) add(Section.VideoWithoutAudio)
                if (hasAudioOnly) add(Section.Audio)
            }
        }

    /** The audio track that pairs with a silent video selection: the user's choice first. */
    fun audioPairIndex(selected: List<Int>, audioOnlyFormats: List<Format>): Int =
        selected.firstOrNull { it in audioOnlyFormats.indices } ?: bestAudioIndex(audioOnlyFormats)

    fun bestAudioIndex(audioOnlyFormats: List<Format>): Int {
        var bestIndex = NOT_SELECTED
        var bestBitrate = Double.NEGATIVE_INFINITY
        audioOnlyFormats.forEachIndexed { index, format ->
            if (format.isAudioOnly() && bitrateOf(format) > bestBitrate) {
                bestIndex = index
                bestBitrate = bitrateOf(format)
            }
        }
        return bestIndex
    }

    /**
     * The picked download type wins; an audio-only format list is the fallback signal for callers
     * that never had a type (the playlist lane). A video pick never flips silently.
     */
    fun taskExtractsAudio(audioOnly: Boolean, formatList: List<Format>): Boolean =
        audioOnly || (formatList.any { it.isAudioOnly() } && formatList.none { it.containsVideo() })

    /**
     * The streams "Suggested" downloads: the extractor's own pick when it covers what the type
     * needs, otherwise the best matching video (+ audio) so the button is never stranded.
     */
    fun suggestedFormats(videoInfo: VideoInfo, audioOnly: Boolean): List<Format> {
        val formats = videoInfo.formats.orEmpty()
        if (formats.isEmpty()) return emptyList()

        val requested =
            videoInfo.requestedFormats.orEmpty().ifEmpty {
                videoInfo.requestedDownloads.orEmpty().flatMap {
                    it.requestedFormats.orEmpty().ifEmpty { listOf(it.toFormat()) }
                }
            }
        if (requested.isEmpty()) return fallbackFormats(formats, audioOnly)

        val requestedHasVideo = requested.any { it.containsVideo() }
        val requestedHasAudio = requested.any { it.containsAudio() }
        return when {
            audioOnly -> if (requestedHasAudio) requested else fallbackFormats(formats, audioOnly)
            requestedHasVideo && requestedHasAudio -> requested
            requestedHasVideo ->
                bestByAudioBitrate(formats.filter { it.isAudioOnly() })
                    ?.let { requested + it }
                    ?: requested
            formats.any { it.containsVideo() } -> fallbackFormats(formats, audioOnly)
            else -> requested
        }
    }

    private fun fallbackFormats(formats: List<Format>, audioOnly: Boolean): List<Format> {
        val bestAudio = bestByAudioBitrate(formats)
        if (audioOnly) {
            return listOfNotNull(
                bestAudio,
                bestVideo(formats.filter { it.containsVideo() && it.containsAudio() }),
            )
                .take(1)
        }
        bestVideo(formats.filter { it.containsVideo() && it.containsAudio() })?.let {
            return listOf(it)
        }
        val video = bestVideo(formats.filter { it.containsVideo() })
        return when {
            video != null && bestAudio != null -> listOf(video, bestAudio)
            video != null -> listOf(video)
            bestAudio != null -> listOf(bestAudio)
            else -> emptyList()
        }
    }

    private fun bestByAudioBitrate(formats: List<Format>): Format? =
        formats.filter { it.isAudioOnly() }.maxWithOrNull(compareBy { bitrateOf(it) })

    private fun bestVideo(formats: List<Format>): Format? =
        formats.maxWithOrNull(
            compareBy<Format> { it.height ?: 0.0 }
                .thenBy { it.fps ?: 0.0 }
                .thenBy { it.tbr ?: 0.0 }
        )

    private fun bitrateOf(format: Format): Double = format.abr ?: format.tbr ?: 0.0
}
