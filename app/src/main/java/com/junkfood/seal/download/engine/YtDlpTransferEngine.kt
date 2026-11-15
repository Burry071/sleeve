package com.junkfood.seal.download.engine

import android.os.Build
import android.util.Log
import com.junkfood.seal.App
import com.junkfood.seal.App.Companion.audioDownloadDir
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.App.Companion.videoDownloadDir
import com.junkfood.seal.R
import com.junkfood.seal.util.ArchiveIndex
import com.junkfood.seal.util.DownloadErrorMessages
import com.junkfood.seal.util.DownloadUtil.OUTPUT_TEMPLATE_CHAPTERS
import com.junkfood.seal.util.DownloadUtil.OUTPUT_TEMPLATE_CLIPS
import com.junkfood.seal.util.DownloadUtil.OUTPUT_TEMPLATE_SPLIT
import com.junkfood.seal.util.DownloadUtil.PLAYLIST_TITLE_SUBDIRECTORY_PREFIX
import com.junkfood.seal.util.FileUtil.getArchiveFile
import com.junkfood.seal.util.FileUtil.getExternalTempDir
import com.junkfood.seal.util.FileUtil.getSdcardTempDir
import com.junkfood.seal.util.M4bPlan
import com.junkfood.seal.util.isNumberInRange
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * The stock yt-dlp lane and the last rung of the ladder. Re-extracts on every
 * attempt with a rotating `player_client` set: the client that won the info
 * fetch may still be refused the media GET, so each failure steps down to
 * yt-dlp's own selection and then the no-JS "tv" rung.
 */
internal object YtDlpTransferEngine : TransferEngine {

