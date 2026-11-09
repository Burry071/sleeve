package com.junkfood.seal.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.CheckResult
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.MainActivity
import com.junkfood.seal.R
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val AUDIO_REGEX = "(mp3|aac|opus|m4a)$"
const val THUMBNAIL_REGEX = "\\.(jpg|png)$"
const val SUBTITLE_REGEX = "\\.(lrc|vtt|srt|ass|json3|srv.|ttml)$"
private const val PRIVATE_DIRECTORY_SUFFIX = ".Seal"
private const val COOKIE_FILE_NAME = "cookies.txt"
private const val STAGED_SHARE_DIR = "share"
private const val STAGED_SHARE_KEEP = 2
private const val STAGED_SHARE_MAX_BYTES = 1L shl 30

// Engine scratch written next to the output: "<name>.tmp" and the
// "<name>.parts[-<tag>]" segment directory.
private val SCRATCH_FILE_REGEX = Regex(""".*\.(tmp|part)""", RegexOption.IGNORE_CASE)
private val SCRATCH_DIR_REGEX = Regex("""\.parts(-[0-9a-f]+)?[\\/]""")

enum class FileAction {
    OPEN,
    SHARE,
}

object FileUtil {
    suspend fun openFileFromResult(downloadResult: Result<List<String>>) {
        val filePaths = downloadResult.getOrNull()
        if (filePaths.isNullOrEmpty()) return
        openFile(filePaths.first()) {
            ToastUtil.makeToastSuspend(context.getString(R.string.file_unavailable))
        }
    }

    suspend fun openFile(path: String, onFailureCallback: (Throwable) -> Unit) {
        runCatching {
                createIntentForOpeningFile(path)?.run { context.startActivity(this) }
                    ?: throw IOException("no activity can open $path")
            }
            .onFailure {
                Log.w(TAG, "open failed for $path: ${it.message}")
                onFailureCallback(it)
            }
    }

    /**
     * Custom download folders live outside every declared provider root, so
     * FileProvider cannot address them. Rather than widening the roots (the
     * public Download tree is already reachable through a broad storage
     * permission), the file is copied into filesDir/share and only the copy is
     * exposed. That copy is the only sanctioned staging root.
     */
    private suspend fun createIntentForFile(path: String?, action: FileAction): Intent? {
        if (path == null) return null

        val parsed = Uri.parse(path)
        // SAF documents already carry their own grant; never copy those.
        if (parsed.scheme == "content") {
            val uri =
                DocumentFile.fromSingleUri(context, parsed)
                    ?.takeIf { it.exists() }
                    ?.uri
                    ?: return null
            return intentForUri(uri, action)
        }

        val file = File(path)
        if (!file.exists()) return null

        val uri = resolveProviderUri(file) ?: return null
        return intentForUri(uri, action)
    }

    private suspend fun resolveProviderUri(file: File): Uri? {
        runCatching { FileProvider.getUriForFile(context, context.getFileProvider(), file) }
            .onSuccess {
                return it
            }
            .onFailure {
                Log.d(TAG, "not under a provider root, staging for sharing: ${file.absolutePath}")
            }

        return withContext(Dispatchers.IO) {
            runCatching {
                    val staged = stageForSharing(file)
                    FileProvider.getUriForFile(context, context.getFileProvider(), staged)
                }
                .onFailure {
                    Log.e(TAG, "share staging failed for ${file.absolutePath}: ${it.message}")
                }
                .getOrNull()
        }
    }

    private fun intentForUri(uri: Uri, action: FileAction): Intent =
        Intent().apply {
            this.action =
                when (action) {
                    FileAction.OPEN -> Intent.ACTION_VIEW
                    FileAction.SHARE -> Intent.ACTION_SEND
                }
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            data = uri
            when (action) {
                FileAction.OPEN -> addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                FileAction.SHARE -> {
                    putExtra(Intent.EXTRA_STREAM, uri)
                    val mimeType = context.contentResolver.getType(uri) ?: "media/*"
                    setDataAndType(uri, mimeType)
                    clipData = ClipData(null, arrayOf(mimeType), ClipData.Item(uri))
                }
            }
        }

    suspend fun createIntentForOpeningFile(path: String?): Intent? =
        createIntentForFile(path, FileAction.OPEN)

    suspend fun createIntentForSharingFile(path: String?): Intent? =
        createIntentForFile(path, FileAction.SHARE)

    /** The tap must always do something: fall back to opening the app. */
    suspend fun createNotificationTapIntent(path: String?): Intent =
        createIntentForOpeningFile(path) ?: openAppIntent()

