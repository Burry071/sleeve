package com.junkfood.seal.ui.page.downloadv2

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.junkfood.seal.R
import com.junkfood.seal.download.BatchInfo
import com.junkfood.seal.download.BatchOps
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.download.MatchCandidate
import com.junkfood.seal.download.MatchOps
import com.junkfood.seal.download.SwapResult
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.phase
import com.junkfood.seal.util.DownloadErrorMessages
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.ui.common.HapticFeedback.slightHapticFeedback
import com.junkfood.seal.ui.common.LocalDarkTheme
import com.junkfood.seal.ui.common.LocalFixedColorRoles
import com.junkfood.seal.ui.common.LocalWindowWidthState
import com.junkfood.seal.ui.component.SealModalBottomSheet
import com.junkfood.seal.ui.component.SelectionGroupDefaults
import com.junkfood.seal.ui.component.SelectionGroupItem
import com.junkfood.seal.ui.component.SelectionGroupRow
import com.junkfood.seal.ui.component.UpdateBanner
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.Config
import com.junkfood.seal.ui.page.downloadv2.configure.DialogScreen
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialog
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.ui.page.downloadv2.configure.FormatPage
import com.junkfood.seal.ui.page.downloadv2.configure.MetadataTracksPage
import com.junkfood.seal.ui.page.downloadv2.configure.PendingMetered
import com.junkfood.seal.ui.page.downloadv2.configure.PendingMeteredDialog
import com.junkfood.seal.ui.page.downloadv2.configure.PlaylistSelectionPage
import com.junkfood.seal.ui.page.downloadv2.configure.PreferencesMock
import com.junkfood.seal.ui.page.downloadv2.configure.dialogScreen
import com.junkfood.seal.ui.page.downloadv2.configure.startsDownload
import com.junkfood.seal.ui.page.download.NotificationPermissionDialog
import com.junkfood.seal.ui.svg.drawablevectors.download
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.NOTIFICATION
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.getErrorReport
import com.junkfood.seal.util.makeToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val TAG = "DownloadPageV2"

enum class Filter {
    All,
    Playlist,
    Downloading,
    Canceled,
    Finished;

    @Composable
    @ReadOnlyComposable
    fun label(): String =
        when (this) {
            All -> stringResource(R.string.all)
            Playlist -> stringResource(R.string.playlist)
            Downloading -> stringResource(R.string.status_downloading)
            Canceled -> stringResource(R.string.status_canceled)
            Finished -> stringResource(R.string.status_completed)
        }

    /** Playlists and books live in one place; All is the single-download inbox. */
    val showsBatchCards: Boolean
        get() = this == Playlist

    /** Individual cards belong to every filter except Playlist. */
    val showsTaskCards: Boolean
        get() = this != Playlist

    fun predict(entry: Pair<Task, Task.State>): Boolean {
        val (task, state) = entry
        // Batch songs live only inside their card.
        if (task.batch != null) return false
        return when (this) {
            All -> true
            Playlist -> false
            Downloading -> {
                when (state.downloadState) {
                    is FetchingInfo,
                    Idle,
                    ReadyWithInfo,
                    is Running -> true
                    else -> false
                }
            }
            Canceled -> {
                state.downloadState is Error || state.downloadState is Task.DownloadState.Canceled
            }
            Finished -> {
                state.downloadState is Completed
            }
        }
    }
}

sealed interface UiAction {
    data class OpenFile(val filePath: String?) : UiAction

    data class ShareFile(val filePath: String?) : UiAction

    data class OpenThumbnailURL(val url: String) : UiAction

    data class CopyVideoURL(val url: String) : UiAction

    data class OpenVideoURL(val url: String) : UiAction

    data object Cancel : UiAction

    data object Delete : UiAction

    data object Resume : UiAction

    data class CopyErrorReport(val throwable: Throwable, val message: String = "") : UiAction

    /** Re-opens the confirm sheet for a login-walled failure. */
    data class RetryWithCookies(val url: String) : UiAction

