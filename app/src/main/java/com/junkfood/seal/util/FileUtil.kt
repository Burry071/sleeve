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
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val AUDIO_REGEX = "(mp3|m4a|m4b|aac|opus)$"
const val THUMBNAIL_REGEX = "\\.(jpg|png)$"
const val SUBTITLE_REGEX = "\\.(lrc|vtt|srt|ass|json3|srv.|ttml)$"
private const val PRIVATE_DIRECTORY_SUFFIX = ".Seal"
private const val COOKIE_FILE_NAME = "cookies.txt"
private const val STAGED_SHARE_DIR = "share"
private const val STAGED_SHARE_KEEP = 2
private const val STAGED_SHARE_MAX_BYTES = 1L shl 30
private const val SCRATCH_SUFFIX = ".part"
private const val MAX_FREE_NAMES = 100
private const val MTIME_TOLERANCE_MS = 2_000L
private const val HEX_CHARS = "0123456789abcdef"

// Engine scratch written next to the output: "<name>.tmp" and the
// "<name>.parts[-<tag>]" segment directory.
private val SCRATCH_FILE_REGEX = Regex(""".*\.(tmp|part)""", RegexOption.IGNORE_CASE)
private val SCRATCH_DIR_REGEX = Regex("""\.parts(-[0-9a-f]+)?[\\/]""")

enum class FileAction {
    OPEN,
    SHARE,
}

/**
 * What the app knows about cookies.txt without exposing a single cookie value.
 * [usable] is the only question the recovery copy asks: a file that exists but
 * cannot be read as Netscape cookies cannot un-block a login wall.
 */
