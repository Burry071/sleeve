package com.junkfood.seal.download.engine

import android.util.Log
import androidx.annotation.CheckResult
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.R
import com.junkfood.seal.download.model.DownloadPreferences
import com.junkfood.seal.download.model.DownloadPreferencesFactory
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.PlaylistResult
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.USER_AGENT
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.YoutubeDLInfo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import java.io.File
import java.security.MessageDigest

/**
 * yt-dlp info extraction and custom-command request helpers extracted from [DownloadUtil].
 *
 * All request option builders reuse the canonical helpers in `YtDlpRequestOptions.kt` and
 * `TransferEngine.kt` so format sorting, proxies, Reddit user-agent handling, and cookie gating
 * have a single source of truth.
 */
internal object YtDlpExtractor {

    /**
     * Builds the single `youtube:...` value for `--extractor-args`.
     *
     * One argument per extractor: a second `youtube:...` option would have to be merged by yt-dlp,
     * and its precedence is not documented. Returns empty string when no overrides apply.
     */
    internal fun buildYoutubeExtractorArgs(
        clients: String?,
        autoSubtitle: Boolean,
        autoTranslatedSubtitles: Boolean,
    ): String {
        val parts =
            buildList {
                if (!clients.isNullOrBlank()) add("player_client=$clients")
                if (autoSubtitle && !autoTranslatedSubtitles) {
                    add("skip=translated_subs")
                }
            }
        return if (parts.isEmpty()) "" else "youtube:${parts.joinToString(separator = ";")}"
    }

    internal fun buildPlaylistOrVideoInfoRequest(
        playlistURL: String,
        preferences: DownloadPreferences,
        customUserAgentEnabled: Boolean = USER_AGENT.getBoolean(),
        applyCookies: YoutubeDLRequest.(String) -> YoutubeDLRequest = { enableCookies(it) },
    ): YoutubeDLRequest =
        YoutubeDLRequest(playlistURL)
            .maybeRedditUserAgent(playlistURL, customUserAgentEnabled)
            .apply {
                // Flat playlist browsing carries no formats, so it runs on
                // yt-dlp's own client selection like every other rung.
                // addOption("--compat-options", "no-youtube-unavailable-videos")
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("-o", DownloadUtil.BASENAME)
                addOption("-R", "1")
                addOption("--socket-timeout", "5")
                preferences.run {
                    if (extractAudio) {
                        addOption("-x")
                    }
                    applyFormatSorter(this, formatSorter(this))
                    if (proxy) {
                        enableProxy(proxyUrl)
                    }
                    if (forceIpv4) {
                        addOption("-4")
                    }
                    if (cookies) {
                        applyCookies(userAgentString)
                    }
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                }
            }

    @CheckResult
    fun getPlaylistOrVideoInfo(
        playlistURL: String,
        downloadPreferences: DownloadPreferences = DownloadPreferencesFactory.createFromPreferences(),
        silent: Boolean = false,
        processId: String? = null,
    ): Result<YoutubeDLInfo> =
        YoutubeDL.runCatching {
            if (!silent) {
                ToastUtil.makeToastSuspend(context.getString(R.string.fetching_playlist_info))
            }
            val request = buildPlaylistOrVideoInfoRequest(playlistURL, downloadPreferences)
            // The task id lets a canceled match kill its yt-dlp search through
            // destroyProcessById; playlist browsing keeps the URL key.
            execute(request, processId ?: playlistURL).out.run {
                val playlistInfo = DownloadUtil.jsonFormat.decodeFromString<PlaylistResult>(this)
                if (playlistInfo.type != "playlist") {
                    DownloadUtil.jsonFormat.decodeFromString<VideoInfo>(this)
                } else playlistInfo
            }
        }

