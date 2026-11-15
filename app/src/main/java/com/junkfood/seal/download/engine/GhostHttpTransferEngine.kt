package com.junkfood.seal.download.engine

import android.util.Log
import com.junkfood.seal.App
import com.junkfood.seal.App.Companion.audioDownloadDir
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.App.Companion.videoDownloadDir
import com.junkfood.seal.ghostengine.GhostEngine
import com.junkfood.seal.ghostengine.GhostEngine.TaskState
import com.junkfood.seal.util.FileUtil.getSdcardTempDir
import com.junkfood.seal.util.RedditResolver
import com.junkfood.seal.util.sanitizeSegment
import com.yausername.youtubedl_android.YoutubeDLException
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Ghost engine handoff (Ghost Downloader ADR-0007 pattern): yt-dlp extracts,
 * the embedded engine transfers the bytes. Eligible only when the selected
 * format is a single progressive HTTP stream and nothing requires yt-dlp's
 * post-processing; anything else fails open to the stock path.
 */
internal object GhostHttpTransferEngine : TransferEngine {

    /** processId -> embedded-engine task id, for cancellation routing. */
    private val ghostTasks = ConcurrentHashMap<String, String>()

    /** @return true when an engine task was registered for this id and cancelled. */
    fun cancel(taskId: String): Boolean =
        ghostTasks.remove(taskId)?.also { GhostEngine.cancel(it) } != null

    /** @return null when this download was NOT handled (caller falls back). */
    override suspend fun transfer(job: TransferJob): Result<List<String>>? {
        val preferences = job.preferences
        val videoInfo = job.videoInfo
        val taskId = job.taskId

        if (!job.settings.engineEnabled && !preferences.galleryDirect) {
            Log.d(TRANSFER_TAG, "Ghost handoff skipped: engine disabled")
            return null
        }
        if (preferences.galleryDirect) {
            Log.d(TRANSFER_TAG, "Ghost handoff: gallery direct item")
        }
        Log.d(TRANSFER_TAG, "Ghost handoff check: taskId=$taskId formatIdString=${preferences.formatIdString}")

        val eligibleFormat =
            with(preferences) {
                if (downloadPlaylist ||
                        extractAudio ||
                        convertAudio ||
                        sponsorBlock ||
                        createThumbnail ||
                        cropArtwork ||
                        embedThumbnail ||
                        downloadSubtitle ||
                        embedSubtitle ||
                        keepSubtitle ||
                        autoSubtitle ||
                        autoTranslatedSubtitles ||
                        convertSubtitle != 0 ||
                        aria2c ||
                        rateLimit ||
                        cookies ||
                        useDownloadArchive ||
                        videoClips.isNotEmpty() ||
                        splitByChapter
                ) {
                    Log.d(TRANSFER_TAG, "Ghost handoff skipped: yt-dlp post-processing requested")
                    return null
                }
                if (formatIdString.contains('+')) {
                    Log.d(TRANSFER_TAG, "Ghost handoff skipped: merged video+audio formats")
                    return null
                }

                val formats =
                    (videoInfo.requestedFormats.orEmpty() + videoInfo.formats.orEmpty())
                        .filter { !it.url.isNullOrBlank() }
                        .distinctBy { it.formatId to it.url }
                Log.d(
                    TRANSFER_TAG,
                    "Ghost handoff formats: ids=${formats.map { it.formatId }} requestedIds=${videoInfo.requestedFormats?.map { it.formatId }}",
                )
                val candidate =
                    if (formatIdString.isNotEmpty())
                        formats.firstOrNull { it.formatId == formatIdString }
                            ?: formats.firstOrNull {
                                it.formatId != null && formatIdString.startsWith(it.formatId!!)
                            }
                    else formats.singleOrNull()
                if (candidate == null) {
                    Log.d(TRANSFER_TAG, "Ghost handoff skipped: no matching single format")
                    return null
                }
                // A single selected format is the exact file yt-dlp would save —
                // unknown codecs (generic extractor reports acodec=null) are fine.

                val url = candidate.url!!
                if (!isSafeRemoteUrl(url)) {
                    Log.d(TRANSFER_TAG, "Ghost handoff skipped: unsafe url")
                    return null
                }
                val lower = url.lowercase()
                if (listOf("m3u8", "mpd", "manifest", "hls", "dash").any { it in lower }) {
                    Log.d(TRANSFER_TAG, "Ghost handoff skipped: segmented manifest url")
                    return null
                }
                candidate
            }

        val ext = eligibleFormat.ext?.takeIf { it.isNotBlank() } ?: "bin"
        val baseName =
            preferences.newTitle.ifEmpty { videoInfo.filename ?: videoInfo.title }
                .ifBlank { "download" }
                .let { sanitizeSegment(it) }
        val downloadDir =
            ghostOutputDir(
                sdcardStagingDir =
                    if (preferences.sdcard) context.getSdcardTempDir(videoInfo.id) else null,
                privateDirectory = preferences.privateDirectory,
                privateDir = App.privateDownloadDir,
                audioDir = audioDownloadDir,
                videoDir = videoDownloadDir,
                isAudioOnly = eligibleFormat.isAudioOnly(),
            )
        // The engine writes atomically with a rename, which fails with EPERM
        // when the destination already exists and belongs to another install
        // (shared Download/Sleeve keeps files across app re-installs). Pick a
        // free name instead of overwriting, the same way yt-dlp lands a new
        // file beside an existing one.
        // videoInfo.filename already carries the container (direct links name
        // the file after the URL), so appending ext again wrote "clip.mp4.mp4".
        val fileName =
            uniqueFileName(
                downloadDir,
                if (baseName.endsWith(".$ext", ignoreCase = true)) baseName
                else "$baseName.$ext",
            )

        // Gallery/embed items carry a per-item Referer from the resolver;
        // prefer it, and reconstruct from what is available here otherwise:
        // the extraction page (yt-dlp formats), falling back to the canonical
        // instagram referer for instagram CDN hosts, which reject signed
        // media without it. Direct items keep the media url as their page,
        // which is not a referer — skip those.
        val mediaUrl = eligibleFormat.url!!
        val pageReferer =
            listOfNotNull(videoInfo.webpageUrl, videoInfo.originalUrl)
                .firstOrNull { it.isNotBlank() && it != mediaUrl }
        val referer =
            preferences.referer.ifBlank {
                if (mediaUrl.contains("cdninstagram") || mediaUrl.contains("fbcdn")) {
                    "https://www.instagram.com/"
                } else {
                    pageReferer
                }
            }
        // An empty UA is not "no header": the engine would send `User-Agent:`
        // and CDNs that gate on it (w3schools) answer 403 for a transfer that
        // yt-dlp completes. Present as the browser the device claims to be.
        val userAgent = preferences.userAgentString.ifBlank { RedditResolver.BROWSER_UA }
        val engineHeaders =
            if (referer.isNullOrBlank()) {
                mapOf("User-Agent" to userAgent)
            } else {
                mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to referer,
                )
            }
        val enqueued =
            runBlocking {
                GhostEngine.download(
                    url = mediaUrl,
                    headers = engineHeaders,
                    outputDir = downloadDir,
                    fileName = fileName,
                )
            }
        val engineTaskId =
            enqueued.getOrElse {
                Log.w(
                    TRANSFER_TAG,
                    "Ghost engine unavailable, falling back to yt-dlp: ${it.message}",
                )
                return null
            }
        ghostTasks[taskId] = engineTaskId
        Log.i(TRANSFER_TAG, "Ghost engine took over $taskId -> $engineTaskId ($fileName)")
        val engineStartedAtMs = System.currentTimeMillis()

