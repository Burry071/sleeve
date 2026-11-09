package com.junkfood.seal.ui.page.downloadv2.configure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.TaskFactory
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.ghostengine.GhostEngine
import com.junkfood.seal.ghostengine.GhostEngine.GalleryItem
import com.junkfood.seal.ghostengine.GhostEngine.SpotifyTrack
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.PlaylistResult
import com.junkfood.seal.util.SPOTIFY_CLIENT_ID
import com.junkfood.seal.util.SPOTIFY_CLIENT_SECRET
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.SleeveQuickDownload
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import android.util.Log

private const val TAG = "DownloadDialogViewModel"
private const val RESOLVE_COOLDOWN_MS = 3000L

class DownloadDialogViewModel(private val downloader: DownloaderV2) : ViewModel() {

    /** Cooldown for repeated resolves of the same source and link. */
    private val resolveGate = ResolveGate(RESOLVE_COOLDOWN_MS)

    /** Last accepted preset download (ms since epoch); see downloadWithPreset. */
    private var lastPresetDownloadAt = 0L

    /**
     * True when [url] was already resolved for [kind] within the cooldown.
     * The caller must return immediately. Parking the sheet on the URL input is
     * required: the duplicate arrives after the sheet was already put into
     * Loading by showDialog(), and silently returning would leave that Loading
     * attached to a job that has finished — a spinner that never resolves.
     */
    private fun resolveThrottled(kind: String, url: String): Boolean {
        if (resolveGate.accept(kind, url)) return false
        mSheetStateFlow.update { SheetState.InputUrl }
        return true
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

        data class Configure(val urlList: List<String>) : SheetState

        data class Loading(val taskKey: String, val job: Job) : SheetState

        data class Error(val action: Action, val throwable: Throwable) : SheetState
    }

    sealed interface SheetValue {
        data object Expanded : SheetValue

        data object Hidden : SheetValue
    }

    sealed interface Action {
        /** Link this action fetches, when it has one. The error page reads it
         * through this property, so adding an action can never leave it with an
         * unhandled branch to throw on. */
        val url: String?
            get() = null

        data object HideSheet : Action

        data class ShowSheet(val urlList: List<String>? = null) : Action

        data class FetchSpotify(override val url: String) : Action

        data class FetchAudible(override val url: String) : Action

        data class FetchGallery(override val url: String) : Action

        data class DownloadMetadataTracks(
            val provider: String,
            val playlistTitle: String,
            val tracks: List<SpotifyTrack>,
        ) : Action

        data class ProceedWithURLs(val urlList: List<String>) : Action

        data object Reset : Action

        data class FetchPlaylist(
            override val url: String,
            val preferences: DownloadUtil.DownloadPreferences,
        ) : Action

        data class FetchFormats(
            override val url: String,
            val audioOnly: Boolean,
            val preferences: DownloadUtil.DownloadPreferences,
        ) : Action

        data class DownloadWithPreset(
            val urlList: List<String>,
            val preferences: DownloadUtil.DownloadPreferences,
        ) : Action

