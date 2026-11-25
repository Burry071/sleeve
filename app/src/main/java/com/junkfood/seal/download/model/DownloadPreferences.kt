package com.junkfood.seal.download.model

import com.junkfood.seal.util.ARIA2C
import com.junkfood.seal.util.AUDIO_CONVERT
import com.junkfood.seal.util.AUDIO_FORMAT
import com.junkfood.seal.util.AUDIO_QUALITY
import com.junkfood.seal.util.AUTO_SUBTITLE
import com.junkfood.seal.util.AUTO_TRANSLATED_SUBTITLES
import com.junkfood.seal.util.COMMAND_DIRECTORY
import com.junkfood.seal.util.CONCURRENT
import com.junkfood.seal.util.CONVERT_SUBTITLE
import com.junkfood.seal.util.COOKIES
import com.junkfood.seal.util.CROP_ARTWORK
import com.junkfood.seal.util.DEBUG
import com.junkfood.seal.util.DOWNLOAD_ARCHIVE
import com.junkfood.seal.util.DeviceCapabilities
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.EMBED_METADATA
import com.junkfood.seal.util.EMBED_SUBTITLE
import com.junkfood.seal.util.EMBED_THUMBNAIL
import com.junkfood.seal.util.EXPORT_M4B
import com.junkfood.seal.util.EXTRACT_AUDIO
import com.junkfood.seal.util.FORCE_IPV4
import com.junkfood.seal.util.FORMAT_SORTING
import com.junkfood.seal.util.KEEP_SUBTITLE_FILES
import com.junkfood.seal.util.MERGE_OUTPUT_MKV
import com.junkfood.seal.util.OUTPUT_TEMPLATE
import com.junkfood.seal.util.PLAYLIST
import com.junkfood.seal.util.PRIVATE_DIRECTORY
import com.junkfood.seal.util.PRIVATE_MODE
import com.junkfood.seal.util.PROXY
import com.junkfood.seal.util.PROXY_URL
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.RATE_LIMIT
import com.junkfood.seal.util.RESTRICT_FILENAMES
import com.junkfood.seal.util.SDCARD_DOWNLOAD
import com.junkfood.seal.util.SDCARD_URI
import com.junkfood.seal.util.SORTING_FIELDS
import com.junkfood.seal.util.SPLIT_BY_CHAPTER
import com.junkfood.seal.util.SPONSORBLOCK
import com.junkfood.seal.util.SUBDIRECTORY_EXTRACTOR
import com.junkfood.seal.util.SUBDIRECTORY_PLAYLIST_TITLE
import com.junkfood.seal.util.SUBTITLE
import com.junkfood.seal.util.SUBTITLE_LANGUAGE
import com.junkfood.seal.util.THUMBNAIL
import com.junkfood.seal.util.USER_AGENT
import com.junkfood.seal.util.USER_AGENT_STRING
import com.junkfood.seal.util.USE_CUSTOM_AUDIO_PRESET

/**
 * Top-level alias for [DownloadUtil.DownloadPreferences].
 *
 * The `@Serializable` data class stays nested inside [DownloadUtil] because Kotlin forbids nested
 * `typealias` declarations inside `object`, and serialized task backups and existing callers
 * reference `DownloadUtil.DownloadPreferences` directly.
 */
typealias DownloadPreferences = DownloadUtil.DownloadPreferences

/**
 * Resolves [DownloadPreferences] snapshots from stored MMKV settings and provides pure preference
 * resolution helpers shared across download entry points.
 */
object DownloadPreferencesFactory {

    val EMPTY: DownloadPreferences =
        DownloadPreferences(
            extractAudio = false,
            createThumbnail = false,
            downloadPlaylist = false,
            subdirectoryExtractor = false,
            subdirectoryPlaylistTitle = false,
            commandDirectory = "",
            downloadSubtitle = false,
            embedSubtitle = false,
            keepSubtitle = false,
            subtitleLanguage = "",
            autoSubtitle = false,
            autoTranslatedSubtitles = false,
            convertSubtitle = 0,
            concurrentFragments = 0,
            sponsorBlock = false,
            sponsorBlockCategory = "",
            cookies = false,
            aria2c = false,
            useCustomAudioPreset = false,
            audioFormat = 0,
            audioQuality = 0,
            convertAudio = false,
            formatSorting = false,
            sortingFields = "",
            audioConvertFormat = 0,
            videoFormat = 0,
            formatIdString = "",
            videoResolution = 0,
            privateMode = false,
            rateLimit = false,
            maxDownloadRate = "",
            privateDirectory = false,
            cropArtwork = false,
            sdcard = false,
            sdcardUri = "",
            embedThumbnail = false,
            videoClips = emptyList(),
            splitByChapter = false,
            debug = false,
            proxy = false,
            proxyUrl = "",
            newTitle = "",
            userAgentString = "",
            outputTemplate = "",
            useDownloadArchive = false,
            embedMetadata = false,
            restrictFilenames = false,
            supportAv1HardwareDecoding = false,
            forceIpv4 = false,
            mergeAudioStream = false,
            mergeToMkv = false,
            galleryDirect = false,
            exportM4b = false,
        )

