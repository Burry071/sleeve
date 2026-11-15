package com.junkfood.seal

import com.junkfood.seal.download.MatchCandidate
import com.junkfood.seal.download.MatchOps
import com.junkfood.seal.download.MatchResult
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.RestartableAction
import com.junkfood.seal.ui.page.downloadv2.cataloguePageUrl
import com.junkfood.seal.ui.page.downloadv2.metadataQuery
import com.junkfood.seal.ui.page.downloadv2.metadataTitle
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class MatchSplitCopyTest {

    private val part = MatchCandidate(index = 1, url = "https://example.com/p1", title = "Part 1")

    private fun entry(match: MatchResult?): Pair<Task, Task.State> {
        val task =
            Task(
                url = "https://example.com/v",
                type = Task.TypeInfo.URL,
                preferences = DownloadPreferences.EMPTY,
            )
        val state =
            Task.State(
                Task.DownloadState.Canceled(RestartableAction.Download),
                null,
                Task.ViewState(),
                match,
            )
        return task to state
    }

    @Test
    fun `held pick with parts is a verified split`() {
        assertEquals(
            true,
            MatchOps.isVerifiedSplit(MatchResult(held = true, parts = listOf(part))),
        )
    }

    @Test
    fun `held pick without parts is not a verified split`() {
        assertEquals(false, MatchOps.isVerifiedSplit(MatchResult(held = true)))
        assertEquals(false, MatchOps.isVerifiedSplit(MatchResult(parts = listOf(part))))
        assertEquals(false, MatchOps.isVerifiedSplit(null))
    }

    @Test
    fun `split only is true when every reviewable entry is a split set`() {
        val split = MatchResult(held = true, parts = listOf(part))
        val entries = listOf(entry(split), entry(split.copy(parts = listOf(part, part))))
        assertEquals(true, MatchOps.splitOnly(entries))
    }

    @Test
    fun `split only is false when any reviewable entry needs a manual pick`() {
        val split = MatchResult(held = true, parts = listOf(part))
        val held = MatchResult(held = true)
        assertEquals(false, MatchOps.splitOnly(listOf(entry(split), entry(held))))
    }

    @Test
    fun `split only is false with nothing to review`() {
        assertEquals(false, MatchOps.splitOnly(emptyList()))
    }

    @Test
    fun `title comes before the author and authors outrank the artist`() {
        val track =
            Task.TypeInfo.MetadataTrack(
                title = "Atomic Habits",
                artist = "Narrator Name",
                authors = "James Clear",
            )
        assertEquals("Atomic Habits — James Clear", metadataTitle(track))
    }

    @Test
    fun `artist is the byline when authors is empty`() {
        val track = Task.TypeInfo.MetadataTrack(title = "Song", artist = "Some Artist")
        assertEquals("Song — Some Artist", metadataTitle(track))
    }

    @Test
    fun `a title alone carries no separator`() {
        assertEquals("Song", metadataTitle(Task.TypeInfo.MetadataTrack(title = " Song ")))
    }

    @Test
    fun `the search query is the title and the byline`() {
        val book =
            Task.TypeInfo.MetadataTrack(
                title = "Atomic Habits",
                artist = "Narrator Name",
                authors = "James Clear",
            )
        val song = Task.TypeInfo.MetadataTrack(title = "Song", artist = "Some Artist")

        assertEquals("Atomic Habits James Clear", metadataQuery(book))
        assertEquals("Song Some Artist", metadataQuery(song))
        assertEquals("Song", metadataQuery(Task.TypeInfo.MetadataTrack(title = " Song ")))
    }

    @Test
    fun `an Audible book opens on its catalogue search`() {
        val book =
            Task.TypeInfo.MetadataTrack(
                provider = "Audible",
                title = "Harry Potter & the Stone",
                authors = "J.K. Rowling",
            )

        assertEquals(
            "https://www.audible.com/search?keywords=Harry%20Potter%20%26%20the%20Stone%20J.K.%20Rowling",
            cataloguePageUrl(book),
        )
    }

    @Test
    fun `no catalogue page without a provider or any terms`() {
        assertEquals(
            null,
            cataloguePageUrl(Task.TypeInfo.MetadataTrack(provider = "Spotify", title = "Song")),
        )
        assertEquals(
            null,
            cataloguePageUrl(Task.TypeInfo.MetadataTrack(provider = "Audible", title = " ")),
        )
    }
}
