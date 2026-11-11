package com.junkfood.seal.ui.page.downloadv2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.download.BatchInfo
import com.junkfood.seal.download.MatchCandidate
import com.junkfood.seal.download.MatchOps
import com.junkfood.seal.download.SwapOutcome
import com.junkfood.seal.download.SwapResult
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.ui.component.SealModalBottomSheet
import com.junkfood.seal.util.toDurationText
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The one review entry for a finished batch. Every candidate shown here was
 * cached when the song was matched, so picking a different version never runs
 * a new search — it only re-downloads that one song.
 *
 * [onSwap] is a suspend lambda owned by this sheet: applying stays open and
 * reports one status line per song, so a partial failure is visible instead of
 * a toast behind a sheet that already closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchReviewSheet(
    batch: BatchInfo,
    entries: List<Pair<Task, Task.State>>,
    onDismissRequest: () -> Unit,
    onSwap: suspend (List<Pair<Task, MatchCandidate>>) -> List<SwapResult>,
    onDownloadParts: suspend (Task, List<MatchCandidate>) -> Int = { _, _ -> 0 },
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
    // A pick that differs from the stored selection is a swap; the batch is
    // only "accepted" when nothing is queued to be replaced.
    val swaps =
        flagged.mapNotNull { (task, state) ->
            val match = state.match ?: return@mapNotNull null
            val chosen = match.candidates.firstOrNull { it.url == picks[task.uiKey] }
            if (chosen != null && chosen.url != match.selectedUrl) task to chosen else null
        }

    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SwapResult>>(emptyList()) }
    var applyFailed by remember { mutableStateOf(false) }
    var pastedUrl by remember { mutableStateOf("") }
    // A single held book is the one case where a pasted full-book link is
    // unambiguous. Multi-book batches stay on the candidate rows.
    val singleHeld =
        flagged
            .singleOrNull()
            ?.takeIf { (_, state) ->
                val match = state.match
                match?.held == true && match.parts.isEmpty()
            }
    val pastedUrlValid =
        pastedUrl.trim().let { it.startsWith("http://") || it.startsWith("https://") }

    fun usePastedLink() {
        val task = singleHeld?.first ?: return
        val url = pastedUrl.trim()
        if (!pastedUrlValid || running) return
        running = true
        results = emptyList()
        applyFailed = false
        scope.launch {
            try {
                results =
                    onSwap(
                        listOf(
                            task to
                                MatchCandidate(
                                    id = url,
                                    url = url,
                                    title = url,
                                )
                        )
                    )
                if (results.any { it.outcome == SwapOutcome.Queued }) pastedUrl = ""
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                applyFailed = true
            } finally {
                running = false
            }
        }
    }

    fun applySwaps() {
        if (running || swaps.isEmpty()) return
        running = true
        results = emptyList()
        applyFailed = false
        scope.launch {
            try {
                results = onSwap(swaps)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Keep the sheet usable, but say the apply never ran: a silent
                // tap that does nothing reads as a broken button.
                applyFailed = true
            } finally {
                running = false
            }
        }
    }

    // A batch of held books is not a low-confidence batch: the gate refused
    // them outright, so the generic hint would understate the problem.
    val heldOnly =
        remember(flagged) {
            flagged.isNotEmpty() && flagged.all { it.second.match?.held == true }
        }
    // A verified part set is held too, but it is the opposite story: the whole
    // book was found, just never as one file.
    val splitOnly = remember(flagged) { MatchOps.splitOnly(flagged) }

    SealModalBottomSheet(
        onDismissRequest = onDismissRequest,
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) {
        Text(
            text =
                entries.singleOrNull()?.first?.metadataTitle()?.takeIf { it.isNotBlank() }
                    ?: batch.title.ifBlank { stringResource(R.string.match_review_title) },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text =
                stringResource(
                    when {
                        splitOnly -> R.string.match_split_hint
                        heldOnly -> R.string.match_held_hint
                        else -> R.string.match_review_hint
                    }
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        // Weighted, not capped: on a short or landscape screen the list takes
        // whatever is left and scrolls, so the action row stays reachable.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).padding(vertical = 12.dp)
        ) {
            items(flagged, key = { it.first.uiKey }) { (task, state) ->
                val match = state.match ?: return@items
                val pending = match.pending
                val requestedMs = (task.type as? Task.TypeInfo.MetadataTrack)?.durationMs ?: 0L
                val warning = (state.downloadState as? Completed)?.warning.orEmpty()
                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text =
                                task.metadataTitle()?.takeIf { it.isNotBlank() }
                                    ?: state.viewState.title.ifBlank { task.url },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (pending) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = stringResource(R.string.match_pending),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    if (match.held) {
                        // The gate refused this book. A score cannot explain a
                        // hold, so the reason is shown verbatim. A verified part
                        // set is evidence the whole book was found, not an
                        // error, so it must not borrow the error colour.
                        val verifiedSplit = MatchOps.isVerifiedSplit(match)
                        if (match.gateReason.isNotBlank()) {
                            Text(
                                text = match.gateReason,
                                style = MaterialTheme.typography.labelMedium,
                                color =
                                    if (verifiedSplit) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Text(
                            text =
                                if (verifiedSplit)
                                    stringResource(
                                        R.string.match_split_row_hint,
                                        match.parts.size,
                                    )
                                else stringResource(R.string.match_held_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (warning.isNotBlank()) {
                        // Measured against the catalogue runtime; no percentage
                        // is invented when either side is unknown.
                        val expectedMs =
                            match.expectedDurationMs.takeIf { it > 0L } ?: requestedMs
                        val actualMs = match.actualDurationMs
                        if (actualMs > 0L && expectedMs > 0L) {
                            Text(
                                text =
                                    stringResource(
                                        R.string.match_verify_mismatch,
                                        (actualMs / 1000L).toInt().toDurationText(),
                                        (expectedMs / 1000L).toInt().toDurationText(),
                                    ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (match.parts.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.match_split_title, match.parts.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        match.parts.forEach { part ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text =
                                        stringResource(
                                            R.string.match_part_label,
                                            MatchOps.partDisplayNumber(match, part),
                                        ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(64.dp),
                                )
                                Text(
                                    text = part.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = part.durationText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Button(
                            onClick = {
                                if (!running) {
                                    running = true
                                    scope.launch {
                                        try {
                                            if (onDownloadParts(task, match.parts) > 0) {
                                                applyFailed = false
                                            } else {
                                                applyFailed = true
                                            }
                                        } catch (_: Throwable) {
                                            applyFailed = true
                                        } finally {
                                            running = false
                                        }
                                    }
                                }
                            },
                            enabled = !running,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.match_download_parts, match.parts.size))
                        }
                    }
                    if (match.parts.isEmpty()) match.candidates.forEach { candidate ->
                        val selected = picks[task.uiKey] == candidate.url
                        val meta =
                            buildList {
                                add(candidate.scoreLabel)
                                candidate.channel
                                    .ifBlank { candidate.uploader }
                                    .takeIf { it.isNotBlank() }
                                    ?.let(::add)
                                candidate.durationText.takeIf { it.isNotBlank() }?.let(::add)
                                durationDelta(candidate.duration, requestedMs)?.let(::add)
                            }
                                .joinToString(" · ")
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .selectable(
                                        selected = selected,
                                        // A pending replacement is already in
                                        // flight; a second pick would orphan it.
                                        enabled = !pending,
                                        role = Role.RadioButton,
                                        onClick = { picks[task.uiKey] = candidate.url },
                                    )
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null, enabled = !pending)
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(
                                    text = candidate.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = meta,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (candidate.reasons.isNotEmpty()) {
                                    Text(
                                        text = candidate.reasons.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (applyFailed) {
            Text(
                text = stringResource(R.string.match_swap_failed),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        if (results.isNotEmpty()) {
            // One headline, then one line per song: a partial failure is the
            // answer the user came for, not a toast behind a dismissed sheet.
            val replaced = results.count { it.outcome == SwapOutcome.Queued }
            val failed = results.size - replaced
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(
                    text =
                        if (failed == 0)
                            stringResource(R.string.match_swap_summary_all, replaced)
                        else
                            stringResource(
                                R.string.match_swap_summary,
                                replaced,
                                results.size,
                                failed,
                            ),
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        if (failed == 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                results.forEach { result -> SwapStatusLine(result = result) }
            }
        }
        if (swaps.isNotEmpty()) {
            Text(
                text = stringResource(R.string.match_accept_all_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
        singleHeld?.let { (_, _) ->
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(
                    text = stringResource(R.string.match_paste_link_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = pastedUrl,
                        onValueChange = { pastedUrl = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !running,
                        placeholder = { Text("https://youtube.com/...") },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { usePastedLink() },
                        enabled = pastedUrlValid && !running,
                    ) {
                        Text(stringResource(R.string.match_use_link))
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onAcceptAll,
                enabled =
                    swaps.isEmpty() &&
                        !running &&
                        flagged.none { (_, state) ->
                            state.match?.let { it.held || it.pending } == true
                        },
            ) {
                Text(stringResource(R.string.accept_all))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { applySwaps() }, enabled = swaps.isNotEmpty() && !running) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.apply_swaps, swaps.size))
            }
        }
    }
}

/** One user-safe line per song; the reason stays short by contract. */
@Composable
private fun SwapStatusLine(result: SwapResult) {
    val label =
        stringResource(
            when (result.outcome) {
                SwapOutcome.Queued -> R.string.match_swap_queued
                SwapOutcome.Busy -> R.string.match_swap_busy
                SwapOutcome.Missing -> R.string.match_swap_missing
                SwapOutcome.FetchFailed -> R.string.match_swap_fetch_failed
            }
        )
    Text(
        text =
            buildString {
                append(result.title.ifBlank { result.candidateTitle })
                append(" · ")
                append(label)
                if (result.reason.isNotBlank()) {
                    append(" · ")
                    append(result.reason)
                }
            },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/**
 * `+m:ss` / `-m:ss` against the requested track. Either side unknown (or an
 * exact match) hides the delta instead of printing a fake 0:00.
 */
private fun durationDelta(candidateSeconds: Long, requestedMs: Long): String? {
    if (candidateSeconds <= 0L || requestedMs <= 0L) return null
    val delta = candidateSeconds - requestedMs / 1000L
    if (delta == 0L) return null
    val absolute = if (delta < 0) -delta else delta
    val text = String.format(Locale.US, "%d:%02d", absolute / 60L, absolute % 60L)
    return if (delta > 0) "+$text" else "-$text"
}
