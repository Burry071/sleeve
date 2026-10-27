package com.junkfood.seal.ui.page.downloadv2.configure

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
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

            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                itemsIndexed(state.tracks) { index, track: SpotifyTrack ->
                    TrackRow(index = index + 1, track = track)
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Button(
                    onClick = { onActionPost(Action.DownloadMetadataTracks(state.provider, state.tracks)) },
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
                            "Download " + state.tracks.size.toString() +
                                if (state.tracks.size == 1) " track" else " tracks",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(index: Int, track: SpotifyTrack) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable {}
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.LibraryMusic,
            contentDescription = null,
            modifier = Modifier.padding(end = 12.dp).size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
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