    private fun openAppIntent(): Intent =
        (
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?: Intent(context, MainActivity::class.java)
            )
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    private fun stageForSharing(source: File): File {
        // One directory per share so purge can drop a whole copy atomically.
        val dir =
            File(
                context.getStagedShareDirectory(),
                "${System.currentTimeMillis()}-${source.absolutePath.hashCode().toUInt().toString(16)}",
            )
        check(dir.mkdirs() || dir.isDirectory) { "cannot create ${dir.absolutePath}" }
        val target = File(dir, source.name)
        source.copyTo(target, overwrite = true)
        // Housekeeping never blocks the share that is already in flight.
        runCatching { purgeStagedShareFiles() }
            .onFailure { Log.w(TAG, "staged share purge failed: ${it.message}") }
        return target
    }

    fun Context.getStagedShareDirectory(): File = File(filesDir, STAGED_SHARE_DIR)

    fun purgeStagedShareFiles() {
        val dirs = context.getStagedShareDirectory().listFiles()?.toList() ?: return
        stagedShareDeletions(dirs).forEach {
            Log.d(TAG, "purging staged share copy ${it.name}")
            it.deleteRecursively()
        }
    }

    fun Context.getFileProvider() = "$packageName.provider"

    fun String.getFileSize(): Long =
        this.run {
            val length = File(this).length()
            if (length == 0L) DocumentFile.fromSingleUri(context, Uri.parse(this))?.length() ?: 0L
            else length
        }

    fun String.getFileName(): String =
        this.run {
            File(this).nameWithoutExtension.ifEmpty {
                DocumentFile.fromSingleUri(context, Uri.parse(this))?.name ?: "video"
            }
        }

    fun deleteFile(path: String) =
        path.runCatching {
            if (!File(path).delete()) DocumentFile.fromSingleUri(context, Uri.parse(this))?.delete()
        }

    @CheckResult
    fun scanFileToMediaLibraryPostDownload(
        title: String,
        downloadDir: String,
        modifiedAfterMs: Long? = null,
        keepImages: Boolean = false,
    ): List<String> {
        val candidates =
            File(downloadDir)
                .walkTopDown()
                .filter {
                    // A zero-byte file is a failed write, never a finished
                    // download; counting it would complete the task with no media.
                    it.isFile &&
                        it.length() > 0 &&
                        // Transfer scratch lives beside the output and its path
                        // also matches the title; only publish real media.
                        !it.name.matches(SCRATCH_FILE_REGEX) &&
                        !it.path.contains(SCRATCH_DIR_REGEX)
                }
                .toList()
        // Files written since the download started are the real output. The
        // title is only a fallback: matching on it alone also picks up older
        // downloads that happen to share the name.
        val fresh =
            if (modifiedAfterMs != null) candidates.filter { it.lastModified() >= modifiedAfterMs }
            else emptyList()
        return (if (fresh.isNotEmpty()) fresh else candidates.filter { it.absolutePath.contains(title) })
            .map { it.absolutePath }
            .toMutableList()
            .apply {
                MediaScannerConnection.scanFile(context, this.toList().toTypedArray(), null, null)
                // A thumbnail sidecar (.jpg/.png) must not be reported as the
                // download, but a gallery/photo item IS the image: dropping it
                // made the task fail after the file had already been written,
                // and the yt-dlp retry downloaded the same image twice.
                removeAll {
                    it.contains(Regex(SUBTITLE_REGEX)) ||
                        (!keepImages && it.contains(Regex(THUMBNAIL_REGEX)))
                }
            }
    }

    fun scanDownloadDirectoryToMediaLibrary(downloadDir: String) =
        File(downloadDir)
            .walkTopDown()
            .filter { it.isFile }
            .map { it.absolutePath }
            .run {
                MediaScannerConnection.scanFile(context, this.toList().toTypedArray(), null, null)
            }

    @CheckResult
    fun moveFilesToSdcard(tempPath: File, sdcardUri: String): Result<List<String>> {
        val copied = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var cause: Throwable? = null
        val destDir =
            runCatching {
                    Uri.parse(sdcardUri).run {
                        DocumentsContract.buildDocumentUriUsingTree(
                            this,
                            DocumentsContract.getTreeDocumentId(this),
                        )
                    }
                }
                .getOrElse { return Result.failure(it) }

        try {
            tempPath.walkTopDown().forEach { file ->
                if (file.isDirectory) return@forEach
                try {
                    val mimeType =
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "*/*"
                    val destUri =
                        DocumentsContract.createDocument(
                            context.contentResolver,
                            destDir,
                            mimeType,
                            file.name,
                        )
                            ?: run {
                                failed += file.name
                                return@forEach
                            }
                    val outputStream =
                        context.contentResolver.openOutputStream(destUri)
                            ?: run {
                                failed += file.name
                                return@forEach
                            }
                    file.inputStream().use { input ->
                        outputStream.use { output -> input.copyTo(output) }
                    }
                    copied.add(destUri.toString())
                } catch (e: Exception) {
                    failed += file.name
                    cause = cause ?: e
                }
            }
        } catch (e: Exception) {
            failed += tempPath.name
            cause = cause ?: e
        }

        val result = sdcardCopyResult(copied = copied, failed = failed, cause = cause)
        // The temp copy is the only intact copy when any file failed; deleting
        // it here is what turned a revoked tree URI into lost media.
        if (result.isSuccess) tempPath.deleteRecursively()
        return result
    }

