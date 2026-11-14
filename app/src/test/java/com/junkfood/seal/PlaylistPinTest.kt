package com.junkfood.seal

import com.junkfood.seal.download.PlaylistPin
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.RestartableAction
import com.junkfood.seal.download.TaskFactory
import com.junkfood.seal.download.TaskTransitions
import com.junkfood.seal.util.DownloadErrorMessages
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.junkfood.seal.util.PlaylistEntry
import com.junkfood.seal.util.PlaylistResult
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistPinTest {

    private val playlistUrl = "https://www.youtube.com/playlist?list=PL123"
    private val watchUrl = "https://www.youtube.com/watch?v=XXXXXXXXXXX&list=PL123"
    private val entry =
        PlaylistEntry(
            type = "url",
            id = "dQw4w9WgXcQ",
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        )

    private val result =
        PlaylistResult(
            title = "List",
            entries =
                listOf(
                    entry,
                    PlaylistEntry(
                        type = "url",
                        id = "abcdefghijk",
                        url = "https://www.youtube.com/watch?v=abcdefghijk",
                    ),
                ),
        )

    @Test
    fun `a playlist url captures the entry's video id`() {
        assertEquals("dQw4w9WgXcQ", PlaylistPin.capture(playlistUrl, entry))
    }

    @Test
    fun `a watch-url playlist captures no pin`() {
        // The fetch for these items runs --no-playlist and returns video X for
        // every index, so a pin would refuse every item but X.
        assertNull(PlaylistPin.capture(watchUrl, entry))
    }

    @Test
    fun `a flat entry falls back to its bare id`() {
        val flat = PlaylistEntry(type = "url", id = "dQw4w9WgXcQ", url = "dQw4w9WgXcQ")
        assertEquals("dQw4w9WgXcQ", PlaylistPin.capture(playlistUrl, flat))
    }

    @Test
    fun `an entry that names no video captures no pin`() {
        val other = PlaylistEntry(type = "url", id = "12345", url = "https://vimeo.com/12345")
        assertNull(PlaylistPin.capture(playlistUrl, other))
    }

    @Test
    fun `a null pin passes`() {
        PlaylistPin.verify(null, "dQw4w9WgXcQ")
    }

    @Test
    fun `a matching pin passes`() {
        PlaylistPin.verify("dQw4w9WgXcQ", "dQw4w9WgXcQ")
    }

    @Test
    fun `a shifted playlist throws and the edge is a legal fetch failure`() {
        val failure =
            assertThrows(DownloadErrorMessages.PlaylistShiftedError::class.java) {
                PlaylistPin.verify("dQw4w9WgXcQ", "abcdefghijk")
            }
        assertEquals("dQw4w9WgXcQ", failure.expectedId)
        assertEquals("abcdefghijk", failure.actualId)
        assertTrue(
            TaskTransitions.isLegal(
                FetchingInfo(Job(), "task"),
                Error(throwable = failure, action = RestartableAction.FetchInfo),
            )
        )
    }

    @Test
    fun `the factory stamps the pin on the state`() {
        val created =
            TaskFactory.createWithPlaylistResult(
                    playlistUrl = playlistUrl,
                    indexList = listOf(1),
                    playlistResult = result,
                    preferences = DownloadPreferences.EMPTY,
                )
                .single()
        assertEquals("dQw4w9WgXcQ", created.state.expectedVideoId)
    }

    @Test
    fun `the factory leaves a watch-url playlist unpinned`() {
        val created =
            TaskFactory.createWithPlaylistResult(
                    playlistUrl = watchUrl,
                    indexList = listOf(1),
                    playlistResult = result,
                    preferences = DownloadPreferences.EMPTY,
                )
                .single()
        assertNull(created.state.expectedVideoId)
    }

    @Test
    fun `queue identity ignores the pin`() {
        val first =
            TaskFactory.createWithPlaylistResult(
                    playlistUrl = playlistUrl,
                    indexList = listOf(1),
                    playlistResult = result,
                    preferences = DownloadPreferences.EMPTY,
                )
                .single()
        val second =
            TaskFactory.createWithPlaylistResult(
                    playlistUrl = playlistUrl,
                    indexList = listOf(1),
                    playlistResult = result,
                    preferences = DownloadPreferences.EMPTY,
                )
                .single()
        assertEquals(first.task.id, second.task.id)
        assertTrue(first.task.isSameWork(second.task))
        assertEquals(
            Task(url = playlistUrl, type = Task.TypeInfo.Playlist(1), preferences = DownloadPreferences.EMPTY).id,
            first.task.id,
        )
    }
}
