package com.junkfood.seal.download.engine

import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.util.CONVERT_ASS
import com.junkfood.seal.util.CONVERT_LRC
import com.junkfood.seal.util.CONVERT_M4A
import com.junkfood.seal.util.CONVERT_MP3
import com.junkfood.seal.util.CONVERT_SRT
import com.junkfood.seal.util.CONVERT_VTT
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.FileUtil.getArchiveFile
import com.junkfood.seal.util.FileUtil.getConfigFile
import com.junkfood.seal.util.FORMAT_COMPATIBILITY
import com.junkfood.seal.util.FORMAT_QUALITY
import com.junkfood.seal.util.HIGH
import com.junkfood.seal.util.LOW
import com.junkfood.seal.util.M4A
import com.junkfood.seal.util.MEDIUM
import com.junkfood.seal.util.OPUS
import com.junkfood.seal.util.RedditResolver
import com.junkfood.seal.util.connectWithDelimiter
import com.yausername.youtubedl_android.YoutubeDLRequest

private const val CROP_ARTWORK_COMMAND =
    """--ppa "ffmpeg: -c:v mjpeg -vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\"""""

internal fun YoutubeDLRequest.enableCookies(userAgentString: String): YoutubeDLRequest =
    DownloadUtil.enableCookies(this, userAgentString)

/**
 * Reddit hard-blocks non-browser user agents before the extractor can even
 * reach the JSON API, so requests for reddit hosts present as the browser this
 * device runs. Skipped when the user set a custom UA.
 */
internal fun YoutubeDLRequest.maybeRedditUserAgent(
    url: String,
    customUserAgentEnabled: Boolean,
): YoutubeDLRequest =
    apply {
        if (RedditResolver.isRedditHost(url) && !customUserAgentEnabled) {
            addOption("--user-agent", RedditResolver.BROWSER_UA)
        }
    }

internal fun YoutubeDLRequest.enableProxy(proxyUrl: String): YoutubeDLRequest =
    this.addOption("--proxy", proxyUrl)

internal fun YoutubeDLRequest.useDownloadArchive(): YoutubeDLRequest =
    this.addOption("--download-archive", context.getArchiveFile().absolutePath)

internal fun YoutubeDLRequest.enableAria2c(): YoutubeDLRequest =
    this.addOption("--downloader", "libaria2c.so")

internal fun audioFormatSorter(preferences: DownloadUtil.DownloadPreferences): String =
    preferences.run {
        if (!useCustomAudioPreset) return@run ""
        val format =
            when (audioFormat) {
                M4A -> "acodec:aac"
                OPUS -> "acodec:opus"
                else -> ""
            }
        val quality =
            when (audioQuality) {
                HIGH -> "abr~192"
                MEDIUM -> "abr~128"
                LOW -> "abr~64"
                else -> ""
            }
        return@run connectWithDelimiter(format, quality, delimiter = ",")
    }

internal fun videoFormatSorter(preferences: DownloadUtil.DownloadPreferences): String =
    preferences.run {
        val format =
            when (videoFormat) {
                FORMAT_COMPATIBILITY -> "proto,vcodec:h264,ext"
                FORMAT_QUALITY ->
                    if (supportAv1HardwareDecoding) {
                        "vcodec:av01"
                    } else {
                        "vcodec:vp9.2"
                    }

                else -> ""
            }
        val res =
            when (videoResolution) {
                1 -> "res:2160"
                2 -> "res:1440"
                3 -> "res:1080"
                4 -> "res:720"
                5 -> "res:480"
                6 -> "res:360"
                7 -> "+res"
                else -> ""
            }
        val sorter =
            if (videoFormat == FORMAT_COMPATIBILITY) {
                connectWithDelimiter(format, res, delimiter = ",")
            } else {
                connectWithDelimiter(res, format, delimiter = ",")
            }
        return@run sorter
    }

/** Combined `-S` value: video terms first, then the audio preset. */
internal fun formatSorter(preferences: DownloadUtil.DownloadPreferences): String =
    connectWithDelimiter(
        videoFormatSorter(preferences),
        audioFormatSorter(preferences),
        delimiter = ",",
    )

