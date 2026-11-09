package com.junkfood.seal.ui.page.downloadv2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.download.BatchInfo
import com.junkfood.seal.download.MatchCandidate
import com.junkfood.seal.download.MatchOps
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.TypeInfo
import com.junkfood.seal.ui.component.SealModalBottomSheet

/**
 * The one review entry for a finished batch. Every candidate shown here was
 * cached when the song was matched, so picking a different version never runs
 * a new search — it only re-downloads that one song.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchReviewSheet(
    batch: BatchInfo,
    entries: List<Pair<Task, Task.State>>,
    onDismissRequest: () -> Unit,
    onSwap: (List<Pair<Task, MatchCandidate>>) -> Unit,
    onAcceptAll: () -> Unit,
) {
    val flagged = remember(entries) { MatchOps.flaggedEntries(entries) }
    val picks = remember(flagged) {
        mutableStateMapOf<String, String>().apply {
            flagged.forEach { (task, state) ->
                state.match?.selectedUrl?.let { put(task.uiKey, it) }
            }
        }
    }
    val swaps =
        flagged.mapNotNull { (task, state) ->
            val match = state.match ?: return@mapNotNull null
            val chosen = match.candidates.firstOrNull { it.url == picks[task.uiKey] }
            if (chosen != null && chosen.url != match.selectedUrl) task to chosen else null
        }

    SealModalBottomSheet(
        onDismissRequest = onDismissRequest,
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) {
        Text(
            text = batch.title.ifBlank { stringResource(R.string.match_review_title) },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.match_review_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(vertical = 12.dp)
        ) {
            items(flagged, key = { it.first.uiKey }) { (task, state) ->
                val match = state.match ?: return@items
                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                    Text(
                        text =
                            state.viewState.title.ifBlank {
                                (task.type as? TypeInfo.MetadataTrack)?.title.orEmpty()
                            },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    match.candidates.forEach { candidate ->
                        val selected = picks[task.uiKey] == candidate.url
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .selectable(
                                        selected = selected,
                                        role = Role.RadioButton,
                                        onClick = { picks[task.uiKey] = candidate.url },
                                    )
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(
                                    text = candidate.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text =
                                        "${candidate.percent}% · " +
                                            candidate.reasons.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onAcceptAll) { Text(stringResource(R.string.accept_all)) }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { onSwap(swaps) }, enabled = swaps.isNotEmpty()) {
                Text(stringResource(R.string.apply_swaps, swaps.size))
            }
        }
    }
}
