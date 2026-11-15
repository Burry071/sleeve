package com.junkfood.seal.util

import android.util.Log
import androidx.annotation.CheckResult
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.R
import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.download.CompletedNote
import com.junkfood.seal.download.MatchCandidate
import com.junkfood.seal.download.MatchResult
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.engine.DirectMedia
import com.junkfood.seal.download.engine.DownloadSettingsSnapshot
import com.junkfood.seal.download.engine.ExtractionClients
import com.junkfood.seal.download.engine.GhostHttpTransferEngine
import com.junkfood.seal.download.engine.Nm3u8TransferEngine
import com.junkfood.seal.download.engine.TransferJob
import com.junkfood.seal.download.engine.YtDlpTransferEngine
import com.junkfood.seal.download.engine.isSafeRemoteUrl as engineUrlIsSafe
import com.junkfood.seal.download.engine.uniqueFileName as engineUniqueFileName
import com.junkfood.seal.ghostengine.GhostEngine
import com.junkfood.seal.ui.page.settings.network.Cookie
import com.junkfood.seal.util.FileUtil.getArchiveFile
import com.junkfood.seal.util.FileUtil.getConfigFile
import com.junkfood.seal.util.FileUtil.getCookieFile
import com.junkfood.seal.util.PreferenceUtil.COOKIE_HEADER
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getString
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URL

object DownloadUtil {

    object CookieScheme {
        const val NAME = "name"
        const val VALUE = "value"
        const val SECURE = "is_secure"
        const val EXPIRY = "expires_utc"
        const val HOST = "host_key"
        const val PATH = "path"
    }

    internal val jsonFormat = Json { ignoreUnknownKeys = true }

    internal const val TAG = "DownloadUtil"

    const val BASENAME = "%(title).200B"

    const val EXTENSION = ".%(ext)s"

    /** Direct-lane format marker: never a real yt-dlp format id. */
    const val DIRECT_FORMAT_ID = "direct"

    private const val ID = "[%(id)s]"

    private const val CLIP_TIMESTAMP = "%(section_start)d-%(section_end)d"

    const val OUTPUT_TEMPLATE_DEFAULT = BASENAME + EXTENSION

    const val OUTPUT_TEMPLATE_ID = "$BASENAME $ID$EXTENSION"

    internal const val OUTPUT_TEMPLATE_CLIPS = "$BASENAME [$CLIP_TIMESTAMP]$EXTENSION"

    internal const val OUTPUT_TEMPLATE_CHAPTERS =
        "chapter:$BASENAME/%(section_number)d - %(section_title).200B$EXTENSION"

    internal const val OUTPUT_TEMPLATE_SPLIT = "$BASENAME/$OUTPUT_TEMPLATE_DEFAULT"

    internal const val PLAYLIST_TITLE_SUBDIRECTORY_PREFIX = "%(playlist)s/"


    @CheckResult
    fun getPlaylistOrVideoInfo(
        playlistURL: String,
        downloadPreferences: DownloadPreferences = DownloadPreferences.createFromPreferences(),
        silent: Boolean = false,
        processId: String? = null,
    ): Result<YoutubeDLInfo> =
        YoutubeDL.runCatching {
            if (!silent) {
                ToastUtil.makeToastSuspend(context.getString(R.string.fetching_playlist_info))
            }
            val request = YoutubeDLRequest(playlistURL).maybeRedditUserAgent(playlistURL)
            with(request) {
                // Native YouTube clients: no JS runtime on Android.
                addOption(
                    "--extractor-args",
                    "youtube:player_client=android_vr,android,ios",
                )
                //            addOption("--compat-options", "no-youtube-unavailable-videos")
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("-o", BASENAME)
                addOption("-R", "1")
                addOption("--socket-timeout", "5")
                downloadPreferences.run {
                    if (extractAudio) {
                        addOption("-x")
                    }
                    applyFormatSorter(this, toFormatSorter())
                    if (proxy) {
                        enableProxy(proxyUrl)
                    }
                    if (forceIpv4) {
                        addOption("-4")
                    }
                    if (cookies) {
                        enableCookies(userAgentString)
                    }
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                }
            }
            // The task id lets a canceled match kill its yt-dlp search through
            // destroyProcessById; playlist browsing keeps the URL key.
            execute(request, processId ?: playlistURL).out.run {
                val playlistInfo = jsonFormat.decodeFromString<PlaylistResult>(this)
                if (playlistInfo.type != "playlist") {
                    jsonFormat.decodeFromString<VideoInfo>(this)
                } else playlistInfo
            }
        }

