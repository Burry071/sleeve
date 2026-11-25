package com.junkfood.seal

import android.app.Service
import android.content.Context.POWER_SERVICE
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.NotificationUtil.SERVICE_NOTIFICATION_ID

private const val TAG = "DownloadService"
private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L

/** This `Service` does nothing */
class DownloadService : Service() {

    /** Set once the service is explicitly started; a started service must
     * survive its binder disconnecting (quick download closes the overlay
     * while transfers are still running). */
    private var isStartedMode = false

    private var wakeLock: PowerManager.WakeLock? = null

    /** Both callbacks that raise the badge go through here. The lock is what an
     * OEM process killer is least likely to ignore once the badge exists. */
    private fun enterForeground() {
        startForeground(SERVICE_NOTIFICATION_ID, makeServiceNotification())
        val lock =
            wakeLock
                ?: (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleeve:downloads")
                    .apply { setReferenceCounted(false) }
                    .also { wakeLock = it }
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    /** The single release point, so the lock cannot outlive the badge on a
     * stop path that does not wait for `onDestroy`. */
    private fun exitForeground() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // START_STICKY restart after the system killed the process: this
            // fresh process has no queued tasks, so resurrecting the
            // "downloading" badge here would be a zombie with nothing behind
            // it. The scheduler re-promotes the service when a task arrives.
            stopSelf()
            return START_NOT_STICKY
        }
        isStartedMode = true
        enterForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        enterForeground()
        return DownloadServiceBinder()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind: ")
        if (!isStartedMode) {
            // Bind-only lifetime (no downloads were ever started through the
            // scheduler): old behavior — drop foreground and die.
            exitForeground()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
            stopSelf()
        }
        // Started mode: App.stopService() tears us down when the queue drains.
        return super.onUnbind(intent)
    }

    private fun makeServiceNotification(): android.app.Notification {
        val pendingIntent: android.app.PendingIntent =
            android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationUtil.makeServiceNotification(pendingIntent)
    }

    override fun onDestroy() {
        exitForeground()
        // Guarantee the badge goes away no matter which stop path ran.
        stopForeground(STOP_FOREGROUND_REMOVE)
        // The started half died with us: App must not believe a later start
        // would be a no-op, or downloads would queue behind a dead service.
        App.onDownloadServiceDestroyed()
        super.onDestroy()
    }

    inner class DownloadServiceBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }
}