    fun clearTempFiles(downloadDir: File): Int {
        var count = 0
        downloadDir.walkTopDown().forEach {
            if (it.isFile && !it.isHidden) {
                if (it.delete()) count++
            }
        }
        return count
    }

    fun Context.getConfigDirectory(): File = cacheDir

    fun Context.getConfigFile(suffix: String = "") = File(getConfigDirectory(), "config$suffix.txt")

    /**
     * The one cookie-file accessor. It lives in filesDir, not cacheDir: the
     * system may wipe the cache at any time, which would silently sign the
     * user out of every site they imported cookies for.
     */
    @Synchronized
    fun Context.getCookieFile(): File {
        val target = File(filesDir, COOKIE_FILE_NAME)
        val legacy = File(cacheDir, COOKIE_FILE_NAME)
        migrateLegacyCookieFile(legacy, target)
        // A failed copy keeps the old location working for this run.
        return if (target.exists() || !legacy.exists()) target else legacy
    }

    fun getExternalTempDir() =
        File(getExternalDownloadDirectory(), "tmp").apply {
            mkdirs()
            createEmptyFile(".nomedia")
        }

    fun Context.getSdcardTempDir(child: String?): File =
        getExternalTempDir().run { child?.let { resolve(it) } ?: this }

    fun Context.getArchiveFile(): File = filesDir.createEmptyFile("archive.txt").getOrThrow()

    fun Context.getInternalTempDir() = File(filesDir, "tmp")

    internal fun getExternalDownloadDirectory() =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Sleeve")
            .also { it.mkdir() }

    internal fun getExternalPrivateDownloadDirectory() =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            PRIVATE_DIRECTORY_SUFFIX,
        )

    fun File.createEmptyFile(fileName: String): Result<File> =
        this.runCatching {
                mkdirs()
                resolve(fileName).apply { this@apply.createNewFile() }
            }
            .onFailure { it.printStackTrace() }

    fun writeContentToFile(content: String, file: File): File = file.apply { writeText(content) }

    fun getRealPath(treeUri: Uri): String {
        val path: String = treeUri.path.toString()
        Log.d(TAG, path)
        if (!path.contains("primary:")) {
            ToastUtil.makeToast("This directory is not supported")
            return getExternalDownloadDirectory().absolutePath
        }
        val last: String = path.split("primary:").last()
        return Environment.getExternalStorageDirectory().absolutePath + "/$last"
    }

    private const val TAG = "FileUtil"
}

/**
 * Staged share copies are disposable copies of real downloads, but they cost
 * disk. The newest copy is always kept (deleting it would break the share that
 * just started); everything older is deleted once either the count or the
 * total-size budget is exceeded.
 */
internal fun stagedShareDeletions(
    staged: List<File>,
    keepCount: Int = STAGED_SHARE_KEEP,
    maxBytes: Long = STAGED_SHARE_MAX_BYTES,
): List<File> {
    if (staged.size <= 1) return emptyList()
    val deletions = mutableListOf<File>()
    var kept = 0
    var bytes = 0L
    staged.sortedByDescending { it.lastModified() }.forEach { entry ->
        val size = entry.recursiveSize()
        if (kept == 0 || (kept < keepCount && bytes + size <= maxBytes)) {
            kept++
            bytes += size
        } else {
            deletions += entry
        }
    }
    return deletions
}

private fun File.recursiveSize(): Long =
    if (isDirectory) walkTopDown().filter { it.isFile }.sumOf { it.length() } else length()

/**
 * One-time move of the cookie file out of cacheDir. The target wins when both
 * copies exist: after this change only the filesDir copy receives writes.
 * @return true when the legacy copy was moved.
 */
internal fun migrateLegacyCookieFile(legacy: File, target: File): Boolean {
    if (!legacy.exists()) return false
    if (target.exists()) {
        legacy.delete()
        return false
    }
    val staging = File(target.parentFile, "${target.name}.migrating")
    return runCatching {
            legacy.copyTo(staging, overwrite = true)
            if (!staging.renameTo(target)) {
                staging.delete()
                return@runCatching false
            }
            legacy.delete()
            true
        }
        .getOrElse {
            staging.delete()
            false
        }
}

/**
 * A partially copied batch is a failure: the task must not report "Downloaded"
 * when some files never reached the SD card. [copied] stays out of the success
 * value so callers cannot mistake a partial move for a complete one.
 */
internal fun sdcardCopyResult(
    copied: List<String>,
    failed: List<String>,
    cause: Throwable? = null,
): Result<List<String>> =
    if (failed.isEmpty()) {
        Result.success(copied)
    } else {
        Result.failure(
            IOException(
                "SD card copy failed for ${failed.size} of ${copied.size + failed.size} file(s): " +
                    failed.joinToString(", "),
                cause,
            )
        )
    }