data class CookieFileStatus(
    val exists: Boolean,
    val entryCount: Int,
    val parseable: Boolean,
) {
    val usable: Boolean get() = exists && parseable && entryCount > 0

    companion object {
        /** No file at all: neither readable nor countable. */
        val Missing = CookieFileStatus(exists = false, entryCount = 0, parseable = false)

        /**
         * Pure: one pass over the file's lines. Netscape rows have exactly
         * seven tab-separated fields. Blank lines and comments are skipped,
         * `#HttpOnly_` is a domain prefix rather than a comment, and any other
         * malformed row makes the whole file unparseable — a file with one bad
         * row is one yt-dlp refuses.
         */
        fun fromLines(lines: List<String>): CookieFileStatus {
            var entries = 0
            var malformed = false
            for (raw in lines) {
                val line = raw.removePrefix("\uFEFF").trimEnd('\r')
                if (line.isBlank()) continue
                if (line.startsWith("#") && !line.startsWith("#HttpOnly_")) continue
                val fields = line.split('\t')
                if (fields.size == 7 && fields[0].isNotBlank()) entries++ else malformed = true
            }
            return CookieFileStatus(
                exists = true,
                entryCount = entries,
                parseable = !malformed && entries > 0,
            )
        }
    }
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
            val document =
                DocumentFile.fromSingleUri(context, parsed)?.takeIf { it.exists() } ?: return null
            return intentForUri(document.uri, action, document.name)
        }

        val file = File(path)
        if (!file.exists()) return null

        val uri = resolveProviderUri(file) ?: return null
        return intentForUri(uri, action, file.name)
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

    private fun intentForUri(uri: Uri, action: FileAction, name: String? = null): Intent =
        Intent().apply {
            this.action =
                when (action) {
                    FileAction.OPEN -> Intent.ACTION_VIEW
                    FileAction.SHARE -> Intent.ACTION_SEND
                }
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            when (action) {
                FileAction.OPEN -> {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // A SAF document's own MIME type comes back as
                    // application/octet-stream for .m4b; the known override
                    // wins so players and viewers claim the intent.
                    val type = mimeTypeFor(name)
                    if (type != null) setDataAndType(uri, type) else data = uri
                }
                FileAction.SHARE -> {
                    putExtra(Intent.EXTRA_STREAM, uri)
                    val mimeType =
                        mimeTypeFor(name) ?: context.contentResolver.getType(uri) ?: "media/*"
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
            if (startsWith("content://")) {
                DocumentFile.fromSingleUri(context, Uri.parse(this))?.length() ?: 0L
            } else {
                val length = File(this).length()
                if (length == 0L) DocumentFile.fromSingleUri(context, Uri.parse(this))?.length() ?: 0L
                else length
            }
        }

    fun String.getFileName(): String =
        this.run {
            if (startsWith("content://")) {
                val docName = DocumentFile.fromSingleUri(context, Uri.parse(this))?.name
                docName?.substringBeforeLast('.')?.ifEmpty { docName } ?: "video"
            } else {
                File(this).nameWithoutExtension.ifEmpty {
                    DocumentFile.fromSingleUri(context, Uri.parse(this))?.name ?: "video"
                }
            }
        }

    fun deleteFile(path: String) =
        path.runCatching {
            if (startsWith("content://")) {
                DocumentFile.fromSingleUri(context, Uri.parse(this))?.delete()
            } else if (!File(this).delete()) {
                DocumentFile.fromSingleUri(context, Uri.parse(this))?.delete()
            } else {
                true
            }
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
            if (modifiedAfterMs != null) {
                // FAT32/FUSE filesystems truncate mtimes to 1–2 s granularity,
                // so a file written right after modifiedAfterMs can report an
                // earlier lastModified() timestamp.
                val cutoffMs = (modifiedAfterMs - MTIME_TOLERANCE_MS).coerceAtLeast(0L)
                candidates.filter { it.lastModified() >= cutoffMs }
            } else {
                emptyList()
            }
        val media =
            (if (fresh.isNotEmpty()) fresh else candidates.filter { it.absolutePath.contains(title) })
                // A thumbnail sidecar (.jpg/.png) must not be reported as the
                // download, but a gallery/photo item IS the image: dropping it
                // made the task fail after the file had already been written,
                // and the yt-dlp retry downloaded the same image twice.
                .filterNot {
                    it.path.contains(Regex(SUBTITLE_REGEX)) ||
                        (!keepImages && it.path.contains(Regex(THUMBNAIL_REGEX)))
                }
                .map { it.absolutePath }
        // Only what this download produced reaches the gallery. Scanning first
        // also indexed the sidecars, and any older file the walk picked up.
        MediaScannerConnection.scanFile(context, media.toTypedArray(), null, null)
        return media
    }

    /**
     * Scans only the exact paths named by a completed transfer, avoiding a
     * shared-folder walk that could pick up concurrent downloads.
     */
    fun scanFilesToMediaLibrary(paths: List<String>) {
        if (paths.isNotEmpty()) {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        }
    }

    /**
     * Publish documents just moved onto the shared volume. The move goes through
     * SAF, so media written that way is invisible to the gallery until the
     * provider's document id is mapped back to a path and scanned.
     */
    fun scanSdcardDocuments(documents: List<String>) {
        val paths = documents.mapNotNull { SafDocumentPaths.externalStoragePath(it) }
        if (paths.isNotEmpty()) {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
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

    /**
     * Move a finished download onto the chosen SD-card tree.
     *
     * Every file is written to a `<name>.part` scratch document, byte-counted,
     * and only then renamed into its final name; [onCopied] reports the name
     * the file ended up with, because the final name can differ from the temp
     * one. A file that never verifies is failed, never reported as copied.
     */
    @CheckResult
    fun moveFilesToSdcard(
        tempPath: File,
        sdcardUri: String,
        onCopied: (sourceName: String, destUri: String) -> Unit = { _, _ -> },
        keepImages: Boolean = false,
    ): Result<List<String>> {
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
                .getOrElse {
                    return Result.failure(
                        DownloadErrorMessages.StorageMoveError(
                            copied = 0,
                            failed = listOf(tempPath.name),
                            retainedDir = tempPath.absolutePath,
                            cause = it,
                        )
                    )
                }

        try {
            tempPath.walkTopDown().forEach { file ->
                if (file.isDirectory) return@forEach
                // An empty file is a failed write, not a download; a scratch
                // name or sidecar belongs to another pass / metadata and must
                // not be published into Room history as a media row.
                if (
                    file.length() == 0L ||
                        file.name.matches(SCRATCH_FILE_REGEX) ||
                        file.path.contains(SCRATCH_DIR_REGEX) ||
                        isSdcardSidecar(file.name, keepImages)
                ) {
                    return@forEach
                }
                val destUri =
                    try {
                        copyVerified(file, destDir)
                    } catch (e: Exception) {
                        cause = cause ?: e
                        null
                    }
                if (destUri == null) {
                    failed += file.name
                } else {
                    copied += destUri.toString()
                    onCopied(file.name, destUri.toString())
                }
            }
        } catch (e: Exception) {
            failed += tempPath.name
            cause = cause ?: e
        }

        val result =
            sdcardCopyResult(
                copied = copied,
                failed = failed,
                cause = cause,
                retainedDir = tempPath.absolutePath,
            )
        // The temp copy is the only intact copy when any file failed; deleting
        // it here is what turned a revoked tree URI into lost media.
        if (result.isSuccess) tempPath.deleteRecursively()
        return result
    }

    /**
     * A provider can reject a document even when the source filesystem allowed
     * the name, so a verified-copy failure is retried once under a name with
     * the reserved characters replaced.
     */
    private fun copyVerified(file: File, dir: Uri): Uri? {
        copyToDocument(context, dir, file, file.name)?.let {
            return it
        }
        val sanitized = sanitizeSegment(file.name)
        if (sanitized == file.name) return null
        return copyToDocument(context, dir, file, sanitized)
    }

    /** Writes one scratch document, verifies its bytes, and renames it. */
    private fun copyToDocument(context: Context, dir: Uri, file: File, name: String): Uri? {
        val resolver = context.contentResolver
        // Typed from the final name, never the ".part" scratch: no MIME entry
        // exists for that suffix, and every document would become "*/*".
        val scratch =
            DocumentsContract.createDocument(
                resolver,
                dir,
                documentMimeType(name),
                "$name$SCRATCH_SUFFIX",
            ) ?: return null
        var published: Uri? = null
        try {
            val outputStream = resolver.openOutputStream(scratch) ?: return null
            file.inputStream().use { input ->
                outputStream.use { output -> input.copyTo(output) }
            }
            // A provider that drops bytes is indistinguishable from a short
            // download, so the count has to match before the name is published.
            if (!isVerifiedCopy(file.length(), destinationLength(context, scratch))) return null
            val finalName = freeName(context, dir, name)
            published = DocumentsContract.renameDocument(resolver, scratch, finalName)
            return published
        } finally {
            if (published == null) {
                runCatching { DocumentsContract.deleteDocument(resolver, scratch) }
            }
        }
    }

    /**
     * `createDocument` silently appends " (1)" on a collision, but
     * `renameDocument` onto a taken name is provider-defined, so the target
     * name is probed first. At 100 occupied names it gives up rather than risk
     * overwriting a document it did not create.
     */
    private fun freeName(context: Context, dir: Uri, name: String): String {
        val parent = DocumentFile.fromTreeUri(context, dir) ?: return name
        if (parent.findFile(name) == null) return name
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        for (suffix in 2..MAX_FREE_NAMES) {
            val candidate = "$base ($suffix)$extension"
            if (parent.findFile(candidate) == null) return candidate
        }
        throw IllegalStateException("no free document name for $name")
    }

    private fun documentMimeType(name: String): String =
        mimeTypeFor(name)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(name).extension)
            ?: "*/*"

    /**
     * Some providers refuse to report a size for a document they just wrote,
     * so a missing size is measured by reading the bytes back.
     */
    private fun destinationLength(context: Context, uri: Uri): Long {
        val resolver = context.contentResolver
        runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.statSize
                }
            }
            .getOrNull()
            ?.takeIf { it > 0 }
            ?.let {
                return it
            }
        return runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                    }
                    total
                } ?: -1L
            }
            .getOrDefault(-1L)
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

    /**
     * Reads the cookie file as it is right now. Never cached: the file is
     * rewritten at every start and replaced whenever profiles are saved, so a
     * cached answer would outlive the file it describes. Safe to call off the
     * main thread only.
     */
    fun Context.readCookieStatus(): CookieFileStatus {
        val file = getCookieFile()
        if (!file.isFile) return CookieFileStatus.Missing
        return runCatching { CookieFileStatus.fromLines(file.readLines()) }
            .getOrElse { CookieFileStatus(exists = true, entryCount = 0, parseable = false) }
    }

    fun getExternalTempDir() =
        File(getExternalDownloadDirectory(), "tmp").apply {
            mkdirs()
            createEmptyFile(".nomedia")
        }

    fun Context.getSdcardTempDir(child: String?): File =
        getExternalTempDir().run { child?.let { resolve(sdcardTempChildName(it)) } ?: this }

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
    retainedDir: String = "",
): Result<List<String>> =
    if (failed.isEmpty()) {
        Result.success(copied)
    } else {
        Result.failure(
            DownloadErrorMessages.StorageMoveError(
                copied = copied.size,
                failed = failed,
                retainedDir = retainedDir,
                cause = cause,
            )
        )
    }