    internal fun buildVideoInfoRequest(
        url: String,
        clients: String?,
        playlistIndex: Int?,
        preferences: DownloadPreferences,
        customUserAgentEnabled: Boolean = USER_AGENT.getBoolean(),
        applyCookies: YoutubeDLRequest.(String) -> YoutubeDLRequest = { enableCookies(it) },
    ): YoutubeDLRequest =
        with(preferences) {
            val youtubeArgs =
                buildYoutubeExtractorArgs(
                    clients = clients,
                    autoSubtitle = autoSubtitle,
                    autoTranslatedSubtitles = autoTranslatedSubtitles,
                )
            YoutubeDLRequest(url)
                .maybeRedditUserAgent(url, customUserAgentEnabled)
                .apply {
                    addOption("-o", DownloadUtil.BASENAME)
                    if (youtubeArgs.isNotEmpty()) {
                        addOption("--extractor-args", youtubeArgs)
                    }
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                    if (extractAudio) {
                        addOption("-x")
                    }
                    applyFormatSorter(this@with, formatSorter(this@with))
                    if (cookies) {
                        applyCookies(userAgentString)
                    }
                    if (proxy) {
                        enableProxy(proxyUrl)
                    }
                    if (forceIpv4) {
                        addOption("-4")
                    }
                    if (autoSubtitle) {
                        addOption("--write-auto-subs")
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
        }

    @CheckResult
    internal fun getVideoInfo(
        request: YoutubeDLRequest,
        taskKey: String? = null,
    ): Result<VideoInfo> =
        request.runCatching {
            val response: YoutubeDLResponse =
                YoutubeDL.getInstance().execute(request, taskKey, null)
            DownloadUtil.jsonFormat.decodeFromString(response.out)
        }

    @CheckResult
    fun fetchVideoInfoFromUrl(
        url: String,
        playlistIndex: Int? = null,
        taskKey: String? = null,
        preferences: DownloadPreferences = DownloadPreferencesFactory.createFromPreferences(),
    ): Result<VideoInfo> {
        // Extraction clients rotate on failure: YouTube's bot checks and
        // rate limits are client-specific, so a request yt-dlp's default
        // selection refuses often succeeds on the TV/embedded pair.
        // Cancellation always stops the ladder.
        val clientLadder: List<String?> =
            extractionLadder(url = url, recorded = null, hasRecordedWin = false)

        var lastFailure: Throwable? = null
        clientLadder.forEach { clients ->
            val request =
                buildVideoInfoRequest(
                    url = url,
                    clients = clients,
                    playlistIndex = playlistIndex,
                    preferences = preferences,
                )
            val result = getVideoInfo(request, taskKey)
            if (result.isSuccess) {
                // Only a rung that worked is worth replaying at download
                // time; a null win must be replayable too, so it is
                // recorded as a win rather than skipped.
                if (taskKey != null) ExtractionClients.record(taskKey, clients)
                return result
            }
            lastFailure = result.exceptionOrNull()
            if (lastFailure is YoutubeDL.CanceledException) return Result.failure(lastFailure!!)
            Log.w(
                TRANSFER_TAG,
                "extraction failed${clients?.let { " (client $it)" } ?: ""}, " +
                    "ladder continues: ${lastFailure?.message?.take(160)}",
            )
        }
        return Result.failure(lastFailure ?: IllegalStateException("extraction failed"))
    }

    internal fun sanitizeCustomCommandUrls(raw: String): List<String> =
        raw.split(Regex("\\s+"))
            .asSequence()
            .map { it.trim() }
            .filter { token ->
                if (token.isEmpty() || token.startsWith("-")) return@filter false
                if (token.any { it.code < 0x20 || it.code == 0x7F }) return@filter false
                val schemeEnd = token.indexOf("://")
                if (schemeEnd <= 0) return@filter false
                val scheme = token.substring(0, schemeEnd).lowercase()
                if (scheme != "http" && scheme != "https") return@filter false
                val afterScheme = token.substring(schemeEnd + 3)
                val authEnd =
                    afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let {
                        if (it == -1) afterScheme.length else it
                    }
                afterScheme.substring(0, authEnd).isNotBlank()
            }
            .toList()

    internal fun customCommandConfigFile(cacheDir: File, taskId: String): File {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(taskId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "config_${digest.take(16)}.txt")
    }

    internal fun buildCustomCommandBaseRequest(
        urlList: List<String>,
        preferences: DownloadPreferences,
        applyArchive: YoutubeDLRequest.() -> YoutubeDLRequest = { useDownloadArchive() },
    ): YoutubeDLRequest =
        with(preferences) {
            YoutubeDLRequest(urlList).apply {
                commandDirectory.takeIf { it.isNotEmpty() }?.let { addOption("-P", it) }
                addOption("--newline")
                if (aria2c) {
                    enableAria2c()
                }
                if (useDownloadArchive) {
                    applyArchive()
                }
                if (restrictFilenames) {
                    addOption("--restrict-filenames")
                }
            }
        }
}
