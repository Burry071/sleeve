package com.junkfood.seal.ui.page.downloadv2.configure

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NewLabel
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.motion.materialSharedAxisX
import com.junkfood.seal.ui.component.ButtonChip
import com.junkfood.seal.ui.component.DrawerSheetSubtitle
import com.junkfood.seal.ui.component.SealModalBottomSheet
import com.junkfood.seal.ui.page.command.TemplatePickerDialog
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SelectionState
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState.Configure
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState.Error
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState.InputUrl
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState.Loading
import com.junkfood.seal.ui.page.settings.command.CommandTemplateDialog
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.CUSTOM_COMMAND
import com.junkfood.seal.util.DownloadType
import com.junkfood.seal.util.DownloadType.Audio
import com.junkfood.seal.util.DownloadType.Command
import com.junkfood.seal.util.DownloadType.Playlist
import com.junkfood.seal.util.DownloadType.Video
import com.junkfood.seal.util.DownloadType.entries
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FORMAT_SELECTION
import com.junkfood.seal.util.FormatSelectionPolicy
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.TEMPLATE_ID
import kotlinx.coroutines.launch

val PreferencesMock = DownloadUtil.DownloadPreferences.EMPTY

data class Config(
    val downloadType: DownloadType? = PreferenceUtil.getDownloadType(),
    val typeEntries: List<DownloadType> =
        when (CUSTOM_COMMAND.getBoolean()) {
            true -> DownloadType.entries
            false -> DownloadType.entries - Command
        },
    val useFormatSelection: Boolean = FORMAT_SELECTION.getBoolean(),
    val savedLinks: Set<String> = PreferenceUtil.getSavedLinks(),
) {
    companion object {
        fun updatePreferences(newValue: Config, oldValue: Config) {
            with(newValue) {
                if (downloadType != oldValue.downloadType) {
                    downloadType?.let { PreferenceUtil.updateDownloadType(it) }
                }
                // The sheet's Playlist tab forces the preset on for that one
                // task; writing it here would switch Settings to "Preset" for
                // every later download too.
                if (savedLinks != oldValue.savedLinks) {
                    PreferenceUtil.updateSavedLinks(savedLinks)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadDialog(
    modifier: Modifier = Modifier,
    config: Config,
    sheetState: SheetState,
    preferences: DownloadUtil.DownloadPreferences,
    onPreferencesUpdate: (DownloadUtil.DownloadPreferences) -> Unit,
    state: DownloadDialogViewModel.SheetState = InputUrl,
    configureSession: ConfigureSessionState? = null,
    onConfigureSessionUpdate: ((ConfigureSessionState) -> ConfigureSessionState) -> Unit = {},
    onActionPost: (Action) -> Unit = {},
) {
    var showVideoPresetDialog by remember { mutableStateOf(false) }
    var showAudioPresetDialog by remember { mutableStateOf(false) }

    SealModalBottomSheet(
        sheetState = sheetState,
        contentPadding = PaddingValues(),
        onDismissRequest = { onActionPost(Action.HideSheet) },
    ) {
        DownloadDialogContent(
            modifier = modifier,
            state = state,
            config = config,
            preferences = preferences,
            configureSession = configureSession,
            onConfigureSessionUpdate = onConfigureSessionUpdate,
            onPresetEdit = { type ->
                when (type) {
                    Audio -> showAudioPresetDialog = true
                    Video -> showVideoPresetDialog = true
                    else -> {}
                }
            },
            onActionPost = onActionPost,
        )
    }

    if (showVideoPresetDialog) {
        VideoPresetQuickSettingsDialog(
            preferences = preferences,
            onDismissRequest = { showVideoPresetDialog = false },
            onPreferencesUpdate = onPreferencesUpdate,
        )
    }

    if (showAudioPresetDialog) {
        AudioPresetQuickSettingsDialog(
            preferences = preferences,
            onDismissRequest = { showAudioPresetDialog = false },
            onPreferencesUpdate = onPreferencesUpdate,
        )
    }
}

@Composable
private fun DownloadDialogContent(
    modifier: Modifier = Modifier,
    state: DownloadDialogViewModel.SheetState,
    config: Config,
    preferences: DownloadUtil.DownloadPreferences,
    configureSession: ConfigureSessionState?,
    onConfigureSessionUpdate: ((ConfigureSessionState) -> ConfigureSessionState) -> Unit,
    onPresetEdit: (DownloadType?) -> Unit,
    onActionPost: (Action) -> Unit,
) {
    AnimatedContent(
        modifier = modifier,
        targetState = state,
        label = "",
        transitionSpec = {
            materialSharedAxisX(initialOffsetX = { it / 4 }, targetOffsetX = { -it / 4 })
        },
    ) { targetState ->
        when (targetState) {
            is Configure -> {
                check(targetState.urlList.isNotEmpty())
                if (targetState.urlList.size == 1) {
                    ConfigurePage(
                        url = targetState.urlList.first(),
                        config = config,
                        preferences = preferences,
                        configureSession = configureSession,
                        onConfigureSessionUpdate = onConfigureSessionUpdate,
                        onPresetEdit = onPresetEdit,
                        onConfigSave = {
                            Config.updatePreferences(newValue = it, oldValue = config)
                        },
                        settingChips = { selectedType, session ->
                            AdditionalSettings(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                isQuickDownload = false,
                                preference = session.preferences,
                                selectedType = selectedType,
                                convertAudio = session.convertAudio,
                                onConvertAudioChange = session.onConvertAudioChange,
                                splitByChapter = session.splitByChapter,
                                onSplitByChapterChange = session.onSplitByChapterChange,
                                onPreferencesChange = session.onPreferencesChange,
                            )
                        },
                        onActionPost = { onActionPost(it) },
                    )
                } else {
                    ConfigurePagePlaylistVariant(
                        initialDownloadType =
                            FormatSelectionPolicy.fallbackType(
                                config.downloadType ?: Video,
                                listOf(Video, Audio),
                            ) ?: Video,
                        preferences = preferences,
                        onPresetEdit = onPresetEdit,
                        onDismissRequest = { onActionPost(Action.HideSheet) },
                    ) { sessionPreferences ->
                        onActionPost(
                            Action.DownloadWithPreset(
                                urlList = targetState.urlList,
                                preferences = sessionPreferences,
                            )
                        )
                    }
                }
            }

            is Error -> {
                ErrorPage(state = targetState, onActionPost = onActionPost)
            }

            is Loading -> {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 120.dp)) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }

            InputUrl -> {
                InputUrlPage(
                    config = config,
                    onConfigUpdate = { Config.updatePreferences(newValue = it, oldValue = config) },
                    onActionPost = onActionPost,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatPage(
    modifier: Modifier = Modifier,
    state: SelectionState.FormatSelection,
    onDismissRequest: () -> Unit,
) {
    // M3 sheet: 32dp Expressive corners + drag handle via the shared wrapper.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun close() =
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() }

    val resolvedPreferences =
        if (state.preferences == DownloadUtil.DownloadPreferences.EMPTY) {
            DownloadUtil.DownloadPreferences.createFromPreferences()
        } else {
            state.preferences
        }

    SealModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismissRequest,
        contentPadding = PaddingValues(0.dp),
    ) {
        FormatPage(
            modifier = modifier,
            videoInfo = state.info,
            audioOnly = state.audioOnly,
            splitByChapter = state.splitByChapter,
            convertAudio = state.convertAudio,
            basePreferences = resolvedPreferences,
            onNavigateBack = { close() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigurePagePreview() {
    SealTheme {
        SealModalBottomSheet(
            sheetState =
                with(LocalDensity.current) {
                    SheetState(
                        initialValue = SheetValue.Expanded,
                        skipPartiallyExpanded = true,
                        velocityThreshold = { 56.dp.toPx() },
                        positionalThreshold = { 125.dp.toPx() },
                    )
                },
            onDismissRequest = {},
            contentPadding = PaddingValues(),
        ) {
            ConfigurePage(
                config =
                    Config(
                        downloadType = Audio,
                        useFormatSelection = true,
                        typeEntries = entries - Command,
                    ),
                preferences = PreferencesMock,
                onConfigSave = {},
                settingChips = { _, _ -> },
            ) {}
        }
    }
}

/**
 * Per-download overrides owned by the configure sheet. The chips change this
 * task only; Settings keeps its own values, so nothing leaks into the next one.
 */
data class FormatSession(
    val convertAudio: Boolean,
    val splitByChapter: Boolean,
    /** This sheet's view of the preferences, including its own overrides. */
    val preferences: DownloadUtil.DownloadPreferences,
    val onConvertAudioChange: (Boolean) -> Unit,
    val onSplitByChapterChange: (Boolean) -> Unit,
    val onPreferencesChange: (DownloadUtil.DownloadPreferences) -> Unit,
)

@Composable
private fun ConfigurePage(
    modifier: Modifier = Modifier,
    url: String = "",
    config: Config,
    preferences: DownloadUtil.DownloadPreferences,
    configureSession: ConfigureSessionState? = null,
    onConfigureSessionUpdate: ((ConfigureSessionState) -> ConfigureSessionState) -> Unit = {},
    settingChips: @Composable (DownloadType?, FormatSession) -> Unit,
    onPresetEdit: (DownloadType?) -> Unit = {},
    onConfigSave: (Config) -> Unit,
    onActionPost: (Action) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var localSession by remember(config) {
        mutableStateOf(
            ConfigureSessionState.fromConfig(
                basePreferences = preferences,
                config = config,
            )
        )
    }
    LaunchedEffect(preferences) {
        localSession = localSession.withBasePreferences(preferences)
    }

    val activeSession = configureSession ?: localSession
    val updateSession: ((ConfigureSessionState) -> ConfigureSessionState) -> Unit =
        if (configureSession != null) {
            onConfigureSessionUpdate
        } else {
            { transform -> localSession = transform(localSession) }
        }

    // A stored type the sheet does not offer (Command hidden in Settings, or a
    // stale value) would leave every tab unselected and the action disabled.
    val selectedType =
        FormatSelectionPolicy.fallbackType(activeSession.selectedType, config.typeEntries)
            ?: config.typeEntries.firstOrNull()
    val useFormatSelection = activeSession.useFormatSelection
    val sessionConvertAudio =
        resolveSessionConvertAudio(
            selectedType = selectedType,
            override = activeSession.convertAudioOverride,
            settingsConvertAudio = activeSession.basePreferences.convertAudio,
        )
    val session =
        FormatSession(
            convertAudio = sessionConvertAudio,
            splitByChapter = activeSession.splitByChapter,
            preferences = activeSession.sessionPreferences,
            onConvertAudioChange = { convert ->
                updateSession { it.setConvertAudio(convert) }
            },
            onSplitByChapterChange = { split ->
                updateSession { it.toggleSplitByChapter(split) }
            },
            onPreferencesChange = { updated ->
                updateSession { it.updateSessionPreferences(updated) }
            },
        )
    val canProceed = selectedType in config.typeEntries

    var showTemplateSelectionDialog by remember { mutableStateOf(false) }
    var showTemplateCreatorDialog by remember { mutableStateOf(false) }
    var showTemplateEditorDialog by remember { mutableStateOf(false) }
    val templateList by PreferenceUtil.templateListStateFlow.collectAsStateWithLifecycle()
    // Never block composition on the template table: re-resolve the selection
    // from the observable list whenever the DB or a dialog changes.
    val template =
        remember(
            templateList,
            showTemplateCreatorDialog,
            showTemplateSelectionDialog,
            showTemplateEditorDialog,
        ) {
            templateList.find { it.id == TEMPLATE_ID.getInt() } ?: templateList.firstOrNull()
        }

    Column {
        Column(modifier = modifier.padding(horizontal = 20.dp)) {
            Header(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                title = stringResource(R.string.settings_before_download),
                icon = Icons.Outlined.DoneAll,
            )
            DrawerSheetSubtitle(text = stringResource(id = R.string.download_type))
            DownloadTypeSelectionGroup(
                typeEntries = config.typeEntries,
                selectedType = selectedType,
                onSelect = { type -> updateSession { it.selectType(type) } },
            )
            Column(modifier = Modifier.animateContentSize()) {
                if (selectedType != Command) {
                    DrawerSheetSubtitle(
                        text = stringResource(id = R.string.format_selection),
                        modifier = Modifier,
                    )
                    Preset(
                        modifier = Modifier,
                        preference = activeSession.basePreferences,
                        selected = !useFormatSelection,
                        downloadType = selectedType,
                        onClick = { updateSession { it.setUseFormatSelection(false) } },
                        showEditIcon = !useFormatSelection && selectedType != Playlist,
                        onEdit = { onPresetEdit(selectedType) },
                    )
                    Custom(
                        selected = useFormatSelection,
                        enabled = selectedType != Playlist,
                        onClick = { updateSession { it.setUseFormatSelection(true) } },
                    )
                } else {
                    if (showTemplateSelectionDialog) {
                        TemplatePickerDialog { showTemplateSelectionDialog = false }
                    }
                    if (showTemplateCreatorDialog) {
                        CommandTemplateDialog(
                            onDismissRequest = { showTemplateCreatorDialog = false },
                            confirmationCallback = { scope.launch { TEMPLATE_ID.updateInt(it) } },
                        )
                    }
                    if (showTemplateEditorDialog && template != null) {
                        CommandTemplateDialog(
                            commandTemplate = template,
                            onDismissRequest = { showTemplateEditorDialog = false },
                        )
                    }
                    DrawerSheetSubtitle(
                        text = stringResource(id = R.string.template_selection),
                        modifier = Modifier,
                    )
                    LazyRow(modifier = Modifier) {
                        item {
                            ButtonChip(
                                icon = Icons.Outlined.Code,
                                label = template?.name.orEmpty(),
                                onClick = { showTemplateSelectionDialog = true },
                            )
                        }
                        item {
                            ButtonChip(
                                icon = Icons.Outlined.NewLabel,
                                label = stringResource(id = R.string.new_template),
                                onClick = { showTemplateCreatorDialog = true },
                            )
                        }
                        item {
                            ButtonChip(
                                icon = Icons.Outlined.Edit,
                                label =
                                    stringResource(
                                        id = R.string.edit_template,
                                        template?.name.orEmpty(),
                                    ),
                                onClick = { showTemplateEditorDialog = true },
                            )
                        }
                    }
                }
            }
        }
        var expanded by remember { mutableStateOf(false) }
        ExpandableTitle(expanded = expanded, onClick = { expanded = true }) {
            settingChips(selectedType, session)
        }

        ActionButtons(
            modifier = Modifier.padding(horizontal = 20.dp),
            canProceed = canProceed,
            selectedType = selectedType,
            useFormatSelection = useFormatSelection,
            onCancel = { onActionPost(Action.HideSheet) },
            onDownload = {
                onConfigSave(
                    config.copy(
                        useFormatSelection = useFormatSelection,
                        downloadType = selectedType,
                    )
                )
                onActionPost(
                    Action.DownloadWithPreset(
                        urlList = listOf(url),
                        preferences =
                            activeSession.sessionPreferences.copy(
                                extractAudio = FormatSelectionPolicy.audioOnly(selectedType),
                                convertAudio = sessionConvertAudio,
                                splitByChapter = activeSession.splitByChapter,
                            ),
                    )
                )
            },
            onFetchInfo = {
                onConfigSave(
                    config.copy(
                        useFormatSelection = useFormatSelection,
                        downloadType = selectedType,
                    )
                )
                if (selectedType == Playlist) {
                    onActionPost(
                        Action.FetchPlaylist(
                            url = url,
                            preferences = activeSession.effectivePlaylistPreferences(),
                        )
                    )
                } else {
                    onActionPost(
                        Action.FetchFormats(
                            url = url,
                            audioOnly = FormatSelectionPolicy.audioOnly(selectedType),
                            preferences =
                                activeSession.sessionPreferences.copy(
                                    convertAudio = sessionConvertAudio,
                                    splitByChapter = activeSession.splitByChapter,
                                ),
                        )
                    )
                }
            },
            onTaskStart = {
                template?.let { selectedTemplate ->
                    onConfigSave(
                        config.copy(
                            useFormatSelection = useFormatSelection,
                            downloadType = selectedType,
                        )
                    )
                    onActionPost(
                        Action.RunCommand(
                            url = url,
                            template = selectedTemplate,
                            preferences = activeSession.sessionPreferences,
                        )
                    )
                }
            },
        )
    }
}

@Composable
fun ConfigurePagePlaylistVariant(
    modifier: Modifier = Modifier,
    initialDownloadType: DownloadType,
    initialSplitByChapter: Boolean = false,
    preferences: DownloadUtil.DownloadPreferences,
    onPresetEdit: (DownloadType?) -> Unit = {},
    onDismissRequest: () -> Unit,
    onDownload: (DownloadUtil.DownloadPreferences) -> Unit,
) {
    var sessionState by remember(initialDownloadType, initialSplitByChapter) {
        mutableStateOf(
            ConfigureSessionState(
                basePreferences = preferences,
                selectedType = initialDownloadType,
                useFormatSelection = false,
                sessionPreferences = preferences,
                splitByChapter = initialSplitByChapter,
                convertAudioOverride = null,
            )
        )
    }
    LaunchedEffect(preferences) {
        sessionState = sessionState.withBasePreferences(preferences)
    }

    Column {
        Column(modifier = modifier.padding(horizontal = 20.dp)) {
            Header(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                title = stringResource(R.string.settings_before_download),
                icon = Icons.Outlined.DoneAll,
            )
            DrawerSheetSubtitle(text = stringResource(id = R.string.download_type))
            DownloadTypeSelectionGroup(
                typeEntries = listOf(Video, Audio),
                selectedType = sessionState.selectedType,
                onSelect = { type -> sessionState = sessionState.selectType(type) },
            )
            DrawerSheetSubtitle(
                text = stringResource(id = R.string.format_selection),
                modifier = Modifier,
            )
            Preset(
                modifier = Modifier,
                preference = sessionState.sessionPreferences,
                selected = true,
                downloadType = sessionState.selectedType,
                onClick = { onPresetEdit(sessionState.selectedType) },
                showEditIcon = true,
                onEdit = { onPresetEdit(sessionState.selectedType) },
            )
        }
        var expanded by remember { mutableStateOf(false) }
        ExpandableTitle(expanded = expanded, onClick = { expanded = true }) {
            AdditionalSettings(
                modifier = Modifier.padding(horizontal = 16.dp),
                isQuickDownload = false,
                preference = sessionState.sessionPreferences,
                selectedType = sessionState.selectedType,
                convertAudio = sessionState.convertAudio,
                onConvertAudioChange = { convert ->
                    sessionState = sessionState.setConvertAudio(convert)
                },
                splitByChapter = sessionState.splitByChapter,
                onSplitByChapterChange = { split ->
                    sessionState = sessionState.toggleSplitByChapter(split)
                },
                onPreferencesChange = { updated ->
                    sessionState = sessionState.updateSessionPreferences(updated)
                },
            )
        }

        ActionButtons(
            modifier = Modifier.padding(horizontal = 20.dp),
            canProceed = true,
            selectedType = sessionState.selectedType,
            useFormatSelection = false,
            onCancel = onDismissRequest,
            onDownload = {
                onDownload(sessionState.effectivePreferences())
                onDismissRequest()
            },
            onFetchInfo = { throw IllegalStateException() },
            onTaskStart = { throw IllegalStateException() },
        )
    }
}
