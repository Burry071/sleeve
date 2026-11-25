package com.junkfood.seal

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.download.Task
import com.junkfood.seal.util.AppUpdateManager
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.UpdateOutcome
import com.junkfood.seal.util.UpdateUtil
import com.junkfood.seal.util.makeToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

private const val TAG = "UpdateDownloadService"

/**
 * Foreground host for the two long transfers the app starts from outside a
 * screen: the update APK download and the yt-dlp engine update. The banner and
 * the update notification both start the APK transfer here, so leaving the
 * screen or backgrounding the app cannot cancel it; the engine update starts
 * here from a failure notification for the same reason.
 */
class UpdateDownloadService : Service(), KoinComponent {

    companion object {
        const val EXTRA_VERSION = "com.junkfood.seal.update.version"
        const val EXTRA_APK_URL = "com.junkfood.seal.update.apkUrl"
        const val EXTRA_APK_DIGEST = "com.junkfood.seal.update.apkDigest"

        /** True for an engine update; the APK extras are then absent. */
        const val EXTRA_ENGINE_UPDATE = "com.junkfood.seal.update.engine"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloadJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_ENGINE_UPDATE, false) == true) {
            return startEngineUpdate(intent.getStringExtra(NotificationActionReceiver.TASK_ID_KEY), startId)
        }
        if (!BuildConfig.ENABLE_IN_APP_UPDATER) {
            stopSafely(startId)
            return START_NOT_STICKY
        }
        val version = intent?.getStringExtra(EXTRA_VERSION)
        val apkUrl = intent?.getStringExtra(EXTRA_APK_URL)
        val digest = intent?.getStringExtra(EXTRA_APK_DIGEST)
        if (version.isNullOrEmpty() || apkUrl.isNullOrEmpty()) {
            stopSafely(startId)
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
                var terminal: AppUpdateManager.State? = null
                try {
                    terminal =
                        AppUpdateManager.download(
                            this@UpdateDownloadService,
                            version,
                            apkUrl,
                            digest,
                        )
                } finally {
                    progress.cancel()
                    // Leave the foreground before posting the terminal state:
                    // stopForeground(REMOVE) would delete those notifications.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    // The state this transfer ended in, not whatever the shared
                    // flow says now; a duplicate start that lost the claim
                    // returns null and announces nothing.
                    terminal?.let {
                        AppUpdateManager.postTerminalNotification(it, version, apkUrl, digest)
                    }
                    stopSelf(startId)
                }
            }
        return START_NOT_STICKY
    }

    /**
     * Satisfies Android 12+'s startForegroundService() contract before stopping
     * when the service was launched for a disabled or malformed APK update.
     */
    private fun stopSafely(startId: Int) {
        runCatching {
            startForeground(
                NotificationUtil.UPDATE_NOTIFICATION_ID,
                NotificationUtil.notifyUpdateDownloading("", 0),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        NotificationUtil.cancelUpdateNotifications()
        stopSelf(startId)
    }

    /**
     * Replaces the engine for every lane. The caller refuses while transfers are
     * running; the mutex inside [UpdateUtil] is the second line of defence for
     * a start that races that check.
     */
    private fun startEngineUpdate(taskId: String?, startId: Int): Int {
        if (downloadJob?.isActive == true) {
            startForeground(
                NotificationUtil.YTDLP_UPDATE_NOTIFICATION_ID,
                NotificationUtil.notifyYtdlpUpdating(),
            )
            return START_NOT_STICKY
        }
        startForeground(
            NotificationUtil.YTDLP_UPDATE_NOTIFICATION_ID,
            NotificationUtil.notifyYtdlpUpdating(),
        )
        downloadJob =
            scope.launch {
                var outcome: UpdateOutcome? = null
                try {
                    outcome = UpdateUtil.updateYtDlp()
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    NotificationUtil.cancelNotification(
                        NotificationUtil.YTDLP_UPDATE_NOTIFICATION_ID
                    )
                    announceEngineOutcome(outcome, taskId)
                    stopSelf(startId)
                }
            }
        return START_NOT_STICKY
    }

    /**
     * The user asked for this from a failed task, so the task itself is the
     * evidence: after a successful update it restarts, but only if it still
     * exists and still carries the failure. A removed task is not resurrected.
     */
    private fun announceEngineOutcome(outcome: UpdateOutcome?, taskId: String?) {
        when (outcome) {
            is UpdateOutcome.Succeeded -> {
                makeToast(getString(R.string.ytdlp_update_done))
                val task = taskId?.let { id ->
                    downloader().getTaskStateMap().entries.firstOrNull { it.key.id == id }?.takeIf {
                        it.value.downloadState is Task.DownloadState.Error
                    }?.key
                }
                task?.let { downloader().restart(it) }
            }
            is UpdateOutcome.Failed ->
                makeToast(getString(R.string.ytdlp_update_action_failed))
            is UpdateOutcome.Busy -> makeToast(getString(R.string.ytdlp_update_busy))
            null -> Unit
        }
    }

    private fun downloader(): DownloaderV2 = get()

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        downloadJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
