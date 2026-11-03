package com.junkfood.seal

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.NotificationUtil.SERVICE_NOTIFICATION_ID

private const val TAG = "DownloadService"

/** This `Service` does nothing */
class DownloadService : Service() {

    /** Set once the service is explicitly started; a started service must
     * survive its binder disconnecting (quick download closes the overlay
     * while transfers are still running). */
    private var isStartedMode = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isStartedMode = true
        startForeground(SERVICE_NOTIFICATION_ID, makeServiceNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }
        val notification = NotificationUtil.makeServiceNotification(pendingIntent)
        startForeground(SERVICE_NOTIFICATION_ID, notification)
        return DownloadServiceBinder()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind: ")
        if (!isStartedMode) {
            // Bind-only lifetime (no downloads were ever started through the
            // scheduler): old behavior — drop foreground and die.
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

    inner class DownloadServiceBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }
}
