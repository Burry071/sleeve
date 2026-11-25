package com.junkfood.seal.ui.page

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.junkfood.seal.BuildConfig
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.ghostengine.GhostEngine
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getLong
import com.junkfood.seal.util.UpdateUtil
import com.junkfood.seal.util.UpdaterPolicy
import com.junkfood.seal.util.YT_DLP_AUTO_UPDATE
import com.junkfood.seal.util.YT_DLP_LAST_FAILURE
import com.junkfood.seal.util.YT_DLP_UPDATE_BUILD
import com.junkfood.seal.util.YT_DLP_UPDATE_FAILURES
import com.junkfood.seal.util.YT_DLP_UPDATE_INTERVAL
import com.junkfood.seal.util.YT_DLP_UPDATE_TIME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
fun YtdlpUpdater(downloader: DownloaderV2 = koinInject()) {

    LaunchedEffect(Unit) {
        if (!PreferenceUtil.isNetworkAvailableForDownload()) {
            return@LaunchedEffect
        }

        // A new build ships a new engine, so the stored timestamp and failure
        // streak describe the old one: the check runs at once. Otherwise the
        // interval, the 6 h failure retry and the five-failure stand-down all
        // live in UpdaterPolicy.
        val sameBuild = YT_DLP_UPDATE_BUILD.getInt() == BuildConfig.VERSION_CODE
        val due =
            UpdaterPolicy.isDue(
                now = System.currentTimeMillis(),
                lastSuccess = YT_DLP_UPDATE_TIME.getLong(),
                lastFailure = YT_DLP_LAST_FAILURE.getLong(),
                intervalMs = YT_DLP_UPDATE_INTERVAL.getLong(),
                failureCount = if (sameBuild) YT_DLP_UPDATE_FAILURES.getInt() else 0,
                autoUpdate = YT_DLP_AUTO_UPDATE.getBoolean(),
                sameBuild = sameBuild,
            )
        if (!due) return@LaunchedEffect

        // Live task map, not the legacy state: an engine swap under a running
        // transfer is the hazard. A gated check waits for the next launch.
        if (!UpdaterPolicy.canAttempt(downloader.activeTransfers())) return@LaunchedEffect

        withContext(Dispatchers.IO) { UpdateUtil.updateYtDlp() }
    }

    // Warm the gallery-dl extractor registry off the critical path: the first
    // import compiles ~300 site modules, so doing it here keeps the first
    // shared gallery link instant.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3_000)
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            GhostEngine.preloadGallery()
        }
    }
}
