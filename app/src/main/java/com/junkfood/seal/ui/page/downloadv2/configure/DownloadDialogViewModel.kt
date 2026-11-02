package com.junkfood.seal.ui.page.downloadv2.configure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.download.Task
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.ghostengine.GhostEngine
import com.junkfood.seal.ghostengine.GhostEngine.SpotifyTrack
import com.junkfood.seal.util.DownloadErrorMessages
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.RedditResolver
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.FileUtil.getCookiesFile
import com.junkfood.seal.util.PlaylistEntry
import com.junkfood.seal.util.PlaylistResult
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.RANKING_STRICTNESS
import com.junkfood.seal.util.SPOTIFY_CLIENT_ID
import com.junkfood.seal.util.SPOTIFY_CLIENT_SECRET
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.SleeveQuickDownload
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.util.Log

private const val TAG = "DownloadDialogViewModel"

/** RankedCandidate.score is 0–100; below this the fallback search runs. */
private const val SPOTIFY_MATCH_THRESHOLD = 55.0
private const val SPOTIFY_RESOLVE_CONCURRENCY = 3

private val trackBracketRegex = Regex("""\([^)]*\)|\[[^]]*]""")
private val trackDecorationRegex =
    Regex(
        """\s+-\s+(?:remaster(?:ed)?(?:\s+\d{4})?|live|single version|radio edit|deluxe(?: edition)?|bonus track)\s*$""",
        RegexOption.IGNORE_CASE,
    )
private val whitespaceRegex = Regex("""\s+""")

private fun String.toSearchQueryPart(): String =
    trackBracketRegex.replace(this, " ")
        .replace(trackDecorationRegex, " ")
        .replace(whitespaceRegex, " ")
        .trim()

