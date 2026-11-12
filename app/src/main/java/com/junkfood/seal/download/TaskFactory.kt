package com.junkfood.seal.download

import androidx.annotation.CheckResult
import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.junkfood.seal.util.Format
import com.junkfood.seal.util.PlaylistResult
import com.junkfood.seal.util.VideoClip
import com.junkfood.seal.util.VideoInfo
import kotlin.math.roundToInt

object TaskFactory {
    /**
     * @return A [TaskWithState] with extra configurations made by user in the custom format
     *   selection page
     */
    @CheckResult
    fun createWithConfigurations(
        videoInfo: VideoInfo,
        formatList: List<Format>,
        videoClips: List<VideoClip>,
        splitByChapter: Boolean,
        newTitle: String,
        selectedSubtitles: List<String>,
        selectedAutoCaptions: List<String>,
    ): TaskWithState {
        val fileSize =
            formatList.fold(.0) { acc, format ->
                acc + (format.fileSize ?: format.fileSizeApprox ?: .0)
            }

        val info =
            videoInfo
                .run { if (fileSize != .0) copy(fileSize = fileSize) else this }
                .run { if (newTitle.isNotEmpty()) copy(title = newTitle) else this }

        val audioOnlyFormats = formatList.filter { it.isAudioOnly() }
        val videoFormats = formatList.filter { it.containsVideo() }
        val audioOnly = audioOnlyFormats.isNotEmpty() && videoFormats.isEmpty()
        val mergeAudioStream = audioOnlyFormats.size > 1
        val formatId = formatList.joinToString(separator = "+") { it.formatId.toString() }

        val subtitleLanguage =
            (selectedSubtitles + selectedAutoCaptions).joinToString(separator = ",")

        val preferences =
            DownloadPreferences.createFromPreferences()
                .run {
                    copy(
                        formatIdString = formatId,
                        videoClips = videoClips,
                        splitByChapter = splitByChapter,
                        newTitle = newTitle,
                        mergeAudioStream = mergeAudioStream,
                        extractAudio = extractAudio || audioOnly,
                    )
                }
                .run {
                    if (subtitleLanguage.isNotEmpty()) {
                        copy(
                            downloadSubtitle = true,
                            autoSubtitle = selectedAutoCaptions.isNotEmpty(),
                            subtitleLanguage = subtitleLanguage,
                        )
                    } else {
                        this
                    }
                }

        val task = Task(url = info.originalUrl.toString(), preferences = preferences)
        val state =
            Task.State(
                downloadState = ReadyWithInfo,
                videoInfo = info,
                viewState =
                    Task.ViewState.fromVideoInfo(info = info)
                        .copy(videoFormats = videoFormats, audioOnlyFormats = audioOnlyFormats),
            )

        return TaskWithState(task, state)
    }

    /** @return List of [TaskWithState]s created from playlist items */
    @CheckResult
    fun createWithPlaylistResult(
        playlistUrl: String,
        indexList: List<Int>,
        playlistResult: PlaylistResult,
        preferences: DownloadPreferences,
    ): List<TaskWithState> {
        checkNotNull(playlistResult.entries)
        val indexEntryMap = indexList.associateWith { index -> playlistResult.entries[index - 1] }

        val taskList =
            indexEntryMap.map { (index, entry) ->
                val viewState =
                    Task.ViewState(
                        url = entry.url ?: "",
                        title = entry.title ?: "${playlistResult.title} - $index",
                        duration = entry.duration?.roundToInt() ?: 0,
                        uploader = entry.uploader ?: entry.channel ?: playlistResult.channel ?: "",
                        thumbnailUrl = (entry.thumbnails?.lastOrNull()?.url) ?: "",
                    )
                val task = Task(url = playlistUrl, preferences = preferences, type = Task.TypeInfo.Playlist(index))
                val state =
                    Task.State(downloadState = Idle, videoInfo = null, viewState = viewState)
                TaskWithState(task, state)
            }

        return taskList
    }

    /**
     * One task per selected catalogue track. The synthetic URL carries the batch id so repeat
     * downloads of the same playlist get distinct task ids; matching to YouTube happens later in
     * DownloaderV2.prepare().
     */
    @CheckResult
    fun createMetadataTasks(
        provider: String,
        playlistTitle: String,
        batchId: String,
        tracks: List<Task.TypeInfo.MetadataTrack>,
        preferences: DownloadPreferences,
    ): List<TaskWithState> {
        val batch =
            BatchInfo(id = batchId, title = playlistTitle.ifBlank { provider }, provider = provider)
        val audioPreferences = preferences.copy(extractAudio = true)
        return tracks.mapIndexed { position, track ->
            val metadata = track.copy(index = position)
            val task =
                Task(
                    url = "metadata://$provider/$batchId/$position",
                    type = metadata,
                    preferences = audioPreferences,
                    batch = batch,
                )
            val state =
                Task.State(
                    downloadState = Idle,
                    videoInfo = null,
                    viewState =
                        Task.ViewState(
                            url = "",
                            title = metadata.title,
                            uploader = metadata.artist,
                            duration = (metadata.durationMs / 1000L).toInt(),
                        ),
                )
            TaskWithState(task, state)
        }
    }

    /**
     * The one place a custom-command task is born. Both user entry points (the
     * configure sheet and the task list's new-task sheet) run a template the
     * user picked; quick and metadata lanes never reach this.
     */
    @CheckResult
    fun createCustomCommand(
        url: String,
        template: CommandTemplate,
        preferences: DownloadPreferences = DownloadPreferences.createFromPreferences(),
    ): Task =
        Task(
            url = url,
            type = Task.TypeInfo.CustomCommand(template = template),
            preferences = preferences,
        )

    /** Tag yt-dlp playlist entries with the batch they were launched as. */
    @CheckResult
    fun stampBatch(
        tasks: List<TaskWithState>,
        preferences: DownloadPreferences,
        batch: BatchInfo,
    ): List<TaskWithState> =
        tasks.map { it.copy(task = it.task.copy(preferences = preferences, batch = batch)) }

    data class TaskWithState(val task: Task, val state: Task.State)
}
