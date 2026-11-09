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
import com.junkfood.seal.R
import java.io.File
import java.io.IOException

const val AUDIO_REGEX = "(mp3|aac|opus|m4a)$"
const val THUMBNAIL_REGEX = "\\.(jpg|png)$"
const val SUBTITLE_REGEX = "\\.(lrc|vtt|srt|ass|json3|srv.|ttml)$"
private const val PRIVATE_DIRECTORY_SUFFIX = ".Seal"

// Engine scratch written next to the output: "<name>.tmp" and the
// "<name>.parts[-<tag>]" segment directory.
private val SCRATCH_FILE_REGEX = Regex(""".*\.(tmp|part)""", RegexOption.IGNORE_CASE)
private val SCRATCH_DIR_REGEX = Regex("""\.parts(-[0-9a-f]+)?[\\/]""")

object FileUtil {
    fun openFileFromResult(downloadResult: Result<List<String>>) {
        val filePaths = downloadResult.getOrNull()
        if (filePaths.isNullOrEmpty()) return
        openFile(filePaths.first()) {
            ToastUtil.makeToastSuspend(context.getString(R.string.file_unavailable))
        }
    }

    inline fun openFile(path: String, onFailureCallback: (Throwable) -> Unit) =
        path
            .runCatching {
                createIntentForOpeningFile(this)?.run { context.startActivity(this) }
                    ?: throw Exception()
            }
            .onFailure { onFailureCallback(it) }

    private fun createIntentForFile(path: String?): Intent? {
        if (path == null) return null

        val uri =
            path
                .runCatching {
                    DocumentFile.fromSingleUri(context, Uri.parse(path)).run {
                        if (this?.exists() == true) {
                            this.uri
                        } else if (File(this@runCatching).exists()) {
                            FileProvider.getUriForFile(
                                context,
                                context.getFileProvider(),
                                File(this@runCatching),
                            )
                        } else null
                    }
                }
                .getOrNull() ?: return null

        return Intent().apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            data = uri
        }
    }

    fun createIntentForOpeningFile(path: String?): Intent? =
        createIntentForFile(path)?.let {
            it.apply {
                action = (Intent.ACTION_VIEW)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

    fun createIntentForSharingFile(path: String?): Intent? =
        createIntentForFile(path)?.apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, data)
            val mimeType = data?.let { context.contentResolver.getType(it) } ?: "media/*"
            setDataAndType(this.data, mimeType)
            clipData = ClipData(null, arrayOf(mimeType), ClipData.Item(data))
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

    fun Context.getCookiesFile() = File(getConfigDirectory(), "cookies.txt")

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
