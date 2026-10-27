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
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.PlaylistResult
import com.junkfood.seal.util.SPOTIFY_CLIENT_ID
import com.junkfood.seal.util.SPOTIFY_CLIENT_SECRET
import com.junkfood.seal.util.PreferenceUtil.getString
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.util.Log

private const val TAG = "DownloadDialogViewModel"

class DownloadDialogViewModel(private val downloader: DownloaderV2) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

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
        data object HideSheet : Action

        data class ShowSheet(val urlList: List<String>? = null) : Action

        data class FetchSpotify(val url: String) : Action

        data class FetchAudible(val url: String) : Action

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
        if (!urlList.isNullOrEmpty()) {
            mSheetStateFlow.update { SheetState.Configure(urlList) }
        } else {
            mSheetStateFlow.update { SheetState.InputUrl }
        }
        mSheetValueFlow.update { SheetValue.Expanded }
    }

    private fun isSpotifyUrl(url: String) =
        url.contains("spotify.com/") || url.startsWith("spotify:")

    private fun isAudibleUrl(url: String) = Regex("audible\\.[a-z.]+/").containsMatchIn(url)

    private fun fetchSpotify(action: Action.FetchSpotify) {
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
        // spotDL pattern with quality-ranked matching: for each metadata track,
        // pull the top YouTube candidates (flat search), let the engine score
        // them (name/artist/duration/forbidden-keywords), and enqueue the best
        // through the normal pipeline, audio-only. One prep lane at a time —
        // the downloads themselves go through the pipeline's 3-slot scheduler.
        val preferences =
            DownloadUtil.DownloadPreferences.createFromPreferences().copy(extractAudio = true)
        viewModelScope.launch(Dispatchers.IO) {
            var queued = 0
            for (track in action.tracks) {
                runCatching {
                    val query =
                        listOf(track.artist, track.title, "audio")
                            .filter { it.isNotBlank() }
                            .joinToString(separator = " ")
                            .let { Regex("\\s+").replace(it, " ").trim() }
                    val candidates =
                        DownloadUtil.getPlaylistOrVideoInfo(
                                playlistURL = "ytsearch5:$query",
                                downloadPreferences = preferences,
                            )
                            .getOrThrow()
                            .run {
                                (this as? PlaylistResult)?.entries
                                    ?: throw IllegalStateException("no candidates")
                            }
                    val best =
                        GhostEngine.rankCandidates(
                                track = track,
                                candidatesJson = json.encodeToString(candidates),
                            )
                            .getOrThrow()
                    val url =
                        best.url.ifBlank {
                            "https://www.youtube.com/watch?v=" + best.id
                        }
                    downloader.enqueue(Task(url = url, preferences = preferences))
                    queued++
                }.onFailure { Log.w(TAG, "track skipped: ${track.title}: ${it.message}") }
            }
            withContext(Dispatchers.Main) {
                resetSelectionState()
                ToastUtil.makeToast(
                    App.context.getString(R.string.tracks_queued, queued),
                )
            }
        }
        hideDialog()
    }

    private fun fetchAudible(action: Action.FetchAudible) {
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
