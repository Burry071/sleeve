package com.junkfood.seal.ui.page.downloadv2.configure

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.ui.component.ClearButton
import com.junkfood.seal.ui.component.ConfirmButton
import com.junkfood.seal.ui.component.DismissButton
import com.junkfood.seal.ui.component.SealDialog
import com.junkfood.seal.ui.component.SealSearchBar
import com.junkfood.seal.ui.page.settings.general.DialogCheckBoxItem
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.ui.theme.generateLabelColor
import com.junkfood.seal.util.Chapter
import com.junkfood.seal.util.SubtitleFormat

/**
 * What an audiobook export would contain: the chapter count, then the first few
 * titles. Shown for any chaptered source so the user sees the shape of the
 * output before starting the download.
 */
@Composable
internal fun ChapterSummary(modifier: Modifier = Modifier, chapters: List<Chapter>) {
    val titles = chapters.take(3).mapNotNull { it.title?.trim()?.takeIf(String::isNotEmpty) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            text =
                (listOf(
                        pluralStringResource(
                            R.plurals.chapter_summary_count,
                            chapters.size,
                            chapters.size,
                        )
                    ) + titles)
                    .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun RenameDialog(
    initialValue: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var filename by remember { mutableStateOf(initialValue) }
    SealDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            ConfirmButton {
                onConfirm(filename)
                onDismissRequest()
            }
        },
        dismissButton = { DismissButton { onDismissRequest() } },
        title = { Text(text = stringResource(id = R.string.rename)) },
        icon = { Icon(imageVector = Icons.Outlined.Edit, contentDescription = null) },
        text = {
            Column {
                OutlinedTextField(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    value = filename,
                    onValueChange = { filename = it },
                    label = { Text(text = stringResource(id = R.string.title)) },
                    trailingIcon = { if (filename == initialValue) ClearButton { filename = "" } },
                )
            }
        },
    )
}

private fun (Map<String, List<SubtitleFormat>>).filterWithSearchText(
    searchText: String
): Map<String, List<SubtitleFormat>> {
    return this.filter {
        it.run {
            searchText.isBlank() ||
                key.contains(searchText, ignoreCase = true) ||
                value.any { format ->
                    format.name?.contains(searchText, ignoreCase = true) ?: false
                }
        }
    }
}

private fun Map<String, List<SubtitleFormat>>.sortedWithSelection(
    selectedKeys: List<String>
): Map<String, List<SubtitleFormat>> {
    return this.toList()
        .sortedWith { entry1, entry2 ->
            when {
                entry1.first in selectedKeys && entry2.first in selectedKeys ->
                    entry1.compareTo(entry2) // Both in selectedKeys - equal priority
                entry1.first in selectedKeys -> -1 // str1 has priority
                entry2.first in selectedKeys -> 1 // str2 has priority
                else -> entry1.compareTo(entry2)
            }
        }
        .toMap()
}

/**
 * Prioritizes comparison of subtitle names (via `getSubtitleName()`) if available, otherwise
 * compares the `key` portion of the pairs.
 *
 * Examples: `zh` (Chinese) should be greater than `en` (English) according to their names
 */
private fun (Pair<String, List<SubtitleFormat>>).compareTo(
    other: (Pair<String, List<SubtitleFormat>>)
): Int {
    val (key, list) = this
    val (otherKey, otherList) = other

    val name = list.getSubtitleName()
    val otherName = otherList.getSubtitleName()

    return if (name != null && otherName != null) {
        name.compareTo(otherName)
    } else {
        key.compareTo(otherKey)
    }
}

