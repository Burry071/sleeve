package com.junkfood.seal.ui.page.downloadv2.configure

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.R
import com.junkfood.seal.ui.component.VideoFilterChip
import com.junkfood.seal.ui.page.downloadv2.cookieRowRes
import com.junkfood.seal.ui.page.settings.format.AudioQuickSettingsDialog
import com.junkfood.seal.ui.page.settings.format.VideoQuickSettingsDialog
import com.junkfood.seal.ui.page.settings.network.CookiesQuickSettingsDialog
import com.junkfood.seal.util.AUDIO_CONVERSION_FORMAT
import com.junkfood.seal.util.AUDIO_CONVERT
import com.junkfood.seal.util.AUDIO_FORMAT
import com.junkfood.seal.util.AUDIO_QUALITY
import com.junkfood.seal.util.CookieFileStatus
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.DownloadType
import com.junkfood.seal.util.DownloadType.Command
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.FormatSelectionPolicy
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.USE_CUSTOM_AUDIO_PRESET
import com.junkfood.seal.util.VIDEO_FORMAT
import com.junkfood.seal.util.VIDEO_QUALITY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-download chip strip for the configure sheet. Every toggle here mutates
 * the active sheet session only; global MMKV Settings remain untouched.
 */
@Composable
internal fun AdditionalSettings(
    modifier: Modifier = Modifier,
    isQuickDownload: Boolean,
    selectedType: DownloadType?,
    preference: DownloadUtil.DownloadPreferences,
    convertAudio: Boolean,
    onConvertAudioChange: (Boolean) -> Unit,
    splitByChapter: Boolean,
    onSplitByChapterChange: (Boolean) -> Unit,
    onNavigateToCookieGeneratorPage: (String) -> Unit = {},
    onPreferencesChange: (DownloadUtil.DownloadPreferences) -> Unit,
) {
    val context = LocalContext.current
    val cookiesProfiles by DatabaseUtil.getCookiesFlow().collectAsStateWithLifecycle(emptyList())
    var showCookiesDialog by rememberSaveable { mutableStateOf(false) }
    var cookieStatus by remember { mutableStateOf<CookieFileStatus?>(null) }
    LaunchedEffect(Unit) {
        cookieStatus = withContext(Dispatchers.IO) { with(FileUtil) { context.readCookieStatus() } }
    }

    with(preference) {
        Row(modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            // Always present, profile rows or not: the file's state decides
            // whether a cookie retry can work, so hiding the row hid the answer.
            VideoFilterChip(
                selected = preference.cookies,
                // A command template is run verbatim; a cookie toggle would
                // only land in preferences the command never reads.
                enabled = selectedType != Command,
                onClick = {
                    if (isQuickDownload) {
                        // This sheet's copy only: Settings keeps its own default.
                        onPreferencesChange(preference.copy(cookies = !cookies))
                    } else {
                        showCookiesDialog = true
                    }
                },
                label = stringResource(id = R.string.cookies),
            )

            VideoFilterChip(
                selected = downloadSubtitle,
                enabled = selectedType != Command,
                onClick = {
                    // This sheet's copy only: Settings keeps its own default.
                    onPreferencesChange(preference.copy(downloadSubtitle = !downloadSubtitle))
                },
                label = stringResource(id = R.string.download_subtitles),
            )
            VideoFilterChip(
                selected = createThumbnail,
                enabled = selectedType != Command,
                onClick = {
                    onPreferencesChange(preference.copy(createThumbnail = !createThumbnail))
                },
                label = stringResource(R.string.create_thumbnail),
            )
        }

        if (FormatSelectionPolicy.showsAudioConversion(selectedType)) {
            Row(modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                VideoFilterChip(
                    selected = !convertAudio,
                    onClick = { onConvertAudioChange(false) },
                    label = stringResource(R.string.audio_source_original),
                )
                VideoFilterChip(
                    selected = convertAudio,
                    onClick = { onConvertAudioChange(true) },
                    label = stringResource(R.string.audio_source_mp3),
                )
            }
        }

        Row(modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            VideoFilterChip(
                selected = splitByChapter,
                enabled = selectedType != Command,
                onClick = { onSplitByChapterChange(!splitByChapter) },
                label = stringResource(FormatSelectionPolicy.splitToggleLabel(selectedType)),
            )
        }

        cookieStatus?.let { status ->
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp)) {
                Text(
                    text = stringResource(cookieRowRes(status), status.entryCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.cookies_row_stale_hint),
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showCookiesDialog) {
            CookiesQuickSettingsDialog(
                onDismissRequest = { showCookiesDialog = false },
                onConfirm = {},
                cookieProfiles = cookiesProfiles,
                onCookieProfileClicked = { onNavigateToCookieGeneratorPage(it.url) },
                isCookiesEnabled = cookies,
                onCookiesToggled = {
                    // Keep cookie override strictly scoped to this session.
                    onPreferencesChange(preference.copy(cookies = !cookies))
                },
            )
        }
    }
}

