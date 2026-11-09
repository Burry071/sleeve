package com.junkfood.seal

import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.junkfood.seal.QuickDownloadActivity
import android.content.Intent
import android.annotation.SuppressLint
import android.app.Application
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.getSystemService
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.download.DownloaderV2Impl
import com.junkfood.seal.ui.page.download.HomePageViewModel
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.ui.page.settings.directory.Directory
import com.junkfood.seal.ui.page.settings.network.CookiesViewModel
import com.junkfood.seal.ui.page.videolist.VideoListViewModel
import com.junkfood.seal.util.ACTION_QUICK_CLIPBOARD
import com.junkfood.seal.util.AUDIO_DIRECTORY
import com.junkfood.seal.util.CLIPBOARD_QUICK_DOWNLOAD
import com.junkfood.seal.util.COMMAND_DIRECTORY
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.EXTRA_QUICK_NONCE
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.FileUtil.createEmptyFile
import com.junkfood.seal.util.FileUtil.getCookieFile
import com.junkfood.seal.util.FileUtil.getExternalDownloadDirectory
import com.junkfood.seal.util.FileUtil.getExternalPrivateDownloadDirectory
import com.junkfood.seal.util.INTERNAL_PREFS
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.junkfood.seal.util.QUICK_NONCE_KEY
import com.junkfood.seal.util.SDCARD_URI
import com.junkfood.seal.util.UpdateUtil
import com.junkfood.seal.util.VIDEO_DIRECTORY
import com.junkfood.seal.util.YT_DLP_VERSION
import com.tencent.mmkv.MMKV
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

