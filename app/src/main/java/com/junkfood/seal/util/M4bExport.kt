package com.junkfood.seal.util

import android.content.Context
import android.util.Log
import com.junkfood.seal.download.CompletedNote
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

/** Room for the source plus its export; unknown free space refuses. */
fun hasRoomFor(dir: File, required: Long): Boolean =
    required <= 0L || dir.usableSpace >= required

private const val TAG = "M4bExport"
private const val TRANSCODE_BITRATE = "128k"

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
): M4bExportResult = runExportGuarded(isActive) { exportNow(context, plan, isActive) }

private fun exportNow(context: Context, plan: M4bPlan, isActive: () -> Boolean): M4bExportResult {
    if (!isActive()) return M4bExportResult.Cancelled
    val source = plan.source
    if (!source.isFile || source.length() <= 0L) return M4bExportResult.Failed

    val sourceMs = AudioVerify.durationMs(context, source)
    val required = source.length() + estimatedOutputBytes(source, plan.copyAudio, sourceMs)
    if (!hasRoomFor(source.parentFile, required)) {
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

        val args =
            buildList {
                add(ffmpeg.absolutePath)
                add("-y")
                add("-i")
                add(source.absolutePath)
                add("-i")
                add(meta.absolutePath)
                add("-map")
                add("0:a:0")
                add("-c:a")
                if (plan.copyAudio) {
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
                val tags = plan.tags
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
                add(plan.scratch.absolutePath)
            }
        val proc =
            ProcessBuilder(args)
                .apply {
                    // libc++_shared.so and the libav* libs live in the
                    // extracted ffmpeg package, the same place the metadata
                    // embed loads them from.
                    environment()["LD_LIBRARY_PATH"] =
                        File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
                            .absolutePath
                    redirectErrorStream(true)
                }
                .start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0 || !plan.scratch.isFile || plan.scratch.length() <= 0L) {
            Log.w(TAG, "m4b: ffmpeg exit $code ${output.takeLast(300)}")
            return M4bExportResult.Failed
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