/**
 * Replace what a storage provider is allowed to refuse. Defaults change
 * nothing else: a name that is already legal must survive untouched, so the
 * collapse/trim flags are opt-in and the trailing dot is left alone.
 */
private val ILLEGAL_NAME_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
private val WHITESPACE_RUN = Regex("\\s+")

private val SIDECAR_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
private val SIDECAR_META_EXTENSIONS =
    setOf("vtt", "srt", "ass", "lrc", "ytdl", "json", "description")

/**
 * True when [fileName] is a yt-dlp sidecar (thumbnail, subtitle, or metadata
 * artifact) that must not be published into Room download history as a video row.
 */
internal fun isSdcardSidecar(fileName: String, keepImages: Boolean = false): Boolean {
    val dot = fileName.lastIndexOf('.')
    if (dot < 0 || dot == fileName.lastIndex) return false
    val ext = fileName.substring(dot + 1).lowercase()
    if (ext in SIDECAR_META_EXTENSIONS) return true
    if (!keepImages && ext in SIDECAR_IMAGE_EXTENSIONS) return true
    return false
}

/**
 * Deterministic, traversal-proof directory name for a per-download SD-card
 * staging folder. Extractor ids from generic/direct links can carry slashes or
 * `..`, so hashing keeps the folder flat and FAT32-legal.
 */
