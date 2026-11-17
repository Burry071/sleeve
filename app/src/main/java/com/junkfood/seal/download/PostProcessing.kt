package com.junkfood.seal.download

/**
 * Which yt-dlp lines mean the transfer is over and the file is being finished:
 * formats merged, audio extracted, tags and cover art written, chapters split,
 * scratch moved into place. The byte percentage is useless here — it can sit at
 * 99.9% (or 100%) through minutes of ffmpeg work — so the last log line is the
 * signal, and an engine that reports its own states never matches.
 */
internal object PostProcessing {

    private val postProcessingSteps =
        listOf(
            "[Merger]",
            "[VideoConvertor]",
            "[VideoRemuxer]",
            "[ExtractAudio]",
            "[FixupM3u8]",
            "[FixupM4a]",
            "[Metadata]",
            "[EmbedSubtitle]",
            "[EmbedThumbnail]",
            "[ThumbnailsConvertor]",
            "[ModifyChapters]",
            "[SplitChapters]",
            "[MoveFiles]",
        )

    fun inProgress(progressText: String): Boolean =
        progressText.isNotEmpty() && postProcessingSteps.any { it in progressText }
}
