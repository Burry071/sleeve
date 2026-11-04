package com.junkfood.seal.ui.page.downloadv2.configure

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.ghostengine.GhostEngine.SpotifyTrack
import com.junkfood.seal.ui.component.SealModalBottomSheet
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import kotlinx.coroutines.launch

/**
 * spotDL-pattern track list for a resolved Spotify playlist/album/track:
 * metadata shown from Spotify, audio downloaded from YouTube via yt-dlp
 * (audio-only preset) by [DownloaderV2].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataTracksPage(
    modifier: Modifier = Modifier,
    state: DownloadDialogViewModel.SelectionState.MetadataTracks,
    onDismissRequest: () -> Unit,
    onActionPost: (Action) -> Unit,
) {
    val sheetState =
        androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun close() = scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() }

    // Default: everything the playlist resolved to is selected.
    val selected =
        remember(state.tracks) {
            mutableStateListOf<Int>().apply { addAll(state.tracks.indices) }
        }
    val allSelected = selected.size == state.tracks.size

    fun toggleAll() {
        if (allSelected) selected.clear() else selected.apply { clear(); addAll(state.tracks.indices) }
    }

    SealModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismissRequest,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { close() }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        text = state.playlistTitle.ifBlank { stringResource(R.string.playlist) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            state.provider + " → YouTube • " +
                                state.tracks.size.toString() +
                                if (state.tracks.size == 1) " track" else " tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .toggleable(
                            value = allSelected,
                            role = Role.Checkbox,
                            onValueChange = { toggleAll() },
                        )
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = allSelected, onCheckedChange = null)
                Text(
                    text = stringResource(R.string.select_all),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.selected_item_count, selected.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                itemsIndexed(state.tracks) { index, track: SpotifyTrack ->
                    TrackRow(
                        index = index + 1,
                        track = track,
                        checked = index in selected,
                        onCheckedChange = {
                            if (index in selected) selected.remove(index) else selected.add(index)
                        },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Button(
                    onClick = {
                        onActionPost(
                            Action.DownloadMetadataTracks(
                                provider = state.provider,
                                playlistTitle = state.playlistTitle,
                                tracks = selected.sorted().map { state.tracks[it] },
                            )
                        )
                    },
                    enabled = selected.isNotEmpty(),
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .navigationBarsPadding(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text =
                            "Download " + selected.size.toString() +
                                if (selected.size == 1) " track" else " tracks",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    index: Int,
    track: SpotifyTrack,
    checked: Boolean,
    onCheckedChange: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onCheckedChange() })
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    listOf(track.artist, track.album)
                        .filter { it.isNotBlank() }
                        .joinToString(separator = " • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text =
                if (track.durationMs >= 3600_000L)
                    "%d:%02d:%02d".format(
                        track.durationMs / 3600_000L,
                        track.durationMs / 60_000L % 60,
                        track.durationMs / 1000L % 60,
                    )
                else "%d:%02d".format(track.durationMs / 60000, track.durationMs / 1000 % 60),
            style =
                MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            fontFamily = FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
