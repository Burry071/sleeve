package com.junkfood.seal.util

import android.content.Context
import android.util.Log
import com.junkfood.seal.download.CompletedNote
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Tags written into the exported `.m4b`; blank fields are simply omitted. */
data class M4bTags(
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val track: Int?,
) {
    companion object {
        /**
         * Catalogue fields win when the task carried a metadata track; the
         * extracted info fills the rest. `album` is never derived: a blank
         * album is an absent album, not a made-up one.
         */
        fun from(videoInfo: VideoInfo, overrides: M4bTagOverrides?): M4bTags =
            M4bTags(
                title = videoInfo.title,
                artist =
                    overrides?.artist?.takeIf { it.isNotBlank() }?.let(::tagArtist)
                        ?: tagArtist(videoInfo.uploader ?: videoInfo.channel),
                albumArtist = overrides?.albumArtist.orEmpty(),
                album = overrides?.album.orEmpty(),
                track = overrides?.track,
            )
    }
}

/**
 * What the download task knows that the extracted info does not: the catalogue
 * entry a metadata track was matched to.
 */
data class M4bTagOverrides(
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val track: Int? = null,
)

/** Drops the resolver's " (narrated by …)" suffix from a catalogue artist. */
fun tagArtist(raw: String?): String {
    val suffix = Regex("""\s*\(narrated by[^)]*\)\s*$""", RegexOption.IGNORE_CASE)
    return raw.orEmpty().replace(suffix, "").trim()
}

sealed interface M4bExportResult {
    /** The verified file the publish tail renames scratch onto, or adopts. */
    data class Verified(val out: File) : M4bExportResult

    /** The task was cancelled; the source is untouched and nothing is published. */
    data object Cancelled : M4bExportResult

    /** ffmpeg, verification or naming refused; the source stays as downloaded. */
    data object Failed : M4bExportResult

    /** The one user-visible consequence of a failed export. */
    fun noteOnFailure(): CompletedNote? =
        if (this is Failed) CompletedNote.M4B_NOT_EXPORTED else null
}

/**
 * One export's inputs. The plan is built in the download's finish line, after
 * the lane reported its exact output, and is confined to that output's folder.
 */
