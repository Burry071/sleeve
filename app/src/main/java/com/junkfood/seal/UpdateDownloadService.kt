package com.junkfood.seal

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.junkfood.seal.util.AppUpdateManager
import com.junkfood.seal.util.NotificationUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "UpdateDownloadService"

/**
 * Foreground host for the update APK download. The banner and the update
 * notification both start the transfer here, so leaving the screen or
 * backgrounding the app cannot cancel it.
 */
class UpdateDownloadService : Service() {

    companion object {
        const val EXTRA_VERSION = "com.junkfood.seal.update.version"
        const val EXTRA_APK_URL = "com.junkfood.seal.update.apkUrl"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloadJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val version = intent?.getStringExtra(EXTRA_VERSION)
        val apkUrl = intent?.getStringExtra(EXTRA_APK_URL)
        if (version.isNullOrEmpty() || apkUrl.isNullOrEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // A start already in flight owns the transfer, but every
        // startForegroundService() call must still be answered with
        // startForeground() or the system kills us for not starting in time.
        if (downloadJob?.isActive == true) {
            val percent =
                (AppUpdateManager.state.value as? AppUpdateManager.State.Downloading)?.progress ?: 0
            startForeground(
                NotificationUtil.UPDATE_NOTIFICATION_ID,
                NotificationUtil.notifyUpdateDownloading(version, percent),
            )
            return START_NOT_STICKY
        }

        startForeground(
            NotificationUtil.UPDATE_NOTIFICATION_ID,
            NotificationUtil.notifyUpdateDownloading(version, 0),
        )
        downloadJob =
            scope.launch {
                val progress =
                    launch {
                        AppUpdateManager.state.collect { state ->
                            if (
                                state is AppUpdateManager.State.Downloading &&
                                    state.version == version
                            ) {
                                NotificationUtil.notifyUpdateDownloading(
                                    state.version,
                                    state.progress,
                                )
                            }
                        }
                    }
                try {
                    AppUpdateManager.download(this@UpdateDownloadService, version, apkUrl)
                } finally {
                    progress.cancel()
                    // Leave the foreground before posting the terminal state:
                    // stopForeground(REMOVE) would delete those notifications.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    when (val state = AppUpdateManager.state.value) {
                        is AppUpdateManager.State.Ready ->
                            NotificationUtil.notifyUpdateReady(
                                state.version,
                                state.file.absolutePath,
                            )
                        is AppUpdateManager.State.Failed ->
                            NotificationUtil.notifyUpdateFailed(version, state.message, apkUrl)
                        else -> NotificationUtil.cancelUpdateNotifications()
                    }
                    stopSelf(startId)
                }
            }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        downloadJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
