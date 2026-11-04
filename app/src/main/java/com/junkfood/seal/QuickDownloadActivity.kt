package com.junkfood.seal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.ui.common.LocalDarkTheme
import com.junkfood.seal.ui.common.SettingsProvider
import com.junkfood.seal.ui.page.downloadv2.configure.Config
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialog
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SelectionState
import com.junkfood.seal.ui.page.downloadv2.configure.FormatPage
import com.junkfood.seal.ui.page.downloadv2.configure.PlaylistSelectionPage
import com.junkfood.seal.ui.page.downloadv2.configure.MetadataTracksPage
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.NOTIFICATION
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.matchUrlFromSharedText
import com.junkfood.seal.util.setLanguage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.lifecycleScope
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.download.Task
import com.junkfood.seal.util.SleeveQuickDownload
import com.junkfood.seal.util.ToastUtil
import org.koin.android.ext.android.get
import org.koin.androidx.viewmodel.ext.android.getViewModel

private const val TAG = "QuickDownloadActivity"
const val EXTRA_QUICK_DOWNLOAD = "quick"
private const val NOTIFICATION_PERMISSION_REQUEST = 1001

class QuickDownloadActivity : ComponentActivity() {
    private var sharedUrlCached: String = ""

    /** Wired in onCreate once the ViewModel exists; singleInstance delivers
     * later shares here instead of a fresh activity, so they must re-route. */
    private var onNewIntentHandler: ((Intent) -> Unit)? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        onNewIntentHandler?.invoke(intent)
    }

    private fun Intent.getSharedURL(): String? {
        val intent = this

        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.dataString
            }

            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val sharedContent =
                    intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?: (intent.extras?.get(Intent.EXTRA_TEXT) as? ArrayList<*>)
                            ?.filterIsInstance<String>()
                            ?.joinToString("\n")
                sharedContent?.let {
                    intent.removeExtra(Intent.EXTRA_TEXT)
                    matchUrlFromSharedText(it)
                }
            }

            else -> {
                null
            }
        }
    }

    /**
     * Progress/finish notifications come from DownloadService; Android 13+
     * hides them until POST_NOTIFICATIONS is granted. The share target never
     * asked, so a shared download stayed silent even with the pref on.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!NOTIFICATION.getBoolean()) return
        if (
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
            return
        runCatching {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getSharedURL()?.let { sharedUrlCached = it }

        val isQuickDownload = intent.getBooleanExtra(EXTRA_QUICK_DOWNLOAD, false)

        if (sharedUrlCached.isEmpty() && !isQuickDownload) {
            finish()
            return
        }

        requestNotificationPermissionIfNeeded()

        if (isQuickDownload && sharedUrlCached.isNotEmpty()) {
            // Share-sheet "Quick download": straight to the queue with the
            // default pipeline, no configure dialog. The gallery route inside
            // handles Instagram and other image sites anonymously first.
            App.startService()
            val url = sharedUrlCached
            lifecycleScope.launch {
                SleeveQuickDownload.quickDownload(applicationContext, url, get<DownloaderV2>())
                    .onSuccess { (queued, source) ->
                        ToastUtil.makeToast(
                            getString(R.string.gallery_items_queued, queued, source),
                        )
                    }
                    .onFailure {
                        ToastUtil.makeToast(it.message ?: getString(R.string.download_error_msg))
                    }
                finish()
            }
            return
        }

        App.startService()

        enableEdgeToEdge()

        window.run {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
            }
        }

        if (Build.VERSION.SDK_INT < 33) {
            runBlocking { setLanguage(PreferenceUtil.getLocaleFromPreference()) }
        }

        val viewModel: DownloadDialogViewModel = getViewModel()
        // The launcher shortcut starts this activity with no shared text; the
        // sheet then opens on its URL input instead of finishing straight away.
        viewModel.postAction(
            Action.ShowSheet(if (sharedUrlCached.isEmpty()) null else listOf(sharedUrlCached))
        )

        onNewIntentHandler = { newIntent ->
            newIntent.getSharedURL()?.takeIf { it.isNotBlank() }?.let { url ->
                sharedUrlCached = url
                viewModel.postAction(Action.Reset)
                viewModel.postAction(Action.ShowSheet(listOf(url)))
            }
        }

        setContent {
            SettingsProvider(calculateWindowSizeClass(this).widthSizeClass) {
                SealTheme(
                    darkTheme = LocalDarkTheme.current.isDarkTheme(),
                    isHighContrastModeEnabled = LocalDarkTheme.current.isHighContrastModeEnabled,
                ) {
                    var preferences by remember {
                        mutableStateOf(DownloadUtil.DownloadPreferences.createFromPreferences())
                    }

                    val sheetValue = viewModel.sheetValueFlow.collectAsStateWithLifecycle().value

                    val state = viewModel.sheetStateFlow.collectAsStateWithLifecycle().value

                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                    val selectionState =
                        viewModel.selectionStateFlow.collectAsStateWithLifecycle().value

                    var showDialog by remember { mutableStateOf(false) }

                    LaunchedEffect(sheetValue) {
                        if (sheetValue == DownloadDialogViewModel.SheetValue.Expanded) {
                            showDialog = true
                        } else if (sheetValue == DownloadDialogViewModel.SheetValue.Hidden) {
                            launch { sheetState.hide() }
                                .invokeOnCompletion {
                                    showDialog = false
                                    // Read the flows, not the composed snapshot: a
                                    // fast fetch sets FormatSelection and hides the
                                    // sheet in the same frame, and the stale
                                    // snapshot used to finish the activity before
                                    // FormatPage could render.
                                    if (
                                        viewModel.selectionStateFlow.value ==
                                            SelectionState.Idle
                                    ) {
                                        this@QuickDownloadActivity.finish()
                                    }
                                }
                        }
                    }

                    if (showDialog) {
                        DownloadDialog(
                            state = state,
                            sheetState = sheetState,
                            config = Config(),
                            preferences = preferences,
                            onPreferencesUpdate = { preferences = it },
                            onActionPost = { viewModel.postAction(it) },
                        )
                    }

                    when (selectionState) {
                        is SelectionState.FormatSelection ->
                            FormatPage(
                                state = selectionState,
                                onDismissRequest = {
                                    viewModel.postAction(Action.Reset)
                                    this.finish()
                                },
                            )

                        SelectionState.Idle -> {}
                        is SelectionState.PlaylistSelection -> {
                            PlaylistSelectionPage(
                                state = selectionState,
                                onDismissRequest = {
                                    viewModel.postAction(Action.Reset)
                                    this.finish()
                                },
                            )
                        }

                        is SelectionState.MetadataTracks ->
                            MetadataTracksPage(
                                state = selectionState,
                                onDismissRequest = {
                                    viewModel.postAction(Action.Reset)
                                    this.finish()
                                },
                                onActionPost = { action ->
                                    viewModel.postAction(action)
                                    // The metadata page replaced the sheet, so the
                                    // Hidden-state finish above already fired. Queue
                                    // first, then close this overlay ourselves.
                                    if (action is Action.DownloadMetadataTracks) {
                                        this@QuickDownloadActivity.finish()
                                    }
                                },
                            )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // onCreate starts the foreground service eagerly so a transfer survives
        // this overlay closing (the process is otherwise frozen mid-download).
        // When the sheet is closed without queueing anything, that start has no
        // pairing and the "Sleeve is downloading…" badge outlives the app being
        // idle. Drop it unless a task still needs the service.
        runCatching {
            val pending =
                get<DownloaderV2>().getTaskStateMap().values.any { state ->
                    when (state.downloadState) {
                        Task.DownloadState.Idle,
                        is Task.DownloadState.FetchingInfo,
                        Task.DownloadState.ReadyWithInfo,
                        is Task.DownloadState.Running -> true
                        else -> false
                    }
                }
            if (!pending) App.stopService()
        }
        super.onDestroy()
    }
}