    /** Opens the runtime audit log (cookie state and every match score). */
    data object ShowLogs : UiAction
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun DownloadPageV2(
    modifier: Modifier = Modifier,
    onMenuOpen: (() -> Unit) = {},
    dialogViewModel: DownloadDialogViewModel,
    downloader: DownloaderV2 = koinInject(),
) {
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    // Downloads notify progress/finish from DownloadService, but Android 13+
    // only shows those notifications after POST_NOTIFICATIONS is granted.
    // Nothing in the V2 flow ever asked for it, so the queue stayed silent.
    var showNotificationDialog by remember { mutableStateOf(false) }
    var logTask by remember { mutableStateOf<Task?>(null) }
    val reviewBatchId by dialogViewModel.matchReviewBatchFlow.collectAsStateWithLifecycle()
    val notificationPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            rememberPermissionState(permission = Manifest.permission.POST_NOTIFICATIONS) {
                    granted ->
                showNotificationDialog = false
                if (!granted) context.makeToast(R.string.permission_denied)
            }
        } else null

    val metered = remember { PendingMetered() }

    fun enqueueAction(action: Action) {
        if (
            action.startsDownload() &&
                NOTIFICATION.getBoolean() &&
                notificationPermission?.status?.isGranted == false
        ) {
            showNotificationDialog = true
        }
        dialogViewModel.postAction(action)
    }

    fun postAction(action: Action) {
        // One gate for both hosts: only actions that hand work to the pipeline
        // are parked, and "Don't allow" drops them without queueing.
        metered.post(action) { enqueueAction(action) }
    }

    DownloadPageImplV2(
        modifier = modifier,
        taskDownloadStateMap = downloader.getTaskStateMap(),
        downloadCallback = {
            view.slightHapticFeedback()
            dialogViewModel.postAction(Action.ShowSheet())
        },
        onMenuOpen = onMenuOpen,
        onBatchStop = { batchId -> downloader.cancelBatch(batchId) },
        onBatchResume = { batchId -> downloader.resumeBatch(batchId) },
        onBatchDismiss = { batchId -> downloader.dismissBatch(batchId) },
        reviewBatchId = reviewBatchId,
        onReviewOpen = { batchId -> dialogViewModel.openMatchReview(batchId) },
        onReviewDismiss = { dialogViewModel.closeMatchReview() },
        onReviewAcceptAll = { batchId ->
            downloader.acknowledgeMatches(batchId)
            dialogViewModel.closeMatchReview()
        },
        onReviewSwap = { selections -> downloader.swapMatches(selections) },
        onReviewParts = { task, parts -> downloader.enqueueParts(task, parts) },
    ) { task, action ->
        view.slightHapticFeedback()
        when (action) {
            UiAction.Cancel -> downloader.cancel(task)
            UiAction.Delete -> downloader.remove(task)
            UiAction.Resume -> downloader.restart(task)
            is UiAction.CopyErrorReport -> {
                clipboardManager.setText(
                    AnnotatedString(getErrorReport(action.throwable, task.url, action.message))
                )
                context.makeToast(R.string.error_copied)
            }
            is UiAction.CopyVideoURL -> {
                clipboardManager.setText(AnnotatedString(action.url))
                context.makeToast(R.string.link_copied)
            }
            is UiAction.OpenFile -> {
                action.filePath?.let {
                    scope.launch {
                        FileUtil.openFile(path = it) {
                            context.makeToast(R.string.file_unavailable)
                        }
                    }
                }
            }
            is UiAction.RetryWithCookies -> {
                dialogViewModel.postAction(Action.ShowSheet(listOf(action.url)))
            }
            is UiAction.OpenThumbnailURL -> {
                uriHandler.openUri(action.url)
            }
            is UiAction.OpenVideoURL -> {
                uriHandler.openUri(action.url)
            }
            is UiAction.ShareFile -> {
                val shareTitle = context.getString(R.string.share)
                scope.launch {
                    FileUtil.createIntentForSharingFile(action.filePath)?.let {
                        context.startActivity(Intent.createChooser(it, shareTitle))
                    } ?: context.makeToast(R.string.file_unavailable)
                }
            }
            UiAction.ShowLogs -> logTask = task
        }
    }

