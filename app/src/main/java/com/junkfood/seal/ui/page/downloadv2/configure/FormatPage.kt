package com.junkfood.seal.ui.page.downloadv2.configure

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSliderState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.junkfood.seal.R
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.ui.component.FormatItem
import com.junkfood.seal.ui.component.FormatSubtitle
import com.junkfood.seal.ui.component.FormatVideoPreview
import com.junkfood.seal.ui.component.PreferenceInfo
import com.junkfood.seal.ui.component.SuggestedFormatItem
import com.junkfood.seal.ui.component.TextButtonWithIcon
import com.junkfood.seal.ui.component.VideoFilterChip
import com.junkfood.seal.ui.page.download.VideoClipDialog
import com.junkfood.seal.ui.page.download.VideoSelectionSlider
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.EMBED_SUBTITLE
import com.junkfood.seal.util.Format
import com.junkfood.seal.util.FormatSelectionPolicy
import com.junkfood.seal.util.MERGE_MULTI_AUDIO_STREAM
import com.junkfood.seal.util.MERGE_OUTPUT_MKV
import com.junkfood.seal.util.PREFER_MP4
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.junkfood.seal.util.SUBTITLE
import com.junkfood.seal.util.SUBTITLE_LANGUAGE
import com.junkfood.seal.util.SubtitleFormat
import com.junkfood.seal.util.SubtitleSelectionPolicy
import com.junkfood.seal.util.VIDEO_CLIP
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.toFileSizeText
import com.junkfood.seal.util.toHttpsUrl
import kotlin.math.min
import kotlin.math.roundToInt
import org.koin.compose.koinInject

@Composable
fun FormatPage(
    modifier: Modifier = Modifier,
    videoInfo: VideoInfo,
    audioOnly: Boolean = false,
    splitByChapter: Boolean = false,
    convertAudio: Boolean = false,
    basePreferences: DownloadUtil.DownloadPreferences =
        DownloadUtil.DownloadPreferences.createFromPreferences(),
    downloader: DownloaderV2 = koinInject(),
    onNavigateBack: () -> Unit = {},
) {
    if (videoInfo.formats.isNullOrEmpty()) return
    val mergeAudioStream = !audioOnly && MERGE_MULTI_AUDIO_STREAM.getBoolean()
    val subtitleLanguageRegex =
        basePreferences.subtitleLanguage.ifBlank { SUBTITLE_LANGUAGE.getString() }
    val isClippingAvailable =
        FormatSelectionPolicy.clippingAvailable(VIDEO_CLIP.getBoolean(), videoInfo.duration)
    val remuxRequired =
        !audioOnly &&
            ((SUBTITLE.getBoolean() && EMBED_SUBTITLE.getBoolean()) ||
                MERGE_OUTPUT_MKV.getBoolean() ||
                PREFER_MP4.getBoolean())

    var pickerState by
        remember(
            videoInfo,
            audioOnly,
            basePreferences,
            splitByChapter,
            convertAudio,
            mergeAudioStream,
            subtitleLanguageRegex,
        ) {
            mutableStateOf(
                FormatPickerState.create(
                    videoInfo = videoInfo,
                    audioOnly = audioOnly,
                    basePreferences = basePreferences,
                    initialSplitByChapter = splitByChapter,
                    initialConvertAudio = convertAudio,
                    mergeAudioStream = mergeAudioStream,
                    subtitleLanguagePatterns = subtitleLanguageRegex,
                    downloadSubtitle = basePreferences.downloadSubtitle,
                )
            )
        }

    var showUpdateSubtitleDialog by remember { mutableStateOf(false) }
    var diffSubtitleLanguages by remember { mutableStateOf(emptySet<String>()) }
    val metered = remember { PendingMetered() }

    FormatPageImpl(
        modifier = modifier,
        pickerState = pickerState,
        onStateChange = { pickerState = it },
        isClippingAvailable = isClippingAvailable,
        remuxRequired = remuxRequired,
        onNavigateBack = onNavigateBack,
        onDownloadPressed = {
            metered.postDownload {
                val config = pickerState.buildDownloadConfig()
                diffSubtitleLanguages = pickerState.diffSubtitleLanguages(subtitleLanguageRegex)
                downloader.enqueue(config.toTaskWithState())

                if (diffSubtitleLanguages.isNotEmpty()) {
                    showUpdateSubtitleDialog = true
                } else {
                    onNavigateBack()
                }
            }
        },
    )
    PendingMeteredDialog(pending = metered)
    if (showUpdateSubtitleDialog) {
        UpdateSubtitleLanguageDialog(
            modifier = Modifier,
            languages = diffSubtitleLanguages,
            onDismissRequest = {
                showUpdateSubtitleDialog = false
                onNavigateBack()
            },
            onConfirm = {
                SUBTITLE_LANGUAGE.updateString(
                    SubtitleSelectionPolicy.buildLanguagePatterns(
                        subtitleLanguageRegex,
                        diffSubtitleLanguages,
                    )
                )
                showUpdateSubtitleDialog = false
                onNavigateBack()
            },
        )
    }
}

