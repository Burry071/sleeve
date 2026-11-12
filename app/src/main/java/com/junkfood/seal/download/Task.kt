package com.junkfood.seal.download

import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.download.Task.TypeInfo
import com.junkfood.seal.download.Task.ViewState
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.Format
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.toHttpsUrl
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.roundToInt

private val TypeInfo.id: String
    get() =
        when (this) {
            is TypeInfo.CustomCommand -> "${template.id}_${template.name}"
            is TypeInfo.Playlist -> "$index"
            is TypeInfo.MetadataTrack -> "metadata:$index"
            TypeInfo.URL -> ""
        }

private fun makeId(url: String, type: TypeInfo, preferences: DownloadUtil.DownloadPreferences): String =
    "${url}_${type.id}_${preferences.hashCode()}"

@Serializable
data class BatchInfo(
    val id: String,
    val title: String,
    val provider: String,
)

/** Which lane produced the task. Runtime audit lines name it explicitly. */
@Serializable
enum class TaskOrigin {
    User,
    Quick,
}

@Serializable
data class Task(
    val url: String,
    val type: TypeInfo = TypeInfo.URL,
    val preferences: DownloadUtil.DownloadPreferences,
    val batch: BatchInfo? = null,
    val origin: TaskOrigin = TaskOrigin.User,
    val id: String = makeId(url, type, preferences),
) : Comparable<Task> {

    val timeCreated: Long = System.currentTimeMillis()

    /**
     * Cookie state as it will actually reach yt-dlp. Quick mode is a fence, so
     * it always reports none regardless of the stored preference.
     */
    val cookieAudit: String
        get() =
            when {
                origin == TaskOrigin.Quick -> "cookies: none (quick mode)"
                preferences.cookies -> "cookies: on (user pref)"
                else -> "cookies: none (user pref)"
            }

    /** Stable Compose list key: ids can repeat across batches of the same playlist. */
    val uiKey: String
        get() = "$id#${batch?.id ?: ""}"

    /** Batch songs report progress through one playlist notification instead. */
    val usesOwnNotification: Boolean
        get() = batch == null

    /**
     * True when both tasks would download the same thing, regardless of which
     * batch queued them. Each playlist submission gets a fresh batch id, so
     * plain equality cannot spot a second submission of work already in flight.
     * Catalogue tracks embed the batch id in their synthetic URL, so they are
     * compared by matched-track identity instead.
     */
    fun isSameWork(other: Task): Boolean {
        if (preferences != other.preferences) return false
        if (type is TypeInfo.MetadataTrack && other.type is TypeInfo.MetadataTrack) {
            return type == other.type
        }
        return url == other.url && type == other.type
    }

    override fun compareTo(other: Task): Int {
        return timeCreated.compareTo(other.timeCreated)
    }

    @Serializable
    sealed interface TypeInfo {

        @Serializable data class Playlist(val index: Int = 0) : TypeInfo

        @Serializable data class CustomCommand(val template: CommandTemplate) : TypeInfo

        @Serializable
        data class MetadataTrack(
            val index: Int = 0,
            val provider: String = "",
            val title: String = "",
            val artist: String = "",
            val album: String = "",
            val durationMs: Long = 0L,
            val authors: String = "",
            val language: String = "",
            /**
             * The URL is one verified upload, not a catalogue entry to search
             * for. A split part carries its own video URL, so resolution goes
             * straight to extraction instead of re-running the book search
             * (which would gate the part as if it were the whole book).
             * Appended last so every positional caller still compiles.
             */
            val directUrl: Boolean = false,
        ) : TypeInfo

        @Serializable data object URL : TypeInfo
    }

    @Serializable
    data class State(
        val downloadState: DownloadState,
        val videoInfo: VideoInfo?,
        val viewState: ViewState,
        val match: MatchResult? = null,
    )

    @Serializable
    sealed interface DownloadState {

        interface Cancelable {
            val job: Job
            val taskId: String
            val action: RestartableAction
        }

        interface Restartable {
            val action: RestartableAction
        }

        /**
         * Scheduler preference between the two runnable states only. A
         * [ReadyWithInfo] task already has its info and can start at once, so it
         * goes before a fresh [Idle] one. Every other state is not picked.
         */
        val schedulerRank: Int
            get() =
                when (this) {
                    ReadyWithInfo -> 0
                    Idle -> 1
                    else -> 2
                }

        /** Card order in the downloads list: active first, finished last. */
        val displayRank: Int
            get() =
                when (this) {
                    is Running -> 0
                    ReadyWithInfo -> 1
                    is FetchingInfo -> 2
                    Idle -> 3
                    is Canceled -> 4
                    is Error -> 5
                    is Completed -> 6
                }

        /** Short name for audit lines; never shown to the user. */
        val label: String
            get() =
                when (this) {
                    is Running -> "running"
                    ReadyWithInfo -> "ready"
                    is FetchingInfo -> "fetching"
                    Idle -> "idle"
                    is Canceled -> "canceled"
                    is Error -> "error"
                    is Completed -> "completed"
                }

        @Serializable data object Idle : DownloadState

        @Serializable
        data class FetchingInfo(
            @Transient override val job: Job = Job(),
            override val taskId: String,
        ) : DownloadState, Cancelable {
            override val action: RestartableAction = RestartableAction.FetchInfo
        }

        @Serializable data object ReadyWithInfo : DownloadState

        @Serializable
        data class Running(
            @Transient override val job: Job = Job(),
            override val taskId: String,
            val progress: Float = PROGRESS_INDETERMINATE,
            val progressText: String = "",
        ) : DownloadState, Cancelable {
            override val action: RestartableAction = RestartableAction.Download
        }

        @Serializable
        data class Canceled(override val action: RestartableAction, val progress: Float? = null) :
            DownloadState, Restartable

        @Serializable
        data class Error(
            @Transient val throwable: Throwable = Throwable(),
            override val action: RestartableAction,
            /** Friendly text for the card; appended last so positional callers still compile. */
            val message: String = "",
        ) : DownloadState, Restartable

        @Serializable
        data class Completed(
            val filePath: String?,
            /**
             * Post-download duration mismatch for an Audible book; empty when
             * the file passed or was never checked. Appended last with a
             * default so states cached by beta.4 still decode.
             */
            val warning: String = "",
        ) : DownloadState
    }

    @Serializable
    sealed interface RestartableAction {
        @Serializable data object FetchInfo : RestartableAction

        @Serializable data object Download : RestartableAction
    }

    @Serializable
    data class ViewState(
        val url: String = "https://www.example.com",
        val title: String = "",
        val uploader: String = "",
        val extractorKey: String = "",
        val duration: Int = 0,
        val fileSizeApprox: Double = .0,
        val thumbnailUrl: String? = null,
        val videoFormats: List<Format>? = null,
        val audioOnlyFormats: List<Format>? = null,
    ) {
        companion object {
            fun fromVideoInfo(info: VideoInfo): ViewState {
                val formats =
                    info.requestedFormats
                        ?: info.requestedDownloads?.map { it.toFormat() }
                        ?: emptyList()

                val videoFormats = formats.filter { it.containsVideo() }
                val audioOnlyFormats = formats.filter { it.isAudioOnly() }

                return ViewState(
                    url = info.originalUrl.toString(),
                    title = info.title,
                    uploader = info.uploader ?: info.channel ?: info.uploaderId ?: "",
                    extractorKey = info.extractorKey,
                    duration = info.duration?.roundToInt() ?: 0,
                    thumbnailUrl = info.thumbnail.toHttpsUrl(),
                    fileSizeApprox = info.fileSize ?: info.fileSizeApprox ?: .0,
                    videoFormats = videoFormats,
                    audioOnlyFormats = audioOnlyFormats,
                )
            }
        }
    }

    companion object {
        private const val PROGRESS_INDETERMINATE = -1f
    }
}