    logTask?.let { TaskLogDialog(task = it, onDismissRequest = { logTask = null }) }

    var preferences by remember {
        mutableStateOf(DownloadUtil.DownloadPreferences.createFromPreferences())
    }
    val sheetValue by dialogViewModel.sheetValueFlow.collectAsStateWithLifecycle()
    val state by dialogViewModel.sheetStateFlow.collectAsStateWithLifecycle()

    val selectionState = dialogViewModel.selectionStateFlow.collectAsStateWithLifecycle().value

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Composition has to outlive the hide animation, so it cannot simply follow
    // the flow value. A latch used to do that and could stay composed forever if
    // a hide completion was missed; this is an observation of what the sheet
    // actually is: shown while the flow is expanded, then until it is gone.
    val showDialog =
        sheetValue == DownloadDialogViewModel.SheetValue.Expanded || sheetState.isVisible

    LaunchedEffect(sheetValue) {
        if (sheetValue == DownloadDialogViewModel.SheetValue.Hidden) sheetState.hide()
    }

    if (showNotificationDialog) {
        NotificationPermissionDialog(
            onDismissRequest = {
                showNotificationDialog = false
                NOTIFICATION.updateBoolean(false)
            },
            onPermissionGranted = { notificationPermission?.launchPermissionRequest() },
        )
    }

    PendingMeteredDialog(pending = metered)

    val screen = dialogScreen(sheetValue, state, selectionState)

    if (showDialog) {

        DownloadDialog(
            state = state,
            sheetState = sheetState,
            config = Config(),
            preferences = preferences,
            onPreferencesUpdate = { preferences = it },
            onActionPost = { postAction(it) },
        )
    }
    when (screen) {
        DialogScreen.Hidden, is DialogScreen.Sheet -> {}

        is DialogScreen.Format ->
            FormatPage(
                state = screen.state,
                onDismissRequest = { dialogViewModel.postAction(Action.Reset) },
            )

        is DialogScreen.Metadata ->
            MetadataTracksPage(
                state = screen.state,
                onDismissRequest = { dialogViewModel.postAction(Action.Reset) },
                onActionPost = { postAction(it) },
            )

        is DialogScreen.Playlist ->
            PlaylistSelectionPage(
                state = screen.state,
                onDismissRequest = { dialogViewModel.postAction(Action.Reset) },
            )
    }
}

@Composable
private operator fun PaddingValues.plus(other: PaddingValues): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        top = calculateTopPadding() + other.calculateTopPadding(),
        bottom = calculateBottomPadding() + other.calculateBottomPadding(),
        start =
            calculateStartPadding(layoutDirection) + other.calculateStartPadding(layoutDirection),
        end = calculateEndPadding(layoutDirection) + other.calculateEndPadding(layoutDirection),
    )
}

