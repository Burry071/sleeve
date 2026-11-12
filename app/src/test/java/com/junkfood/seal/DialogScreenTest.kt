package com.junkfood.seal

import com.junkfood.seal.ghostengine.GhostEngine.SpotifyTrack
import com.junkfood.seal.ui.page.downloadv2.configure.DialogScreen
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SelectionState
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetValue
import com.junkfood.seal.ui.page.downloadv2.configure.dialogScreen
import com.junkfood.seal.util.PlaylistResult
import com.junkfood.seal.util.VideoInfo
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * One projection, two hosts (the downloads page and QuickDownloadActivity).
 * The full grid is pinned here so a host can never disagree with the other
 * about what is on screen — before this, each one walked the same two flows
 * with its own precedence rules.
 */
class DialogScreenTest {
    private val formats = SelectionState.FormatSelection(VideoInfo(id = "v1"))
    private val playlist = SelectionState.PlaylistSelection(PlaylistResult(title = "p1"))
    private val metadata =
        SelectionState.MetadataTracks(
            provider = "Spotify",
            playlistTitle = "Now",
            tracks = listOf(SpotifyTrack(title = "Song", artist = "A", album = "B", durationMs = 1L)),
        )

    private val sheetStates =
        listOf(
            SheetState.InputUrl,
            SheetState.Configure(listOf("https://example.test/v")),
            SheetState.Loading("key", Job()),
            SheetState.Error(
                action = Action.FetchSpotify("https://example.test/v"),
                throwable = RuntimeException("boom"),
            ),
        )

    private val sheetValues = listOf(SheetValue.Expanded, SheetValue.Hidden)
    private val selections = listOf(SelectionState.Idle, formats, playlist, metadata)

    @Test
    fun aSelectionWinsOverTheSheetInEveryCombination() {
        sheetValues.forEach { value ->
            sheetStates.forEach { sheet ->
                val format = dialogScreen(value, sheet, formats)
                assertEquals(DialogScreen.Format::class, format::class)
                assertSame(formats, (format as DialogScreen.Format).state)

                val playlistScreen = dialogScreen(value, sheet, playlist)
                assertEquals(DialogScreen.Playlist::class, playlistScreen::class)
                assertSame(playlist, (playlistScreen as DialogScreen.Playlist).state)

                val metadataScreen = dialogScreen(value, sheet, metadata)
                assertEquals(DialogScreen.Metadata::class, metadataScreen::class)
                assertSame(metadata, (metadataScreen as DialogScreen.Metadata).state)
            }
        }
    }

    @Test
    fun anExpandedIdleSheetShowsTheSheetWithItsCurrentState() {
        sheetStates.forEach { sheet ->
            val screen = dialogScreen(SheetValue.Expanded, sheet, SelectionState.Idle)
            assertEquals(DialogScreen.Sheet::class, screen::class)
            assertSame(sheet, (screen as DialogScreen.Sheet).state)
        }
    }

    @Test
    fun aHiddenIdleSheetShowsNothingEvenWithAStaleSheetState() {
        sheetStates.forEach { sheet ->
            assertEquals(
                DialogScreen.Hidden,
                dialogScreen(SheetValue.Hidden, sheet, SelectionState.Idle),
            )
        }
    }

    @Test
    fun theGridCoversEveryInput() {
        // 2 sheet values x 4 sheet states x 4 selections, no combination left
        // to a default branch.
        var seen = 0
        sheetValues.forEach { value ->
            sheetStates.forEach { sheet ->
                selections.forEach { selection ->
                    dialogScreen(value, sheet, selection)
                    seen++
                }
            }
        }
        assertEquals(sheetValues.size * sheetStates.size * selections.size, seen)
    }
}
