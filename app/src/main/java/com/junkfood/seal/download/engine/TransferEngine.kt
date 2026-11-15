package com.junkfood.seal.download.engine

import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.GHOST_ENGINE
import com.junkfood.seal.util.MERGE_OUTPUT_MKV
import com.junkfood.seal.util.PREFER_MP4
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.USER_AGENT
import com.yausername.youtubedl_android.YoutubeDLRequest

/**
 * Immutable per-task view of the settings the transfer lanes need. Captured
 * once when the task enters the ladder, so no lane reads [PreferenceUtil]
 * statics mid-transfer and every rung of the ladder sees the same values.
 */
internal class DownloadSettingsSnapshot private constructor(
    val preferences: DownloadUtil.DownloadPreferences,
    /** Ghost engine / N_m3u8DL-RE master switch. */
    val engineEnabled: Boolean,
    val preferMp4: Boolean,
    val mergeOutputMkv: Boolean,
    /** Whether a custom user agent is configured (Reddit and cookie headers). */
    val customUserAgentEnabled: Boolean,
) {
    companion object {
        fun capture(preferences: DownloadUtil.DownloadPreferences) =
            DownloadSettingsSnapshot(
                preferences = preferences,
                engineEnabled = GHOST_ENGINE.getBoolean(),
                preferMp4 = PREFER_MP4.getBoolean(),
                mergeOutputMkv = MERGE_OUTPUT_MKV.getBoolean(),
                customUserAgentEnabled = USER_AGENT.getBoolean(),
            )
    }
}

/** One download handed to a lane; [url] is the resolved media/document URL. */
internal data class TransferJob(
    val videoInfo: com.junkfood.seal.util.VideoInfo,
    val settings: DownloadSettingsSnapshot,
    val url: String,
    val taskId: String,
    val playlistUrl: String,
    val playlistItem: Int,
    val progressCallback: ((Float, Long, String) -> Unit)?,
    val processIdFactory: () -> String,
    val onProcessStarted: (String) -> Unit,
    val isActive: () -> Boolean,
    val m4bTagOverrides: com.junkfood.seal.util.M4bTagOverrides?,
    val onNote: (com.junkfood.seal.download.CompletedNote) -> Unit,
    val onLocalDurationMs: (Long) -> Unit,
) {
    val preferences: DownloadUtil.DownloadPreferences
        get() = settings.preferences
}

/**
 * One transfer strategy. `null` means the lane declined this job and the
 * caller falls through to the next one; a failure means the lane claimed the
 * job and its verdict stands (the Ghost rung is the exception the ladder
 * documents: it logs and still falls through).
 */
internal fun interface TransferEngine {
    suspend fun transfer(job: TransferJob): Result<List<String>>?
}

/** yt-dlp request options shared by the ladder and the extraction resolver. */
internal fun YoutubeDLRequest.applyFormatSorter(
    preferences: DownloadUtil.DownloadPreferences,
    sorter: String,
) =
    preferences.run {
        if (formatSorting && sortingFields.isNotEmpty()) addOption("-S", sortingFields)
        else if (sorter.isNotEmpty()) addOption("-S", sorter) else {}
    }
