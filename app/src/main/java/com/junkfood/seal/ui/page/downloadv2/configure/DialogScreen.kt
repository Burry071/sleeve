package com.junkfood.seal.ui.page.downloadv2.configure

import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SelectionState
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetValue

/**
 * The one thing the configure flow puts on screen, derived from all three of
 * its flows at once.
 *
 * The sheet visibility and the selection page used to be decided separately in
 * both hosts, so a fetch that resolved and hid the sheet in one frame could
 * leave the sheet and its replacement on screen together. A selection wins
 * here: it is the result the sheet was waiting for. [SheetValue.Hidden] wins
 * over a stale [SheetState] for the same reason — a hidden sheet shows nothing.
 */
sealed interface DialogScreen {
    data object Hidden : DialogScreen

    data class Sheet(val state: SheetState) : DialogScreen

    data class Format(val state: SelectionState.FormatSelection) : DialogScreen

    data class Playlist(val state: SelectionState.PlaylistSelection) : DialogScreen

    data class Metadata(val state: SelectionState.MetadataTracks) : DialogScreen
}

fun dialogScreen(
    sheetValue: SheetValue,
    sheetState: SheetState,
    selection: SelectionState,
): DialogScreen =
    when (selection) {
        is SelectionState.FormatSelection -> DialogScreen.Format(selection)
        is SelectionState.PlaylistSelection -> DialogScreen.Playlist(selection)
        is SelectionState.MetadataTracks -> DialogScreen.Metadata(selection)
        SelectionState.Idle ->
            when (sheetValue) {
                SheetValue.Expanded -> DialogScreen.Sheet(sheetState)
                SheetValue.Hidden -> DialogScreen.Hidden
            }
    }