class DownloadDialogViewModel(private val downloader: DownloaderV2) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    /** Resolve cooldown: shared metadata-source rate limiting (ms since epoch). */
    private val lastResolveAt = mutableMapOf<String, Long>()

    private fun resolveThrottled(kind: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastResolveAt[kind] ?: 0L
        if (now - last < 3000) return true
        lastResolveAt[kind] = now
        return false
    }

    sealed interface SelectionState {
        data object Idle : SelectionState

        data class PlaylistSelection(val result: PlaylistResult) : SelectionState

        data class FormatSelection(val info: VideoInfo) : SelectionState

        data class MetadataTracks(
            val provider: String,
            val playlistTitle: String,
            val tracks: List<SpotifyTrack>,
        ) : SelectionState
    }

    sealed interface SheetState {
        data object InputUrl : SheetState

        /**
         * Seal's DownloadSettingDialog equivalent: type chips + toggles + Download.
         * [gallery] is set when the URL resolved through the gallery/embed path —
         * the download then runs through the ghost-aware enqueue with the
         * resolved direct items instead of yt-dlp extraction.
         */
        data class Configure(
            val urlList: List<String>,
            val gallery: SleeveQuickDownload.Resolved? = null,
        ) : SheetState

        data class Loading(val taskKey: String, val job: Job) : SheetState

        data class Error(val action: Action, val throwable: Throwable) : SheetState
    }

    sealed interface SheetValue {
        data object Expanded : SheetValue

        data object Hidden : SheetValue
    }

    sealed interface Action {
        data object HideSheet : Action

        data class ShowSheet(val urlList: List<String>? = null) : Action

        data class FetchSpotify(val url: String) : Action

        data class FetchAudible(val url: String) : Action

        data class FetchGallery(val url: String) : Action

        data class DownloadMetadataTracks(val provider: String, val tracks: List<SpotifyTrack>) :
            Action

        data class ProceedWithURLs(val urlList: List<String>) : Action

        data object Reset : Action

        data class FetchPlaylist(
            val url: String,
            val preferences: DownloadUtil.DownloadPreferences,
        ) : Action

        data class FetchFormats(
            val url: String,
            val audioOnly: Boolean,
            val preferences: DownloadUtil.DownloadPreferences,
        ) : Action

        data class DownloadWithPreset(
            val urlList: List<String>,
            val preferences: DownloadUtil.DownloadPreferences,
        ) : Action

        data class RunCommand(
            val url: String,
            val template: CommandTemplate,
            val preferences: DownloadUtil.DownloadPreferences,
        ) : Action

        data object Cancel : Action
    }

    private val mSelectionStateFlow: MutableStateFlow<SelectionState> =
        MutableStateFlow(SelectionState.Idle)
    private val mSheetStateFlow: MutableStateFlow<SheetState> =
        MutableStateFlow(SheetState.InputUrl)
    private val mSheetValueFlow: MutableStateFlow<SheetValue> = MutableStateFlow(SheetValue.Hidden)

    val selectionStateFlow = mSelectionStateFlow.asStateFlow()
    val sheetStateFlow = mSheetStateFlow.asStateFlow()
    val sheetValueFlow = mSheetValueFlow.asStateFlow()

    private val sheetState
        get() = sheetStateFlow.value

    fun postAction(action: Action) {
        with(action) {
            when (this) {
                is Action.ProceedWithURLs -> proceedWithUrls(this)
                is Action.FetchFormats -> fetchFormat(this)
                is Action.FetchPlaylist -> fetchPlaylist(this)
                is Action.DownloadWithPreset -> downloadWithPreset(urlList, preferences)
                is Action.RunCommand -> runCommand(url, template, preferences)
                Action.HideSheet -> hideDialog()
                is Action.ShowSheet -> showDialog(this)
                is Action.FetchSpotify -> fetchSpotify(this)
                is Action.FetchAudible -> fetchAudible(this)
                is Action.FetchGallery -> fetchGallery(this)
                is Action.DownloadMetadataTracks -> downloadMetadataTracks(this)
                Action.Cancel -> cancel()
                Action.Reset -> resetSelectionState()
            }
        }
    }

    private fun proceedWithUrls(action: Action.ProceedWithURLs) {
        action.urlList.firstOrNull { isSpotifyUrl(it) }?.let { spotifyUrl ->
            fetchSpotify(Action.FetchSpotify(spotifyUrl))
            return
        }
        action.urlList.firstOrNull { isAudibleUrl(it) }?.let { audibleUrl ->
            fetchAudible(Action.FetchAudible(audibleUrl))
            return
        }
        if (redditPreRoute(action.urlList)) return
        mSheetStateFlow.update { SheetState.Configure(action.urlList) }
    }

    private fun fetchPlaylist(action: Action.FetchPlaylist) {
        val (url, preferences) = action

        val job =
            viewModelScope.launch(Dispatchers.IO) {
                DownloadUtil.getPlaylistOrVideoInfo(
                        playlistURL = url,
                        downloadPreferences = preferences,
                    )
                    .onSuccess { info ->
                        withContext(Dispatchers.Main) {
                            when (info) {
                                is PlaylistResult -> {
                                    mSelectionStateFlow.update {
                                        SelectionState.PlaylistSelection(result = info)
                                    }
                                }

                                is VideoInfo -> {
                                    mSelectionStateFlow.update {
                                        SelectionState.FormatSelection(info = info)
                                    }
                                }
                            }
                            hideDialog()
                        }
                    }
                    .onFailure { th ->
                        mSheetStateFlow.update {
                            SheetState.Error(action = action, throwable = DownloadErrorMessages.asFriendly(th))
                        }
                    }
            }
        mSheetStateFlow.update { SheetState.Loading(taskKey = "FetchPlaylist_$url", job = job) }
    }

    private fun fetchFormat(action: Action.FetchFormats) {
        val (url, audioOnly, preferences) = action

        val job =
            viewModelScope.launch(Dispatchers.IO) {
                DownloadUtil.fetchVideoInfoFromUrl(
                        url = url,
                        preferences = preferences.copy(extractAudio = audioOnly),
                        taskKey = "FetchFormat_$url",
                    )
                    .onSuccess { info ->
                        withContext(Dispatchers.Main) {
                            mSelectionStateFlow.update {
                                SelectionState.FormatSelection(info = info)
                            }
                            hideDialog()
                        }
                    }
                    .onFailure { th ->
                        withContext(Dispatchers.Main) {
                            mSheetStateFlow.update {
                                SheetState.Error(action, throwable = DownloadErrorMessages.asFriendly(th))
                            }
                        }
                    }
            }

        mSheetStateFlow.update { SheetState.Loading(taskKey = "FetchFormat_$url", job = job) }
    }

    private var pendingGallery: SleeveQuickDownload.Resolved? = null

    private fun downloadWithPreset(
        urlList: List<String>,
        preferences: DownloadUtil.DownloadPreferences,
    ) {
        val gallery = pendingGallery
        if (gallery != null && urlList.firstOrNull() == gallery.url) {
            pendingGallery = null
            viewModelScope.launch(Dispatchers.IO) {
                SleeveQuickDownload.enqueueResolved(
                        downloader,
                        gallery,
                        extractAudio = preferences.extractAudio,
                    )
                    .onSuccess { (queued, source) ->
                        withContext(Dispatchers.Main) {
                            ToastUtil.makeToast(
                                App.context.getString(
                                    R.string.gallery_items_queued,
                                    queued,
                                    source,
                                ),
                            )
                            hideDialog()
                        }
                    }
                    .onFailure { th -> galleryError(Action.FetchGallery(gallery.url), th) }
            }
            return
        }
        urlList.forEach { downloader.enqueue(Task(url = it, preferences = preferences)) }
        hideDialog()
    }

    private fun runCommand(
        url: String,
        template: CommandTemplate,
        preferences: DownloadUtil.DownloadPreferences,
    ) {
        val task =
            Task(
                url = url,
                type = Task.TypeInfo.CustomCommand(template = template),
                preferences = preferences,
            )
        downloader.enqueue(task)
    }

    private fun hideDialog() {
        mSheetValueFlow.update { SheetValue.Hidden }
        when (sheetState) {
            is SheetState.Loading -> {
                cancel()
            }

            else -> {}
        }
    }

    private fun showDialog(action: Action.ShowSheet) {
        val urlList = action.urlList
        urlList?.firstOrNull { isSpotifyUrl(it) }?.let { spotifyUrl ->
            mSheetValueFlow.update { SheetValue.Expanded }
            fetchSpotify(Action.FetchSpotify(spotifyUrl))
            return
        }
        urlList?.firstOrNull { isAudibleUrl(it) }?.let { audibleUrl ->
            mSheetValueFlow.update { SheetValue.Expanded }
            fetchAudible(Action.FetchAudible(audibleUrl))
            return
        }
        if (urlList != null && redditPreRoute(urlList)) return
        urlList?.firstOrNull { !isVideoFirstHost(it) }?.let { candidate ->
            // gallery-dl check runs on IO (first call loads ~300 site modules);
            // a hit routes to the gallery flow, a miss falls through to Configure.
            mSheetValueFlow.update { SheetValue.Expanded }
            val job =
                viewModelScope.launch(Dispatchers.IO) {
                    val matched =
                        runCatching { GhostEngine.galleryMatches(candidate) }
                            .onFailure { Log.e(TAG, "galleryMatches error: ${it.message}") }
                            .getOrDefault(false)
                    Log.i(TAG, "gallery pre-route: candidate=$candidate matched=$matched")
                    withContext(Dispatchers.Main) {
                        if (matched) {
                            fetchGallery(Action.FetchGallery(candidate))
                        } else {
                            mSheetStateFlow.update { SheetState.Configure(urlList) }
                        }
                    }
                }
            mSheetStateFlow.update {
                SheetState.Loading(taskKey = "Gallery_$candidate", job = job)
            }
            return
        }
        if (!urlList.isNullOrEmpty()) {
            mSheetStateFlow.update { SheetState.Configure(urlList) }
        } else {
            mSheetStateFlow.update { SheetState.InputUrl }
        }
        mSheetValueFlow.update { SheetValue.Expanded }
    }