internal fun YoutubeDLRequest.addOptionsForVideoDownloads(
    settings: DownloadSettingsSnapshot
): YoutubeDLRequest {
    val request = this
    with(settings.preferences) {
        request.addOption("--add-metadata")
        request.addOption("--no-embed-info-json")
        // "direct" is the direct-lane marker, not a yt-dlp format id.
        // When such a task falls back to yt-dlp (e.g. a manifest URL
        // with the Ghost engine off), the format sorter must pick the
        // format — `-f direct` always fails format selection.
        if (formatIdString.isNotEmpty() && formatIdString != DownloadUtil.DIRECT_FORMAT_ID) {
            request.addOption("-f", formatIdString)
            if (mergeAudioStream) {
                request.addOption("--audio-multistreams")
            }
        } else {
            request.applyFormatSorter(settings.preferences, formatSorter(settings.preferences))
        }
        if (downloadSubtitle) {
            if (autoSubtitle) {
                request.addOption("--write-auto-subs")
                if (!autoTranslatedSubtitles) {
                    request.addOption("--extractor-args", "youtube:skip=translated_subs")
                }
            }
            subtitleLanguage
                .takeIf { it.isNotEmpty() }
                ?.let { request.addOption("--sub-langs", it) }
            if (embedSubtitle) {
                request.addOption("--embed-subs")
                if (keepSubtitle) {
                    request.addOption("--write-subs")
                }
            } else {
                request.addOption("--write-subs")
            }
            when (convertSubtitle) {
                CONVERT_ASS -> request.addOption("--convert-subs", "ass")
                CONVERT_SRT -> request.addOption("--convert-subs", "srt")
                CONVERT_VTT -> request.addOption("--convert-subs", "vtt")
                CONVERT_LRC -> request.addOption("--convert-subs", "lrc")
                else -> {}
            }
        }
        if (mergeToMkv || settings.preferMp4) {
            val target =
                when {
                    // The explicit toggle always wins over "Prefer MP4".
                    settings.mergeOutputMkv -> "mkv"
                    settings.preferMp4 -> "mp4/mkv"
                    else -> "mkv" // merge forced by embedded subtitles
                }
            request.addOption("--remux-video", target)
            request.addOption("--merge-output-format", target)
        }
        if (embedThumbnail) {
            request.addOption("--embed-thumbnail")
        }
        if (videoClips.isEmpty()) request.addOption("--embed-chapters")
    }
    return request
}

internal fun YoutubeDLRequest.addOptionsForAudioDownloads(
    id: String,
    settings: DownloadSettingsSnapshot,
    playlistUrl: String,
): YoutubeDLRequest {
    val request = this
    with(settings.preferences) {
        request.addOption("-x")
        if (downloadSubtitle) {
            request.addOption("--write-subs")

            if (autoSubtitle) {
                request.addOption("--write-auto-subs")
                if (!autoTranslatedSubtitles) {
                    request.addOption("--extractor-args", "youtube:skip=translated_subs")
                }
            }
            subtitleLanguage
                .takeIf { it.isNotEmpty() }
                ?.let { request.addOption("--sub-langs", it) }
            when (convertSubtitle) {
                CONVERT_ASS -> request.addOption("--convert-subs", "ass")
                CONVERT_SRT -> request.addOption("--convert-subs", "srt")
                CONVERT_VTT -> request.addOption("--convert-subs", "vtt")
                CONVERT_LRC -> request.addOption("--convert-subs", "lrc")
                else -> {}
            }
        }
        if (formatIdString.isNotEmpty() && formatIdString != DownloadUtil.DIRECT_FORMAT_ID) {
            request.addOption("-f", formatIdString)
            if (mergeAudioStream) {
                request.addOption("--audio-multistreams")
            }
        } else if (!convertAudio) {
            request.applyFormatSorter(settings.preferences, audioFormatSorter(settings.preferences))
        }
        // Conversion is a postprocessor, not a format pick: a user-chosen
        // audio format still has to come out as MP3/M4A when they asked for it.
        if (convertAudio) {
            when (audioConvertFormat) {
                CONVERT_MP3 -> request.addOption("--audio-format", "mp3")
                CONVERT_M4A -> request.addOption("--audio-format", "m4a")
            }
        }

        if (embedMetadata) {
            request.addOption("--embed-metadata")
            request.addOption("--embed-thumbnail")
            request.addOption("--convert-thumbnails", "jpg")

            if (cropArtwork) {
                val configFile = context.getConfigFile(id)
                FileUtil.writeContentToFile(CROP_ARTWORK_COMMAND, configFile)
                request.addOption("--config", configFile.absolutePath)
            }
        }
        request.addOption("--parse-metadata", "%(release_year,upload_date)s:%(meta_date)s")

        if (playlistUrl.isNotEmpty()) {
            request.addOption("--parse-metadata", "%(album,playlist,title)s:%(meta_album)s")
            request.addOption("--parse-metadata", "%(track_number,playlist_index)d:%(meta_track)s")
        } else {
            request.addOption("--parse-metadata", "%(album,title)s:%(meta_album)s")
        }
    }
    return request
}
