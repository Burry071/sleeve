package com.junkfood.seal.util

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.NotificationActionReceiver
import com.junkfood.seal.NotificationActionReceiver.Companion.ACTION_CANCEL_TASK
import com.junkfood.seal.NotificationActionReceiver.Companion.ACTION_CANCEL_UPDATE
import com.junkfood.seal.NotificationActionReceiver.Companion.ACTION_ERROR_REPORT
import com.junkfood.seal.NotificationActionReceiver.Companion.ACTION_INSTALL_UPDATE
import com.junkfood.seal.NotificationActionReceiver.Companion.ACTION_KEY
import com.junkfood.seal.NotificationActionReceiver.Companion.APK_PATH_KEY
import com.junkfood.seal.NotificationActionReceiver.Companion.ERROR_REPORT_KEY
import com.junkfood.seal.NotificationActionReceiver.Companion.NOTIFICATION_ID_KEY
import com.junkfood.seal.NotificationActionReceiver.Companion.TASK_ID_KEY
import com.junkfood.seal.R
import com.junkfood.seal.UpdateDownloadService
import com.junkfood.seal.util.PreferenceUtil.getBoolean

private const val TAG = "NotificationUtil"

@SuppressLint("StaticFieldLeak")
object NotificationUtil {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private const val PROGRESS_MAX = 100
    private const val PROGRESS_INITIAL = 0
    private const val CHANNEL_ID = "download_notification"
    private const val SERVICE_CHANNEL_ID = "download_service"
    private const val UPDATE_CHANNEL_ID = "app_update"
    private const val NOTIFICATION_GROUP_ID = "seal.download.notification"
    private const val DEFAULT_NOTIFICATION_ID = 100
    const val SERVICE_NOTIFICATION_ID = 123
    const val UPDATE_NOTIFICATION_ID = 200
    private lateinit var serviceNotification: Notification

