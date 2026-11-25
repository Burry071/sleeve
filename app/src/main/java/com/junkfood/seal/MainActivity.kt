package com.junkfood.seal

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.junkfood.seal.ui.common.LocalDarkTheme
import com.junkfood.seal.ui.common.Route
import com.junkfood.seal.ui.common.SettingsProvider
import com.junkfood.seal.ui.page.AppEntry
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.matchAllUrlsFromSharedText
import com.junkfood.seal.util.setLanguage
import kotlinx.coroutines.runBlocking
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.compose.KoinContext

class MainActivity : AppCompatActivity() {

    // Per-instance: a process-wide static held a stale link across
    // unrelated shares.
    private var sharedUrlCached = ""
    private val dialogViewModel: DownloadDialogViewModel by viewModel()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT < 33) {
            runBlocking { setLanguage(PreferenceUtil.getLocaleFromPreference()) }
        }
        enableEdgeToEdge()

        handleIntent(intent)
        setContent {
            KoinContext {
                val windowSizeClass = calculateWindowSizeClass(this)
                SettingsProvider(windowWidthSizeClass = windowSizeClass.widthSizeClass) {
                    SealTheme(
                        darkTheme = LocalDarkTheme.current.isDarkTheme(),
                        isHighContrastModeEnabled = LocalDarkTheme.current.isHighContrastModeEnabled,
                    ) {
                        AppEntry(dialogViewModel = dialogViewModel)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // Single-instance launch mode delivers a cold-start share intent to
    // onCreate, so both entry points must route through the same handler.
    private fun handleIntent(intent: Intent) {
        intent.getStringExtra(NotificationUtil.EXTRA_OPEN_ROUTE)?.let { rawRoute ->
            intent.removeExtra(NotificationUtil.EXTRA_OPEN_ROUTE)
            allowedExternalRouteOrNull(rawRoute)?.let { safeRoute ->
                dialogViewModel.requestRoute(safeRoute)
            }
            return
        }
        intent.getStringExtra(NotificationUtil.EXTRA_REVIEW_BATCH_ID)?.let { batchId ->
            intent.removeExtra(NotificationUtil.EXTRA_REVIEW_BATCH_ID)
            dialogViewModel.openMatchReview(batchId)
            return
        }
        val url = intent.getSharedURL()
        if (url != null) {
            dialogViewModel.postAction(
                DownloadDialogViewModel.Action.ShowSheet(
                    url.split("\n").map { it.trim() }.filter { it.isNotBlank() },
                ),
            )
        }
    }

    private fun Intent.getSharedURL(): String? {
        val intent = this

        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.dataString
            }

            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedContent ->
                    intent.removeExtra(Intent.EXTRA_TEXT)
                    matchAllUrlsFromSharedText(sharedContent).also { matchedUrl ->
                        if (sharedUrlCached != matchedUrl) {
                            sharedUrlCached = matchedUrl
                        }
                    }
                }
            }

            else -> {
                null
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        private val ALLOWED_EXTERNAL_ROUTES: Set<String> =
            setOf(
                Route.HOME,
                Route.DOWNLOADS,
                Route.TASK_LIST,
                Route.SETTINGS,
                Route.SETTINGS_PAGE,
                Route.ABOUT,
                Route.APPEARANCE,
                Route.CREDITS,
                Route.DARK_THEME,
                Route.DOWNLOAD_DIRECTORY,
                Route.DOWNLOAD_FORMAT,
                Route.GENERAL_DOWNLOAD_PREFERENCES,
                Route.INTERACTION,
                Route.LANGUAGES,
                Route.NETWORK_PREFERENCES,
                Route.SUBTITLE_PREFERENCES,
                Route.TROUBLESHOOTING,
            )

        internal fun allowedExternalRouteOrNull(route: String?): String? =
            route?.takeIf { it in ALLOWED_EXTERNAL_ROUTES }
    }
}