        val outcome =
            runBlocking {
                // progressFlow is a UI mirror only: the terminal verdict is the
                // value watch() returns for THIS task. Reading the flow after
                // await() raced a newer task's snapshot into this decision.
                val bridge =
                    launch(Dispatchers.IO) {
                        GhostEngine.progressFlow.collect { p ->
                            if (p?.taskId != engineTaskId) return@collect
                            if (p.totalBytes > 0) {
                                job.progressCallback?.invoke(
                                    p.downloadedBytes * 100f / p.totalBytes,
                                    0,
                                    "Ghost: %.1f / %.1f MB • %.1f MB/s".format(
                                        p.downloadedBytes / 1_048_576.0,
                                        p.totalBytes / 1_048_576.0,
                                        p.speedBps / 1_048_576.0,
                                    ),
                                )
                            } else if (p.state == TaskState.RUNNING) {
                                job.progressCallback?.invoke(-1f, 0, "Ghost: downloading…")
                            }
                        }
                    }
                val terminal = GhostEngine.watch(engineTaskId)
                bridge.cancel()
                terminal
            }
        ghostTasks.remove(taskId)

        if (outcome?.state == TaskState.COMPLETED) {
            // The engine reporting COMPLETED is not proof of a file: verify the
            // exact output exists and is non-empty before history/UI claim it.
            val produced = File(downloadDir, fileName)
            if (!produced.isFile || produced.length() == 0L) {
                Log.w(TRANSFER_TAG, "Ghost reported COMPLETED without output: ${produced.absolutePath}")
                return Result.failure(
                    YoutubeDLException("Ghost engine: no output file produced")
                )
            }
            if (preferences.embedMetadata) {
                embedMetadataWithFfmpeg(
                    filePath = produced.absolutePath,
                    videoInfo = videoInfo,
                    preferences = preferences,
                )
            }
            return onFinishDownloading(
                preferences = preferences,
                videoInfo = videoInfo,
                downloadPath = downloadDir,
                sdcardUri = preferences.sdcardUri,
                startedAtMs = engineStartedAtMs,
                produced = produced,
                isActive = job.isActive,
                onNote = job.onNote,
                onLocalDurationMs = job.onLocalDurationMs,
            )
        }

        val reason =
            outcome?.error?.ifBlank { outcome.state.name.lowercase() } ?: "unknown"
        Log.w(TRANSFER_TAG, "Ghost engine failed for $taskId: $reason")
        // A failed transfer leaves either a single-stream partial, the
        // staging file, or the segment directory; none is media, and a
        // later retry would scan them as output. Drop them now.
        File(downloadDir).listFiles()?.forEach { file ->
            if (file.name == fileName || file.name.startsWith("$fileName.")) {
                file.deleteRecursively()
            }
        }
        return Result.failure(YoutubeDLException("Ghost engine: $reason"))
    }
}

/**
 * Where the engine writes. With "Save to SD card" on, yt-dlp and N_m3u8DL-RE
 * stage in the app-owned temp folder under that card and the finish line moves
 * the file from there; writing to the local folder left the move with nothing
 * to copy, so a complete transfer was reported as failed and downloaded again.
 */
internal fun ghostOutputDir(
    sdcardStagingDir: File?,
    privateDirectory: Boolean,
    privateDir: String,
    audioDir: String,
    videoDir: String,
    isAudioOnly: Boolean,
): String =
    when {
        sdcardStagingDir != null -> sdcardStagingDir.absolutePath
        privateDirectory -> privateDir
        isAudioOnly -> audioDir
        else -> videoDir
    }