private val VIDEO_FIRST_HOSTS: Regex =
    Regex(
        "youtube\\.com|youtu\\.be|vimeo\\.com|twitch\\.tv|tiktok\\.com|twitter\\.com|x\\.com" +
            "|reddit\\.com|redd\\.it|instagram\\.com|instagr\\.am",
    )

    /** Hosts whose video flows yt-dlp (with the ghost fallback) serves well
     * skip the gallery-dl pre-route entirely — instagram above all: the
     * share dialog stays configure-with-options and the download runs the
     * same pipeline an in-app paste would.  */
    private fun isVideoFirstHost(url: String) =
        VIDEO_FIRST_HOSTS
            .containsMatchIn(url)

    /**
     * Reddit pre-route: the anonymous JSON resolver fetches the post's media
     * (video manifest, images, galleries) without any login. Success lands in
     * the configure sheet on the direct lane — the same pipeline as gallery
     * items; any failure quietly falls through to the plain yt-dlp configure
     * sheet, never a dead end.
     *
     * @return true when a reddit URL was claimed and resolution is running.
     */
    private fun redditPreRoute(urlList: List<String>): Boolean {
        val candidate = urlList.firstOrNull { RedditResolver.isPostUrl(it) } ?: return false
        if (resolveThrottled("reddit")) return true
        mSheetValueFlow.update { SheetValue.Expanded }
        val job =
            viewModelScope.launch(Dispatchers.IO) {
                RedditResolver.resolve(candidate)
                    .onSuccess { items ->
                        val resolved =
                            SleeveQuickDownload.Resolved(candidate, items, "reddit")
                        withContext(Dispatchers.Main) {
                            pendingGallery = resolved
                            mSheetStateFlow.update {
                                SheetState.Configure(listOf(candidate), gallery = resolved)
                            }
                        }
                    }
                    .onFailure { th ->
                        Log.e(TAG, "reddit resolve failed, degrading: ${th.message}")
                        withContext(Dispatchers.Main) {
                            mSheetStateFlow.update { SheetState.Configure(urlList) }
                        }
                    }
            }
        mSheetStateFlow.update {
            SheetState.Loading(taskKey = "Reddit_$candidate", job = job)
        }
        return true
    }

    private fun fetchGallery(action: Action.FetchGallery) {
        if (resolveThrottled("gallery")) return
        val job =
            viewModelScope.launch(Dispatchers.IO) {
                SleeveQuickDownload.resolve(App.context, action.url)
                    .onSuccess { resolved ->
                        withContext(Dispatchers.Main) {
                            pendingGallery = resolved
                            mSheetStateFlow.update {
                                SheetState.Configure(listOf(resolved.url), gallery = resolved)
                            }
                        }
                    }
                    .onFailure { th ->
                        // Not a dead end: degrade to the plain configure
                        // sheet so the download runs the same pipeline an
                        // in-app paste would (yt-dlp + ghost/ladder).
                        Log.e(TAG, "gallery resolve failed, degrading: ${th.message}")
                        withContext(Dispatchers.Main) {
                            ToastUtil.makeToast(
                                App.context.getString(R.string.gallery_resolve_fallback),
                            )
                            mSheetStateFlow.update { SheetState.Configure(listOf(action.url)) }
                        }
                    }
            }
        mSheetStateFlow.update {
            SheetState.Loading(taskKey = "Gallery_${action.url}", job = job)
        }
    }

    private suspend fun galleryError(action: Action.FetchGallery, th: Throwable) {
        Log.e(TAG, "gallery resolve failed: ${th.message}")
        val message = th.message ?: ""
        val friendly =
            if (
                Regex(
                        "401|403|429|login required|login page|redirect to login|rate.?limit",
                        RegexOption.IGNORE_CASE,
                    )
                    .containsMatchIn(message)
            ) {
                App.context.getString(R.string.gallery_auth_required)
            } else {
                message
            }
        withContext(Dispatchers.Main) {
            mSheetStateFlow.update { SheetState.Error(action, IllegalStateException(friendly)) }
        }
    }

    private fun isSpotifyUrl(url: String) =
        url.contains("spotify.com/") || url.startsWith("spotify:")

    private fun isAudibleUrl(url: String) = Regex("audible\\.[a-z.]+/").containsMatchIn(url)

    private fun fetchSpotify(action: Action.FetchSpotify) {
        if (resolveThrottled("spotify")) return
        val job =
            viewModelScope.launch(Dispatchers.IO) {
                GhostEngine.spotifyResolve(
                        url = action.url,
                        clientId = SPOTIFY_CLIENT_ID.getString(),
                        clientSecret = SPOTIFY_CLIENT_SECRET.getString(),
                    )
                    .onSuccess { (playlistTitle, tracks) ->
                        withContext(Dispatchers.Main) {
                            mSelectionStateFlow.update {
                                SelectionState.MetadataTracks(
                                    provider = "Spotify",
                                    playlistTitle = playlistTitle,
                                    tracks = tracks,
                                )
                            }
                            hideDialog()
                        }
                    }
                    .onFailure { th ->
                        withContext(Dispatchers.Main) {
                            mSheetStateFlow.update { SheetState.Error(action, th) }
                        }
                    }
            }
        mSheetStateFlow.update {
            SheetState.Loading(taskKey = "FetchSpotify_${action.url}", job = job)
        }
    }

    private suspend fun resolveSpotifyTrack(
        track: SpotifyTrack,
        preferences: DownloadUtil.DownloadPreferences,
    ) {
        fun fetch(pool: String, query: String): List<PlaylistEntry> =
            DownloadUtil.getPlaylistOrVideoInfo(
                    playlistURL = "$pool:$query",
                    downloadPreferences = preferences,
                )
                .map { it as? PlaylistResult }
                .getOrElse { null }
                ?.entries
                .orEmpty()

        fun rank(candidates: List<PlaylistEntry>) =
            GhostEngine.rankCandidates(
                    track = track,
                    candidatesJson = json.encodeToString(candidates),
                    strictness = RANKING_STRICTNESS.getInt(),
                )
                .getOrThrow()

        // A floor miss ("no candidate passed") is a normal rejection, not a
        // fatal error: it must widen the pool instead of dropping the track.
        fun rankOrNull(candidates: List<PlaylistEntry>) =
            candidates.takeIf { it.isNotEmpty() }?.let { runCatching { rank(it) }.getOrNull() }

        val fullQuery =
            listOf(track.artist, track.title)
                .map { it.toSearchQueryPart() }
                .filter { it.isNotBlank() }
                .joinToString(separator = " ")
        val titleQuery = track.title.toSearchQueryPart()

        var best = rankOrNull(fetch("ytmsearch10", fullQuery))
        if (best == null || best.score < SPOTIFY_MATCH_THRESHOLD) {
            val wideBest = rankOrNull(fetch("ytsearch10", fullQuery))
            best = listOfNotNull(best, wideBest).maxByOrNull { it.score }
        }
        if ((best == null || best.score < SPOTIFY_MATCH_THRESHOLD) && titleQuery.isNotBlank()) {
            val titleBest = rankOrNull(fetch("ytsearch5", titleQuery))
            best = listOfNotNull(best, titleBest).maxByOrNull { it.score } ?: best
        }

        val match = best ?: error("no candidate matched")
        val url = match.url.ifBlank { "https://www.youtube.com/watch?v=" + match.id }
        downloader.enqueue(Task(url = url, preferences = preferences))
    }

    private fun downloadMetadataTracks(action: Action.DownloadMetadataTracks) {
        // spotDL pattern with quality-ranked matching: for each metadata track,
        // pull the top YouTube candidates (flat search), let the engine score
        // them (name/artist/duration/forbidden-keywords), and enqueue the best
        // through the normal pipeline, audio-only. Resolution runs with bounded
        // concurrency; the downloads themselves go through the pipeline's
        // 3-slot scheduler.
        val preferences =
            DownloadUtil.DownloadPreferences.createFromPreferences().copy(extractAudio = true)
        viewModelScope.launch(Dispatchers.IO) {
            val semaphore = Semaphore(SPOTIFY_RESOLVE_CONCURRENCY)
            val results =
                coroutineScope {
                    action.tracks
                        .map { track ->
                            async {
                                track to
                                    semaphore.withPermit {
                                        runCatching { resolveSpotifyTrack(track, preferences) }
                                    }
                            }
                        }
                        .awaitAll()
                }
            val queued = results.count { it.second.isSuccess }
            val skippedTitles = results.filter { it.second.isFailure }.map { it.first.title }
            withContext(Dispatchers.Main) {
                resetSelectionState()
                if (skippedTitles.isEmpty()) {
                    ToastUtil.makeToast(App.context.getString(R.string.tracks_queued, queued))
                } else {
                    val preview = skippedTitles.take(3).joinToString(", ")
                    val suffix = if (skippedTitles.size > 3) "…" else ""
                    ToastUtil.makeToast(
                        App.context.getString(
                            R.string.tracks_queued_partial,
                            queued,
                            skippedTitles.size,
                            preview + suffix,
                        )
                    )
                }
            }
        }
        hideDialog()
    }

    private fun fetchAudible(action: Action.FetchAudible) {
        if (resolveThrottled("audible")) return
        val job =
            viewModelScope.launch(Dispatchers.IO) {
                GhostEngine.audibleResolve(url = action.url)
                    .onSuccess { (bookTitle, tracks) ->
                        withContext(Dispatchers.Main) {
                            mSelectionStateFlow.update {
                                SelectionState.MetadataTracks(
                                    provider = "Audible",
                                    playlistTitle = bookTitle,
                                    tracks = tracks,
                                )
                            }
                            hideDialog()
                        }
                    }
                    .onFailure { th ->
                        withContext(Dispatchers.Main) {
                            mSheetStateFlow.update { SheetState.Error(action, th) }
                        }
                    }
            }
        mSheetStateFlow.update {
            SheetState.Loading(taskKey = "FetchAudible_${action.url}", job = job)
        }
    }

    private fun cancel(): Boolean {
        return when (val state = sheetState) {
            is SheetState.Loading -> {
                val res = YoutubeDL.destroyProcessById(id = state.taskKey)
                if (res) {
                    state.job.cancel()
                }
                return res
            }
            else -> false
        }
    }

    private fun resetSelectionState() {
        mSelectionStateFlow.update { SelectionState.Idle }
    }
}