    override suspend fun transfer(job: TransferJob): Result<List<String>>? {
        val videoInfo = job.videoInfo
        val settings = job.settings
        val url = job.url
        val downloadPreferences = job.preferences

        with(downloadPreferences) {
            // The download re-extracts, so the winning info-fetch client set
            // goes first. That win is no media win: SABR-era YouTube can 403
            // the media GET of a client that claimed the info fine
            // ("android_vr client https formats require a GVS PO Token",
            // "SABR-only streaming"). Any failure rotates down the ladder:
            // yt-dlp's own client selection next, then the no-JS "tv" rung.
            val downloadClientLadder: List<String?> =
                listOf(
                    ExtractionClients.get(job.taskId) ?: "android_vr,android,ios",
                    null,
                    "tv",
                )
            val downloadStartedAtMs = System.currentTimeMillis()
            // The export needs the exact file THIS attempt wrote, not a guess
            // from the folder. Asking yt-dlp for it is additive; the report is
            // a cache file read once and removed. Reports a crashed run left
            // behind are stale by definition.
            val m4bPlanned = M4bPlan.applies(videoInfo, downloadPreferences)
            for ((attemptIndex, clients) in downloadClientLadder.withIndex()) {
                if (!job.isActive()) return Result.failure(CancellationException("task canceled"))
                val processId = job.processIdFactory()
                job.onProcessStarted(processId)
                val request =
                    YoutubeDLRequest(url)
                        .maybeRedditUserAgent(url, settings.customUserAgentEnabled)
                if (clients != null) {
                    Log.i(
                        TRANSFER_TAG,
                        "download attempt ${attemptIndex + 1}: player_client=$clients",
                    )
                    request.addOption("--extractor-args", "youtube:player_client=$clients")
                } else {
                    Log.i(TRANSFER_TAG, "download attempt ${attemptIndex + 1}: yt-dlp default clients")
                }
                val pathBuilder = StringBuilder()
                val outputBuilder = StringBuilder()
                val pathReport =
                    if (m4bPlanned) M4bPlan.reportFile(context.cacheDir, job.taskId) else null
                pathReport?.delete()

                val attemptFailure =
                    request
                    .apply {
                        addOption("--no-mtime")
                        //                addOption("-v")
                        if (cookies) {
                            enableCookies(userAgentString)
                        }
                        if (galleryDirect) {
                            // Engine declined this direct item and we fell back to
                            // yt-dlp: without the page referer the generic
                            // extractor gets 403 from instagram CDN hosts.
                            val directReferer =
                                referer.ifBlank {
                                    if (
                                        url.contains("cdninstagram") || url.contains("fbcdn")
                                    ) {
                                        "https://www.instagram.com/"
                                    } else {
                                        ""
                                    }
                                }
                            if (directReferer.isNotBlank()) {
                                addOption("--add-headers", "Referer:$directReferer")
                            }
                        }
                        if (restrictFilenames) {
                            addOption("--restrict-filenames")
                        }
                        if (proxy) {
                            enableProxy(proxyUrl)
                        }
                        if (forceIpv4) {
                            addOption("-4")
                        }
                        if (debug) {
                            addOption("-v")
                        }
                        if (useDownloadArchive) {
                            val archiveFile = context.getArchiveFile()
                            if (
                                ArchiveIndex.contains(
                                    archiveFile,
                                    videoInfo.extractorKey,
                                    videoInfo.id,
                                )
                            ) {
                                // Returned, not thrown: this site has no
                                // surrounding try/catch, and a throw would
                                // escape the caller's onFailure and wedge the
                                // task in Running.
                                return Result.failure(
                                    DownloadErrorMessages.AlreadyDownloadedError(
                                        extractorKey = videoInfo.extractorKey,
                                        id = videoInfo.id,
                                        message = context.getString(R.string.download_archive_error),
                                    )
                                )
                            } else {
                                useDownloadArchive()
                            }
                        }

                        if (rateLimit && maxDownloadRate.isNumberInRange(1, 1000000)) {
                            addOption("-r", "${maxDownloadRate}K")
                        }

                        if (job.playlistItem != 0 && downloadPlaylist) {
                            addOption("--playlist-items", job.playlistItem)
                            if (subdirectoryPlaylistTitle && !videoInfo.playlist.isNullOrEmpty()) {
                                outputBuilder.append(PLAYLIST_TITLE_SUBDIRECTORY_PREFIX)
                            }
                            //                    addOption("--compat-options",
                            // "no-youtube-unavailable-videos")
                        } else {
                            addOption("--no-playlist")
                        }

                        if (aria2c) {
                            enableAria2c()
                        } else if (concurrentFragments > 1) {
                            addOption("--concurrent-fragments", concurrentFragments)
                        }

                        if (extractAudio || (videoInfo.vcodec == "none")) {
                            if (privateDirectory) pathBuilder.append(App.privateDownloadDir)
                            else pathBuilder.append(audioDownloadDir)
                            addOptionsForAudioDownloads(
                                id = videoInfo.id,
                                settings = settings,
                                playlistUrl = job.playlistUrl,
                            )
                        } else {
                            if (privateDirectory) pathBuilder.append(App.privateDownloadDir)
                            else pathBuilder.append(videoDownloadDir)
                            addOptionsForVideoDownloads(settings)
                        }
                        if (sponsorBlock) {
                            addOption("--sponsorblock-remove", sponsorBlockCategory)
                        }

                        if (createThumbnail) {
                            addOption("--write-thumbnail")
                            addOption("--convert-thumbnails", "png")
                        }
                        if (subdirectoryExtractor) {
                            pathBuilder.append("/${videoInfo.extractorKey}")
                        }

                        if (sdcard) {
                            addOption("-P", context.getSdcardTempDir(videoInfo.id).absolutePath)
                        } else {
                            addOption("-P", pathBuilder.toString())
                        }

                        videoClips.forEach {
                            addOption(
                                "--download-sections",
                                "*%d-%d".format(locale = Locale.US, it.start, it.end),
                            )
                        }
                        if (newTitle.isNotEmpty()) {
                            addCommands(listOf("--replace-in-metadata", "title", ".+", newTitle))
                        }
                        if (Build.VERSION.SDK_INT > 23 && !sdcard)
                            addOption("-P", "temp:" + getExternalTempDir())

                        if (splitByChapter) {
                            addOption("-o", OUTPUT_TEMPLATE_CHAPTERS)
                            addOption("--split-chapters")
                        }

                        // Generic/direct links make yt-dlp use the whole URL as
                        // the video id, so a template containing [%(id)s] (the
                        // default) produces 700-char filenames and Errno 36 on
                        // CDN shares. Trim the final name (extension preserved)
                        // at the source; 80 chars keeps even fullwidth names
                        // inside ext4's 255-byte component limit.
                        addOption("--trim-filenames", "80")

                        val output =
                            if (splitByChapter) {
                                OUTPUT_TEMPLATE_SPLIT
                            } else if (videoClips.isEmpty()) {
                                outputTemplate
                            } else {
                                OUTPUT_TEMPLATE_CLIPS
                            }

                        addOption("-o", outputBuilder.append(output).toString())

                        if (pathReport != null) {
                            addCommands(
                                listOf(
                                    "--print-to-file",
                                    "after_move:filepath",
                                    pathReport.absolutePath,
                                )
                            )
                        }

                        for (s in request.buildCommand()) Log.d(TRANSFER_TAG, s)
                    }
                    .runCatching {
                        YoutubeDL.getInstance()
                            .execute(
                                request = this,
                                processId = processId,
                                callback = job.progressCallback,
                            )
                    }
                    .exceptionOrNull()
                val produced =
                    pathReport?.let { report ->
                        val outDir =
                            if (sdcard) context.getSdcardTempDir(videoInfo.id)
                            else File(pathBuilder.toString())
                        M4bPlan.producedFrom(report, outDir)
                    }
                pathReport?.delete()
                if (attemptFailure == null) {
                    return onFinishDownloading(
                        preferences = downloadPreferences,
                        videoInfo = videoInfo,
                        downloadPath = pathBuilder.toString(),
                        sdcardUri = sdcardUri,
                        startedAtMs = downloadStartedAtMs,
                        produced = produced,
                        m4bTagOverrides = job.m4bTagOverrides,
                        isActive = job.isActive,
                        onNote = job.onNote,
                        onLocalDurationMs = job.onLocalDurationMs,
                    )
                }
                val failureMessage = attemptFailure.message.orEmpty()
                // Extraction success is no media success: the parallel info
                // call never touches the media host. Any rung-1/rung-2 failure
                // costs one more attempt; the next rung re-extracts fresh.
                if (attemptIndex < downloadClientLadder.lastIndex) {
                    Log.w(
                        TRANSFER_TAG,
                        "player_client=${clients ?: "<yt-dlp default>"} failure: " +
                            "$failureMessage; rotating extraction clients",
                    )
                    ExtractionClients.clear(job.taskId)
                    continue
                }
                if (
                    sponsorBlock &&
                        failureMessage.contains(
                            "Unable to communicate with SponsorBlock API",
                        )
                ) {
                    attemptFailure.printStackTrace()
                    return onFinishDownloading(
                        preferences = downloadPreferences,
                        videoInfo = videoInfo,
                        downloadPath = pathBuilder.toString(),
                        sdcardUri = sdcardUri,
                        startedAtMs = downloadStartedAtMs,
                        produced = produced,
                        m4bTagOverrides = job.m4bTagOverrides,
                        isActive = job.isActive,
                        onNote = job.onNote,
                        onLocalDurationMs = job.onLocalDurationMs,
                    )
                }
                Log.e(
                    TRANSFER_TAG,
                    "download failed on player_client=${clients ?: "<yt-dlp default>"}: " +
                        "$failureMessage",
                )
                return Result.failure(attemptFailure)
            }
            // Every rung returns above; kept for exhaustiveness.
            return Result.failure(IllegalStateException("download failed"))
        }
    }
}
