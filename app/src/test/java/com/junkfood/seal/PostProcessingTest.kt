package com.junkfood.seal

import com.junkfood.seal.download.PostProcessing
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostProcessingTest {

    @Test
    fun `a transfer line is not post-processing`() {
        assertFalse(PostProcessing.inProgress("[download]  42.1% of 12.34MiB at 1.20MiB/s"))
        assertFalse(PostProcessing.inProgress("[download] Destination: clip.mp4"))
        assertFalse(PostProcessing.inProgress("[youtube] Extracting URL: https://youtu.be/x"))
        assertFalse(PostProcessing.inProgress(""))
    }

    @Test
    fun `merging and extracting count as post-processing`() {
        assertTrue(PostProcessing.inProgress("[Merger] Merging formats into \"clip.mp4\""))
        assertTrue(PostProcessing.inProgress("[ExtractAudio] Destination: clip.mp3"))
        assertTrue(PostProcessing.inProgress("[FixupM4a] Correcting container of \"x.m4a\""))
        assertTrue(PostProcessing.inProgress("[VideoRemuxer] Remuxing video from flv to mp4"))
    }

    @Test
    fun `tagging, cover art and file moves count as post-processing`() {
        assertTrue(PostProcessing.inProgress("[Metadata] Adding metadata to \"book.m4b\""))
        assertTrue(PostProcessing.inProgress("[EmbedThumbnail] Adding thumbnail to \"clip.mp4\""))
        assertTrue(PostProcessing.inProgress("[ThumbnailsConvertor] Converting thumbnail \"a.jpg\" to png"))
        assertTrue(PostProcessing.inProgress("[SplitChapters] Splitting video into chapters"))
        assertTrue(PostProcessing.inProgress("[MoveFiles] Moving file \"a\" to \"b\""))
    }

    @Test
    fun `an engine that reports no lines is not post-processing`() {
        // The Ghost lane reports its own states as text, not yt-dlp log lines.
        assertFalse(PostProcessing.inProgress("Ghost: downloading…"))
        assertFalse(PostProcessing.inProgress("Ghost: downloading…, 12.3 MiB"))
    }
}