private const val HeaderSpacingDp = 28

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadPageImplV2(
    modifier: Modifier = Modifier,
    taskDownloadStateMap: SnapshotStateMap<Task, Task.State>,
    downloadCallback: () -> Unit = {},
    onMenuOpen: () -> Unit = {},
    onBatchStop: (String) -> Unit = {},
    onBatchResume: (String) -> Unit = {},
    onBatchDismiss: (String) -> Unit = {},
    reviewBatchId: String? = null,
    onReviewOpen: (String) -> Unit = {},
    onReviewDismiss: () -> Unit = {},
    onReviewAcceptAll: (String) -> Unit = {},
    onReviewSwap: suspend (List<Pair<Task, MatchCandidate>>) -> List<SwapResult> = { emptyList() },
    onReviewParts: suspend (Task, List<MatchCandidate>) -> Int = { _, _ -> 0 },
    onActionPost: (Task, UiAction) -> Unit,
) {
    var activeFilter by remember { mutableStateOf(Filter.All) }
    var dismissedBatches by remember { mutableStateOf(PreferenceUtil.getDismissedBatches()) }
    var dismissTarget by remember { mutableStateOf<BatchInfo?>(null) }
    // Only collapsed ids are stored: a new batch opens expanded, and removing
    // an id is the same operation as expanding it (the old tri-state map could
    // not tell "never touched" from "expanded again").
    var collapsedBatches by remember { mutableStateOf(emptySet<String>()) }
    val liveBatchIds by
        remember {
            derivedStateOf {
                taskDownloadStateMap.keys.mapNotNullTo(mutableSetOf()) { it.batch?.id }
            }
        }
    // A tombstone only hides a batch while its songs are still in the queue;
    // once they are gone it is dead weight, so every membership change prunes.
    LaunchedEffect(liveBatchIds) {
        PreferenceUtil.pruneDismissedBatches(liveBatchIds)
        dismissedBatches = PreferenceUtil.getDismissedBatches()
    }
    val playlists by
        remember(activeFilter, dismissedBatches) {
            derivedStateOf {
                BatchOps.activityOrder(BatchOps.groups(taskDownloadStateMap, dismissedBatches))
            }
        }
    val filteredMap by
        remember(activeFilter) {
            derivedStateOf { taskDownloadStateMap.filter { activeFilter.predict(it.toPair()) } }
        }
    val scope = rememberCoroutineScope()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var selectedTask by remember { mutableStateOf<Task?>(null) }
    val view = LocalView.current

    fun showActionSheet(task: Task) {
        view.slightHapticFeedback()
        scope.launch {
            selectedTask = task
            delay(50)
            sheetState.show()
        }
    }

    // The sheet must close when its task leaves the queue. Watching the map's
    // size missed a remove-then-add that kept the count, which left the sheet
    // open on a task that no longer existed.
    LaunchedEffect(selectedTask) {
        val task = selectedTask ?: return@LaunchedEffect
        snapshotFlow { taskDownloadStateMap.containsKey(task) }.first { present -> !present }
        selectedTask = null
    }

    Scaffold(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        containerColor = MaterialTheme.colorScheme.surface,
        floatingActionButton = { FABs(modifier = Modifier, downloadCallback = downloadCallback) },
    ) { windowInsetsPadding ->
        val lazyListState = rememberLazyGridState()
        val windowWidthSizeClass = LocalWindowWidthState.current
        val spacerHeight =
            with(LocalDensity.current) {
                if (windowWidthSizeClass != WindowWidthSizeClass.Compact) 0f
                else HeaderSpacingDp.dp.toPx()
            }
        var headerOffset by remember { mutableFloatStateOf(spacerHeight) }
        var isGridView by rememberSaveable { mutableStateOf(true) }

        Column(
            modifier =
                Modifier.fillMaxSize()
                    .then(
                        if (windowWidthSizeClass != WindowWidthSizeClass.Compact) Modifier
                        else
                            Modifier.nestedScroll(
                                connection =
                                    TopBarNestedScrollConnection(
                                        maxOffset = spacerHeight,
                                        flingAnimationSpec = rememberSplineBasedDecay(),
                                        offset = { headerOffset },
                                        onOffsetUpdate = { headerOffset = it },
                                    )
                            )
                    )
        ) {
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(with(LocalDensity.current) { headerOffset.toDp() }))
                    UpdateBanner(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    Header(onMenuOpen = onMenuOpen, modifier = Modifier.padding(horizontal = 16.dp))
                    SelectionGroupRow(
                        modifier =
                            Modifier.horizontalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp)
                    ) {
                        Filter.entries.forEach { filter ->
                            SelectionGroupItem(
                                colors =
                                    SelectionGroupDefaults.colors(
                                        activeContainerColor =
                                            LocalFixedColorRoles.current.tertiaryFixed,
                                        activeContentColor =
                                            LocalFixedColorRoles.current.onTertiaryFixed,
                                    ),
                                selected = activeFilter == filter,
                                onClick = {
                                    if (activeFilter == filter) {
                                        scope.launch { lazyListState.animateScrollToItem(0) }
                                        scope.launch {
                                            val initialValue = headerOffset
                                            AnimationState(initialValue = initialValue).animateTo(
                                                spacerHeight
                                            ) {
                                                headerOffset = value
                                            }
                                        }
                                    } else {
                                        activeFilter = filter
                                    }
                                },
                            ) {
                                Text(filter.label())
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (headerOffset <= 0.1f && spacerHeight > 0f) {
                        HorizontalDivider(thickness = Dp.Hairline)
                    }
                }

                LazyVerticalGrid(
                    modifier = Modifier,
                    state = lazyListState,
                    columns = GridCells.Adaptive(240.dp),
                    contentPadding =
                        windowInsetsPadding +
                            PaddingValues(start = 20.dp, end = 20.dp, bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    // A mixed All tab is the one place both kinds are visible;
                    // point at Playlist in one line instead of hiding the batches.
                    if (
                        activeFilter == Filter.All &&
                            playlists.isNotEmpty() &&
                            filteredMap.isNotEmpty()
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            BatchInPlaylistBanner(onClick = { activeFilter = Filter.Playlist })
                        }
                    }

                    if (activeFilter.showsTaskCards && filteredMap.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            val videoCount =
                                filteredMap.count {
                                    !it.value.viewState.videoFormats.isNullOrEmpty()
                                }
                            SubHeader(
                                modifier = Modifier,
                                videoCount = videoCount,
                                audioCount = filteredMap.size - videoCount,
                                isGridView = isGridView,
                                onToggleView = { isGridView = !isGridView },
                            )
                        }
                    }

                    if (activeFilter.showsBatchCards) {
                        items(
                            items = playlists,
                            key = { "batch:${it.batch.id}" },
                            span = { GridItemSpan(maxLineSpan) },
                        ) { group ->
                            PlaylistBatchCard(
                                modifier = Modifier.animateItem().padding(bottom = 16.dp),
                                batch = group.batch,
                                entries = group.entries,
                                expanded = group.batch.id !in collapsedBatches,
                                onToggleExpanded = {
                                    collapsedBatches =
                                        if (group.batch.id in collapsedBatches)
                                            collapsedBatches - group.batch.id
                                        else collapsedBatches + group.batch.id
                                },
                                onStop = { onBatchStop(group.batch.id) },
                                onResume = { onBatchResume(group.batch.id) },
                                onDismiss = { dismissTarget = group.batch },
                                onReview = { onReviewOpen(group.batch.id) },
                                onSongClick = { showActionSheet(it) },
                                onSongAction = { task, action -> onActionPost(task, action) },
                            )
                        }
                    }
                    if (activeFilter.showsTaskCards) {
                        if (isGridView) {
                            items(
                                items =
                                    filteredMap.toList().sortedBy { (_, state) ->
                                        state.downloadState.displayRank
                                    },
                                key = { (task, _) -> task.uiKey },
                            ) { (task, state) ->
                                with(state.viewState) {
                                    VideoCardV2(
                                        modifier =
                                            Modifier.animateItem()
                                                .padding(bottom = 20.dp)
                                                .padding(),
                                        viewState = this,
                                        progress =
                                            (state.downloadState as? Running)
                                                ?.progress
                                                ?.takeIf { it >= 0f },
                                        footerContent =
                                            if (state.downloadState is Error) {
                                                {
                                                    ErrorFooter(
                                                        onRetry = {
                                                            onActionPost(task, UiAction.Resume)
                                                        },
                                                        onRetryWithCookies =
                                                            (state.downloadState as Error)
                                                                .throwable
                                                                .takeIf {
                                                                    it is
                                                                        DownloadErrorMessages
                                                                            .LoginRequiredError
                                                                }
                                                                ?.let {
                                                                    {
                                                                        onActionPost(
                                                                            task,
                                                                            UiAction
                                                                                .RetryWithCookies(
                                                                                    task.url
                                                                                ),
                                                                        )
                                                                    }
                                                                },
                                                    )
                                                }
                                            } else null,
                                        actionButton = {
                                            ActionButton(
                                                modifier = Modifier,
                                                downloadState = state.downloadState,
                                                matchPhase = state.match.phase,
                                            ) {
                                                onActionPost(task, it)
                                            }
                                        },
                                        stateIndicator = {
                                            CardStateIndicator(
                                                modifier = Modifier,
                                                downloadState = state.downloadState,
                                                matchPhase = state.match.phase,
                                            )
                                        },
                                        onButtonClick = { showActionSheet(task) },
                                    )
                                }
                            }
                        } else {
                            items(
                                items =
                                    filteredMap.toList().sortedBy { (_, state) ->
                                        state.downloadState.displayRank
                                    },
                                key = { (task, _) -> task.uiKey },
                                span = { GridItemSpan(maxLineSpan) },
                            ) { (task, state) ->
                                VideoListItem(
                                    modifier = Modifier.animateItem().padding(bottom = 16.dp),
                                    viewState = state.viewState,
                                    stateIndicator = {
                                        ListItemStateText(
                                            modifier = Modifier.padding(top = 3.dp),
                                            downloadState = state.downloadState,
                                        )
                                    },
                                    onButtonClick = { showActionSheet(task) },
                                )
                            }
                        }
                    }
                }
            }
        }
        // Tabs never mix kinds: All is the single-download inbox, Playlist holds
        // playlists and books, state tabs filter singles. The empty states are the
        // only place a tab points at another one.
        when {
            activeFilter == Filter.Playlist && playlists.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.no_playlists_yet),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.no_playlists_hint),
                            modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            // An empty All must not read as "nothing is downloading" while a
            // playlist or book runs under Playlist.
            activeFilter == Filter.All && filteredMap.isEmpty() && playlists.isNotEmpty() -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.batches_live_in_playlist),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(
                            modifier = Modifier.padding(top = 8.dp),
                            onClick = { activeFilter = Filter.Playlist },
                        ) {
                            Text(stringResource(R.string.show_playlists))
                        }
                    }
                }
            }
            activeFilter.showsTaskCards &&
                filteredMap.isEmpty() &&
                playlists.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    // The empty state is also the only place a first-time user
                    // can start without the FAB; keep the action under the copy.
                    Column(
                        modifier =
                            Modifier.fillMaxHeight(0.7f)
                                .widthIn(max = 360.dp)
                                .align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        DownloadQueuePlaceholder(
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        )
                        Button(
                            onClick = downloadCallback,
                            modifier = Modifier.padding(bottom = 16.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.paste_link))
                        }
                    }
                }
            }
            // A state tab never lists batch songs, so an empty state tab can
            // still have visible downloads under Playlist. Say where they are
            // instead of leaving the screen blank.
            activeFilter.showsTaskCards && filteredMap.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.no_single_downloads),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (playlists.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.batches_live_in_playlist),
                                modifier = Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
    // A nullable let, not an early return: a task removed while its sheet is
    // open must not stop the rest of the page (and its sheet state) composing.
    selectedTask?.let { task ->
        taskDownloadStateMap[task]?.let { taskState ->
            val (downloadState, _, viewState) = taskState
            SealModalBottomSheet(
                sheetState = sheetState,
                contentPadding = PaddingValues(),
                onDismissRequest = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { selectedTask = null }
                },
            ) {
                SheetContent(
                    task = task,
                    downloadState = downloadState,
                    viewState = viewState,
                    onDismissRequest = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            selectedTask = null
                        }
                    },
                    onActionPost = onActionPost,
                )
            }
        }
    }
    dismissTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { dismissTarget = null },
            title = { Text(stringResource(R.string.dismiss_playlist)) },
            text = { Text(stringResource(R.string.dismiss_playlist_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onBatchDismiss(target.id)
                        PreferenceUtil.dismissBatch(target.id, liveBatchIds)
                        dismissedBatches = dismissedBatches + target.id
                        dismissTarget = null
                    }
                ) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { dismissTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    reviewBatchId?.let { batchId ->
        val group = playlists.firstOrNull { it.batch.id == batchId }
        val flagged = group?.let { MatchOps.flaggedEntries(it.entries) }.orEmpty()
        if (group == null || flagged.isEmpty()) {
            // Nothing left to review (acknowledged, dismissed, or swapped).
            LaunchedEffect(batchId) { onReviewDismiss() }
        } else {
            MatchReviewSheet(
                batch = group.batch,
                entries = group.entries,
                onDismissRequest = onReviewDismiss,
                onSwap = onReviewSwap,
                onDownloadParts = onReviewParts,
                onAcceptAll = { onReviewAcceptAll(batchId) },
            )
        }
    }
}

@Composable
fun Header(modifier: Modifier = Modifier, onMenuOpen: () -> Unit = {}) {
    val windowWidthSizeClass = LocalWindowWidthState.current
    when (windowWidthSizeClass) {
        WindowWidthSizeClass.Expanded -> {
            HeaderExpanded(modifier = modifier)
        }
        else -> {
            HeaderCompact(modifier = modifier, onMenuOpen = onMenuOpen)
        }
    }
}

@Composable
private fun HeaderCompact(modifier: Modifier = Modifier, onMenuOpen: () -> Unit) {

    Row(modifier = modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenuOpen, modifier = Modifier) {
            Icon(
                imageVector = Icons.Outlined.Menu,
                contentDescription = stringResource(R.string.show_navigation_drawer),
                modifier = Modifier,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            stringResource(R.string.nav_active_downloads),
            style =
                MaterialTheme.typography.titleLarge.copy(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                ),
        )
    }
}

@Composable
private fun HeaderExpanded(modifier: Modifier = Modifier) {
    Row(modifier = modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            stringResource(R.string.nav_active_downloads),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
        )
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
fun FABs(modifier: Modifier = Modifier, downloadCallback: () -> Unit = {}) {
    val expanded = LocalWindowWidthState.current != WindowWidthSizeClass.Compact
    Column(modifier = modifier.padding(6.dp), horizontalAlignment = Alignment.End) {
        FloatingActionButton(
            onClick = downloadCallback,
            content = {
                if (expanded) {
                    Row(
                        modifier = Modifier.widthIn(min = 80.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.download))
                    }
                } else {
                    Icon(
                        Icons.Outlined.FileDownload,
                        contentDescription = stringResource(R.string.download),
                    )
                }
            },
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
@Preview
private fun DownloadQueuePlaceholder(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val floatTransition = rememberInfiniteTransition(label = "waveFloat")
        val floatY by
            floatTransition.animateFloat(
                initialValue = 0f,
                targetValue = -6f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "waveFloatY",
            )
        var revealed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(180)
            revealed = true
        }
        ConstraintLayout {
            val (image, text) = createRefs()
            val showImage =
                with(LocalDensity.current) {
                    this@BoxWithConstraints.constraints.maxHeight >= 240.dp.toPx()
                }
            if (showImage) {
                Image(
                    painter = painterResource(R.drawable.seal_mascot),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier.size(180.dp)
                            .graphicsLayer { translationY = floatY.dp.toPx() }
                            .clip(CircleShape)
                            .constrainAs(image) {
                                top.linkTo(parent.top)
                                bottom.linkTo(parent.bottom)
                                start.linkTo(parent.start)
                                end.linkTo(parent.end)
                            },
                )
            } else {
                Spacer(Modifier.height(72.dp).constrainAs(image) { top.linkTo(parent.top) })
            }
            AnimatedVisibility(
                visible = revealed,
                enter =
                    fadeIn(tween(durationMillis = 520)) +
                        slideInVertically(
                            initialOffsetY = { it / 3 },
                            animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
                        ),
                modifier =
                    Modifier.constrainAs(text) { top.linkTo(image.bottom, margin = 36.dp) },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.you_ll_find_your_downloads_here),
                        modifier = Modifier.padding(horizontal = 24.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.download_hint),
                        modifier = Modifier.padding(top = 4.dp).padding(horizontal = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun SubHeader(
    modifier: Modifier = Modifier,
    containerColor: Color =
        MaterialTheme.colorScheme.run {
            if (LocalDarkTheme.current.isDarkTheme()) surfaceContainer else surfaceContainerLowest
        },
    videoCount: Int = 0,
    audioCount: Int = 0,
    isGridView: Boolean = true,
    onToggleView: () -> Unit,
) {
    val text = buildString {
        if (videoCount > 0) {
            append(pluralStringResource(R.plurals.video_count, videoCount).format(videoCount))
            if (audioCount > 0) {
                append(", ")
            }
        }
        if (audioCount > 0) {
            append(pluralStringResource(R.plurals.audio_count, audioCount).format(audioCount))
        }
    }

    Row(
        modifier = modifier.padding(top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        FilledIconButton(
            onClick = onToggleView,
            modifier = Modifier.clearAndSetSemantics {}.size(32.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = containerColor),
        ) {
            Icon(
                imageVector =
                    if (isGridView) Icons.AutoMirrored.Outlined.List else Icons.Outlined.GridView,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }

    }
}

/** One line over the All list: the batches are one tap away in Playlist. */
@Composable
private fun BatchInPlaylistBanner(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.batches_live_in_playlist),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.show_playlists),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

internal class DownloadPageV2Test {
    private val mockDownloader =
        object : DownloaderV2 {
            private val map = mutableStateMapOf<Task, Task.State>()

            init {
                val viewState =
                    Task.ViewState(title = "Sample title", uploader = "dummy video uploader")
                val list =
                    listOf(
                        Task.State(Idle, null, viewState),
                        Task.State(Canceled(Task.RestartableAction.Download), null, viewState),
                        Task.State(Completed(null), null, viewState),
                    )
                map.run {
                    repeat(9) {
                        put(Task(url = "$it", preferences = PreferencesMock), list[it % 3])
                    }
                }
                val scope = CoroutineScope(SupervisorJob())

                scope.launch(Dispatchers.Default) {
                    while (true) {
                        delay(1000)
                        val newEntries =
                            map.toMap().map { (task, state) ->
                                val newDownloadState =
                                    when (state.downloadState) {
                                        is Canceled -> Idle
                                        is Completed -> Idle
                                        is Error -> Idle
                                        is FetchingInfo -> ReadyWithInfo
                                        Idle -> FetchingInfo(Job(), task.id)
                                        ReadyWithInfo -> Running(Job(), task.id)
                                        is Running -> {
                                            val preState: Running = state.downloadState
                                            if (preState.progress >= 1f) Completed(null)
                                            else preState.copy(progress = preState.progress + 0.1f)
                                        }
                                    }
                                task to state.copy(downloadState = newDownloadState)
                            }
                        Snapshot.withMutableSnapshot {
                            newEntries.forEach { (task, state) ->
                                delay(100)
                                map[task] = state
                            }
                        }
                    }
                }
            }

            override fun getTaskStateMap(): SnapshotStateMap<Task, Task.State> {
                return map
            }

            override fun cancel(task: Task): Boolean {
                return false
            }

            override fun restart(task: Task) {}

            override fun enqueue(task: Task) {}

            override fun enqueue(task: Task, state: Task.State) {}

            override fun remove(task: Task): Boolean {
                return true
            }
        }

    @Composable
    @Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
    @Preview(name = "Tablet", device = "spec:width=600dp,height=800dp,dpi=240")
    private fun Preview() {

        val downloader: DownloaderV2 = mockDownloader
        SealTheme {
            Column() {
                DownloadPageImplV2(
                    taskDownloadStateMap = downloader.getTaskStateMap(),
                    onActionPost = { task, state -> },
                    onMenuOpen = {},
                )
            }
        }
    }
}