@Preview
@Composable
fun FormatPagePreview() {
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
    val videoInfo =
        VideoInfo(
            formats =
                buildList {
                    repeat(7) { add(Format(formatId = "$it")) }
                    repeat(7) { add(Format(formatId = "$it", vcodec = "avc1", acodec = "none")) }
                    repeat(7) {
                        add(
                            Format(
                                formatId = "$it",
                                acodec = "aac",
                                vcodec = "none",
                                format = "251 - audio only (medium)",
                                fileSizeApprox = 2000000.0,
                                tbr = 128.0,
                            )
                        )
                    }
                },
            subtitles = subMap,
            automaticCaptions = captionsMap,
            requestedFormats =
                buildList {
                    add(
                        Format(
                            formatId = "616",
                            format = "616 - 1920x1080 (Premium)",
                            acodec = "none",
                            vcodec = "vp09.00.40.08",
                            ext = "webm",
                        )
                    )
                    add(
                        Format(
                            formatId = "251",
                            format = "251 - audio only (medium)",
                            acodec = "opus",
                            vcodec = "none",
                            ext = "webm",
                        )
                    )
                },
            duration = 180.0,
        )
    SealTheme {
        var previewState by remember {
            mutableStateOf(
                FormatPickerState.create(
                    videoInfo = videoInfo,
                    mergeAudioStream = true,
                    subtitleLanguagePatterns = "en,ja-en",
                    downloadSubtitle = true,
                )
            )
        }
        FormatPageImpl(
            pickerState = previewState,
            onStateChange = { previewState = it },
            isClippingAvailable = true,
            remuxRequired = false,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatPageImpl(
    modifier: Modifier = Modifier,
    pickerState: FormatPickerState,
    onStateChange: (FormatPickerState) -> Unit,
    isClippingAvailable: Boolean = false,
    remuxRequired: Boolean = false,
    onNavigateBack: () -> Unit = {},
    onDownloadPressed: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val videoInfo = pickerState.videoInfo
    if (videoInfo.formats.isNullOrEmpty()) return

    val videoOnlyFormats = pickerState.videoOnlyFormats
    val audioOnlyFormats = pickerState.audioOnlyFormats
    val videoAudioFormats = pickerState.videoAudioFormats
    val duration = pickerState.duration

    var videoOnlyItemLimit by remember(videoInfo) { mutableIntStateOf(6) }
    var audioOnlyItemLimit by remember(videoInfo) { mutableIntStateOf(6) }
    var videoAudioItemLimit by remember(videoInfo) { mutableIntStateOf(6) }

    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val hapticFeedback = LocalHapticFeedback.current

    fun String?.share() =
        this?.let {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            context.startActivity(
                Intent.createChooser(
                    Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, it)
                    },
                    null,
                ),
                null,
            )
        }

    var showVideoClipDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSubtitleSelectionDialog by remember { mutableStateOf(false) }

    val lazyGridState = rememberLazyGridState()

    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.format_selection),
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                    )
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { onNavigateBack() }) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.close))
                    }
                },
            )
        },
        bottomBar = {
            // Pinned full-width pill CTA: the one action the screen exists for,
            // always reachable instead of a FAB that collapses on scroll.
            AnimatedVisibility(
                visible = pickerState.canDownload,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Button(
                        onClick = onDownloadPressed,
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
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.start_download))
                    }
                }
            }
        },
    ) { paddingValues ->
        LazyVerticalGrid(
            modifier = Modifier.padding(paddingValues),
            state = lazyGridState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            videoInfo.run {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FormatVideoPreview(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = pickerState.rename.ifEmpty { title },
                        author = uploader ?: channel ?: uploaderId.toString(),
                        thumbnailUrl = thumbnail.toHttpsUrl(),
                        duration = pickerState.duration.roundToInt(),
                        isClippingVideo = pickerState.isClippingVideo,
                        isSplittingVideo = pickerState.isSplittingVideo,
                        isClippingAvailable = isClippingAvailable,
                        isSplitByChapterAvailable = pickerState.isSplitByChapterAvailable,
                        onClippingToggled = { onStateChange(pickerState.toggleClipping()) },
                        onSplittingToggled = { onStateChange(pickerState.toggleSplitting()) },
                        onRename = { showRenameDialog = true },
                        onOpenThumbnail = { uriHandler.openUri(thumbnail.toHttpsUrl()) },
                    )
                }
            }

            videoInfo.chapters?.takeIf { it.isNotEmpty() }?.let { chapters ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ChapterSummary(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        chapters = chapters,
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                var shouldUpdateClipDuration by remember { mutableStateOf(false) }

                Column {
                    AnimatedVisibility(visible = pickerState.isClippingVideo) {
                        Column {
                            val sliderState =
                                remember(pickerState.isClippingVideo, showVideoClipDialog) {
                                    RangeSliderState(
                                        activeRangeStart = pickerState.clipRange.start,
                                        activeRangeEnd = pickerState.clipRange.endInclusive,
                                        valueRange = pickerState.videoDurationRange,
                                        onValueChangeFinished = { shouldUpdateClipDuration = true },
                                    )
                                }
                            LaunchedEffect(shouldUpdateClipDuration) {
                                if (shouldUpdateClipDuration) {
                                    onStateChange(
                                        pickerState.updateClipRange(
                                            sliderState.activeRangeStart..sliderState.activeRangeEnd
                                        )
                                    )
                                    shouldUpdateClipDuration = false
                                }
                            }

                            VideoSelectionSlider(
                                modifier = Modifier.fillMaxWidth(),
                                state = sliderState,
                                onDiscard = { onStateChange(pickerState.toggleClipping(false)) },
                                onDurationClick = { showVideoClipDialog = true },
                            )
                            HorizontalDivider()
                        }
                    }

                    AnimatedVisibility(visible = pickerState.isSplittingVideo) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text =
                                        stringResource(
                                            id = R.string.split_video_msg,
                                            videoInfo.chapters?.size ?: 0,
                                        ),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                TextButtonWithIcon(
                                    onClick = { onStateChange(pickerState.toggleSplitting(false)) },
                                    icon = Icons.Outlined.Delete,
                                    text = stringResource(id = R.string.discard),
                                    contentColor = MaterialTheme.colorScheme.error,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }

            val suggestedSubtitleMap = pickerState.suggestedSubtitleMap
            if (suggestedSubtitleMap.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 12.dp).padding(horizontal = 12.dp),
                        ) {
                            Text(
                                text = stringResource(id = R.string.subtitle_language),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )

                            ClickableTextAction(
                                visible = true,
                                text =
                                    stringResource(
                                        id =
                                            androidx.appcompat.R.string
                                                .abc_activity_chooser_view_see_all
                                    ),
                            ) {
                                showSubtitleSelectionDialog = true
                            }
                        }

                        LazyRow(modifier = Modifier.padding()) {
                            for ((code, formats) in suggestedSubtitleMap) {
                                item {
                                    VideoFilterChip(
                                        selected = pickerState.isSubtitleSelected(code),
                                        onClick = {
                                            onStateChange(pickerState.toggleSubtitle(code))
                                        },
                                        label = formats.first().run { name ?: protocol ?: code },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (pickerState.isSuggestedFormatAvailable) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.padding(top = 12.dp, bottom = 4.dp).padding(horizontal = 12.dp),
                    ) {
                        FormatSubtitle(text = stringResource(R.string.suggested))
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SuggestedFormatItem(
                            modifier = Modifier.weight(1f),
                            videoInfo = videoInfo,
                            formats = pickerState.suggestedFormats,
                            selected = pickerState.isSuggestedFormatSelected,
                            onClick = { onStateChange(pickerState.selectSuggested()) },
                        )
                    }
                }
            }

            fun audioSection() {
                if (audioOnlyFormats.isEmpty()) return
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 16.dp).padding(horizontal = 12.dp),
                    ) {
                        FormatSubtitle(
                            text = stringResource(R.string.audio),
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                        )

                        ClickableTextAction(
                            visible = audioOnlyItemLimit < audioOnlyFormats.size,
                            text = stringResource(R.string.show_all_items, audioOnlyFormats.size),
                        ) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            audioOnlyItemLimit = Int.MAX_VALUE
                        }
                    }
                }

                if (pickerState.audioOnly)
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            VideoFilterChip(
                                selected = !pickerState.convertAudio,
                                onClick = {
                                    onStateChange(
                                        pickerState.setConvertAudio(
                                            convert = false,
                                            fromMp3Chip = false,
                                        )
                                    )
                                },
                                label = stringResource(R.string.audio_source_original),
                            )
                            VideoFilterChip(
                                selected = pickerState.convertAudio,
                                onClick = {
                                    onStateChange(
                                        pickerState.setConvertAudio(
                                            convert = true,
                                            fromMp3Chip = true,
                                        )
                                    )
                                },
                                label = stringResource(R.string.audio_source_mp3),
                            )
                        }
                    }

                itemsIndexed(
                    audioOnlyFormats.subList(
                        fromIndex = 0,
                        toIndex = min(audioOnlyItemLimit, audioOnlyFormats.size),
                    )
                ) { index, formatInfo ->
                    FormatItem(
                        formatInfo = formatInfo,
                        duration = duration,
                        selected = index in pickerState.selectedAudioOnlyFormats,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        outlineColor = MaterialTheme.colorScheme.secondary,
                        onLongClick = { formatInfo.url.share() },
                    ) {
                        onStateChange(pickerState.toggleAudioOnly(index))
                    }
                }
            }

            fun videoWithoutAudioSection() {
                if (videoOnlyFormats.isEmpty()) return
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 16.dp).padding(horizontal = 12.dp),
                    ) {
                        FormatSubtitle(
                            text = stringResource(R.string.video_only),
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                        )

                        ClickableTextAction(
                            visible = videoOnlyItemLimit < videoOnlyFormats.size,
                            text = stringResource(R.string.show_all_items, videoOnlyFormats.size),
                        ) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            videoOnlyItemLimit = Int.MAX_VALUE
                        }
                    }
                }
                itemsIndexed(
                    videoOnlyFormats.subList(0, min(videoOnlyItemLimit, videoOnlyFormats.size))
                ) { index, formatInfo ->
                    FormatItem(
                        formatInfo = formatInfo,
                        duration = duration,
                        selected = pickerState.selectedVideoOnlyFormat == index,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        outlineColor = MaterialTheme.colorScheme.tertiary,
                        onLongClick = { formatInfo.url.share() },
                    ) {
                        onStateChange(pickerState.toggleVideoOnly(index))
                    }
                }
            }

            fun videoSection() {
                if (videoAudioFormats.isEmpty()) return
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 16.dp).padding(horizontal = 12.dp),
                    ) {
                        FormatSubtitle(
                            text =
                                if (pickerState.audioFallback)
                                    stringResource(R.string.video_fallback_extracts_audio)
                                else stringResource(R.string.video),
                            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                        )
                        ClickableTextAction(
                            visible = videoAudioItemLimit < videoAudioFormats.size,
                            text = stringResource(R.string.show_all_items, videoAudioFormats.size),
                        ) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            videoAudioItemLimit = Int.MAX_VALUE
                        }
                    }
                }
                itemsIndexed(
                    videoAudioFormats.subList(0, min(videoAudioItemLimit, videoAudioFormats.size))
                ) { index, formatInfo ->
                    FormatItem(
                        formatInfo = formatInfo,
                        duration = duration,
                        selected = pickerState.selectedVideoAudioFormat == index,
                        onLongClick = { formatInfo.url.share() },
                    ) {
                        onStateChange(pickerState.toggleVideoAudio(index))
                    }
                }
            }

            FormatSelectionPolicy.sectionOrder(
                    audioOnly = pickerState.audioOnly,
                    hasVideoAudio = videoAudioFormats.isNotEmpty(),
                    hasVideoOnly = videoOnlyFormats.isNotEmpty(),
                    hasAudioOnly = audioOnlyFormats.isNotEmpty(),
                )
                .forEach { section ->
                    when (section) {
                        FormatSelectionPolicy.Section.Video -> videoSection()
                        FormatSelectionPolicy.Section.VideoWithoutAudio ->
                            videoWithoutAudioSection()
                        FormatSelectionPolicy.Section.Audio -> audioSection()
                    }
                }

            if (!pickerState.audioOnly && audioOnlyFormats.isNotEmpty() && videoOnlyFormats.isNotEmpty())
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PreferenceInfo(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        text = stringResource(R.string.abs_hint),
                        applyPaddings = false,
                    )
                }
            if (!pickerState.audioOnly) {
                val expectedBytes = pickerState.expectedBytes
                if ((remuxRequired || pickerState.isMergingVideoAndAudio) && expectedBytes > 0) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PreferenceInfo(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            text =
                                stringResource(
                                    R.string.merge_space_hint,
                                    (expectedBytes * 2.2).toLong().toFileSizeText(),
                                ),
                            applyPaddings = false,
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(64.dp)) }
        }
    }
    if (showVideoClipDialog)
        VideoClipDialog(
            onDismissRequest = { showVideoClipDialog = false },
            initialValue = pickerState.clipRange,
            valueRange = pickerState.videoDurationRange,
            onConfirm = { onStateChange(pickerState.updateClipRange(it)) },
        )

    if (showRenameDialog)
        RenameDialog(
            initialValue = pickerState.rename.ifEmpty { videoInfo.title },
            onDismissRequest = { showRenameDialog = false },
        ) {
            onStateChange(pickerState.updateRename(it))
        }
    if (showSubtitleSelectionDialog)
        SubtitleSelectionDialog(
            suggestedSubtitles = pickerState.suggestedSubtitleMap,
            autoCaptions = pickerState.otherSubtitleMap,
            selectedSubtitles = pickerState.selectedSubtitles,
            selectedAutoCaptions = pickerState.selectedAutoCaptions,
            isAutoCaption = { code ->
                SubtitleSelectionPolicy.goesToAutoCaptions(
                    videoInfo.subtitles,
                    videoInfo.automaticCaptions,
                    code,
                )
            },
            onDismissRequest = { showSubtitleSelectionDialog = false },
            onConfirm = { subs, autoSubs ->
                onStateChange(pickerState.updateSubtitles(subs, autoSubs))
                showSubtitleSelectionDialog = false
            },
        )
}