internal fun sdcardTempChildName(child: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(child.toByteArray(Charsets.UTF_8))
    val hex = StringBuilder(16)
    for (i in 0 until 8) {
        val b = digest[i].toInt() and 0xFF
        hex.append(HEX_CHARS[b ushr 4])
        hex.append(HEX_CHARS[b and 0x0F])
    }
    return hex.toString()
}

internal fun sanitizeSegment(
    raw: String,
    maxLength: Int = 120,
    collapseWhitespace: Boolean = false,
    trimWhitespace: Boolean = false,
): String {
    var value = raw.replace(ILLEGAL_NAME_CHARS, "_")
    if (collapseWhitespace) value = value.replace(WHITESPACE_RUN, " ")
    if (trimWhitespace) value = value.trim()
    value = value.take(maxLength)
    val trimmed = value.trim()
    if (trimmed == "." || trimmed == "..") {
        value = value.replace('.', '_')
    }
    return value
}

/** A copy is complete only when the destination holds every source byte. */
internal fun isVerifiedCopy(sourceLength: Long, destinationLength: Long): Boolean =
    sourceLength > 0 && sourceLength == destinationLength

/** Extensions Android's MIME table has no usable entry for. */
internal fun mimeTypeFor(name: String?): String? {
    val extension = name?.substringAfterLast('.', "")?.lowercase() ?: return null
    return when (extension) {
        "m4b" -> "audio/mp4"
        else -> null
    }
}

private val SHARE_MEDIA_EXTENSIONS =
    AUDIO_FILE_EXTENSIONS +
        setOf("m4b", "mp4", "m4v", "webm", "mkv", "mov", "avi", "3gp", "ts", "flv")

/**
 * The file "Share kept file" should open when the move failed: a real media
 * file still sitting in [retainedDir]. It never points at the directory
 * itself, a scratch or `.ytdl` leftover, or a hidden file, because sharing
 * any of those gives the user nothing.
 */
internal fun retainedShareCandidate(
    retainedDir: String,
    failed: List<String>,
    exists: (String) -> Boolean = { name -> File(retainedDir, name).isFile },
    listing: () -> List<String> = {
        File(retainedDir).listFiles()?.map { it.name }.orEmpty()
    },
): String? {
    val dirName = File(retainedDir).name

    fun usable(name: String): Boolean =
        name.isNotEmpty() &&
            name != dirName &&
            !name.startsWith(".") &&
            !name.endsWith(".ytdl", ignoreCase = true) &&
            !name.matches(SCRATCH_FILE_REGEX) &&
            exists(name)

    fun shareable(name: String): Boolean =
        usable(name) && name.substringAfterLast('.', "").lowercase() in SHARE_MEDIA_EXTENSIONS

    val name =
        failed.firstOrNull(::shareable)
            ?: listing().firstOrNull(::shareable)
            ?: failed.firstOrNull(::usable)
            ?: return null
    return "${retainedDir.trimEnd('/')}/$name"
}