    //    private var builder =
    //        NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_seal)
    private val commandNotificationBuilder =
        NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_seal)

    @RequiresApi(Build.VERSION_CODES.O)
    fun createNotificationChannel() {
        val name = context.getString(R.string.channel_name)
        val descriptionText = context.getString(R.string.channel_description)
        val importance = NotificationManager.IMPORTANCE_LOW
        val channelGroup =
            NotificationChannelGroup(NOTIFICATION_GROUP_ID, context.getString(R.string.download))
        val channel =
            NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                group = NOTIFICATION_GROUP_ID
            }
        val serviceChannel =
            NotificationChannel(SERVICE_CHANNEL_ID, name, importance).apply {
                description = context.getString(R.string.service_title)
                group = NOTIFICATION_GROUP_ID
            }
        val updateChannel =
            NotificationChannel(
                UPDATE_CHANNEL_ID,
                context.getString(R.string.channel_app_update),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_app_update_desc)
                group = NOTIFICATION_GROUP_ID
            }
        notificationManager.createNotificationChannelGroup(channelGroup)
        notificationManager.createNotificationChannel(channel)
        notificationManager.createNotificationChannel(serviceChannel)
        notificationManager.createNotificationChannel(updateChannel)
    }

    /** Update found on GitHub Releases: actionable so the APK can be fetched
     * straight from the shade, one nudge per version. */
    fun notifyUpdateAvailable(version: String, apkUrl: String, digest: String? = null) {
        val notification =
            NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_seal)
                .setContentTitle(context.getString(R.string.app_update_notif_available_title))
                .setContentText(context.getString(R.string.app_update_notif_available_text, version))
                .setContentIntent(openAppPendingIntent(UPDATE_NOTIFICATION_ID))
                .setAutoCancel(true)
                .addAction(
                    0,
                    context.getString(R.string.download),
                    updateDownloadPendingIntent(version, apkUrl, digest),
                )
                .build()
        notificationManager.notify(UPDATE_NOTIFICATION_ID, notification)
    }

    /** Foreground notification while the update APK downloads. Ongoing: the
     * service is the only thing keeping the transfer alive. */
    fun notifyUpdateDownloading(version: String, percent: Int): Notification {
        val notification =
            NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_seal)
                .setContentTitle(
                    context.getString(R.string.app_update_notif_downloading_title, version)
                )
                .setContentText("$percent%")
                .setProgress(PROGRESS_MAX, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openAppPendingIntent(UPDATE_NOTIFICATION_ID))
                .addAction(
                    R.drawable.outline_cancel_24,
                    context.getString(R.string.cancel),
                    cancelUpdatePendingIntent(),
                )
                .build()
        notificationManager.notify(UPDATE_NOTIFICATION_ID, notification)
        return notification
    }

    /** APK downloaded: one tap hands it to the system installer. */
    fun notifyUpdateReady(version: String, apkPath: String) {
        val install =
            PendingIntent.getBroadcast(
                context.applicationContext,
                // Distinct request code: PendingIntent identity ignores extras,
                // so Install and Cancel would otherwise overwrite each other.
                UPDATE_NOTIFICATION_ID + 1,
                Intent(context.applicationContext, NotificationActionReceiver::class.java)
                    .putExtra(ACTION_KEY, ACTION_INSTALL_UPDATE)
                    .putExtra(APK_PATH_KEY, apkPath),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_seal)
                .setContentTitle(context.getString(R.string.app_update_notif_ready_title, version))
                .setContentText(context.getString(R.string.app_update_notif_ready_text))
                .setContentIntent(openAppPendingIntent(UPDATE_NOTIFICATION_ID))
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.install), install)
                .build()
        notificationManager.notify(UPDATE_NOTIFICATION_ID, notification)
    }

    /** Download failed: keep the version, URL, and digest so Retry can restart it. */
    fun notifyUpdateFailed(
        version: String,
        message: String,
        apkUrl: String,
        digest: String? = null,
    ) {
        val notification =
            NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_seal)
                .setContentTitle(context.getString(R.string.app_update_notif_failed_title, version))
                .setContentText(message)
                .setContentIntent(openAppPendingIntent(UPDATE_NOTIFICATION_ID))
                .setAutoCancel(true)
                .addAction(
                    0,
                    context.getString(R.string.retry),
                    updateDownloadPendingIntent(version, apkUrl, digest),
                )
                .build()
        notificationManager.notify(UPDATE_NOTIFICATION_ID, notification)
    }

    private fun openAppPendingIntent(requestCode: Int): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(
                context,
                requestCode,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

    private fun updateDownloadPendingIntent(
        version: String,
        apkUrl: String,
        digest: String?,
    ): PendingIntent {
        val intent =
            Intent(context.applicationContext, UpdateDownloadService::class.java)
                .putExtra(UpdateDownloadService.EXTRA_VERSION, version)
                .putExtra(UpdateDownloadService.EXTRA_APK_URL, apkUrl)
                .putExtra(UpdateDownloadService.EXTRA_APK_DIGEST, digest)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context.applicationContext,
                UPDATE_NOTIFICATION_ID,
                intent,
                flags,
            )
        } else {
            @Suppress("DEPRECATION")
            PendingIntent.getService(
                context.applicationContext,
                UPDATE_NOTIFICATION_ID,
                intent,
                flags,
            )
        }
    }

    private fun cancelUpdatePendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext,
            UPDATE_NOTIFICATION_ID,
            Intent(context.applicationContext, NotificationActionReceiver::class.java)
                .putExtra(ACTION_KEY, ACTION_CANCEL_UPDATE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Update notifications outlive the process and the install they advertise;
     * cleared whenever the installed build covers them. */
    fun cancelUpdateNotifications() {
        notificationManager.cancel(UPDATE_NOTIFICATION_ID)
        notificationManager.cancel(UPDATE_NOTIFICATION_ID + 1)
    }

    fun notifyProgress(
        title: String,
        notificationId: Int = DEFAULT_NOTIFICATION_ID,
        progress: Int = PROGRESS_INITIAL,
        taskId: String? = null,
        text: String? = null,
    ) {
        if (!NOTIFICATION.getBoolean()) return
        val pendingIntent =
            taskId?.let {
                Intent(context.applicationContext, NotificationActionReceiver::class.java)
                    .putExtra(TASK_ID_KEY, taskId)
                    .putExtra(NOTIFICATION_ID_KEY, notificationId)
                    .putExtra(ACTION_KEY, ACTION_CANCEL_TASK)
                    .run {
                        PendingIntent.getBroadcast(
                            context.applicationContext,
                            notificationId,
                            this,
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    }
            }

        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_seal)
            .setContentTitle(title)
            .setProgress(PROGRESS_MAX, progress, progress <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .run {
                pendingIntent?.let {
                    addAction(R.drawable.outline_cancel_24, context.getString(R.string.cancel), it)
                }
                notificationManager.notify(notificationId, build())
            }
    }

    /** One aggregate notification per playlist batch; no per-song Cancel action. */
    fun notifyBatch(
        title: String,
        text: String,
        progress: Int,
        notificationId: Int,
    ) {
        if (!NOTIFICATION.getBoolean()) return
        val pendingIntent =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
                PendingIntent.getActivity(
                    context,
                    notificationId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_seal)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(PROGRESS_MAX, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
            .also { notificationManager.notify(notificationId, it) }
    }

    fun finishNotification(
        notificationId: Int = DEFAULT_NOTIFICATION_ID,
        title: String? = null,
        text: String? = null,
        intent: PendingIntent? = null,
    ) {
        Log.d(TAG, "finishNotification: ")
        notificationManager.cancel(notificationId)
        if (!NOTIFICATION.getBoolean()) return

        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_seal)
                .setContentText(text)
                .setOngoing(false)
                .setAutoCancel(true)
        title?.let { builder.setContentTitle(title) }
        intent?.let { builder.setContentIntent(intent) }
        notificationManager.notify(notificationId, builder.build())
    }

    fun finishNotificationForCustomCommands(
        notificationId: Int = DEFAULT_NOTIFICATION_ID,
        title: String? = null,
        text: String? = null,
    ) {
        //        notificationManager.cancel(notificationId)
        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_seal)
                .setContentText(text)
                .setProgress(0, 0, false)
                .setAutoCancel(true)
                .setOngoing(false)
                .setStyle(null)
        title?.let { builder.setContentTitle(title) }

        notificationManager.notify(notificationId, builder.build())
    }

    fun makeServiceNotification(intent: PendingIntent, text: String? = null): Notification {
        serviceNotification =
            NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_seal)
                .setContentTitle(context.getString(R.string.service_title))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(intent)
                .setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        return serviceNotification
    }

    fun updateServiceNotificationForPlaylist(index: Int, itemCount: Int) {
        serviceNotification =
            NotificationCompat.Builder(context, serviceNotification)
                .setContentTitle(context.getString(R.string.service_title) + " ($index/$itemCount)")
                .build()
        notificationManager.notify(SERVICE_NOTIFICATION_ID, serviceNotification)
    }

    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    fun notifyError(
        title: String,
        textId: Int = R.string.download_error_msg,
        notificationId: Int,
        report: String,
    ) {
        if (!NOTIFICATION.getBoolean()) return

        val intent =
            Intent()
                .setClass(context, NotificationActionReceiver::class.java)
                .putExtra(NOTIFICATION_ID_KEY, notificationId)
                .putExtra(ERROR_REPORT_KEY, report)
                .putExtra(ACTION_KEY, ACTION_ERROR_REPORT)

        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_ONE_SHOT or
                    PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent.FLAG_UPDATE_CURRENT,
            )
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_seal)
            .setContentTitle(title)
            .setContentText(context.getString(textId))
            .setOngoing(false)
            .addAction(
                R.drawable.outline_content_copy_24,
                context.getString(R.string.copy_error_report),
                pendingIntent,
            )
            .run {
                notificationManager.cancel(notificationId)
                notificationManager.notify(notificationId, build())
            }
    }

    fun makeNotificationForCustomCommand(
        notificationId: Int,
        taskId: String,
        progress: Int,
        text: String? = null,
        templateName: String,
        taskUrl: String,
    ) {
        if (!NOTIFICATION.getBoolean()) return

        val intent =
            Intent(context.applicationContext, NotificationActionReceiver::class.java)
                .putExtra(TASK_ID_KEY, taskId)
                .putExtra(NOTIFICATION_ID_KEY, notificationId)
                .putExtra(ACTION_KEY, ACTION_CANCEL_TASK)

        val pendingIntent =
            PendingIntent.getBroadcast(
                context.applicationContext,
                notificationId,
                intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )

        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_seal)
            .setContentTitle(
                "[${templateName}_${taskUrl}] " +
                    context.getString(R.string.execute_command_notification)
            )
            .setContentText(text)
            .setOngoing(true)
            .setProgress(PROGRESS_MAX, progress, progress == -1)
            .addAction(
                R.drawable.outline_cancel_24,
                context.getString(R.string.cancel),
                pendingIntent,
            )
            .run { notificationManager.notify(notificationId, build()) }
    }

    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }

    fun areNotificationsEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT <= 24) true
        else notificationManager.areNotificationsEnabled()
    }
}
