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
import com.junkfood.seal.download.engine.GhostHttpTransferEngine
import com.junkfood.seal.download.engine.Nm3u8TransferEngine
import com.junkfood.seal.download.engine.TransferJob
import com.junkfood.seal.download.engine.YtDlpExtractor
import com.junkfood.seal.download.engine.YtDlpTransferEngine
import com.junkfood.seal.download.engine.formatSorter
import com.junkfood.seal.download.engine.isSafeRemoteUrl as engineUrlIsSafe
import com.junkfood.seal.download.engine.uniqueFileName as engineUniqueFileName
import com.junkfood.seal.download.model.DownloadPreferencesFactory
import com.junkfood.seal.ghostengine.GhostEngine
import com.junkfood.seal.ui.page.settings.network.Cookie
import com.junkfood.seal.util.FileUtil.getCookieFile
import com.junkfood.seal.util.PreferenceUtil.COOKIE_HEADER
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
        YtDlpExtractor.getPlaylistOrVideoInfo(
            playlistURL = playlistURL,
            downloadPreferences = downloadPreferences,
            silent = silent,
            processId = processId,
        )

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
    fun fetchVideoInfoFromUrl(
        url: String,
        playlistIndex: Int? = null,
        taskKey: String? = null,
        preferences: DownloadPreferences = DownloadPreferences.createFromPreferences(),
    ): Result<VideoInfo> =
        YtDlpExtractor.fetchVideoInfoFromUrl(
            url = url,
            playlistIndex = playlistIndex,
            taskKey = taskKey,
            preferences = preferences,
        )

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
            val EMPTY: DownloadPreferences = DownloadPreferencesFactory.EMPTY

            fun createFromPreferences(): DownloadPreferences =
                DownloadPreferencesFactory.createFromPreferences()
        }
    }

    internal fun enableCookies(
        request: YoutubeDLRequest,
        userAgentString: String,
    ): YoutubeDLRequest =
        request.addOption("--cookies", context.getCookieFile().absolutePath).apply {
            if (userAgentString.isNotEmpty()) {
                addOption("--add-header", "User-Agent:$userAgentString")
            }
        }

    @CheckResult
    fun getCookieListFromDatabase(): Result<List<Cookie>> = CookieStore.listFromDatabase()

    fun List<Cookie>.toCookiesFileContent(): String =
        this.fold(StringBuilder(COOKIE_HEADER)) { acc, cookie ->
                acc.append(cookie.toNetscapeCookieString()).append("\n")
            }
            .toString()

    fun getCookiesContentFromDatabase(): Result<String> =
        getCookieListFromDatabase().mapCatching { it.toCookiesFileContent() }

    @CheckResult
    fun DownloadPreferences.toFormatSorter(): String = formatSorter(this)

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


    internal fun sanitizeCustomCommandUrls(raw: String): List<String> =
        YtDlpExtractor.sanitizeCustomCommandUrls(raw)

    internal fun customCommandConfigFile(cacheDir: File, taskId: String): File =
        YtDlpExtractor.customCommandConfigFile(cacheDir, taskId)

    @CheckResult
    fun executeCustomCommandTask(
        urlString: String,
        taskId: String,
        template: CommandTemplate,
        preferences: DownloadPreferences,
        progressCallback: ((Float, Long, String) -> Unit),
    ): Result<YoutubeDLResponse> {
        val urlList = sanitizeCustomCommandUrls(urlString)
        if (urlList.isEmpty()) {
            return Result.failure(IllegalArgumentException("No valid http(s) URLs in custom command"))
        }

        val configFile = customCommandConfigFile(context.cacheDir, taskId)
        return try {
            runCatching {
                FileUtil.writeContentToFile(template.template, configFile)
                val request =
                    YtDlpExtractor.buildCustomCommandBaseRequest(urlList, preferences).apply {
                        addOption(
                            "--config-locations",
                            configFile.absolutePath,
                        )
                        if (preferences.cookies) {
                            enableCookies(this, preferences.userAgentString)
                        }
                        addOption("--")
                    }

                YoutubeDL.getInstance()
                    .execute(request = request, processId = taskId, callback = progressCallback)
            }
        } finally {
            configFile.delete()
        }
    }
}
