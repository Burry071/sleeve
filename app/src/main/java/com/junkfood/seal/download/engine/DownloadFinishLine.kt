package com.junkfood.seal.download.engine

import android.util.Log
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.R
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.download.CompletedNote
import com.junkfood.seal.util.AudioVerify
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.FileUtil.getFileName
import com.junkfood.seal.util.FileUtil.getSdcardTempDir
import com.junkfood.seal.util.FileUtil.moveFilesToSdcard
import com.junkfood.seal.util.M4bExportResult
import com.junkfood.seal.util.M4bPlan
import com.junkfood.seal.util.M4bTagOverrides
import com.junkfood.seal.util.M4bTags
import com.junkfood.seal.util.SplitHistoryPolicy
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.exportToM4b
import com.junkfood.seal.util.toHttpsUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The single finish line every lane goes through. Runs under
 * [NonCancellable]: once a lane has reported its file, the export, the
 * scratch rename, the source removal, the media scan and the insert form
 * one publish decision that a cancel must not tear in half.
 */
internal suspend fun onFinishDownloading(
    preferences: DownloadUtil.DownloadPreferences,
    videoInfo: VideoInfo,
    downloadPath: String,
    sdcardUri: String,
    startedAtMs: Long? = null,
    /** The exact file this lane wrote; null when the lane cannot name it. */
    produced: File? = null,
    m4bTagOverrides: M4bTagOverrides? = null,
    isActive: () -> Boolean = { true },
    onNote: (CompletedNote) -> Unit = {},
    onLocalDurationMs: (Long) -> Unit = {},
): Result<List<String>> =
    withContext(NonCancellable) {
        preferences.run {
            val fileName =
                (preferences.newTitle.ifEmpty {
                    videoInfo.filename
                        ?: videoInfo.requestedDownloads?.firstOrNull()?.filename
                        ?: videoInfo.title
                }).substringBefore('?').take(150)

            Log.d(TRANSFER_TAG, "onFinishDownloading: $fileName")

            val source = produced?.takeIf { it.isFile && it.length() > 0L }
            var exported: File? = null
            var exportPlan: M4bPlan? = null
            if (M4bPlan.applies(videoInfo, preferences)) {
                when {
                    // Planned, but the lane could not name its file: there
                    // is nothing to feed ffmpeg, and what landed is not the
                    // .m4b the user asked for.
                    source == null -> onNote(CompletedNote.M4B_NOT_EXPORTED)
                    source.extension.equals("m4b", ignoreCase = true) -> Unit
                    else -> {
                        val plan =
                            M4bPlan(
                                source = source,
                                chapters = videoInfo.chapters.orEmpty(),
                                tags =
                                    M4bTags.from(videoInfo, m4bTagOverrides).let { tags ->
                                        tags.copy(title = newTitle.ifEmpty { tags.title })
                                    },
                                copyAudio =
                                    source.extension.lowercase() == "m4a" ||
                                        source.extension.lowercase() == "mp4",
                                ownedDir = sdcard,
                            )
                        val result = exportToM4b(context, plan, isActive)
                        result.noteOnFailure()?.let(onNote)
                        when (result) {
                            is M4bExportResult.Verified -> {
                                exportPlan = plan
                                exported = result.out
                            }
                            M4bExportResult.Cancelled ->
                                return@run Result.failure(
                                    CancellationException("m4b export cancelled"),
                                )
                            M4bExportResult.Failed -> Unit
                        }
                    }
                }
            }

            // (b)+(c) The verified scratch becomes the published name, and
            // the source it replaces goes away. A rename that fails keeps
            // the source and downgrades the whole export to a note.
            val plan = exportPlan
            if (plan != null && exported != null) {
                if (plan.scratch.isFile && !plan.scratch.renameTo(exported)) {
                    Log.w(TRANSFER_TAG, "m4b: could not rename ${plan.scratch.name}")
                    plan.scratch.delete()
                    exported = null
                }
                if (exported != null && !exported.isFile) exported = null
                if (exported != null) {
                    source?.delete()
                } else {
                    onNote(CompletedNote.M4B_NOT_EXPORTED)
                }
            }

            // One local measurement, before any SD move: the Audible check
            // compares this instead of re-reading a file that the move may
            // already have taken away.
            val local = exported ?: source
            onLocalDurationMs(local?.let { AudioVerify.durationMs(context, it) } ?: 0L)

            // --split-chapters publishes one file per chapter and leaves the
            // unsplit master beside them. Remove it only once a chapter file
            // actually landed, so a split that produced nothing keeps the one
            // artifact that did.
            if (splitByChapter && removeSplitMaster(source, downloadPath, startedAtMs)) {
                Log.d(TRANSFER_TAG, "split: removed unsplit master ${source?.name}")
            }

            val savedFiles =
                if (sdcard) {
                    var exportUri: String? = null
                    moveFilesToSdcard(
                            sdcardUri = sdcardUri,
                            tempPath = context.getSdcardTempDir(videoInfo.id),
                            onCopied = { copiedName, destUri ->
                                if (copiedName == exported?.name) exportUri = destUri
                            },
                        )
                        .onSuccess { FileUtil.scanSdcardDocuments(it) }
                        .map { paths ->
                            val uri = exportUri
                            if (uri != null) listOf(uri) + paths.filter { it != uri } else paths
                        }
                } else {
                    Result.success(
                            FileUtil.scanFileToMediaLibraryPostDownload(
                                title = fileName,
                                downloadDir = downloadPath,
                                // The disk filename can fail the title match
                                // (--trim-filenames 80, sanitized quick-download
                                // titles): a title-only scan returns an empty list
                                // and the history insert never runs, silently
                                // dropping the completed task from Downloads.
                                // Files modified since the download started are
                                // the real match.
                                modifiedAfterMs = startedAtMs,
                                // Gallery/photo items are pictures, not thumbnail
                                // sidecars: keep them in the completed set.
                                keepImages = galleryDirect,
                            )
                        )
                        .map { paths ->
                            val target = exported?.absolutePath
                            if (target != null) listOf(target) + paths.filter { it != target }
                            else paths
                        }
                }
            savedFiles.fold(
                onSuccess = { paths ->
                    Log.d(TRANSFER_TAG, "history insert: ${paths.size} file(s) $paths")
                    when {
                        // Private mode deliberately keeps files out of history.
                        privateMode -> Result.success(emptyList())
                        // No output means the transfer did not really finish.
                        // Reporting success here would render the card as
                        // "Downloaded" with no file behind it.
                        paths.isEmpty() ->
                            Result.failure(
                                IllegalStateException(
                                    context.getString(R.string.download_error_msg)
                                )
                            )
                        splitByChapter ->
                            Result.success(
                                insertSplitChapterIntoHistory(
                                    videoInfo,
                                    SplitHistoryPolicy.chapterPaths(paths, source?.absolutePath),
                                )
                            )
                        else -> Result.success(insertInfoIntoDownloadHistory(videoInfo, paths))
                    }
                },
                onFailure = { Result.failure(it) },
            )
        }
    }

