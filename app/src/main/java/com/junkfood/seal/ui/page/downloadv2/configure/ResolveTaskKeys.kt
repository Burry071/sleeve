package com.junkfood.seal.ui.page.downloadv2.configure

/**
 * Loading keys whose resolve runs as a yt-dlp process. Gallery, Spotify, and
 * Audible resolves only own a coroutine, so Cancel has no process to kill for
 * them and must still cancel the job.
 */
internal object ResolveTaskKeys {
    private val processBackedPrefixes = listOf("FetchPlaylist_", "FetchFormat_")

    fun isProcessBacked(taskKey: String): Boolean =
        processBackedPrefixes.any { taskKey.startsWith(it) }
}