    internal fun buildMetadataQuery(
        track: GhostEngine.SpotifyTrack,
        provider: String = "",
    ): String = MetadataMatch.buildMetadataQuery(track, provider)

    internal fun buildAudibleQuery(track: GhostEngine.SpotifyTrack): String =
        MetadataMatch.buildAudibleQuery(track)

    internal fun shouldWidenSearch(bestScore: Double?): Boolean =
        MetadataMatch.shouldWidenSearch(bestScore)

    internal fun matchBestCandidates(
        isActive: () -> Boolean,
        musicSearch: () -> List<GhostEngine.RankedCandidate>,
        wideSearch: () -> List<GhostEngine.RankedCandidate>,
        limit: Int = 3,
    ): List<GhostEngine.RankedCandidate>? =
        MetadataMatch.matchBestCandidates(isActive, musicSearch, wideSearch, limit)

    /** A resolved catalogue track plus the cached candidates the review sheet needs. */
    data class MetadataResolution(val videoInfo: VideoInfo, val match: MatchResult)

    internal fun noSourceResolution(
        metadata: Task.TypeInfo.MetadataTrack,
        reason: String,
    ): MetadataResolution = MetadataMatch.noSourceResolution(metadata, reason)

    internal fun GhostEngine.RankedCandidate.toMatchCandidate(): MatchCandidate =
        MatchCandidate(
            index = index,
            id = id,
            url = url.ifBlank { "https://www.youtube.com/watch?v=$id" },
            title = title,
            score = score,
            reasons = reasons,
            uploader = uploader,
            channel = channel,
            duration = duration,
        )
    internal fun GhostEngine.AudiblePart.toMatchCandidate(): MatchCandidate =
        MatchCandidate(
            index = index,
            id = id,
            url = url,
            title = title,
            score = 0.0,
            uploader = channel,
            channel = channel,
            duration = durationSeconds,
        )
    /** One gate decision: the candidate to use and whether it must be held. */
    internal data class GateDecision(
        val pick: GhostEngine.RankedCandidate,
        val held: Boolean,
        val reason: String = "",
        /**
         * Verified part set of a split, in part order. Empty for a hold and for
         * a pass: a hold has no plan, and a pass needs only its one pick.
         */
        val parts: List<GhostEngine.AudiblePart> = emptyList(),
    )

    internal fun decideGatePick(
        gate: GhostEngine.AudibleGateResult,
        ranked: List<GhostEngine.RankedCandidate>,
    ): GateDecision = MetadataMatch.decideGatePick(gate, ranked)

    @CheckResult
    fun resolveMetadataTrack(
        metadata: Task.TypeInfo.MetadataTrack,
        taskKey: String,
        preferences: DownloadPreferences,
        isActive: () -> Boolean = { true },
        /** User-edited terms that replace the catalogue-built query. */
        queryOverride: String = "",
    ): Result<MetadataResolution> =
        MetadataMatch.resolveMetadataTrack(metadata, taskKey, preferences, isActive, queryOverride)

    @CheckResult
    private fun getVideoInfo(
        request: YoutubeDLRequest,
        taskKey: String? = null,
    ): Result<VideoInfo> =
        request.runCatching {
            val response: YoutubeDLResponse =
                YoutubeDL.getInstance().execute(request, taskKey, null)
            jsonFormat.decodeFromString(response.out)
        }