private val SPLIT_MEDIA_EXTENSIONS = setOf("mp4", "mkv", "webm", "m4a", "mp3", "opus", "ogg")

/**
 * Deletes the unsplit master of a chapter split, but only after a sibling
 * media file written by this download exists. Without a start time, or with
 * no sibling output, the master is the only proof the download landed, so it
 * stays.
 */
private fun removeSplitMaster(master: File?, downloadPath: String, startedAtMs: Long?): Boolean {
    if (master == null || !master.isFile || startedAtMs == null) return false
    val hasChapterOutput =
        File(downloadPath).walkTopDown().any { candidate ->
            candidate.isFile &&
                candidate.absolutePath != master.absolutePath &&
                candidate.extension.lowercase() in SPLIT_MEDIA_EXTENSIONS &&
                candidate.lastModified() >= startedAtMs
        }
    return hasChapterOutput && master.delete()
}

private fun insertInfoIntoDownloadHistory(
    videoInfo: VideoInfo,
    filePaths: List<String>,
): List<String> =
    filePaths.onEach {
        DatabaseUtil.insertInfo(videoInfo.toDownloadedVideoInfo(videoPath = it))
    }

private fun VideoInfo.toDownloadedVideoInfo(
    id: Int = 0,
    videoPath: String,
): DownloadedVideoInfo =
    this.run {
        DownloadedVideoInfo(
            id = id,
            videoTitle = title,
            videoAuthor = uploader ?: channel ?: uploaderId.toString(),
            videoUrl = webpageUrl ?: originalUrl.toString(),
            thumbnailUrl = thumbnail.toHttpsUrl(),
            videoPath = videoPath,
            extractor = extractorKey,
        )
    }

private fun insertSplitChapterIntoHistory(videoInfo: VideoInfo, filePaths: List<String>) =
    filePaths.onEach {
        DatabaseUtil.insertInfo(
            videoInfo.toDownloadedVideoInfo(videoPath = it).copy(videoTitle = it.getFileName())
        )
    }