private fun (List<SubtitleFormat>).getSubtitleName(): String? = firstOrNull()?.name

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubtitleSelectionDialog(
    suggestedSubtitles: Map<String, List<SubtitleFormat>>,
    autoCaptions: Map<String, List<SubtitleFormat>>,
    selectedSubtitles: List<String>,
    selectedAutoCaptions: List<String> = emptyList(),
    isAutoCaption: (String) -> Boolean = { false },
    onDismissRequest: () -> Unit = {},
    onConfirm: (subs: List<String>, autoSubs: List<String>) -> Unit = { _, _ -> },
) {
    var searchText by remember { mutableStateOf("") }
    val selectedSubtitlesState = remember {
        mutableStateListOf<String>().apply { addAll(selectedSubtitles) }
    }
    val selectedAutoCaptionsState = remember {
        mutableStateListOf<String>().apply { addAll(selectedAutoCaptions) }
    }

    val suggestedSubtitlesFiltered =
        suggestedSubtitles
            .filterWithSearchText(searchText)
            .sortedWithSelection(selectedSubtitlesState + selectedAutoCaptionsState)
    val autoCaptionsFiltered =
        autoCaptions
            .filterWithSearchText(searchText)
            .sortedWithSelection(selectedAutoCaptionsState)

    SealDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        onDismissRequest = onDismissRequest,
        confirmButton = {
            ConfirmButton { onConfirm(selectedSubtitlesState, selectedAutoCaptionsState) }
        },
        dismissButton = { DismissButton { onDismissRequest() } },
        title = { Text(text = stringResource(id = R.string.subtitle_language)) },
        icon = { Icon(imageVector = Icons.Outlined.Subtitles, contentDescription = null) },
        text = {
            Column {
                if (autoCaptions.size + suggestedSubtitles.size > 5) {
                    SealSearchBar(
                        text = searchText,
                        placeholderText = stringResource(R.string.search_in_subtitles),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        searchText = it
                    }
                }

                LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
                    if (suggestedSubtitlesFiltered.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(id = R.string.suggested),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                            )
                        }
                    }
                    for ((code, formats) in suggestedSubtitlesFiltered) {
                        item(key = code) {
                            val target =
                                if (isAutoCaption(code)) selectedAutoCaptionsState
                                else selectedSubtitlesState
                            DialogCheckBoxItem(
                                modifier = Modifier.animateItem(),
                                checked = target.contains(code),
                                onValueChange = {
                                    if (target.contains(code)) {
                                        target.remove(code)
                                    } else {
                                        target.add(code)
                                    }
                                },
                                text = formats.first().run { name ?: protocol ?: code },
                            )
                        }
                    }

                    if (autoCaptionsFiltered.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(id = R.string.auto_subtitle),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            )
                        }
                        for ((code, formats) in autoCaptionsFiltered) {
                            item(key = code) {
                                DialogCheckBoxItem(
                                    modifier = Modifier.animateItem(),
                                    checked = selectedAutoCaptionsState.contains(code),
                                    onValueChange = {
                                        if (selectedAutoCaptionsState.contains(code)) {
                                            selectedAutoCaptionsState.remove(code)
                                        } else {
                                            selectedAutoCaptionsState.add(code)
                                        }
                                    },
                                    text = formats.first().run { name ?: protocol ?: code },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        },
    )
}

@Preview
@Composable
private fun SubtitleSelectionDialogPreview() {
    val captionsMap =
        mapOf(
            "en-en" to listOf(SubtitleFormat(ext = "", url = "", name = "English from English")),
            "ja-en" to listOf(SubtitleFormat(ext = "", url = "", name = "Japanese from English")),
            "zh-Hans-en" to
                listOf(
                    SubtitleFormat(ext = "", url = "", name = "Chinese (Simplified) from English")
                ),
            "zh-Hant-en" to
                listOf(
                    SubtitleFormat(ext = "", url = "", name = "Chinese (Traditional) from English")
                ),
        )

    val subMap = buildMap {
        put("en", listOf(SubtitleFormat(ext = "ass", url = "", name = "English")))
        put("ja", listOf(SubtitleFormat(ext = "ass", url = "", name = "Japanese")))
    }

    SealTheme {
        SubtitleSelectionDialog(
            suggestedSubtitles = subMap,
            autoCaptions = captionsMap,
            selectedSubtitles = listOf(),
        )
    }
}

@Composable
internal fun ClickableTextAction(
    visible: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AnimatedVisibility(visible = visible, exit = fadeOut(animationSpec = spring())) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
            modifier =
                modifier
                    .clip(CircleShape)
                    .clickable(onClick = onClick)
                    .padding(vertical = 4.dp, horizontal = 12.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Preview
fun UpdateSubtitleLanguageDialog(
    modifier: Modifier = Modifier,
    languages: Set<String> = setOf("en", "ja"),
    onDismissRequest: () -> Unit = {},
    onConfirm: () -> Unit = {},
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = stringResource(R.string.update_subtitle_languages),
                textAlign = TextAlign.Center,
            )
        },
        icon = { Icon(imageVector = Icons.Filled.Subtitles, contentDescription = null) },
        text = {
            Column {
                Text(text = stringResource(R.string.update_language_msg))

                Spacer(modifier = Modifier.height(24.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    languages.forEach {
                        Row(modifier = Modifier, verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier =
                                    Modifier.padding(end = 8.dp)
                                        .size(16.dp)
                                        .background(
                                            color = it.hashCode().generateLabelColor(),
                                            shape = CircleShape,
                                        )
                                        .clearAndSetSemantics {}
                            ) {}
                            Text(
                                text = it,
                                modifier = Modifier,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(text = stringResource(id = R.string.okay)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismissRequest) {
                Text(text = stringResource(id = R.string.no_thanks))
            }
        },
    )
}
