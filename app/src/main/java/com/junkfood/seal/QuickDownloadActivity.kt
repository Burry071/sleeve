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
import com.junkfood.seal.ui.page.downloadv2.configure.DialogScreen
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialog
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.FormatPage
import com.junkfood.seal.ui.page.downloadv2.configure.MetadataTracksPage
import com.junkfood.seal.ui.page.downloadv2.configure.PendingMetered
import com.junkfood.seal.ui.page.downloadv2.configure.PendingMeteredDialog
import com.junkfood.seal.ui.page.downloadv2.configure.PlaylistSelectionPage
import com.junkfood.seal.ui.page.downloadv2.configure.dialogScreen
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.ACTION_QUICK_CLIPBOARD
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.EXTRA_QUICK_NONCE
import com.junkfood.seal.util.INTERNAL_PREFS
import com.junkfood.seal.util.NetworkPolicy
import com.junkfood.seal.util.NOTIFICATION
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.QUICK_NONCE_KEY
import com.junkfood.seal.util.isTrustedQuickIntent
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

    /** Wired in onCreate once the ViewModel exists; singleInstance delivers
     * later shares here instead of a fresh activity, so they must re-route. */
    private var onNewIntentHandler: ((List<String>?) -> Unit)? = null

    /** Android 10+ refuses clipboard reads until this window has focus, and the
     * shortcut tap starts us before focus arrives. */
    private var awaitingClipboardQuickDownload = false

    /**
     * A download the metered gate parked. The dialog hosting it lives in the
     * sheet composition, so this holder is what survives until the user
     * answers. Shared with the downloads page so both gates cannot drift.
     */
    private val metered = PendingMetered()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (val launch = intent.quickLaunch()) {
            null -> Unit
            QuickLaunch.Clipboard ->
                if (hasWindowFocus()) handleClipboardQuickDownload()
                else awaitingClipboardQuickDownload = true

            // A warm launch never enqueues silently: the user shared something
            // while the sheet was already open, so show them what arrived.
            is QuickLaunch.Silent,
            is QuickLaunch.Confirm -> onNewIntentHandler?.invoke(launch.urlList)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && awaitingClipboardQuickDownload) {
            awaitingClipboardQuickDownload = false
            handleClipboardQuickDownload()
        }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (val launch = intent.quickLaunch()) {
            null -> {
                finish()
                return
            }

            is QuickLaunch.Clipboard -> {
                requestNotificationPermissionIfNeeded()
                App.startService()
                awaitingClipboardQuickDownload = true
            }

            is QuickLaunch.Silent -> {
                requestNotificationPermissionIfNeeded()
                App.startService()
                quickDownload(launch.url)
            }

            is QuickLaunch.Confirm -> {
                requestNotificationPermissionIfNeeded()
                App.startService()
                showConfigureSheet(launch.urlList)
            }
        }
    }

    /**
     * What one launch asks for, decided once per intent.
     *
     * Trust used to be recomputed in two entry paths with slightly different
     * preconditions; one classifier means a launch can only be silent when the
     * whole rule says so.
     */
    private sealed interface QuickLaunch {
        /** URLs to pre-fill the configure sheet with, when there are any. */
        val urlList: List<String>?

        /** Trusted nonce-carrying request: enqueue without showing the sheet. */
        data class Silent(val url: String) : QuickLaunch {
            override val urlList: List<String>
                get() = listOf(url)
        }

        /** Trusted clipboard shortcut: read it once the window has focus. */
        data object Clipboard : QuickLaunch {
            override val urlList: List<String>?
                get() = null
        }

        /** Everything else, including a forged or stale shortcut: ask first. */
        data class Confirm(override val urlList: List<String>?) : QuickLaunch
    }

    /** One pass over action, URL, and nonce — the only place trust is read. */
    private fun Intent.quickLaunch(): QuickLaunch? {
        if (action == ACTION_QUICK_CLIPBOARD) {
            return if (isTrustedQuick()) QuickLaunch.Clipboard else QuickLaunch.Confirm(null)
        }
        val url = getSharedURL()?.takeIf { it.isNotBlank() }
        if (url == null && !getBooleanExtra(EXTRA_QUICK_DOWNLOAD, false)) return null
        // Silent quick mode needs a nonce-carrying, non-share launch; the
        // launcher shortcut is a share, so it opens the sheet instead.
        return if (isTrustedQuick() && url != null) QuickLaunch.Silent(url)
        else QuickLaunch.Confirm(url?.let(::listOf))
    }

    private fun handleClipboardQuickDownload() {
        val url =
            readClipboardText()?.takeIf { it.isNotBlank() }?.let { matchUrlFromSharedText(it) }
                ?.takeIf { it.isNotBlank() }
        if (url == null) {
            ToastUtil.makeToast(getString(R.string.quick_clipboard_no_link))
            finish()
            return
        }
        quickDownload(url)
    }

    /** The single fenced quick lane: cookie-less, template-less, announced. */
    private fun quickDownload(url: String) {
        if (!NetworkPolicy.canStartDownloads()) {
            // No format chosen yet, so don't raise the cellular dialog here:
            // the sheet's postSheetAction gate covers the actual Download.
            showConfigureSheet(listOf(url))
            return
        }
        startQuickDownload(url)
    }

    private fun startQuickDownload(url: String) {
        lifecycleScope.launch {
            SleeveQuickDownload.quickDownload(applicationContext, url, get<DownloaderV2>())
                .onSuccess { (queued, source) ->
                    ToastUtil.makeToast(getString(R.string.gallery_items_queued, queued, source))
                    finish()
                }
                .onFailure {
                    // Nothing was queued: gallery resolution failed and the
                    // default lane could not enqueue either. Retrying from
                    // the sheet runs the full pipeline with the session.
                    ToastUtil.makeToast(getString(R.string.quick_download_needs_confirm))
                    showConfigureSheet(listOf(url))
                }
        }
    }

    private fun readClipboardText(): String? =
        runCatching {
                App.clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(this)
                    ?.toString()
            }
            .getOrNull()

    /** Nonce-gated: a forged extra from another app opens the sheet instead. */
    private fun Intent.isTrustedQuick(): Boolean =
        isTrustedQuickIntent(
            hasQuickExtra = getBooleanExtra(EXTRA_QUICK_DOWNLOAD, false),
            isShareAction = action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE,
            providedNonce = getStringExtra(EXTRA_QUICK_NONCE),
            expectedNonce =
                getSharedPreferences(INTERNAL_PREFS, MODE_PRIVATE)
                    .getString(QUICK_NONCE_KEY, null),
        )

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalMaterial3Api::class)
    private fun showConfigureSheet(urlList: List<String>?) {
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
        viewModel.postAction(Action.ShowSheet(urlList))

        // Both enqueue entry points in this sheet pass the same gate as the
        // main page; "Don't allow" simply drops the action.
        fun postSheetAction(action: Action) {
            metered.post(action) {
                viewModel.postAction(action)
                // The metadata page replaced the sheet, so the hidden-state
                // finish already fired. Queue first, then close this overlay.
                if (action is Action.DownloadMetadataTracks) finish()
            }
        }

        onNewIntentHandler = { urlList ->
            viewModel.postAction(Action.Reset)
            viewModel.postAction(Action.ShowSheet(urlList))
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

                    // Composition has to outlive the hide animation, so this is
                    // an observation of the sheet itself rather than a latch that
                    // a missed completion could leave stuck on.
                    val showDialog =
                        sheetValue == DownloadDialogViewModel.SheetValue.Expanded ||
                            sheetState.isVisible

                    LaunchedEffect(sheetValue) {
                        if (sheetValue != DownloadDialogViewModel.SheetValue.Hidden) {
                            return@LaunchedEffect
                        }
                        sheetState.hide()
                        // Read the flows, not the composed snapshot: a fast fetch
                        // sets FormatSelection and hides the sheet in the same
                        // frame, and the stale snapshot used to finish the
                        // activity before FormatPage could render.
                        val live =
                            dialogScreen(
                                viewModel.sheetValueFlow.value,
                                viewModel.sheetStateFlow.value,
                                viewModel.selectionStateFlow.value,
                            )
                        if (live == DialogScreen.Hidden) {
                            this@QuickDownloadActivity.finish()
                        }
                    }

                    PendingMeteredDialog(pending = metered)

                    if (showDialog) {
                        DownloadDialog(
                            state = state,
                            sheetState = sheetState,
                            config = Config(),
                            preferences = preferences,
                            onPreferencesUpdate = { preferences = it },
                            onActionPost = { postSheetAction(it) },
                        )
                    }

                    when (val screen = dialogScreen(sheetValue, state, selectionState)) {
                        DialogScreen.Hidden, is DialogScreen.Sheet -> {}

                        is DialogScreen.Format ->
                            FormatPage(
                                state = screen.state,
                                onDismissRequest = {
                                    viewModel.postAction(Action.Reset)
                                    this.finish()
                                },
                            )

                        is DialogScreen.Playlist ->
                            PlaylistSelectionPage(
                                state = screen.state,
                                onDismissRequest = {
                                    viewModel.postAction(Action.Reset)
                                    this.finish()
                                },
                            )

                        is DialogScreen.Metadata ->
                            MetadataTracksPage(
                                state = screen.state,
                                onDismissRequest = {
                                    viewModel.postAction(Action.Reset)
                                    this.finish()
                                },
                                onActionPost = { action -> postSheetAction(action) },
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