    @CheckResult
    fun fetchVideoInfoFromUrl(
        url: String,
        playlistIndex: Int? = null,
        taskKey: String? = null,
        preferences: DownloadPreferences = DownloadPreferences.createFromPreferences(),
    ): Result<VideoInfo> {
        with(preferences) {
            // Extraction clients rotate on failure: YouTube's bot checks and
            // rate limits are client-specific, so a request the native clients
            // refuse often succeeds on the TV client or yt-dlp's own default.
            // Cancellation always stops the ladder.
            val clientLadder =
                when {
                    url.contains("youtube.com", true) || url.contains("youtu.be", true) ->
                        listOf(
                            "android_vr,android,ios", // native clients, no JS needed
                            "tv", // yt-dlp TV client, different challenge path
                            null, // yt-dlp's own client selection
                        )

                    else -> listOf<String?>(null)
                }

            var lastFailure: Throwable? = null
            clientLadder.forEach { clients ->
                // The download stage re-extracts with the same clients that
                // won here; a mismatch could fail a task that just worked.
                if (taskKey != null && clients != null) {
                    ExtractionClients.record(taskKey, clients)
                }
                val request =
                    YoutubeDLRequest(url)
                        .maybeRedditUserAgent(url)
                        .apply {
                            addOption("-o", BASENAME)
                            if (clients != null) {
                                // No JS runtime exists on Android, so YouTube web
                                // clients degrade (nsig throttling, missing formats).
                                // Native clients need no JavaScript.
                                addOption(
                                    "--extractor-args",
                                    "youtube:player_client=$clients",
                                )
                            }
                            if (restrictFilenames) {
                                addOption("--restrict-filenames")
                            }
                            if (extractAudio) {
                                addOption("-x")
                            }
                            applyFormatSorter(this@with, toFormatSorter())
                            if (cookies) {
                                enableCookies(userAgentString)
                            }
                            if (proxy) {
                                enableProxy(proxyUrl)
                            }
                            if (forceIpv4) {
                                addOption("-4")
                            }
                            /*            if (debug) {
                                addOption("-v")
                            }*/
                            if (autoSubtitle) {
                                addOption("--write-auto-subs")
                                if (!autoTranslatedSubtitles) {
                                    addOption("--extractor-args", "youtube:skip=translated_subs")
                                }
                            }
                            if (playlistIndex != null) {
                                addOption("--playlist-items", playlistIndex)
                                addOption("--dump-json")
                            } else {
                                addOption("--dump-single-json")
                            }
                            addOption("-R", "1")
                            addOption("--no-playlist")
                            addOption("--socket-timeout", "5")
                        }
                val result = getVideoInfo(request, taskKey)
                if (result.isSuccess) return result
                lastFailure = result.exceptionOrNull()
                if (lastFailure is YoutubeDL.CanceledException) return Result.failure(lastFailure!!)
                Log.w(
                    TAG,
                    "extraction failed${clients?.let { " (client $it)" } ?: ""}, " +
                        "ladder continues: ${lastFailure?.message?.take(160)}",
                )
            }
            return Result.failure(lastFailure ?: IllegalStateException("extraction failed"))
        }
    }