data class M4bPlan(
    val source: File,
    val chapters: List<Chapter>,
    val tags: M4bTags,
    /** Stream copy is lossless and cheap; anything else is re-encoded to AAC. */
    val copyAudio: Boolean,
    /**
     * True only for the SD-card staging folder, which the app owns and may
     * reuse. A pre-existing output is adopted there; in a shared folder it is
     * never touched and a free name is picked instead.
     */
    val ownedDir: Boolean,
) {
    val out: File = File(source.parentFile, source.nameWithoutExtension + ".m4b")
    val scratch: File = File(out.parentFile, out.name + ".tmp")

    /** `<base>.m4b`, else `<base> (2..100).m4b`; null when every name is taken. */
    fun freeOut(): File? {
        if (!out.exists()) return out
        for (index in 2..100) {
            val candidate = File(out.parentFile, "${source.nameWithoutExtension} ($index).m4b")
            if (!candidate.exists()) return candidate
        }
        return null
    }

    companion object {
        /** Task ids are raw URLs; only their digest is safe as a path component. */
        fun reportFile(cacheDir: File, taskId: String): File {
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest(taskId.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            return File(cacheDir, "m4b_report_${digest.take(24)}.txt")
        }

        /**
         * Whether the finished audio should become one chaptered audiobook:
         * the toggle is on, nothing else reshaped the file, the lane produced
         * audio, and the extractor found chapters.
         */
        fun applies(videoInfo: VideoInfo, preferences: DownloadPreferences): Boolean =
            preferences.exportM4b &&
                !preferences.splitByChapter &&
                preferences.videoClips.isEmpty() &&
                (preferences.extractAudio || videoInfo.vcodec == "none") &&
                !videoInfo.chapters.isNullOrEmpty()

        /**
         * Reads the path yt-dlp reported for this attempt. Exactly one absolute,
         * existing, regular file inside [outDir] is accepted: a multi-line
         * report, a relative path or anything outside the lane's folder is
         * refused rather than guessed at.
         */
        fun producedFrom(report: File, outDir: File): File? {
            if (!report.isFile) return null
            val result =
                runCatching {
                    val lines = report.readLines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (lines.size != 1) return null
                    val candidate = File(lines[0])
                    if (!candidate.isAbsolute || !candidate.isFile) return null
                    val root = outDir.canonicalFile
                    val path = candidate.canonicalFile
                    if (path == root || !path.path.startsWith(root.path + File.separator)) return null
                    path
                }
            return result.getOrNull()
        }
    }
}

/** Rough cost of the exported file, used only for the free-space pre-check. */
fun estimatedOutputBytes(source: File, copyAudio: Boolean, durationMs: Long): Long =
    if (copyAudio) {
        (source.length() * 1.05).toLong()
    } else {
        if (durationMs <= 0L) 0L
        else ((128_000L / 8L) * (durationMs / 1000L) * 1.15).toLong()
    }

/** Free bytes needed for the export scratch (the source is already on disk). */
internal fun requiredFreeBytesForExport(
    source: File,
    copyAudio: Boolean,
    durationMs: Long,
): Long = estimatedOutputBytes(source, copyAudio, durationMs)

/** Room for the export scratch; unknown free space refuses. */
fun hasRoomFor(dir: File, required: Long): Boolean =
    required <= 0L || dir.usableSpace >= required

private const val TAG = "M4bExport"
private const val TRANSCODE_BITRATE = "128k"
private const val FFMPEG_POLL_MS = 250L
private const val FFMPEG_TAIL_MAX_CHARS = 4096

internal sealed interface ProcessRunResult {
    data class Exited(val exitCode: Int, val outputTail: String) : ProcessRunResult

    data object Cancelled : ProcessRunResult
}

internal fun buildFfmpegArgs(
    ffmpegPath: String,
    sourcePath: String,
    chaptersPath: String,
    scratchPath: String,
    tags: M4bTags,
    copyAudio: Boolean,
): List<String> = buildList {
    add(ffmpegPath)
    add("-y")
    add("-i")
    add(sourcePath)
    add("-i")
    add(chaptersPath)
    add("-map")
    add("0:a:0")
    add("-c:a")
    if (copyAudio) {
        add("copy")
    } else {
        add("aac")
        add("-b:a")
        add(TRANSCODE_BITRATE)
    }
    add("-map_metadata")
    add("0")
    add("-map_metadata")
    add("1")
    add("-map_chapters")
    add("1")
    if (tags.title.isNotBlank()) {
        add("-metadata")
        add("title=${tags.title}")
    }
    if (tags.artist.isNotBlank()) {
        add("-metadata")
        add("artist=${tags.artist}")
    }
    if (tags.albumArtist.isNotBlank()) {
        add("-metadata")
        add("album_artist=${tags.albumArtist}")
    }
    if (tags.album.isNotBlank()) {
        add("-metadata")
        add("album=${tags.album}")
    }
    tags.track?.takeIf { it > 0 }?.let {
        add("-metadata")
        add("track=$it")
    }
    add("-f")
    add("ipod")
    add(scratchPath)
}

/**
 * Runs a child process while polling [isActive] on a bounded interval and
 * draining merged output into a bounded tail buffer on a daemon thread so pipe
 * buffers never deadlock and heap usage stays capped.
 */
internal fun runProcessCancellable(
    startProcess: () -> Process,
    isActive: () -> Boolean,
    pollIntervalMs: Long = FFMPEG_POLL_MS,
    maxTailChars: Int = FFMPEG_TAIL_MAX_CHARS,
): ProcessRunResult {
    if (!isActive()) return ProcessRunResult.Cancelled
    val proc = startProcess()
    val tailBuffer = StringBuilder()
    val reader =
        Thread(
            {
                runCatching {
                    proc.inputStream.bufferedReader().use { br ->
                        val chunk = CharArray(1024)
                        while (true) {
                            val read = br.read(chunk)
                            if (read < 0) break
                            synchronized(tailBuffer) {
                                tailBuffer.append(chunk, 0, read)
                                if (tailBuffer.length > maxTailChars) {
                                    tailBuffer.delete(0, tailBuffer.length - maxTailChars)
                                }
                            }
                        }
                    }
                }
            },
            "m4b-ffmpeg-drain",
        )
            .apply {
                isDaemon = true
                start()
            }

    var cancelled = false
    try {
        while (true) {
            if (!isActive()) {
                cancelled = true
                proc.destroyForcibly()
                return ProcessRunResult.Cancelled
            }
            if (proc.waitFor(pollIntervalMs, TimeUnit.MILLISECONDS)) {
                break
            }
        }
        reader.join(1_000L)
        val tail = synchronized(tailBuffer) { tailBuffer.toString() }
        return ProcessRunResult.Exited(proc.exitValue(), tail)
    } catch (t: Throwable) {
        if (t is InterruptedException) {
            Thread.currentThread().interrupt()
        }
        proc.destroyForcibly()
        throw t
    } finally {
        if (cancelled || proc.isAlive) {
            proc.destroyForcibly()
            runCatching { proc.waitFor(500L, TimeUnit.MILLISECONDS) }
        }
        runCatching { proc.inputStream.close() }
        runCatching { proc.errorStream.close() }
        runCatching { proc.outputStream.close() }
        reader.join(500L)
    }
}

/**
 * Runs ffmpeg with [copyAudio]; if stream copy fails (for example Opus inside
 * an `.m4a`/`.mp4` container which `-f ipod -c:a copy` rejects), retries once
 * by transcoding to AAC.
 */
internal fun runWithCopyFallback(
    copyAudio: Boolean,
    scratch: File,
    isActive: () -> Boolean,
    canFallback: () -> Boolean = { true },
    onCopyFallback: (ProcessRunResult.Exited) -> Unit = {},
    runAttempt: (useCopyAudio: Boolean) -> ProcessRunResult,
): ProcessRunResult {
    scratch.delete()
    val first = runAttempt(copyAudio)
    if (first is ProcessRunResult.Cancelled) {
        scratch.delete()
        return first
    }
    val firstExited = first as ProcessRunResult.Exited
    if (firstExited.exitCode == 0 && scratch.isFile && scratch.length() > 0L) {
        return firstExited
    }
    if (!isActive()) {
        scratch.delete()
        return ProcessRunResult.Cancelled
    }
    if (!copyAudio || !canFallback()) {
        scratch.delete()
        return firstExited
    }
    onCopyFallback(firstExited)
    scratch.delete()
    val fallback = runAttempt(false)
    if (fallback is ProcessRunResult.Cancelled) {
        scratch.delete()
    }
    return fallback
}

// Exports are mutually exclusive process-wide: each runs a full ffmpeg pass
// over a book-sized file, and two at once on a phone only contend for IO.
private val exportMutex = Mutex()

internal suspend fun runExportGuarded(
    isActive: () -> Boolean,
    body: suspend () -> M4bExportResult,
): M4bExportResult =
    exportMutex.withLock {
        // Acquired cancellably: a cancel while queued aborts the wait.
        if (!isActive()) return@withLock M4bExportResult.Cancelled
        // From here the export decides its own fate: cleanup and the single
        // publish decision must not be interrupted, so the outer task's
        // liveness is read through isActive() instead of the coroutine's.
        withContext(NonCancellable + Dispatchers.IO) {
            if (!isActive()) return@withContext M4bExportResult.Cancelled
            body()
        }
    }

/**
 * Writes `<base>.m4b.tmp` beside the source, verifies its length, and names
 * the file the publish tail may adopt. The source is never renamed or deleted
 * here: the caller owns that, after this returns Verified.
 */
suspend fun exportToM4b(
    context: Context,
    plan: M4bPlan,
    isActive: () -> Boolean,
): M4bExportResult {
    val callerJob = currentCoroutineContext()[Job]
    val exportActive = { isActive() && (callerJob?.isActive != false) }
    return runExportGuarded(exportActive) { exportNow(context, plan, exportActive) }
}

private fun exportNow(context: Context, plan: M4bPlan, isActive: () -> Boolean): M4bExportResult {
    if (!isActive()) return M4bExportResult.Cancelled
    val source = plan.source
    if (!source.isFile || source.length() <= 0L) return M4bExportResult.Failed
    val parentDir = source.parentFile ?: return M4bExportResult.Failed

    val sourceMs = AudioVerify.durationMs(context, source)
    val required = requiredFreeBytesForExport(source, plan.copyAudio, sourceMs)
    if (!hasRoomFor(parentDir, required)) {
        Log.w(TAG, "m4b: not enough free space for $required bytes")
        return M4bExportResult.Failed
    }

    // An interrupted earlier run can leave an export behind. In the folder the
    // app owns that file is the same book's finished export: adopting it saves
    // a second full ffmpeg pass.
    if (plan.ownedDir && plan.out.isFile && plan.out.length() > 0L) {
        val adoptedMs = AudioVerify.durationMs(context, plan.out)
        if (AudioVerify.canReplaceSource(sourceMs, adoptedMs)) {
            plan.scratch.delete()
            Log.i(TAG, "m4b: adopted ${plan.out.name}")
            return if (isActive()) M4bExportResult.Verified(plan.out)
            else M4bExportResult.Cancelled
        }
        Log.w(TAG, "m4b: not adopting ${plan.out.name} ($adoptedMs ms of $sourceMs ms)")
    }

    val ffmpeg =
        File(context.applicationInfo.nativeLibraryDir ?: return M4bExportResult.Failed, "libffmpeg.so")
            .takeIf { it.exists() } ?: return M4bExportResult.Failed

    var sidecar: File? = null
    var keptScratch = false
    plan.scratch.delete()
    try {
        val meta = File.createTempFile("m4b_chapters", ".txt", context.cacheDir)
        sidecar = meta
        meta.writeText(M4bMetadata.chaptersFile(plan.chapters, sourceMs))

        val runResult =
            runWithCopyFallback(
                copyAudio = plan.copyAudio,
                scratch = plan.scratch,
                isActive = isActive,
                canFallback = {
                    val fallbackRequired =
                        requiredFreeBytesForExport(source, copyAudio = false, durationMs = sourceMs)
                    hasRoomFor(parentDir, fallbackRequired).also { enough ->
                        if (!enough) {
                            Log.w(
                                TAG,
                                "m4b: not enough free space for AAC fallback ($fallbackRequired bytes)",
                            )
                        }
                    }
                },
                onCopyFallback = { failed ->
                    Log.w(
                        TAG,
                        "m4b: ffmpeg -c:a copy exited ${failed.exitCode}, retrying with AAC: ${failed.outputTail.takeLast(300)}",
                    )
                },
            ) { useCopy ->
                val args =
                    buildFfmpegArgs(
                        ffmpegPath = ffmpeg.absolutePath,
                        sourcePath = source.absolutePath,
                        chaptersPath = meta.absolutePath,
                        scratchPath = plan.scratch.absolutePath,
                        tags = plan.tags,
                        copyAudio = useCopy,
                    )
                runProcessCancellable(
                    startProcess = {
                        ProcessBuilder(args)
                            .apply {
                                // libc++_shared.so and the libav* libs live in the
                                // extracted ffmpeg package, the same place the metadata
                                // embed loads them from.
                                environment()["LD_LIBRARY_PATH"] =
                                    File(
                                        context.noBackupFilesDir,
                                        "youtubedl-android/packages/ffmpeg/usr/lib",
                                    )
                                        .absolutePath
                                redirectErrorStream(true)
                            }
                            .start()
                    },
                    isActive = isActive,
                )
            }

        when (runResult) {
            ProcessRunResult.Cancelled -> return M4bExportResult.Cancelled
            is ProcessRunResult.Exited -> {
                if (runResult.exitCode != 0 || !plan.scratch.isFile || plan.scratch.length() <= 0L) {
                    Log.w(
                        TAG,
                        "m4b: ffmpeg exit ${runResult.exitCode} ${runResult.outputTail.takeLast(300)}",
                    )
                    return M4bExportResult.Failed
                }
            }
        }

        val outMs = AudioVerify.durationMs(context, plan.scratch)
        if (!AudioVerify.canReplaceSource(sourceMs, outMs)) {
            Log.w(TAG, "m4b: rejected output ($outMs ms of $sourceMs ms)")
            return M4bExportResult.Failed
        }
        val target = plan.freeOut()
        if (target == null) {
            Log.w(TAG, "m4b: no free name for ${plan.out.name}")
            return M4bExportResult.Failed
        }
        if (!isActive()) return M4bExportResult.Cancelled
        keptScratch = true
        return M4bExportResult.Verified(target)
    } catch (e: Exception) {
        Log.w(TAG, "m4b: export failed: ${e.message}")
        return M4bExportResult.Failed
    } finally {
        sidecar?.delete()
        if (!keptScratch) plan.scratch.delete()
    }
}