        data class RunCommand(
            override val url: String,
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

    /** Batch whose match review should be on screen; set by the card and the notification. */
    private val mMatchReviewBatchFlow = MutableStateFlow<String?>(null)
    val matchReviewBatchFlow = mMatchReviewBatchFlow.asStateFlow()

    fun openMatchReview(batchId: String) {
        mMatchReviewBatchFlow.value = batchId
    }

    fun closeMatchReview() {
        mMatchReviewBatchFlow.value = null
    }

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
                        mSheetStateFlow.update { SheetState.Error(action = action, throwable = th) }
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
                            mSheetStateFlow.update { SheetState.Error(action, throwable = th) }
                        }
                    }
            }

        mSheetStateFlow.update { SheetState.Loading(taskKey = "FetchFormat_$url", job = job) }
    }

    private fun downloadWithPreset(
        urlList: List<String>,
        preferences: DownloadUtil.DownloadPreferences,
    ) {
        // The sheet hides on the first tap; a very fast second tap must not
        // queue the same batch again (two tasks, two files, two history rows).
        val now = System.currentTimeMillis()
        if (now - lastPresetDownloadAt < 1000) return
        lastPresetDownloadAt = now
        urlList.distinct().forEach { downloader.enqueue(Task(url = it, preferences = preferences)) }
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
        // Success paths only hide the sheet; they never park the state. Without
        // this the next open resurrects a Loading whose job is already gone.
        if (sheetState is SheetState.Loading) cancel()
        mSheetStateFlow.update { SheetState.InputUrl }
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

// instagram.com belongs here: the anonymous embed resolves reels to their
// poster JPG, so the gallery pre-route silently downloaded an image, skipped
// the preference sheet, and never fetched the video. yt-dlp is the lane that
// returns the actual reel; letting the sheet open also keeps IG consistent
// with the other video hosts.
private val VIDEO_FIRST_HOSTS: Regex =
    Regex("youtube\\.com|youtu\\.be|vimeo\\.com|twitch\\.tv|tiktok\\.com|twitter\\.com|x\\.com|reddit\\.com|redd\\.it|instagram\\.com")

    /** Hosts whose video flows yt-dlp already serves well stay on yt-dlp. */
    private fun isVideoFirstHost(url: String) =
        VIDEO_FIRST_HOSTS
            .containsMatchIn(url)

    private fun fetchGallery(action: Action.FetchGallery) {
        if (resolveThrottled("gallery", action.url)) return
        val job =
            viewModelScope.launch(Dispatchers.IO) {
                SleeveQuickDownload.enqueue(App.context, action.url, downloader)
                    .onSuccess { (queued, source) ->
                        withContext(Dispatchers.Main) {
                            resetSelectionState()
                            ToastUtil.makeToast(
                                App.context.getString(R.string.gallery_items_queued, queued, source),
                            )
                            hideDialog()
                        }
                    }
                    .onFailure { th ->
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
            }
        mSheetStateFlow.update {
            SheetState.Loading(taskKey = "Gallery_${action.url}", job = job)
        }
    }

    private fun isSpotifyUrl(url: String) =
        url.contains("spotify.com/") || url.startsWith("spotify:")

    private fun isAudibleUrl(url: String) = Regex("audible\\.[a-z.]+/").containsMatchIn(url)

    private fun fetchSpotify(action: Action.FetchSpotify) {
        if (resolveThrottled("spotify", action.url)) return
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

    private fun downloadMetadataTracks(action: Action.DownloadMetadataTracks) {
        // Matching used to run here, one blocking yt-dlp search per song with a toast each
        // time. Now every selected song becomes a task immediately and the queue matches up to
        // three at a time, with one batch notification.
        val preferences = DownloadUtil.DownloadPreferences.createFromPreferences()
        val tracks =
            action.tracks.mapIndexed { index, track ->
                Task.TypeInfo.MetadataTrack(
                    index = index,
                    provider = action.provider,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                )
            }
        val tasks =
            TaskFactory.createMetadataTasks(
                provider = action.provider,
                playlistTitle = action.playlistTitle,
                batchId = UUID.randomUUID().toString(),
                tracks = tracks,
                preferences = preferences,
            )
        tasks.forEach(downloader::enqueue)
        resetSelectionState()
        ToastUtil.makeToast(App.context.getString(R.string.tracks_queued, tasks.size))
        hideDialog()
    }

    private fun fetchAudible(action: Action.FetchAudible) {
        if (resolveThrottled("audible", action.url)) return
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

    private fun cancel() {
        val state = sheetState as? SheetState.Loading ?: return
        // Gallery, Spotify, and Audible resolve without a process, so killing one
        // is best-effort; the job cancel is what actually stops every lane.
        if (ResolveTaskKeys.isProcessBacked(state.taskKey)) {
            YoutubeDL.destroyProcessById(id = state.taskKey)
        }
        state.job.cancel()
    }

    private fun resetSelectionState() {
        mSelectionStateFlow.update { SelectionState.Idle }
    }
}