@Composable
fun ExpandableTitle(
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column {
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(thickness = Dp.Hairline, modifier = Modifier.padding(horizontal = 20.dp))
        Column(
            modifier =
                modifier
                    .clickable(
                        onClick = onClick,
                        onClickLabel = stringResource(R.string.show_more_actions),
                        enabled = !expanded,
                    )
                    .padding(top = 12.dp, bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = stringResource(R.string.additional_settings),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (!expanded) {
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(32.dp))
                }
            }
            AnimatedVisibility(expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    content()
                }
            }
        }
    }
}

@Composable
internal fun VideoPresetQuickSettingsDialog(
    preferences: DownloadUtil.DownloadPreferences,
    onDismissRequest: () -> Unit,
    onPreferencesUpdate: (DownloadUtil.DownloadPreferences) -> Unit,
) {
    var res by remember(preferences) { mutableIntStateOf(preferences.videoResolution) }
    var format by remember(preferences) { mutableIntStateOf(preferences.videoFormat) }

    VideoQuickSettingsDialog(
        videoResolution = res,
        videoFormatPreference = format,
        onResolutionSelect = { res = it },
        onFormatSelect = { format = it },
        onDismissRequest = onDismissRequest,
        onSave = {
            VIDEO_FORMAT.updateInt(format)
            VIDEO_QUALITY.updateInt(res)
            onPreferencesUpdate(DownloadUtil.DownloadPreferences.createFromPreferences())
        },
    )
}

@Composable
internal fun AudioPresetQuickSettingsDialog(
    preferences: DownloadUtil.DownloadPreferences,
    onDismissRequest: () -> Unit,
    onPreferencesUpdate: (DownloadUtil.DownloadPreferences) -> Unit,
) {
    var quality by remember(preferences) { mutableIntStateOf(preferences.audioQuality) }
    var customPreset by
        remember(preferences) { mutableStateOf(preferences.useCustomAudioPreset) }
    var conversionFmt by
        remember(preferences) { mutableIntStateOf(preferences.audioConvertFormat) }
    var convertAudio by remember(preferences) { mutableStateOf(preferences.convertAudio) }
    var preferredFormat by remember(preferences) { mutableIntStateOf(preferences.audioFormat) }

    AudioQuickSettingsDialog(
        modifier = Modifier,
        preferences = preferences,
        audioQuality = quality,
        onQualitySelect = { quality = it },
        useCustomAudioPreset = customPreset,
        onCustomPresetToggle = { customPreset = it },
        convertAudio = convertAudio,
        onConvertToggled = { convertAudio = it },
        conversionFormat = conversionFmt,
        onConversionSelect = { conversionFmt = it },
        preferredFormat = preferredFormat,
        onPreferredSelect = { preferredFormat = it },
        onDismissRequest = onDismissRequest,
        onSave = {
            AUDIO_QUALITY.updateInt(quality)
            USE_CUSTOM_AUDIO_PRESET.updateBoolean(customPreset)
            AUDIO_CONVERSION_FORMAT.updateInt(conversionFmt)
            AUDIO_CONVERT.updateBoolean(convertAudio)
            AUDIO_FORMAT.updateInt(preferredFormat)
            onPreferencesUpdate(DownloadUtil.DownloadPreferences.createFromPreferences())
        },
    )
}
