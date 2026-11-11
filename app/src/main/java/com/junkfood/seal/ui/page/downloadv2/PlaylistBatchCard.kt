package com.junkfood.seal.ui.page.downloadv2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.download.BatchInfo
import com.junkfood.seal.download.BatchOps
import com.junkfood.seal.download.MatchOps
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.ui.common.LocalDarkTheme
import com.kyant.monet.TonalPalettes.Companion.toTonalPalettes

/**
 * Amber warning pair for the low-confidence badge. Green is reserved for the
 * completed pill, so a flagged pick can never read as success.
 */
private val MatchWarnPalettes = Color(0xFFFFA000).toTonalPalettes()

/**
 * "Title — Author": the requested catalogue song, never the picked video's
 * title. `authors` outranks `artist` because an audiobook's artist field is the
 * narrator, which reads as the byline when shown first.
 */
internal fun metadataTitle(track: Task.TypeInfo.MetadataTrack): String =
    listOf(track.title, track.authors.ifBlank { track.artist })
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" — ")

/** The catalogue title for a metadata task, or null for ordinary URL shares. */
internal fun Task.metadataTitle(): String? =
    (type as? Task.TypeInfo.MetadataTrack)?.let(::metadataTitle)

/**
 * One playlist download: a summary header with batch actions and compact song
 * rows that reuse the single-download card's state pill and action button, so
 * colours, animations, and typography stay identical.
 */
@Composable
fun PlaylistBatchCard(
    modifier: Modifier = Modifier,
    batch: BatchInfo,
    entries: List<Pair<Task, Task.State>>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
    onReview: () -> Unit,
    onSongClick: (Task) -> Unit,
    onSongAction: (Task, UiAction) -> Unit,
) {
    val summary = remember(entries) { BatchOps.summary(entries) }
    val reviewCount = remember(entries) { MatchOps.reviewCount(entries) }
    // A single share still rides a one-song batch. Name the actual song and drop
    // the "0/1 done" line: a lone track has no progress to summarise.
    val singleTrack = remember(entries) { entries.singleOrNull()?.first }
    val containerColor =
        MaterialTheme.colorScheme.run {
            if (LocalDarkTheme.current.isDarkTheme()) surfaceContainer else surfaceContainerLowest
        }
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable(onClick = onToggleExpanded)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        text =
                            singleTrack?.metadataTitle()?.takeIf { it.isNotBlank() }
                                ?: batch.title.ifBlank { stringResource(R.string.playlist) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (singleTrack == null) {
                        Text(
                            text =
                                if (summary.failed > 0)
                                    stringResource(
                                        R.string.playlist_progress_failed,
                                        summary.completed,
                                        summary.total,
                                        summary.failed,
                                    )
                                else
                                    stringResource(
                                        R.string.playlist_progress,
                                        summary.completed,
                                        summary.total,
                                    ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector =
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.show_more_actions),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dismiss_playlist)) },
                            onClick = {
                                menuExpanded = false
                                onDismiss()
                            },
                        )
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                // Independent conditions: a batch can be partly stopped and
                // partly failed, so both actions may be visible at once.
                if (summary.unfinished > 0) {
                    TextButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
                }
                if (summary.canceled + summary.failed > 0) {
                    TextButton(onClick = onResume) { Text(stringResource(R.string.resume)) }
                }
                // The single review entry: only after the batch finishes and
                // only while at least one flagged pick is still unacknowledged.
                if (MatchOps.reviewVisible(entries)) {
                    TextButton(onClick = onReview) {
                        Text(stringResource(R.string.review_matches, reviewCount))
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    entries.forEach { (task, state) ->
                        key(task.uiKey) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            SongRow(
                                task = task,
                                state = state,
                                onClick = { onSongClick(task) },
                                onAction = { onSongAction(task, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongRow(
    task: Task,
    state: Task.State,
    onClick: () -> Unit,
    onAction: (UiAction) -> Unit,
) {
    val progress = (state.downloadState as? Running)?.progress?.takeIf { it >= 0f }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text =
                    task.metadataTitle()?.takeIf { it.isNotBlank() }
                        ?: state.viewState.title.ifBlank { task.url },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (MatchOps.reviewable(state)) {
                val isDarkTheme = LocalDarkTheme.current.isDarkTheme()
                // A pending replacement is already downloading: say so, and keep
                // the amber pair for "this pick may be wrong" only.
                val pending = state.match?.pending == true
                val held = state.match?.held == true
                Surface(
                    color =
                        if (pending) MaterialTheme.colorScheme.primaryContainer
                        else MatchWarnPalettes.accent1(if (isDarkTheme) 30.0 else 90.0),
                    contentColor =
                        if (pending) MaterialTheme.colorScheme.onPrimaryContainer
                        else MatchWarnPalettes.accent1(if (isDarkTheme) 90.0 else 30.0),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    // Compact icon + one word: a row cannot act on a percentage.
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!pending) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text =
                                stringResource(
                                    when {
                                        pending -> R.string.match_pending
                                        held -> R.string.match_held_badge
                                        else -> R.string.match_badge_short
                                    }
                                ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            val warning = (state.downloadState as? Completed)?.warning.orEmpty()
            if (warning.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.status_incomplete),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
        CardStateIndicator(modifier = Modifier, downloadState = state.downloadState)
        ActionButton(
            modifier = Modifier,
            downloadState = state.downloadState,
            onActionPost = onAction,
        )
    }
}
