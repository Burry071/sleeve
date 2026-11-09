package com.junkfood.seal

import com.junkfood.seal.ui.page.downloadv2.configure.ResolveTaskKeys
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveTaskKeysTest {

    @Test
    fun gallerySpotifyAndAudibleResolvesHaveNoProcess() {
        assertFalse(ResolveTaskKeys.isProcessBacked("Gallery_https://example.com/p/1"))
        assertFalse(ResolveTaskKeys.isProcessBacked("FetchSpotify_https://open.spotify.com/track/1"))
        assertFalse(ResolveTaskKeys.isProcessBacked("FetchAudible_https://www.audible.com/pd/1"))
    }

    @Test
    fun playlistAndFormatResolvesAreProcessBacked() {
        assertTrue(ResolveTaskKeys.isProcessBacked("FetchPlaylist_https://www.youtube.com/playlist?list=1"))
        assertTrue(ResolveTaskKeys.isProcessBacked("FetchFormat_https://www.youtube.com/watch?v=1"))
    }

    @Test
    fun unknownResolveKindIsTreatedAsCoroutineOnly() {
        assertFalse(ResolveTaskKeys.isProcessBacked("SomethingNew_https://example.com"))
    }
}