    internal fun shouldMergeToMkv(
        downloadSubtitle: Boolean,
        embedSubtitle: Boolean,
        mergeOutputMkv: Boolean,
    ): Boolean = (downloadSubtitle && embedSubtitle) || mergeOutputMkv

    internal fun resolveUserAgentString(
        customUserAgentEnabled: Boolean,
        storedUserAgent: () -> String,
    ): String = if (customUserAgentEnabled) storedUserAgent() else ""

    fun createFromPreferences(): DownloadPreferences {
        val downloadSubtitle = SUBTITLE.getBoolean()
        val embedSubtitle = EMBED_SUBTITLE.getBoolean()
        return DownloadPreferences(
            extractAudio = EXTRACT_AUDIO.getBoolean(),
            createThumbnail = THUMBNAIL.getBoolean(),
            downloadPlaylist = PLAYLIST.getBoolean(),
            subdirectoryExtractor = SUBDIRECTORY_EXTRACTOR.getBoolean(),
            subdirectoryPlaylistTitle = SUBDIRECTORY_PLAYLIST_TITLE.getBoolean(),
            commandDirectory = COMMAND_DIRECTORY.getString(),
            downloadSubtitle = downloadSubtitle,
            embedSubtitle = embedSubtitle,
            keepSubtitle = KEEP_SUBTITLE_FILES.getBoolean(),
            subtitleLanguage = SUBTITLE_LANGUAGE.getString(),
            autoSubtitle = AUTO_SUBTITLE.getBoolean(),
            autoTranslatedSubtitles = AUTO_TRANSLATED_SUBTITLES.getBoolean(),
            convertSubtitle = CONVERT_SUBTITLE.getInt(),
            concurrentFragments = CONCURRENT.getInt(),
            sponsorBlock = SPONSORBLOCK.getBoolean(),
            sponsorBlockCategory = PreferenceUtil.getSponsorBlockCategories(),
            cookies = COOKIES.getBoolean(),
            aria2c = ARIA2C.getBoolean(),
            useCustomAudioPreset = USE_CUSTOM_AUDIO_PRESET.getBoolean(),
            audioFormat = AUDIO_FORMAT.getInt(),
            audioQuality = AUDIO_QUALITY.getInt(),
            convertAudio = AUDIO_CONVERT.getBoolean(),
            formatSorting = FORMAT_SORTING.getBoolean(),
            sortingFields = SORTING_FIELDS.getString(),
            audioConvertFormat = PreferenceUtil.getAudioConvertFormat(),
            videoFormat = PreferenceUtil.getVideoFormat(),
            formatIdString = "",
            videoResolution = PreferenceUtil.getVideoResolution(),
            privateMode = PRIVATE_MODE.getBoolean(),
            rateLimit = RATE_LIMIT.getBoolean(),
            maxDownloadRate = PreferenceUtil.getMaxDownloadRate(),
            privateDirectory = PRIVATE_DIRECTORY.getBoolean(),
            cropArtwork = CROP_ARTWORK.getBoolean(),
            sdcard = SDCARD_DOWNLOAD.getBoolean(),
            sdcardUri = SDCARD_URI.getString(),
            embedThumbnail = EMBED_THUMBNAIL.getBoolean(),
            videoClips = emptyList(),
            splitByChapter = SPLIT_BY_CHAPTER.getBoolean(),
            debug = DEBUG.getBoolean(),
            proxy = PROXY.getBoolean(),
            proxyUrl = PROXY_URL.getString(),
            newTitle = "",
            userAgentString =
                resolveUserAgentString(
                    customUserAgentEnabled = USER_AGENT.getBoolean(),
                    storedUserAgent = { USER_AGENT_STRING.getString() },
                ),
            outputTemplate = OUTPUT_TEMPLATE.getString(),
            useDownloadArchive = DOWNLOAD_ARCHIVE.getBoolean(),
            embedMetadata = EMBED_METADATA.getBoolean(),
            restrictFilenames = RESTRICT_FILENAMES.getBoolean(),
            supportAv1HardwareDecoding = DeviceCapabilities.av1HardwareAccelerated(),
            forceIpv4 = FORCE_IPV4.getBoolean(),
            mergeAudioStream = false,
            mergeToMkv =
                shouldMergeToMkv(
                    downloadSubtitle = downloadSubtitle,
                    embedSubtitle = embedSubtitle,
                    mergeOutputMkv = MERGE_OUTPUT_MKV.getBoolean(),
                ),
            galleryDirect = false,
            exportM4b = EXPORT_M4B.getBoolean(),
        )
    }
}
