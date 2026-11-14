package com.junkfood.seal.download

import com.junkfood.seal.util.DownloadErrorMessages
import com.junkfood.seal.util.PlaylistEntry
import com.junkfood.seal.util.isYouTubeId
import com.junkfood.seal.util.youTubeIdFrom

/**
 * F2: an item enqueued from a playlist URL remembers which video sat at its
 * index, so a playlist that gained or lost an item is refused instead of
 * silently downloading whatever now sits at that position.
 */
internal object PlaylistPin {

    /** The id to pin for [entry], or null when its fetch is not index-based. */
    fun capture(taskUrl: String, entry: PlaylistEntry): String? {
        // A playlist opened from a watch URL is fetched with --no-playlist and
        // resolves video X for every index; pinning those would refuse every
        // item but X, where today they download X behind the right preview.
        if (youTubeIdFrom(taskUrl) != null) return null
        return youTubeIdFrom(entry.url.orEmpty())
            ?: entry.id?.takeIf { entry.type == "url" && isYouTubeId(it) }
    }

    /** Throws when the fetch resolved a different video than the one pinned. */
    fun verify(expectedVideoId: String?, actualVideoId: String) {
        if (expectedVideoId != null && expectedVideoId != actualVideoId) {
            throw DownloadErrorMessages.PlaylistShiftedError(expectedVideoId, actualVideoId)
        }
    }
}