private const val TAG = "App"

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        com.junkfood.seal.ghostengine.GhostEngine.init(this)

        startKoin {
            androidLogger()
            androidContext(this@App)
            modules(
                module {
                    single<DownloaderV2> { DownloaderV2Impl(androidContext()) }
                    viewModel { DownloadDialogViewModel(downloader = get()) }
                    viewModel { HomePageViewModel() }
                    viewModel { CookiesViewModel() }
                    viewModel { VideoListViewModel() }
                }
            )
        }

        context = applicationContext
        packageInfo =
            packageManager.run {
                if (Build.VERSION.SDK_INT >= 33)
                    getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                else getPackageInfo(packageName, 0)
            }
        applicationScope = CoroutineScope(SupervisorJob())

        clipboard = getSystemService()!!
        connectivityManager = getSystemService()!!

        publishQuickDownloadShortcut(this@App)

        applicationScope.launch((Dispatchers.IO)) {
            FileUtil.purgeStagedShareFiles()
            try {
                YoutubeDL.init(this@App)
                FFmpeg.init(this@App)
                Aria2c.init(this@App)
                DownloadUtil.getCookiesContentFromDatabase().getOrNull()?.let {
                    FileUtil.writeContentToFile(it, getCookieFile())
                }
                UpdateUtil.deleteOutdatedApk()
            } catch (th: Throwable) {
                withContext(Dispatchers.Main) { startCrashReportActivity(th) }
            }
        }

        videoDownloadDir = VIDEO_DIRECTORY.getString(getExternalDownloadDirectory().absolutePath)

        audioDownloadDir = AUDIO_DIRECTORY.getString(File(videoDownloadDir, "Audio").absolutePath)
        if (!PreferenceUtil.containsKey(COMMAND_DIRECTORY)) {
            COMMAND_DIRECTORY.updateString(videoDownloadDir)
        }
        // Downloads completed before the history-insert fix landed live only on
        // disk; restore their rows once the directories are resolved.
        DatabaseUtil.backfillDownloadHistoryFromDisk()
        if (Build.VERSION.SDK_INT >= 26) NotificationUtil.createNotificationChannel()

        Thread.setDefaultUncaughtExceptionHandler { _, e -> startCrashReportActivity(e) }
    }

    private fun startCrashReportActivity(th: Throwable) {
        th.printStackTrace()
        startActivity(
            Intent(this, CrashReportActivity::class.java)
                .setAction("$packageName.error_report")
                .apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("error_report", getVersionReport() + "\n" + th.stackTraceToString())
                }
        )
    }

    companion object {
        lateinit var clipboard: ClipboardManager
        lateinit var videoDownloadDir: String
        lateinit var audioDownloadDir: String
        lateinit var applicationScope: CoroutineScope
        lateinit var connectivityManager: ConnectivityManager
        lateinit var packageInfo: PackageInfo

        var isServiceRunning = false

        /** Tracks the STARTED half of the service: a bound-only service dies
         * with its binder, which froze the process mid-download whenever the
         * quick-download overlay closed before the transfer finished. */
        private var isServiceStarted = false

        private val connection =
            object : ServiceConnection {
                override fun onServiceConnected(className: ComponentName, service: IBinder) {
                    val binder = service as DownloadService.DownloadServiceBinder
                    isServiceRunning = true
                }

                override fun onServiceDisconnected(arg0: ComponentName) {}
            }

        fun startService() {
            val intent = Intent(context.applicationContext, DownloadService::class.java)
            if (!isServiceRunning) {
                context.applicationContext.bindService(
                    intent,
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }
            if (!isServiceStarted) {
                // Started + foreground: the process stays alive with no
                // activity on screen while tasks are in the queue.
                context.applicationContext.startService(intent)
                isServiceStarted = true
            }
        }

        private const val QUICK_SHORTCUT_ID = "quick-download"
        private const val CLIPBOARD_SHORTCUT_ID = "quick-clipboard"

        /**
         * Silent quick mode is nonce-gated: only Sleeve knows the value it hands
         * its own shortcuts, so other apps cannot mint a trusted launch. The
         * clipboard shortcut exists only while the preference is on. Re-publishing
         * on a nonce rotation revokes every intent handed out before it; pinned
         * copies then land on the confirm sheet instead of enqueuing.
         */
        fun publishQuickDownloadShortcut(context: Context, rotateNonce: Boolean = true): Boolean =
            runCatching {
                    val prefs =
                        context.getSharedPreferences(INTERNAL_PREFS, Context.MODE_PRIVATE)
                    val storedNonce = prefs.getString(QUICK_NONCE_KEY, null)
                    val nonce =
                        if (rotateNonce || storedNonce == null) UUID.randomUUID().toString()
                        else storedNonce
                    if (nonce != storedNonce)
                        prefs.edit().putString(QUICK_NONCE_KEY, nonce).apply()

                    val quickPushed =
                        ShortcutManagerCompat.pushDynamicShortcut(
                            context,
                            shortcutFor(
                                context = context,
                                id = QUICK_SHORTCUT_ID,
                                labelRes = R.string.quick_download_label,
                                intent =
                                    Intent(Intent.ACTION_SEND)
                                        .setClass(context, QuickDownloadActivity::class.java)
                                        .setType("text/plain")
                                        .putExtra(EXTRA_QUICK_DOWNLOAD, true)
                                        .putExtra(EXTRA_QUICK_NONCE, nonce),
                            ),
                        )

                    val clipboardOk =
                        if (CLIPBOARD_QUICK_DOWNLOAD.getBoolean()) {
                            ShortcutManagerCompat.pushDynamicShortcut(
                                context,
                                shortcutFor(
                                    context = context,
                                    id = CLIPBOARD_SHORTCUT_ID,
                                    labelRes = R.string.quick_clipboard_label,
                                    intent =
                                        Intent(ACTION_QUICK_CLIPBOARD)
                                            .setClass(
                                                context,
                                                QuickDownloadActivity::class.java,
                                            )
                                            .putExtra(EXTRA_QUICK_DOWNLOAD, true)
                                            .putExtra(EXTRA_QUICK_NONCE, nonce),
                                ),
                            )
                        } else {
                            ShortcutManagerCompat.removeDynamicShortcuts(
                                context,
                                listOf(CLIPBOARD_SHORTCUT_ID),
                            )
                            true
                        }
                    quickPushed && clipboardOk
                }
                .onFailure { Log.w(TAG, "shortcut publish failed: ${it.message}") }
                .getOrDefault(false)

        private fun shortcutFor(
            context: Context,
            id: String,
            labelRes: Int,
            intent: Intent,
        ): ShortcutInfoCompat =
            ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(context.getString(labelRes))
                .setLongLabel(context.getString(labelRes))
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(intent)
                .setLongLived(true)
                .build()

        fun stopService() {
            if (isServiceStarted) {
                isServiceStarted = false
                try {
                    context.applicationContext.stopService(
                        Intent(context.applicationContext, DownloadService::class.java),
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (!isServiceRunning) return
            try {
                isServiceRunning = false
                context.applicationContext.run { unbindService(connection) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val privateDownloadDir: String
            get() =
                getExternalPrivateDownloadDirectory().run {
                    createEmptyFile(".nomedia")
                    absolutePath
                }

        fun updateDownloadDir(uri: Uri, directoryType: Directory) {
            when (directoryType) {
                Directory.AUDIO -> {
                    val path = FileUtil.getRealPath(uri)
                    audioDownloadDir = path
                    PreferenceUtil.encodeString(AUDIO_DIRECTORY, path)
                }

                Directory.VIDEO -> {
                    val path = FileUtil.getRealPath(uri)
                    videoDownloadDir = path
                    PreferenceUtil.encodeString(VIDEO_DIRECTORY, path)
                }

                Directory.CUSTOM_COMMAND -> {
                    val path = FileUtil.getRealPath(uri)
                }

                Directory.SDCARD -> {
                    context.contentResolver?.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    PreferenceUtil.encodeString(SDCARD_URI, uri.toString())
                }
            }
        }

        fun getVersionReport(): String {
            val versionName = packageInfo.versionName
            val page = packageInfo
            val versionCode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    packageInfo.versionCode.toLong()
                }
            val release =
                if (Build.VERSION.SDK_INT >= 30) {
                    Build.VERSION.RELEASE_OR_CODENAME
                } else {
                    Build.VERSION.RELEASE
                }
            return StringBuilder()
                .append("App version: $versionName ($versionCode)\n")
                .append("Device information: Android $release (API ${Build.VERSION.SDK_INT})\n")
                .append("Supported ABIs: ${Build.SUPPORTED_ABIS.contentToString()}\n")
                .append("Yt-dlp version: ${YT_DLP_VERSION.getString()}\n")
                .toString()
        }

        fun isFDroidBuild(): Boolean = BuildConfig.FLAVOR == "fdroid"

        fun isDebugBuild(): Boolean = BuildConfig.DEBUG

        @SuppressLint("StaticFieldLeak") lateinit var context: Context
    }
}
