package com.junkfood.seal.util

import android.os.Build
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import android.media.MediaCodecList
import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.CheckResult
import com.junkfood.seal.App
import com.junkfood.seal.App.Companion.audioDownloadDir
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.App.Companion.videoDownloadDir
import com.junkfood.seal.Downloader
import com.junkfood.seal.Downloader.onProcessEnded
import com.junkfood.seal.Downloader.onProcessStarted
import com.junkfood.seal.Downloader.onTaskEnded
import com.junkfood.seal.Downloader.onTaskError
import com.junkfood.seal.Downloader.onTaskStarted
import com.junkfood.seal.Downloader.toNotificationId
import com.junkfood.seal.R
import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.download.Task
import com.junkfood.seal.ghostengine.GhostEngine
import com.junkfood.seal.ghostengine.GhostEngine.TaskState
import com.junkfood.seal.ui.page.settings.network.Cookie
import com.junkfood.seal.util.FileUtil.getArchiveFile
import com.junkfood.seal.util.FileUtil.getConfigFile
import com.junkfood.seal.util.FileUtil.getCookieFile
import com.junkfood.seal.util.FileUtil.getExternalTempDir
import com.junkfood.seal.util.FileUtil.getFileName
import com.junkfood.seal.util.FileUtil.getSdcardTempDir
import com.junkfood.seal.util.FileUtil.moveFilesToSdcard
import com.junkfood.seal.util.PreferenceUtil.COOKIE_HEADER
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object DownloadUtil {

    object CookieScheme {
        const val NAME = "name"
        const val VALUE = "value"
        const val SECURE = "is_secure"
        const val EXPIRY = "expires_utc"
        const val HOST = "host_key"
        const val PATH = "path"
    }

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    private const val TAG = "DownloadUtil"

    const val BASENAME = "%(title).200B"

    const val EXTENSION = ".%(ext)s"

    /** Direct-lane format marker: never a real yt-dlp format id. */
    const val DIRECT_FORMAT_ID = "direct"

    private const val ID = "[%(id)s]"

    private const val CLIP_TIMESTAMP = "%(section_start)d-%(section_end)d"

    const val OUTPUT_TEMPLATE_DEFAULT = BASENAME + EXTENSION

    const val OUTPUT_TEMPLATE_ID = "$BASENAME $ID$EXTENSION"

    private const val OUTPUT_TEMPLATE_CLIPS = "$BASENAME [$CLIP_TIMESTAMP]$EXTENSION"

    private const val OUTPUT_TEMPLATE_CHAPTERS =
        "chapter:$BASENAME/%(section_number)d - %(section_title).200B$EXTENSION"

    private const val OUTPUT_TEMPLATE_SPLIT = "$BASENAME/$OUTPUT_TEMPLATE_DEFAULT"

    private const val PLAYLIST_TITLE_SUBDIRECTORY_PREFIX = "%(playlist)s/"

    private const val CROP_ARTWORK_COMMAND =
        """--ppa "ffmpeg: -c:v mjpeg -vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\"""""

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

    /**
     * Normalized YouTube query for a catalogue track. Audible tracks get the
     * quoted-title form: their [GhostEngine.SpotifyTrack.artist] carries the
     * narrator roll ("Author (narrated by A, B, ... 12 names)"), which drags
     * the search away from the actual audiobook upload.
     */
    internal fun buildMetadataQuery(
        track: GhostEngine.SpotifyTrack,
        provider: String = "",
    ): String =
        if (provider.equals(AUDIBLE_PROVIDER, ignoreCase = true)) buildAudibleQuery(track)
        else
            listOf(track.artist, track.title, "audio")
                .filter { it.isNotBlank() }
                .joinToString(separator = " ")
                .let(::collapse)

    /** "<exact title>" <authors> audiobook — falls back to the quoted title. */
    internal fun buildAudibleQuery(track: GhostEngine.SpotifyTrack): String {
        val title =
            collapse(track.title.replace('"', ' ')).takeIf { it.isNotBlank() }?.let { "\"$it\"" }
        val authors = collapse(track.authors).takeIf { it.isNotBlank() }
        return listOfNotNull(title, authors, "audiobook").joinToString(separator = " ")
    }

    private fun collapse(value: String): String = Regex("\\s+").replace(value, " ").trim()

    private const val AUDIBLE_PROVIDER = "Audible"

    /**
     * The ranker scores candidates 0-100. Anything under 60 is not a confident
     * match, so the plain-YouTube pool is searched as well.
     */
    private const val WIDEN_BELOW_SCORE = 60.0

    /** A missing or weak YouTube Music match widens to a plain YouTube search. */
    internal fun shouldWidenSearch(bestScore: Double?): Boolean =
        bestScore == null || bestScore < WIDEN_BELOW_SCORE

    /**
     * Picks the winner between the YouTube Music pool and the plain-YouTube
     * pool. A floor-rejected music pool arrives here as null instead of as an
     * error, so the widening leg still runs. [isActive] is polled before the
     * second search and after it, so a cancel never leaves that search running.
     */
    internal fun matchBestCandidate(
        isActive: () -> Boolean,
        musicSearch: () -> GhostEngine.RankedCandidate?,
        wideSearch: () -> GhostEngine.RankedCandidate?,
    ): GhostEngine.RankedCandidate? {
        val musicBest = musicSearch()
        if (!isActive()) return null
        if (!shouldWidenSearch(musicBest?.score)) return musicBest
        val best = listOfNotNull(musicBest, wideSearch()).maxByOrNull { it.score }
        return if (isActive()) best else null
    }

    /** Cancellation bail-out for [resolveMetadataTrack]; returned, never thrown. */
    private fun matchCanceled(): Result<VideoInfo> =
        Result.failure(IllegalStateException("match canceled"))

    /**
     * spotDL-pattern matching for one catalogue track: rank a YouTube Music pool,
     * widen when confidence is low, then resolve the winner through the normal
     * extractor so the download stage reuses the winning client.
     *
     * [isActive] is polled between the legs. A cancel destroys the in-flight
     * yt-dlp process, which reads as a failed (empty) search here; without the
     * poll the widening leg and the extraction would spawn two more processes
     * that no later cancel can reach.
     */
    @CheckResult
    fun resolveMetadataTrack(
        metadata: Task.TypeInfo.MetadataTrack,
        taskKey: String,
        preferences: DownloadPreferences,
        isActive: () -> Boolean = { true },
    ): Result<VideoInfo> =
        runCatching {
            val track =
                GhostEngine.SpotifyTrack(
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album,
                    durationMs = metadata.durationMs,
                    authors = metadata.authors,
                )
            val query = buildMetadataQuery(track, metadata.provider)
            Log.i(TAG, "match query: $query")

            fun search(pool: String): List<PlaylistEntry> =
                getPlaylistOrVideoInfo(
                        playlistURL = "$pool:$query",
                        downloadPreferences = preferences,
                        silent = true,
                        processId = taskKey,
                    )
                    .map { it as? PlaylistResult }
                    .getOrElse { null }
                    ?.entries
                    .orEmpty()

            var rankError: Throwable? = null

            // A floor rejection is a failed ranking, not a fatal error: the
            // plain-YouTube pool still needs its chance before the task fails.
            fun ranked(pool: String): GhostEngine.RankedCandidate? {
                val candidates = search(pool)
                if (candidates.isEmpty()) return null
                return GhostEngine.rankCandidates(
                        track = track,
                        candidatesJson = jsonFormat.encodeToString(candidates),
                        strictness = RANKING_STRICTNESS.getInt(),
                    )
                    .onFailure { rankError = it }
                    .getOrNull()
            }

            val best =
                matchBestCandidate(
                    isActive = isActive,
                    musicSearch = { ranked("ytmsearch5") },
                    wideSearch = { ranked("ytsearch10") },
                )

            // A canceled first leg degrades to an empty pool, so stop before
            // extraction spawns a process no later cancel can reach.
            if (!isActive()) return matchCanceled()

            if (best == null) {
                throw IllegalStateException(
                    "No confident YouTube match for \"${track.title}\" by \"${track.artist}\"" +
                        (rankError?.message?.let { ": $it" } ?: "")
                )
            }

            val resolvedUrl = best.url.ifBlank { "https://www.youtube.com/watch?v=" + best.id }
            val resolved =
                fetchVideoInfoFromUrl(
                        url = resolvedUrl,
                        preferences = preferences,
                        taskKey = taskKey,
                    )
                    .getOrThrow()
            if (!isActive()) return matchCanceled()
            resolved
        }

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
                    successfulExtractionClients[taskKey] = clients
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
    fun getCookieListFromDatabase(): Result<List<Cookie>> = runCatching {
        CookieManager.getInstance().run {
            if (!hasCookies()) throw Exception("There is no cookies in the database!")
            flush()
        }
        SQLiteDatabase.openDatabase(
                context.dataDir.resolve("app_webview/Default/Cookies").absolutePath,
                null,
                OPEN_READONLY,
            )
            .run {
                val projection =
                    arrayOf(
                        CookieScheme.HOST,
                        CookieScheme.EXPIRY,
                        CookieScheme.PATH,
                        CookieScheme.NAME,
                        CookieScheme.VALUE,
                        CookieScheme.SECURE,
                    )
                val cookieList = mutableListOf<Cookie>()
                query("cookies", projection, null, null, null, null, null).run {
                    while (moveToNext()) {
                        val expiry = getLong(getColumnIndexOrThrow(CookieScheme.EXPIRY))
                        val name = getString(getColumnIndexOrThrow(CookieScheme.NAME))
                        val value = getString(getColumnIndexOrThrow(CookieScheme.VALUE))
                        val path = getString(getColumnIndexOrThrow(CookieScheme.PATH))
                        val secure = getLong(getColumnIndexOrThrow(CookieScheme.SECURE)) == 1L
                        val hostKey = getString(getColumnIndexOrThrow(CookieScheme.HOST))

                        val host = if (hostKey[0] != '.') ".$hostKey" else hostKey
                        cookieList.add(
                            Cookie(
                                domain = host,
                                name = name,
                                value = value,
                                path = path,
                                secure = secure,
                                expiry = expiry,
                            )
                        )
                    }
                    close()
                }
                close()
                cookieList
            }
    }

    fun List<Cookie>.toCookiesFileContent(): String =
        this.fold(StringBuilder(COOKIE_HEADER)) { acc, cookie ->
                acc.append(cookie.toNetscapeCookieString()).append("\n")
            }
            .toString()

    fun getCookiesContentFromDatabase(): Result<String> =
        getCookieListFromDatabase().mapCatching { it.toCookiesFileContent() }

    private fun YoutubeDLRequest.enableAria2c(): YoutubeDLRequest =
        this.addOption("--downloader", "libaria2c.so")

    private fun YoutubeDLRequest.addOptionsForVideoDownloads(
        downloadPreferences: DownloadPreferences
    ): YoutubeDLRequest =
        this.apply {
            downloadPreferences.run {
                addOption("--add-metadata")
                addOption("--no-embed-info-json")
                // "direct" is the direct-lane marker, not a yt-dlp format id.
                // When such a task falls back to yt-dlp (e.g. a manifest URL
                // with the Ghost engine off), the format sorter must pick the
                // format — `-f direct` always fails format selection.
                if (formatIdString.isNotEmpty() && formatIdString != DIRECT_FORMAT_ID) {
                    addOption("-f", formatIdString)
                    if (mergeAudioStream) {
                        addOption("--audio-multistreams")
                    }
                } else {
                    applyFormatSorter(this, toFormatSorter())
                }
                if (downloadSubtitle) {
                    if (autoSubtitle) {
                        addOption("--write-auto-subs")
                        if (!autoTranslatedSubtitles) {
                            addOption("--extractor-args", "youtube:skip=translated_subs")
                        }
                    }
                    subtitleLanguage
                        .takeIf { it.isNotEmpty() }
                        ?.let { addOption("--sub-langs", it) }
                    if (embedSubtitle) {
                        addOption("--embed-subs")
                        if (keepSubtitle) {
                            addOption("--write-subs")
                        }
                    } else {
                        addOption("--write-subs")
                    }
                    when (convertSubtitle) {
                        CONVERT_ASS -> addOption("--convert-subs", "ass")
                        CONVERT_SRT -> addOption("--convert-subs", "srt")
                        CONVERT_VTT -> addOption("--convert-subs", "vtt")
                        CONVERT_LRC -> addOption("--convert-subs", "lrc")
                        else -> {}
                    }
                }
                if (mergeToMkv) {
                    addOption("--remux-video", "mkv")
                    addOption("--merge-output-format", "mkv")
                }
                if (embedThumbnail) {
                    addOption("--embed-thumbnail")
                }
                if (videoClips.isEmpty()) addOption("--embed-chapters")
            }
        }

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

    private fun YoutubeDLRequest.addOptionsForAudioDownloads(
        id: String,
        preferences: DownloadPreferences,
        playlistUrl: String,
    ): YoutubeDLRequest =
        this.apply {
            with(preferences) {
                addOption("-x")
                if (downloadSubtitle) {
                    addOption("--write-subs")

                    if (autoSubtitle) {
                        addOption("--write-auto-subs")
                        if (!autoTranslatedSubtitles) {
                            addOption("--extractor-args", "youtube:skip=translated_subs")
                        }
                    }
                    subtitleLanguage
                        .takeIf { it.isNotEmpty() }
                        ?.let { addOption("--sub-langs", it) }
                    when (convertSubtitle) {
                        CONVERT_ASS -> addOption("--convert-subs", "ass")
                        CONVERT_SRT -> addOption("--convert-subs", "srt")
                        CONVERT_VTT -> addOption("--convert-subs", "vtt")
                        CONVERT_LRC -> addOption("--convert-subs", "lrc")
                        else -> {}
                    }
                }
                if (formatIdString.isNotEmpty() && formatIdString != DIRECT_FORMAT_ID) {
                    addOption("-f", formatIdString)
                    if (mergeAudioStream) {
                        addOption("--audio-multistreams")
                    }
                } else if (convertAudio) {
                    when (audioConvertFormat) {
                        CONVERT_MP3 -> {
                            addOption("--audio-format", "mp3")
                        }

                        CONVERT_M4A -> {
                            addOption("--audio-format", "m4a")
                        }
                    }
                } else {
                    applyFormatSorter(preferences, toAudioFormatSorter())
                }

                if (embedMetadata) {
                    addOption("--embed-metadata")
                    addOption("--embed-thumbnail")
                    addOption("--convert-thumbnails", "jpg")

                    if (cropArtwork) {
                        val configFile = context.getConfigFile(id)
                        FileUtil.writeContentToFile(CROP_ARTWORK_COMMAND, configFile)
                        addOption("--config", configFile.absolutePath)
                    }
                }
                addOption("--parse-metadata", "%(release_year,upload_date)s:%(meta_date)s")

                if (playlistUrl.isNotEmpty()) {
                    addOption("--parse-metadata", "%(album,playlist,title)s:%(meta_album)s")
                    addOption("--parse-metadata", "%(track_number,playlist_index)d:%(meta_track)s")
                } else {
                    addOption("--parse-metadata", "%(album,title)s:%(meta_album)s")
                }
            }
        }

    private fun insertInfoIntoDownloadHistory(
        videoInfo: VideoInfo,
        filePaths: List<String>,
    ): List<String> =
        filePaths.onEach {
            DatabaseUtil.insertInfo(videoInfo.toDownloadedVideoInfo(videoPath = it))
        }

    private fun VideoInfo.toDownloadedVideoInfo(
        id: Int = 0,
        videoPath: String,
    ): DownloadedVideoInfo =
        this.run {
            DownloadedVideoInfo(
                id = id,
                videoTitle = title,
                videoAuthor = uploader ?: channel ?: uploaderId.toString(),
                videoUrl = webpageUrl ?: originalUrl.toString(),
                thumbnailUrl = thumbnail.toHttpsUrl(),
                videoPath = videoPath,
                extractor = extractorKey,
            )
        }

    private fun insertSplitChapterIntoHistory(videoInfo: VideoInfo, filePaths: List<String>) =
        filePaths.onEach {
            DatabaseUtil.insertInfo(
                videoInfo.toDownloadedVideoInfo(videoPath = it).copy(videoTitle = it.getFileName())
            )
        }

    /** processId -> embedded-engine task id, for cancellation routing. */
    private val ghostTasks = ConcurrentHashMap<String, String>()

    /** @return true when an engine task was registered for this id and cancelled. */
    fun cancelGhostTask(taskId: String): Boolean =
        ghostTasks.remove(taskId)?.also { GhostEngine.cancel(it) } != null

    /** Client set that last won extraction, per task key (YouTube ladder). */
    private val successfulExtractionClients = ConcurrentHashMap<String, String>()

    /** Live N_m3u8DL-RE processes by task id, so cancel can kill them. */
    private val nreProcesses = ConcurrentHashMap<String, Process>()

    /** @return true when a running N_m3u8DL-RE process was terminated. */
    fun cancelNm3u8DlRe(taskId: String): Boolean =
        (nreProcesses.remove(taskId)?.also { it.destroy() }) != null


    /** Extensions that mark a direct file as audio for directory routing. */
    private val DIRECT_AUDIO_EXTS = AUDIO_FILE_EXTENSIONS

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

    /** Remote-URL guard shared by engine and N_m3u8DL-RE routes. */
    private val PRIVATE_HOST_RE =
        Regex(
            "^(localhost|127\\.|10\\.|0\\.|169\\.254\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\." +
                "|\\[::1\\]|\\[fc|\\[fd|\\[fe80)",
            RegexOption.IGNORE_CASE,
        )

    /** Shared with the direct-lane resolvers: page content decides what gets fetched. */
    internal fun isSafeRemoteUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val rest = url.substringAfter("://")
        // Bracketed IPv6 hosts must not be split on ':' (would leave "[" and
        // bypass the blocklist).
        val hostPart =
            if (rest.startsWith("["))
                rest.substringBefore(']')
            else
                rest.substringBefore('/').substringBefore(':')
        // Match the pattern against the token as-is: IPv6 alternatives in the
        // blocklist are written bracketed ("[::1]", "[fc", "[fd").
        return hostPart.length > 2 && !PRIVATE_HOST_RE.containsMatchIn(hostPart)
    }

    private val ELF_MACHINES =
        mapOf(
            "arm64-v8a" to 183L, // EM_AARCH64
            "x86_64" to 62L, // EM_X86_64
        )

    /** True when the bundled ELF binary matches one of the device's ABIs. */
    private fun canExecuteElf(file: File): Boolean {
        if (!file.exists()) return false
        return runCatching {
            val header = file.inputStream().use { it.readNBytes(20) }
            if (header.size < 20 || header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte())
                return false
            val machine = ((header[19].toLong() and 0xFF) shl 8) or (header[18].toLong() and 0xFF)
            val abi =
                Build.SUPPORTED_ABIS.firstOrNull { ELF_MACHINES.containsKey(it) }
                    ?: return false
            ELF_MACHINES[abi] == machine
        }.getOrDefault(false)
    }

    private fun nativeBinary(name: String): File? {
        val dir = App.context.applicationInfo.nativeLibraryDir ?: return null
        val f = File(dir, name)
        return if (f.exists()) f else null
    }

    /**
     * First free "name.ext", "name (1).ext", … inside [dir]. Files under
     * Download/Sleeve survive re-installs, and a name owned by another app UID
     * cannot be replaced (EPERM), so never plan to overwrite one.
     */
    private fun uniqueFileName(dir: String, name: String): String {
        if (!File(dir, name).exists()) return name
        val stem = name.substringBeforeLast('.', name)
        val suffix = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var index = 1
        while (true) {
            val candidate = "$stem ($index)$suffix"
            if (!File(dir, candidate).exists()) return candidate
            index++
        }
    }

    /**
     * HLS/DASH handoff to the bundled N_m3u8DL-RE (Ghost-Downloader's binary,
     * GPL-3.0): multi-threaded segment download, AES-128 decryption, muxing.
     * Manifest URLs only; everything else fails open to yt-dlp.
     *
     * @return null when this download was NOT handled (caller falls back).
     */
    private fun runNm3u8DlReIfEligible(
        videoInfo: VideoInfo,
        preferences: DownloadPreferences,
        taskId: String,
        progressCallback: ((Float, Long, String) -> Unit)?,
    ): Result<List<String>>? {
        if (!GHOST_ENGINE.getBoolean()) return null
        val bin =
            nativeBinary("libnm3u8dlre.so")
                ?.takeIf { canExecuteElf(it) }
                ?: return null.also {
                    Log.d(TAG, "N_m3u8DL-RE skipped: binary missing or wrong arch")
                }

        val manifestUrl =
            with(preferences) {
                if (downloadPlaylist ||
                        extractAudio ||
                        convertAudio ||
                        sponsorBlock ||
                        createThumbnail ||
                        cropArtwork ||
                        embedThumbnail ||
                        downloadSubtitle ||
                        embedSubtitle ||
                        keepSubtitle ||
                        autoSubtitle ||
                        autoTranslatedSubtitles ||
                        convertSubtitle != 0 ||
                        aria2c ||
                        rateLimit ||
                        cookies ||
                        sdcard ||
                        useDownloadArchive ||
                        videoClips.isNotEmpty() ||
                        splitByChapter
                ) {
                    Log.d(TAG, "N_m3u8DL-RE skipped: yt-dlp post-processing requested")
                    return null
                }
                if (formatIdString.contains('+')) return null

                val manifestish =
                    (videoInfo.requestedFormats.orEmpty() + videoInfo.formats.orEmpty())
                        .filter { !it.url.isNullOrBlank() }
                        .filter {
                            val lower = it.url!!.lowercase()
                            listOf("m3u8", "mpd", "manifest").any { k -> k in lower }
                        }
                val pick =
                    if (formatIdString.isNotEmpty())
                        manifestish.firstOrNull { it.formatId == formatIdString }
                    else
                        manifestish.maxByOrNull { it.tbr ?: it.fileSizeApprox ?: 0.0 }
                pick?.url ?: return null
            }
            if (!isSafeRemoteUrl(manifestUrl)) {
                Log.w(TAG, "N_m3u8DL-RE skipped: unsafe manifest url")
                return null
            }

        val baseName =
            preferences.newTitle.ifEmpty { videoInfo.filename ?: videoInfo.title }
                .ifBlank { "download" }
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(120)
        val downloadDir =
            if (preferences.privateDirectory) App.privateDownloadDir else videoDownloadDir
        val fileName = "$baseName.mp4" // N_m3u8DL-RE muxes to mp4 when ffmpeg is present

        val workDir =
            File(App.context.cacheDir, "nm3re_$taskId").apply { mkdirs() }
        val args =
            mutableListOf(
                bin.absolutePath,
                manifestUrl,
                "--save-dir=${workDir.absolutePath}",
                "--save-name=media",
                "--tmp-dir=${File(workDir, "tmp").apply { mkdirs() }.absolutePath}",
                "--thread-count=8",
                "--download-retry-count=3",
                "--http-request-timeout=30",
                "--concurrent-download=true",
                "--binary-merge=true",
                "--del-after-done=true",
                "--no-log=true",
                "--no-ansi-color=true",
                "--disable-update-check=true",
                "--use-system-proxy=false",
                "--auto-selection",
            )
        nativeBinary("libffmpeg.so")?.let {
            args.add("--ffmpeg-binary-path=${it.absolutePath}")
        }

        Log.i(TAG, "N_m3u8DL-RE took over $taskId -> $manifestUrl")
        val process =
            runCatching {
                ProcessBuilder(args)
                    .apply { redirectErrorStream(true) }
                    .start()
            }.getOrElse {
                Log.w(TAG, "N_m3u8DL-RE unavailable, falling back to yt-dlp: ${it.message}")
                workDir.deleteRecursively()
                return null
            }
        // Registered so a task cancel can terminate the process; waitFor
        // below would otherwise block until the transfer finishes.
        nreProcesses[taskId] = process
        val nreStartedAtMs = System.currentTimeMillis()

        // Stream-copy done; the produced file is the largest under workDir.
        val produced =
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val percent = Regex("\\[(\\d{1,3}(?:\\.\\d+)?)%").find(line)
                    if (percent != null) {
                        progressCallback?.invoke(
                            percent.groupValues[1].toFloatOrNull() ?: 0f,
                            0,
                            "N_m3u8DL-RE: ${line.takeLast(80)}",
                        )
                    }
                }
                workDir
                    .walkTopDown()
                    .filter { it.isFile && !it.relativeTo(workDir).path.startsWith("tmp") }
                    .maxByOrNull { it.length() }
            }
        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        nreProcesses.remove(taskId)
        if (code != 0 || produced == null || produced.length() == 0L) {
            Log.w(TAG, "N_m3u8DL-RE failed (exit $code), falling back to yt-dlp")
            workDir.deleteRecursively()
            return null
        }

        val target = File(downloadDir, fileName)
        runCatching {
            produced.copyTo(target, overwrite = true)
            produced.delete()
        }
        workDir.deleteRecursively()

        if (preferences.embedMetadata) {
            embedMetadataWithFfmpeg(
                filePath = target.absolutePath,
                videoInfo = videoInfo,
                preferences = preferences,
            )
        }
        return onFinishDownloading(
            preferences = preferences,
            videoInfo = videoInfo,
            downloadPath = downloadDir,
            sdcardUri = preferences.sdcardUri,
            startedAtMs = nreStartedAtMs,
        )
    }

    /**
     * Ghost engine handoff (Ghost Downloader ADR-0007 pattern): yt-dlp extracts,
     * the embedded engine transfers the bytes. Eligible only when the selected
     * format is a single progressive HTTP stream and nothing requires yt-dlp's
     * post-processing; anything else fails open to the stock path.
     *
     * @return null when this download was NOT handled (caller falls back to yt-dlp).
     */
    private fun runGhostDownloadIfEligible(
        videoInfo: VideoInfo,
        preferences: DownloadPreferences,
        taskId: String,
        progressCallback: ((Float, Long, String) -> Unit)?,
    ): Result<List<String>>? {
        if (!GHOST_ENGINE.getBoolean() && !preferences.galleryDirect) {
            Log.d(TAG, "Ghost handoff skipped: engine disabled")
            return null
        }
        if (preferences.galleryDirect) {
            Log.d(TAG, "Ghost handoff: gallery direct item")
        }
        Log.d(TAG, "Ghost handoff check: taskId=$taskId formatIdString=${preferences.formatIdString}")

        val eligibleFormat =
            with(preferences) {
                if (downloadPlaylist ||
                        extractAudio ||
                        convertAudio ||
                        sponsorBlock ||
                        createThumbnail ||
                        cropArtwork ||
                        embedThumbnail ||
                        downloadSubtitle ||
                        embedSubtitle ||
                        keepSubtitle ||
                        autoSubtitle ||
                        autoTranslatedSubtitles ||
                        convertSubtitle != 0 ||
                        aria2c ||
                        rateLimit ||
                        cookies ||
                        sdcard ||
                        useDownloadArchive ||
                        videoClips.isNotEmpty() ||
                        splitByChapter
                ) {
                    Log.d(TAG, "Ghost handoff skipped: yt-dlp post-processing requested")
                    return null
                }
                if (formatIdString.contains('+')) {
                    Log.d(TAG, "Ghost handoff skipped: merged video+audio formats")
                    return null
                }

                val formats =
                    (videoInfo.requestedFormats.orEmpty() + videoInfo.formats.orEmpty())
                        .filter { !it.url.isNullOrBlank() }
                        .distinctBy { it.formatId to it.url }
                Log.d(
                    TAG,
                    "Ghost handoff formats: ids=${formats.map { it.formatId }} requestedIds=${videoInfo.requestedFormats?.map { it.formatId }}",
                )
                val candidate =
                    if (formatIdString.isNotEmpty())
                        formats.firstOrNull { it.formatId == formatIdString }
                            ?: formats.firstOrNull {
                                it.formatId != null && formatIdString.startsWith(it.formatId!!)
                            }
                    else formats.singleOrNull()
                if (candidate == null) {
                    Log.d(TAG, "Ghost handoff skipped: no matching single format")
                    return null
                }
                // A single selected format is the exact file yt-dlp would save —
                // unknown codecs (generic extractor reports acodec=null) are fine.

                val url = candidate.url!!
                if (!isSafeRemoteUrl(url)) {
                    Log.d(TAG, "Ghost handoff skipped: unsafe url")
                    return null
                }
                val lower = url.lowercase()
                if (listOf("m3u8", "mpd", "manifest", "hls", "dash").any { it in lower }) {
                    Log.d(TAG, "Ghost handoff skipped: segmented manifest url")
                    return null
                }
                candidate
            }

        val ext = eligibleFormat.ext?.takeIf { it.isNotBlank() } ?: "bin"
        val baseName =
            preferences.newTitle.ifEmpty { videoInfo.filename ?: videoInfo.title }
                .ifBlank { "download" }
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(120)
        val downloadDir =
            if (preferences.privateDirectory) App.privateDownloadDir
            else
                if (eligibleFormat.isAudioOnly()) audioDownloadDir else videoDownloadDir
        // The engine writes atomically with a rename, which fails with EPERM
        // when the destination already exists and belongs to another install
        // (shared Download/Sleeve keeps files across app re-installs). Pick a
        // free name instead of overwriting, the same way yt-dlp lands a new
        // file beside an existing one.
        // videoInfo.filename already carries the container (direct links name
        // the file after the URL), so appending ext again wrote "clip.mp4.mp4".
        val fileName =
            uniqueFileName(
                downloadDir,
                if (baseName.endsWith(".$ext", ignoreCase = true)) baseName
                else "$baseName.$ext",
            )

        // Gallery/embed items carry a per-item Referer from the resolver;
        // prefer it, and reconstruct from what is available here otherwise:
        // the extraction page (yt-dlp formats), falling back to the canonical
        // instagram referer for instagram CDN hosts, which reject signed
        // media without it. Direct items keep the media url as their page,
        // which is not a referer — skip those.
        val mediaUrl = eligibleFormat.url!!
        val pageReferer =
            listOfNotNull(videoInfo.webpageUrl, videoInfo.originalUrl)
                .firstOrNull { it.isNotBlank() && it != mediaUrl }
        val referer =
            preferences.referer.ifBlank {
                if (mediaUrl.contains("cdninstagram") || mediaUrl.contains("fbcdn")) {
                    "https://www.instagram.com/"
                } else {
                    pageReferer
                }
            }
        val engineHeaders =
            if (referer.isNullOrBlank()) {
                mapOf("User-Agent" to preferences.userAgentString)
            } else {
                mapOf(
                    "User-Agent" to preferences.userAgentString,
                    "Referer" to referer,
                )
            }
        val enqueued =
            runBlocking {
                GhostEngine.download(
                    url = mediaUrl,
                    headers = engineHeaders,
                    outputDir = downloadDir,
                    fileName = fileName,
                )
            }
        val engineTaskId =
            enqueued.getOrElse {
                Log.w(TAG, "Ghost engine unavailable, falling back to yt-dlp: ${it.message}")
                return null
            }
        ghostTasks[taskId] = engineTaskId
        Log.i(TAG, "Ghost engine took over $taskId -> $engineTaskId ($fileName)")
        val engineStartedAtMs = System.currentTimeMillis()

        val outcome =
            runBlocking {
                val settle = async { GhostEngine.watch(engineTaskId) }
                val bridge =
                    launch(Dispatchers.IO) {
                        GhostEngine.progressFlow.collect { p ->
                            if (p?.taskId != engineTaskId) return@collect
                            if (p.totalBytes > 0) {
                                progressCallback?.invoke(
                                    p.downloadedBytes * 100f / p.totalBytes,
                                    0,
                                    "Ghost: %.1f / %.1f MB • %.1f MB/s".format(
                                        p.downloadedBytes / 1_048_576.0,
                                        p.totalBytes / 1_048_576.0,
                                        p.speedBps / 1_048_576.0,
                                    ),
                                )
                            } else if (p.state == TaskState.RUNNING) {
                                progressCallback?.invoke(-1f, 0, "Ghost: downloading…")
                            }
                        }
                    }
                settle.await()
                bridge.cancel()
                GhostEngine.progressFlow.value
            }
        ghostTasks.remove(taskId)

        if (outcome?.state == TaskState.COMPLETED) {
            // The engine reporting COMPLETED is not proof of a file: verify the
            // exact output exists and is non-empty before history/UI claim it.
            val produced = File(downloadDir, fileName)
            if (!produced.isFile || produced.length() == 0L) {
                Log.w(TAG, "Ghost reported COMPLETED without output: ${produced.absolutePath}")
                return Result.failure(
                    YoutubeDLException("Ghost engine: no output file produced")
                )
            }
            if (preferences.embedMetadata) {
                embedMetadataWithFfmpeg(
                    filePath = produced.absolutePath,
                    videoInfo = videoInfo,
                    preferences = preferences,
                )
            }
        }

        return when (outcome?.state) {
            TaskState.COMPLETED ->
                onFinishDownloading(
                    preferences = preferences,
                    videoInfo = videoInfo,
                    downloadPath = downloadDir,
                    sdcardUri = preferences.sdcardUri,
                    startedAtMs = engineStartedAtMs,
                )
            else -> {
                val reason =
                    outcome?.error?.ifBlank { outcome.state.name.lowercase() } ?: "unknown"
                Log.w(TAG, "Ghost engine failed for $taskId: $reason")
                // A failed transfer leaves either a single-stream partial, the
                // staging file, or the segment directory; none is media, and a
                // later retry would scan them as output. Drop them now.
                File(downloadDir).listFiles()?.forEach { file ->
                    if (file.name == fileName || file.name.startsWith("$fileName.")) {
                        file.deleteRecursively()
                    }
                }
                Result.failure(YoutubeDLException("Ghost engine: $reason"))
            }
        }
    }

    /**
     * Best-effort metadata embed for engine-transferred files, restoring the
     * embedMetadata post-processing the stock yt-dlp path would have run.
     * Stream-copies (-c copy) so the transfer bytes are untouched; any ffmpeg
     * failure keeps the original file.
     */
    private fun embedMetadataWithFfmpeg(
        filePath: String,
        videoInfo: VideoInfo,
        preferences: DownloadPreferences,
    ) {
        val ffmpeg =
            File(App.context.applicationInfo.nativeLibraryDir ?: return, "libffmpeg.so")
                .takeIf { it.exists() } ?: return
        val inFile = File(filePath)
        if (!inFile.exists()) return
        // Keep the real extension on the temp file so ffmpeg can pick the muxer.
        val tmpOut =
            File(
                inFile.parentFile,
                inFile.nameWithoutExtension + ".meta_tmp." + inFile.extension,
            )

        val title = preferences.newTitle.ifEmpty { videoInfo.title }
        val artist = videoInfo.uploader ?: videoInfo.channel ?: ""
        val args =
            mutableListOf(
                ffmpeg.absolutePath,
                "-y",
                "-i",
                inFile.absolutePath,
                "-map",
                "0",
                "-c",
                "copy",
                "-metadata",
                "title=$title",
                "-metadata",
                "artist=$artist",
                "-metadata",
                "album_artist=$artist",
                "-metadata",
                "comment=Sleeve",
                tmpOut.absolutePath,
            )
        runCatching {
            val proc =
                ProcessBuilder(args)
                    .apply {
                        // libc++_shared.so and the libav* libs live in the
                        // extracted ffmpeg package, where yt-dlp's own ffmpeg
                        // invocations also load them from.
                        environment()["LD_LIBRARY_PATH"] =
                            File(
                                App.context.noBackupFilesDir,
                                "youtubedl-android/packages/ffmpeg/usr/lib",
                            )
                                .absolutePath
                        redirectErrorStream(true)
                    }
                    .start()
            val output = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code == 0 && tmpOut.exists() && tmpOut.length() > 0) {
                if (inFile.delete() && tmpOut.renameTo(inFile)) {
                    Log.i(TAG, "ffmpeg: metadata embedded into ${inFile.name}")
                } else {
                    Log.w(TAG, "ffmpeg: could not replace original")
                    tmpOut.delete()
                }
            } else {
                Log.w(TAG, "ffmpeg: exit $code ${output.takeLast(300)}")
                tmpOut.delete()
            }
        }.onFailure {
            Log.w(TAG, "ffmpeg: embed failed: ${it.message}")
            tmpOut.delete()
        }
    }

    @CheckResult
    fun downloadVideo(
        videoInfo: VideoInfo? = null,
        playlistUrl: String = "",
        playlistItem: Int = 0,
        taskId: String,
        downloadPreferences: DownloadPreferences,
        progressCallback: ((Float, Long, String) -> Unit)?,
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

        with(downloadPreferences) {
            val url =
                playlistUrl.ifEmpty {
                    videoInfo.originalUrl
                        ?: videoInfo.webpageUrl
                        ?: return Result.failure(
                            Throwable(context.getString(R.string.fetch_info_error_msg))
                        )
                }

            // HLS/DASH first: the bundled N_m3u8DL-RE handles segmented
            // manifests; direct progressive files fall through to the engine.
            runNm3u8DlReIfEligible(
                videoInfo = videoInfo,
                preferences = downloadPreferences,
                taskId = taskId,
                progressCallback = progressCallback,
            )?.let { return it }

            // Ghost engine handoff: when the embedded engine can transfer this
            // download itself, it reports progress through the same callback and
            // finishes through the same history insertion as the yt-dlp path.
            // A failed handoff must not end the task: CDNs can reject the plain
            // transfer (googlevideo answers 403 for range GETs) while yt-dlp
            // succeeds with its own headers, so fall through to that ladder.
            runGhostDownloadIfEligible(
                    videoInfo = videoInfo,
                    preferences = downloadPreferences,
                    taskId = taskId,
                    progressCallback = progressCallback,
                )
                ?.let { ghostResult ->
                    if (ghostResult.isSuccess) return ghostResult
                    Log.w(
                        TAG,
                        "Ghost handoff failed, retrying via yt-dlp: ${ghostResult.exceptionOrNull()?.message}",
                    )
                }

            // The download re-extracts, so the winning info-fetch client set
            // goes first. That win is no media win: SABR-era YouTube can 403
            // the media GET of a client that claimed the info fine
            // ("android_vr client https formats require a GVS PO Token",
            // "SABR-only streaming"). Any failure rotates down the ladder:
            // yt-dlp's own client selection next, then the no-JS "tv" rung.
            val downloadClientLadder: List<String?> =
                listOf(
                    successfulExtractionClients[taskId] ?: "android_vr,android,ios",
                    null,
                    "tv",
                )
            val downloadStartedAtMs = System.currentTimeMillis()
            for ((attemptIndex, clients) in downloadClientLadder.withIndex()) {
                val request = YoutubeDLRequest(url).maybeRedditUserAgent(url)
                if (clients != null) {
                    Log.i(TAG, "download attempt ${attemptIndex + 1}: player_client=$clients")
                    request.addOption("--extractor-args", "youtube:player_client=$clients")
                } else {
                    Log.i(TAG, "download attempt ${attemptIndex + 1}: yt-dlp default clients")
                }
                val pathBuilder = StringBuilder()
                val outputBuilder = StringBuilder()

                val attemptFailure =
                    request
                    .apply {
                        addOption("--no-mtime")
                        //                addOption("-v")
                        if (cookies) {
                            enableCookies(userAgentString)
                        }
                        if (galleryDirect) {
                            // Engine declined this direct item and we fell back to
                            // yt-dlp: without the page referer the generic
                            // extractor gets 403 from instagram CDN hosts.
                            val directReferer =
                                referer.ifBlank {
                                    if (
                                        url.contains("cdninstagram") || url.contains("fbcdn")
                                    ) {
                                        "https://www.instagram.com/"
                                    } else {
                                        ""
                                    }
                                }
                            if (directReferer.isNotBlank()) {
                                addOption("--add-headers", "Referer:$directReferer")
                            }
                        }
                        if (restrictFilenames) {
                            addOption("--restrict-filenames")
                        }
                        if (proxy) {
                            enableProxy(proxyUrl)
                        }
                        if (forceIpv4) {
                            addOption("-4")
                        }
                        if (debug) {
                            addOption("-v")
                        }
                        if (useDownloadArchive) {
                            val archiveFile = context.getArchiveFile()
                            val archiveFileContent = archiveFile.readText()
                            if (archiveFileContent.contains("${videoInfo.extractor} ${videoInfo.id}")) {
                                return Result.failure(
                                    YoutubeDLException(
                                        context.getString(R.string.download_archive_error)
                                    )
                                )
                            } else {
                                useDownloadArchive()
                            }
                        }

                        if (rateLimit && maxDownloadRate.isNumberInRange(1, 1000000)) {
                            addOption("-r", "${maxDownloadRate}K")
                        }

                        if (playlistItem != 0 && downloadPlaylist) {
                            addOption("--playlist-items", playlistItem)
                            if (subdirectoryPlaylistTitle && !videoInfo.playlist.isNullOrEmpty()) {
                                outputBuilder.append(PLAYLIST_TITLE_SUBDIRECTORY_PREFIX)
                            }
                            //                    addOption("--compat-options",
                            // "no-youtube-unavailable-videos")
                        } else {
                            addOption("--no-playlist")
                        }

                        if (aria2c) {
                            enableAria2c()
                        } else if (concurrentFragments > 1) {
                            addOption("--concurrent-fragments", concurrentFragments)
                        }

                        if (extractAudio || (videoInfo.vcodec == "none")) {
                            if (privateDirectory) pathBuilder.append(App.privateDownloadDir)
                            else pathBuilder.append(audioDownloadDir)
                            addOptionsForAudioDownloads(
                                id = videoInfo.id,
                                preferences = downloadPreferences,
                                playlistUrl = playlistUrl,
                            )
                        } else {
                            if (privateDirectory) pathBuilder.append(App.privateDownloadDir)
                            else pathBuilder.append(videoDownloadDir)
                            addOptionsForVideoDownloads(downloadPreferences)
                        }
                        if (sponsorBlock) {
                            addOption("--sponsorblock-remove", sponsorBlockCategory)
                        }

                        if (createThumbnail) {
                            addOption("--write-thumbnail")
                            addOption("--convert-thumbnails", "png")
                        }
                        if (subdirectoryExtractor) {
                            pathBuilder.append("/${videoInfo.extractorKey}")
                        }

                        if (sdcard) {
                            addOption("-P", context.getSdcardTempDir(videoInfo.id).absolutePath)
                        } else {
                            addOption("-P", pathBuilder.toString())
                        }

                        videoClips.forEach {
                            addOption(
                                "--download-sections",
                                "*%d-%d".format(locale = Locale.US, it.start, it.end),
                            )
                        }
                        if (newTitle.isNotEmpty()) {
                            addCommands(listOf("--replace-in-metadata", "title", ".+", newTitle))
                        }
                        if (Build.VERSION.SDK_INT > 23 && !sdcard)
                            addOption("-P", "temp:" + getExternalTempDir())

                        if (splitByChapter) {
                            addOption("-o", OUTPUT_TEMPLATE_CHAPTERS)
                            addOption("--split-chapters")
                        }

                        // Generic/direct links make yt-dlp use the whole URL as
                        // the video id, so a template containing [%(id)s] (the
                        // default) produces 700-char filenames and Errno 36 on
                        // CDN shares. Trim the final name (extension preserved)
                        // at the source; 80 chars keeps even fullwidth names
                        // inside ext4's 255-byte component limit.
                        addOption("--trim-filenames", "80")

                        val output =
                            if (splitByChapter) {
                                OUTPUT_TEMPLATE_SPLIT
                            } else if (videoClips.isEmpty()) {
                                outputTemplate
                            } else {
                                OUTPUT_TEMPLATE_CLIPS
                            }

                        addOption("-o", outputBuilder.append(output).toString())

                        for (s in request.buildCommand()) Log.d(TAG, s)
                    }
                    .runCatching {
                        YoutubeDL.getInstance()
                            .execute(request = this, processId = taskId, callback = progressCallback)
                    }
                    .exceptionOrNull()
                if (attemptFailure == null) {
                    return onFinishDownloading(
                        preferences = this,
                        videoInfo = videoInfo,
                        downloadPath = pathBuilder.toString(),
                        sdcardUri = sdcardUri,
                        startedAtMs = downloadStartedAtMs,
                    )
                }
                val failureMessage = attemptFailure.message.orEmpty()
                // Extraction success is no media success: the parallel info
                // call never touches the media host. Any rung-1/rung-2 failure
                // costs one more attempt; the next rung re-extracts fresh.
                if (attemptIndex < downloadClientLadder.lastIndex) {
                    Log.w(
                        TAG,
                        "player_client=${clients ?: "<yt-dlp default>"} failure: " +
                            "$failureMessage; rotating extraction clients",
                    )
                    successfulExtractionClients.remove(taskId)
                    continue
                }
                if (
                    sponsorBlock &&
                        failureMessage.contains(
                            "Unable to communicate with SponsorBlock API",
                        )
                ) {
                    attemptFailure.printStackTrace()
                    return onFinishDownloading(
                        preferences = this,
                        videoInfo = videoInfo,
                        downloadPath = pathBuilder.toString(),
                        sdcardUri = sdcardUri,
                        startedAtMs = downloadStartedAtMs,
                    )
                }
                Log.e(
                    TAG,
                    "download failed on player_client=${clients ?: "<yt-dlp default>"}: " +
                        "$failureMessage",
                )
                return Result.failure(attemptFailure)
            }
            // Every rung returns above; kept for exhaustiveness.
            return Result.failure(IllegalStateException("download failed"))
        }
    }

    private fun onFinishDownloading(
        preferences: DownloadPreferences,
        videoInfo: VideoInfo,
        downloadPath: String,
        sdcardUri: String,
        startedAtMs: Long? = null,
    ): Result<List<String>> =
        preferences.run {
            val fileName =
                (preferences.newTitle.ifEmpty {
                    videoInfo.filename
                        ?: videoInfo.requestedDownloads?.firstOrNull()?.filename
                        ?: videoInfo.title
                }).substringBefore('?').take(150)

            Log.d(TAG, "onFinishDownloading: $fileName")
            val savedFiles =
                if (sdcard) {
                    moveFilesToSdcard(
                        sdcardUri = sdcardUri,
                        tempPath = context.getSdcardTempDir(videoInfo.id),
                    )
                } else {
                    Result.success(
                        FileUtil.scanFileToMediaLibraryPostDownload(
                            title = fileName,
                            downloadDir = downloadPath,
                            // The disk filename can fail the title match
                            // (--trim-filenames 80, sanitized quick-download
                            // titles): a title-only scan returns an empty list
                            // and the history insert never runs, silently
                            // dropping the completed task from Downloads.
                            // Files modified since the download started are
                            // the real match.
                            modifiedAfterMs = startedAtMs,
                            // Gallery/photo items are pictures, not thumbnail
                            // sidecars: keep them in the completed set.
                            keepImages = galleryDirect,
                        )
                    )
                }
            savedFiles.fold(
                onSuccess = { paths ->
                    Log.d(TAG, "history insert: ${paths.size} file(s) $paths")
                    when {
                        // Private mode deliberately keeps files out of history.
                        privateMode -> Result.success(emptyList())
                        // No output means the transfer did not really finish.
                        // Reporting success here would render the card as
                        // "Downloaded" with no file behind it.
                        paths.isEmpty() ->
                            Result.failure(
                                IllegalStateException(
                                    context.getString(R.string.download_error_msg)
                                )
                            )
                        splitByChapter ->
                            Result.success(insertSplitChapterIntoHistory(videoInfo, paths))
                        else -> Result.success(insertInfoIntoDownloadHistory(videoInfo, paths))
                    }
                },
                onFailure = { Result.failure(it) },
            )
        }

    /**
     * Best-effort content length for a direct media URL via a bounded HEAD
     * request; 0 means unknown. Callers run on background dispatchers
     * (DownloaderV2.prepare launches on Dispatchers.Default). No fallback
     * guess is made when the server withholds Content-Length.
     */
    private fun fetchContentLength(url: String): Long =
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)")
            val len = if (conn.responseCode in 200..299) conn.contentLengthLong else -1L
            conn.disconnect()
            if (len > 0) len else 0L
        }.getOrDefault(0L)

    /**
     * Minimal VideoInfo for a raw media URL (gallery-dl item, sniffed page
     * asset): one synthetic "direct" format so the Ghost handoff transfers the
     * bytes without yt-dlp extraction. [contentLength] is injectable for
     * tests; production resolves it with a bounded HEAD so the queue card
     * shows the real size instead of a defaulting "0.00 MB".
     */
    fun buildDirectVideoInfo(
        url: String,
        title: String,
        contentLength: Long = fetchContentLength(url),
    ): VideoInfo? {
        if (!isSafeRemoteUrl(url)) return null
        val cleanUrl = url.substringBefore('#').substringBefore('?').substringBefore('？')
        val path = cleanUrl.substringBefore('?').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 }
        // Format.isAudioOnly() treats a null vcodec as audio-only, which would
        // file every direct image/video under the audio dir; classify by the
        // file extension instead.
        val directVcodec = if (ext in DIRECT_AUDIO_EXTS) "none" else "h264"
        val baseName =
            title.ifBlank { path }
                .ifBlank { cleanUrl }
                .replace(Regex("[\\/:*?\"<>|]"), "_")
                .take(120)
        val fullName = if (ext != null && !baseName.endsWith(".$ext")) "$baseName.$ext" else baseName
        // requestedFormats = the chosen file: ViewState.videoFormats and the
        // ghost-handoff candidate read requestedFormats first, and an empty
        // list would label the task "audio" in the queue header.
        val directFormat =
            Format(
                formatId = "direct",
                ext = ext,
                url = url,
                vcodec = directVcodec,
            )
        return VideoInfo(
            id = Integer.toHexString(url.hashCode()),
            title = baseName,
            webpageUrl = url,
            originalUrl = url,
            extractor = "sleeve-direct",
            extractorKey = "SleeveDirect",
            ext = ext ?: "bin",
            filename = fullName,
            formats = listOf(directFormat),
            requestedFormats = listOf(directFormat),
            // Content-Length from the HEAD probe; 0 stays 0 (unknown) — the
            // size is never invented.
            fileSizeApprox = if (contentLength > 0) contentLength.toDouble() else .0,
        )
    }

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

    suspend fun executeCommandInBackground(
        url: String,
        template: CommandTemplate = PreferenceUtil.getTemplate(),
        downloadPreferences: DownloadPreferences = DownloadPreferences.createFromPreferences(),
    ) {
        downloadPreferences.run {
            val taskId = Downloader.makeKey(url = url, templateName = template.name)
            val notificationId = taskId.toNotificationId()
            val urlList = url.split(Regex("[\n ]")).filter { it.isNotBlank() }

            ToastUtil.makeToastSuspend(context.getString(R.string.start_execute))
            val request =
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

            onProcessStarted()
            withContext(Dispatchers.Main) { onTaskStarted(template, url) }
            runCatching {
                    val response =
                        YoutubeDL.getInstance().execute(request = request, processId = taskId) {
                            progress,
                            _,
                            text ->
                            NotificationUtil.makeNotificationForCustomCommand(
                                notificationId = notificationId,
                                taskId = taskId,
                                progress = progress.toInt(),
                                templateName = template.name,
                                taskUrl = url,
                                text = text,
                            )
                            Downloader.updateTaskOutput(
                                template = template,
                                url = url,
                                line = text,
                                progress = progress,
                            )
                        }
                    onTaskEnded(template, url, response.out + "\n" + response.err)
                }
                .onFailure {
                    it.printStackTrace()
                    if (it is YoutubeDL.CanceledException) return@onFailure
                    it.message.run {
                        if (isNullOrEmpty()) onTaskEnded(template, url)
                        else onTaskError(this, template, url)
                    }
                }
            onProcessEnded()
        }
    }

    private fun checkIfAv1HardwareAccelerated(): Boolean {
        if (PreferenceUtil.containsKey(AV1_HARDWARE_ACCELERATED)) {
            return AV1_HARDWARE_ACCELERATED.getBoolean()
        } else {
            val res =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    false
                } else {
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                        info.supportedTypes.any { it.equals("video/av01", ignoreCase = true) } &&
                            info.isHardwareAccelerated
                    }
                }
            AV1_HARDWARE_ACCELERATED.updateBoolean(res)
            return res
        }
    }
}
