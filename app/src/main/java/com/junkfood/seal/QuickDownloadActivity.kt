package com.junkfood.seal

import android.content.Intent
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
import com.junkfood.seal.util.DownloadErrorMessages
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.matchAllUrlsFromSharedText
import com.junkfood.seal.util.setLanguage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.lifecycleScope
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.util.SleeveQuickDownload
import com.junkfood.seal.util.ToastUtil
import org.koin.android.ext.android.get
import org.koin.androidx.viewmodel.ext.android.getViewModel

const val EXTRA_QUICK_DOWNLOAD = "quick"

class QuickDownloadActivity : ComponentActivity() {
    private var sharedUrlCached: String = ""

    private fun Intent.getSharedURL(): String? {
        val intent = this

        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.dataString
            }

            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                // ACTION_SEND_MULTIPLE senders may put one string, an
                // ArrayList<String>, or HTML under EXTRA_TEXT.
                val sharedContent =
                    (intent.extras?.get(Intent.EXTRA_TEXT) as? String)
                        ?: (intent.extras?.get(Intent.EXTRA_TEXT) as? ArrayList<*>)
                            ?.filterIsInstance<String>()
                            ?.joinToString("\n")
                        ?: (intent.extras?.get(Intent.EXTRA_HTML_TEXT) as? String)
                sharedContent?.let { matchAllUrlsFromSharedText(it) }
            }

            else -> {
                null
            }
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getSharedURL()?.let { sharedUrlCached = it }

        if (sharedUrlCached.isEmpty()) {
            ToastUtil.makeToast(R.string.share_fail_msg)
            finish()
            return
        }

        // Sharing a link from another app (ACTION_SEND) opens the configure
        // sheet, like the stock Seal share target — the user picks format,
        // preset and destination before anything is queued. Only the
        // dedicated quick-download shortcut (app shortcut / tile with the
        // quick extra) skips the dialog and downloads immediately.
        if (
            intent.getBooleanExtra(EXTRA_QUICK_DOWNLOAD, false) ||
                intent.getStringExtra("android.intent.extra.shortcut.ID") == "quick-download"
        ) {
            // Share-sheet "Quick download": straight to the queue with the
            // default pipeline, no configure dialog. The gallery route inside
            // handles Instagram and other image sites anonymously first.
            App.startService()
            val urls =
                sharedUrlCached.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            lifecycleScope.launch {
                var queued = 0
                urls.forEach { url ->
                    SleeveQuickDownload.quickDownload(applicationContext, url, get<DownloaderV2>())
                        .onSuccess { (count, _) -> queued += count }
                        .onFailure {
                            ToastUtil.makeToast(
                                DownloadErrorMessages.friendly(it)
                                    .ifEmpty { getString(R.string.download_error_msg) },
                            )
                        }
                }
                if (queued > 0) {
                    ToastUtil.makeToast(
                        getString(R.string.gallery_items_queued, queued, "quick"),
                    )
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
        viewModel.postAction(
            Action.ShowSheet(
                sharedUrlCached.split("\n").map { it.trim() }.filter { it.isNotBlank() },
            ),
        )

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

                    // Seal's quick-download mechanics: the overlay exists only
                    // while the sheet is on screen. The moment it is gone —
                    // Download tapped, task enqueued, cancelled — the share
                    // overlay finishes instead of lingering over the app.
                    LaunchedEffect(Unit) {
                        var sheetShown = false
                        viewModel.sheetValueFlow.collect { value ->
                            if (value == DownloadDialogViewModel.SheetValue.Expanded) {
                                sheetShown = true
                            } else if (sheetShown) {
                                finish()
                            }
                        }
                    }

                    val state = viewModel.sheetStateFlow.collectAsStateWithLifecycle().value

                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                    val selectionState =
                        viewModel.selectionStateFlow.collectAsStateWithLifecycle().value

                    var showDialog by remember { mutableStateOf(false) }
                    // The flows start at Hidden/Idle, so only auto-close after
                    // the sheet actually expanded once — otherwise the first
                    // composition finishes the activity before ShowSheet lands.
                    var wasExpanded by remember { mutableStateOf(false) }

                    LaunchedEffect(sheetValue, selectionState) {
                        if (sheetValue == DownloadDialogViewModel.SheetValue.Expanded) {
                            wasExpanded = true
                            showDialog = true
                        } else if (
                            sheetValue == DownloadDialogViewModel.SheetValue.Hidden && wasExpanded
                        ) {
                            launch { sheetState.hide() }
                                .invokeOnCompletion {
                                    showDialog = false
                                    if (selectionState == SelectionState.Idle) {
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
                                onActionPost = { viewModel.postAction(it) },
                            )
                    }
                }
            }
        }
    }
}
