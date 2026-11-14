package com.junkfood.seal.util

import kotlin.math.roundToLong

/**
 * Builds the FFMETADATA1 sidecar ffmpeg reads alongside the audio: chapter
 * titles only, with millisecond boundaries. Times arrive as float seconds from
 * the extractor's `chapters[]`; the conversion happens here, once.
 */
object M4bMetadata {

    private const val HEADER = ";FFMETADATA1"

    /**
     * @param sourceDurationMs measured duration of the file being exported. The
     * last chapter ends there when the extractor omitted its end time.
     */
    fun chaptersFile(chapters: List<Chapter>, sourceDurationMs: Long): String {
        val usable =
            chapters.filter { chapter ->
                !chapter.title.isNullOrBlank() ||
                    !chapter.startTime.isNullOrNaNOrZero() ||
                    !chapter.endTime.isNullOrNaNOrZero()
            }
        return buildString {
            append(HEADER).append('\n')
            usable.forEachIndexed { index, chapter ->
                // The first chapter always starts at zero: a stray offset would
                // leave the opening seconds untitled.
                val startMs =
                    if (index == 0) 0L
                    else chapter.startTime.secondsToMs().coerceAtLeast(0L)
                val nextStart = usable.getOrNull(index + 1)?.startTime?.secondsToMs()
                val endMs =
                    chapter.endTime.secondsToMs().takeIf { it > startMs }
                        ?: nextStart?.takeIf { it > startMs }
                        ?: sourceDurationMs.takeIf { it > startMs }
                        ?: startMs
                val title = chapter.title?.trim().orEmpty().ifBlank { "Chapter ${index + 1}" }
                append("[CHAPTER]\n")
                append("TIMEBASE=1/1000\n")
                append("START=").append(startMs).append('\n')
                append("END=").append(endMs).append('\n')
                append("title=").append(escape(title)).append('\n')
            }
        }
    }

    private fun Double?.secondsToMs(): Long =
        if (this == null || isNaN() || this <= 0.0) 0L else (this * 1000.0).roundToLong()

    private fun Double?.isNullOrNaNOrZero(): Boolean =
        this == null || isNaN() || this <= 0.0

    /** FFMETADATA1 treats these as syntax; newlines are written as literals. */
    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("=", "\\=")
            .replace(";", "\\;")
            .replace("#", "\\#")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace("\r", "\\n")
}