    @Serializable
    data class DownloadPreferences(
        val extractAudio: Boolean,
        val createThumbnail: Boolean,
        val downloadPlaylist: Boolean,
        val subdirectoryExtractor: Boolean,
        val subdirectoryPlaylistTitle: Boolean,
        val commandDirectory: String,
        val downloadSubtitle: Boolean,
        val embedSubtitle: Boolean,
        val keepSubtitle: Boolean,
        val subtitleLanguage: String,
        val autoSubtitle: Boolean,
        val autoTranslatedSubtitles: Boolean,
        val convertSubtitle: Int,
        val concurrentFragments: Int,
        val sponsorBlock: Boolean,
        val sponsorBlockCategory: String,
        val cookies: Boolean,
        val aria2c: Boolean,
        val useCustomAudioPreset: Boolean,
        val audioFormat: Int,
        val audioQuality: Int,
        val convertAudio: Boolean,
        val formatSorting: Boolean,
        val sortingFields: String,
        val audioConvertFormat: Int,
        val videoFormat: Int,
        val formatIdString: String,
        val videoResolution: Int,
        val privateMode: Boolean,
        val rateLimit: Boolean,
        val maxDownloadRate: String,
        val privateDirectory: Boolean,
        val cropArtwork: Boolean,
        val sdcard: Boolean,
        val sdcardUri: String,
        val embedThumbnail: Boolean,
        val videoClips: List<VideoClip>,
        val splitByChapter: Boolean,
        val debug: Boolean,
        val proxy: Boolean,
        val proxyUrl: String,
        val newTitle: String,
        val userAgentString: String,
        val outputTemplate: String,
        val useDownloadArchive: Boolean,
        val embedMetadata: Boolean,
        val restrictFilenames: Boolean,
        val supportAv1HardwareDecoding: Boolean,
        val forceIpv4: Boolean,
        val mergeAudioStream: Boolean,
        val mergeToMkv: Boolean,
        val galleryDirect: Boolean = false,
        // Page the direct media URL was resolved from (gallery-dl/embed/sniff
        // items). Instagram CDN hosts 403 without it. Default keeps backups
        // written before this field decodable.
        val referer: String = "",
        // Chaptered audio becomes one .m4b beside the audio switch. Defaulted
        // so backups written before this field still decode.
        val exportM4b: Boolean = false,
    ) {
        companion object {
            val EMPTY =
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
                    useCustomAudioPreset = false,
                    galleryDirect = false,
                    exportM4b = false,
                )

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
                    splitByChapter = false,
                    debug = DEBUG.getBoolean(),
                    proxy = PROXY.getBoolean(),
                    proxyUrl = PROXY_URL.getString(),
                    newTitle = "",
                    userAgentString =
                        USER_AGENT_STRING.run { if (USER_AGENT.getBoolean()) getString() else "" },
                    outputTemplate = OUTPUT_TEMPLATE.getString(),
                    useDownloadArchive = DOWNLOAD_ARCHIVE.getBoolean(),
                    embedMetadata = EMBED_METADATA.getBoolean(),
                    restrictFilenames = RESTRICT_FILENAMES.getBoolean(),
                    supportAv1HardwareDecoding = checkIfAv1HardwareAccelerated(),
                    forceIpv4 = FORCE_IPV4.getBoolean(),
                    mergeAudioStream = false,
                    mergeToMkv =
                        (downloadSubtitle && embedSubtitle) || MERGE_OUTPUT_MKV.getBoolean(),
                    galleryDirect = false,
                    exportM4b = EXPORT_M4B.getBoolean(),
                )
            }
        }
    }

    private fun YoutubeDLRequest.enableCookies(userAgentString: String): YoutubeDLRequest =
        this.addOption("--cookies", context.getCookieFile().absolutePath).apply {
            if (userAgentString.isNotEmpty()) {
                addOption("--add-header", "User-Agent:$userAgentString")
            }
        }

    /**
     * Reddit hard-blocks non-browser user agents before the extractor can
     * even reach the JSON API, so requests for reddit hosts present as the
     * browser this device runs. Skipped when the user set a custom UA.
     */
    private fun YoutubeDLRequest.maybeRedditUserAgent(url: String): YoutubeDLRequest =
        apply {
            if (RedditResolver.isRedditHost(url) && !USER_AGENT.getBoolean()) {
                addOption("--user-agent", RedditResolver.BROWSER_UA)
            }
        }

    private fun YoutubeDLRequest.enableProxy(proxyUrl: String): YoutubeDLRequest =
        this.addOption("--proxy", proxyUrl)

    private fun YoutubeDLRequest.useDownloadArchive(): YoutubeDLRequest =
        this.addOption("--download-archive", context.getArchiveFile().absolutePath)

    @CheckResult
    fun getCookieListFromDatabase(): Result<List<Cookie>> = CookieStore.listFromDatabase()


    fun List<Cookie>.toCookiesFileContent(): String =
        this.fold(StringBuilder(COOKIE_HEADER)) { acc, cookie ->
                acc.append(cookie.toNetscapeCookieString()).append("\n")
            }
            .toString()

    fun getCookiesContentFromDatabase(): Result<String> =
        getCookieListFromDatabase().mapCatching { it.toCookiesFileContent() }

    private fun YoutubeDLRequest.enableAria2c(): YoutubeDLRequest =
        this.addOption("--downloader", "libaria2c.so")

    @CheckResult
    private fun DownloadPreferences.toAudioFormatSorter(): String =
        this.run {
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

    @CheckResult
    private fun DownloadPreferences.toVideoFormatSorter(): String =
        this.run {
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
            val sorter = if (videoFormat == FORMAT_COMPATIBILITY) {
                connectWithDelimiter(format, res, delimiter = ",")
            } else {
                connectWithDelimiter(res, format, delimiter = ",")
            }
            return@run sorter
        }

    private fun YoutubeDLRequest.applyFormatSorter(
        preferences: DownloadPreferences,
        sorter: String,
    ) =
        preferences.run {
            if (formatSorting && sortingFields.isNotEmpty()) addOption("-S", sortingFields)
            else if (sorter.isNotEmpty()) addOption("-S", sorter) else {}
        }

    @CheckResult
    fun DownloadPreferences.toFormatSorter(): String =
        connectWithDelimiter(
            this.toVideoFormatSorter(),
            this.toAudioFormatSorter(),
            delimiter = ",",
        )

    /** @return true when a lane registered this id and cancelled it. */
    fun cancelGhostTask(taskId: String): Boolean = GhostHttpTransferEngine.cancel(taskId)

    /** @return true when a running N_m3u8DL-RE process was terminated. */
    fun cancelNm3u8DlRe(taskId: String): Boolean = Nm3u8TransferEngine.cancel(taskId)

    /** Remote-URL guard shared by engine and N_m3u8DL-RE routes. */
    internal fun isSafeRemoteUrl(url: String): Boolean = engineUrlIsSafe(url)

    /**
     * First free "name.ext", "name (1).ext", … inside [dir]. Files under
     * Download/Sleeve survive re-installs, and a name owned by another app UID
     * cannot be replaced (EPERM), so never plan to overwrite one.
     */
    internal fun uniqueFileName(dir: String, name: String): String = engineUniqueFileName(dir, name)

    /** Extensions that mark a direct file as audio for directory routing. */

    /** Extensions that mark a bare direct media link (a CDN asset such as
     * instagram.fbcdn/…jpg or video.twimg/…mp4?tag=29). These must bypass
     * yt-dlp's generic extractor: generic makes the whole URL the video id,
     * so the default "[%(id)s]" output template produces 700-byte filenames
     * (Errno 36) and plain video files hit "Requested format is not
     * available". */
    // Routing table lives in DirectMediaRules (pure, unit-tested) — the
    // user's .heic Instagram share was missing from it and fell into the
    // generic extractor.
    val DIRECT_MEDIA_EXTS get() = DirectMediaRules.MEDIA_EXTS

    fun isDirectMediaUrl(url: String): Boolean = DirectMediaRules.isDirectMediaUrl(url)

    fun isDirectImageUrl(url: String): Boolean = DirectMediaRules.isDirectImageUrl(url)

    @CheckResult
    suspend fun downloadVideo(
        videoInfo: VideoInfo? = null,
        playlistUrl: String = "",
        playlistItem: Int = 0,
        taskId: String,
        downloadPreferences: DownloadPreferences,
        progressCallback: ((Float, Long, String) -> Unit)?,
        /**
         * yt-dlp process ids must never repeat: the vendor keeps a process map
         * keyed by id and a killed-but-unreaped entry makes every later execute
         * with that id fail with "Process ID already exists" until the app
         * process dies. Each attempt asks for a fresh id instead.
         */
        processIdFactory: () -> String = { taskId },
        /** Reports the id of the attempt about to run, so cancel targets it. */
        onProcessStarted: (String) -> Unit = {},
        /** False once the task was canceled; checked before every attempt. */
        isActive: () -> Boolean = { true },
        /** Catalogue fields for the .m4b export, when the task has them. */
        m4bTagOverrides: M4bTagOverrides? = null,
        /** A secondary remark the card should carry on success. */
        onNote: (CompletedNote) -> Unit = {},
        /** Duration of the first published local file, measured before any move. */
        onLocalDurationMs: (Long) -> Unit = {},
    ): Result<List<String>> {
        if (videoInfo == null)
            return Result.failure(Throwable(context.getString(R.string.fetch_info_error_msg)))

        // Direct-media items (gallery/embed/synth CDN links) ARE the final
        // artifact: yt-dlp's thumbnail postprocessor errors on them
        // ("Postprocessing: Error opening output files"), and a picture does
        // not need a thumbnail of itself. Stripping the flags also lets the
        // ghost handoff accept the task again when the user saves thumbnails.
        val directItem = downloadPreferences.galleryDirect ||
            videoInfo.extractorKey == "SleeveDirect"
        val downloadPreferences =
            if (directItem)
                downloadPreferences.copy(createThumbnail = false, embedThumbnail = false)
            else downloadPreferences

        if (!isActive()) return Result.failure(CancellationException("task canceled"))

        val url =
            playlistUrl.ifEmpty {
                videoInfo.originalUrl
                    ?: videoInfo.webpageUrl
                    ?: return Result.failure(
                        Throwable(context.getString(R.string.fetch_info_error_msg))
                    )
            }

        // One settings snapshot for the whole task: no lane re-reads
        // preference statics mid-transfer, and every rung sees the same data.
        val job =
            TransferJob(
                videoInfo = videoInfo,
                settings = DownloadSettingsSnapshot.capture(downloadPreferences),
                url = url,
                taskId = taskId,
                playlistUrl = playlistUrl,
                playlistItem = playlistItem,
                progressCallback = progressCallback,
                processIdFactory = processIdFactory,
                onProcessStarted = onProcessStarted,
                isActive = isActive,
                m4bTagOverrides = m4bTagOverrides,
                onNote = onNote,
                onLocalDurationMs = onLocalDurationMs,
            )

        // HLS/DASH first: the bundled N_m3u8DL-RE handles segmented
        // manifests; direct progressive files fall through to the engine.
        Nm3u8TransferEngine.transfer(job)?.let { return it }

        // Ghost engine handoff: when the embedded engine can transfer this
        // download itself, it reports progress through the same callback and
        // finishes through the same history insertion as the yt-dlp path.
        // A failed handoff must not end the task: CDNs can reject the plain
        // transfer (googlevideo answers 403 for range GETs) while yt-dlp
        // succeeds with its own headers, so fall through to that ladder.
        GhostHttpTransferEngine.transfer(job)?.let { ghostResult ->
            if (ghostResult.isSuccess) return ghostResult
            Log.w(
                TAG,
                "Ghost handoff failed, retrying via yt-dlp: ${ghostResult.exceptionOrNull()?.message}",
            )
        }

        // The stock ladder claims every job it is given.
        return YtDlpTransferEngine.transfer(job)
            ?: Result.failure(IllegalStateException("download failed"))
    }

    /**
     * Minimal VideoInfo for a raw media URL (gallery-dl item, sniffed page
     * asset): one synthetic "direct" format so the Ghost handoff transfers the
     * bytes without yt-dlp extraction. The bounded HEAD probe behind the
     * default keeps the queue card's size real instead of a booked zero.
     */
    fun buildDirectVideoInfo(
        url: String,
        title: String,
        contentLength: Long = fetchContentLength(url),
    ): VideoInfo? = DirectMedia.buildDirectVideoInfo(url, title, contentLength)

    private fun fetchContentLength(url: String): Long = DirectMedia.fetchContentLength(url)


    @CheckResult
    fun executeCustomCommandTask(
        urlString: String,
        taskId: String,
        template: CommandTemplate,
        preferences: DownloadPreferences,
        progressCallback: ((Float, Long, String) -> Unit),
    ): Result<YoutubeDLResponse> {
        val urlList = urlString.split(Regex("[\n ]")).filter { it.isNotBlank() }

        val request =
            with(preferences) {
                YoutubeDLRequest(urlList).apply {
                    commandDirectory.takeIf { it.isNotEmpty() }?.let { addOption("-P", it) }
                    addOption("--newline")
                    if (aria2c) {
                        enableAria2c()
                    }
                    if (useDownloadArchive) {
                        useDownloadArchive()
                    }
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                    addOption(
                        "--config-locations",
                        FileUtil.writeContentToFile(template.template, context.getConfigFile())
                            .absolutePath,
                    )
                    if (cookies) {
                        enableCookies(userAgentString)
                    }
                }
            }

        return runCatching {
            YoutubeDL.getInstance()
                .execute(request = request, processId = taskId, callback = progressCallback)
        }
    }

    private fun checkIfAv1HardwareAccelerated(): Boolean =
        DeviceCapabilities.av1HardwareAccelerated()

}
