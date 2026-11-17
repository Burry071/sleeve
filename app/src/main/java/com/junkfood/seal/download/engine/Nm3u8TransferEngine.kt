package com.junkfood.seal.download.engine

import android.util.Log
import com.junkfood.seal.App
import com.junkfood.seal.App.Companion.audioDownloadDir
import com.junkfood.seal.App.Companion.videoDownloadDir
import com.junkfood.seal.util.sanitizeSegment
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * HLS/DASH handoff to the bundled N_m3u8DL-RE (Ghost-Downloader's binary,
 * GPL-3.0): multi-threaded segment download, AES-128 decryption, muxing.
 * Manifest URLs only; everything else fails open to yt-dlp.
 */
internal object Nm3u8TransferEngine : TransferEngine {

    /** Live N_m3u8DL-RE processes by task id, so cancel can kill them. */
    private val processes = ConcurrentHashMap<String, Process>()

    /** @return true when a running N_m3u8DL-RE process was terminated. */
    fun cancel(taskId: String): Boolean =
        (processes.remove(taskId)?.also { it.destroy() }) != null

    /** @return null when this download was NOT handled (caller falls back). */
    override suspend fun transfer(job: TransferJob): Result<List<String>>? {
        if (!job.settings.engineEnabled) return null
        val bin =
            nativeBinary("libnm3u8dlre.so")
                ?.takeIf { canExecuteElf(it) }
                ?: return null.also {
                    Log.d(TRANSFER_TAG, "N_m3u8DL-RE skipped: binary missing or wrong arch")
                }

        val preferences = job.preferences
        val videoInfo = job.videoInfo
        val taskId = job.taskId

        val (manifestUrl, manifestFormat) =
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
                        sdcard ||
                        useDownloadArchive ||
                        videoClips.isNotEmpty() ||
                        splitByChapter
                ) {
                    Log.d(TRANSFER_TAG, "N_m3u8DL-RE skipped: yt-dlp post-processing requested")
                    return null
                }
                if (formatIdString.contains('+')) return null

                val manifestish =
                    (videoInfo.requestedFormats.orEmpty() + videoInfo.formats.orEmpty())
                        .filter { !it.url.isNullOrBlank() }
                        .filter {
                            val lower = it.url!!.lowercase()
                            listOf("m3u8", "mpd", "manifest").any { k -> k in lower }
                        }
                val pick =
                    if (formatIdString.isNotEmpty())
                        manifestish.firstOrNull { it.formatId == formatIdString }
                    else
                        manifestish.maxByOrNull { it.tbr ?: it.fileSizeApprox ?: 0.0 }
                if (pick == null) return null
                // N_m3u8DL-RE writes one stream and cannot mux: a silent video
                // strand would land without audio, and an .mkv remux is
                // unreachable.
                if (!singleFormatHandOffAllowed(pick, job.settings.mergeOutputMkv)) {
                    Log.d(TRANSFER_TAG, "N_m3u8DL-RE skipped: silent video or mkv remux")
                    return null
                }
                (pick.url ?: return null) to pick
            }
        if (!isSafeRemoteUrl(manifestUrl)) {
            Log.w(TRANSFER_TAG, "N_m3u8DL-RE skipped: unsafe manifest url")
            return null
        }

        val baseName =
            preferences.newTitle.ifEmpty { videoInfo.filename ?: videoInfo.title }
                .ifBlank { "download" }
                .let { sanitizeSegment(it) }
        val downloadDir =
            when {
                preferences.privateDirectory -> App.privateDownloadDir
                // The folder follows the stream that was actually picked, not
                // the download-type flag: an audio-only manifest is audio.
                outputIsAudio(preferences.extractAudio, listOf(manifestFormat)) ->
                    audioDownloadDir
                else -> videoDownloadDir
            }
        // The copy below overwrites, but Scoped Storage refuses a replace that
        // crosses app ownership (a file left by another install): copyTo then
        // throws EPERM and the lane falls back to yt-dlp for nothing. Land
        // beside the existing file instead, like every other lane does.
        val fileName =
            uniqueFileName(
                downloadDir,
                "$baseName.mp4", // N_m3u8DL-RE muxes to mp4 when ffmpeg is present
            )

        val workDir =
            File(App.context.cacheDir, "nm3re_$taskId").apply { mkdirs() }
        val args =
            mutableListOf(
                bin.absolutePath,
                manifestUrl,
                "--save-dir=${workDir.absolutePath}",
                "--save-name=media",
                "--tmp-dir=${File(workDir, "tmp").apply { mkdirs() }.absolutePath}",
                "--thread-count=8",
                "--download-retry-count=3",
                "--http-request-timeout=30",
                "--concurrent-download=true",
                "--binary-merge=true",
                "--del-after-done=true",
                "--no-log=true",
                "--no-ansi-color=true",
                "--disable-update-check=true",
                "--use-system-proxy=false",
                "--auto-selection",
            )
        nativeBinary("libffmpeg.so")?.let {
            args.add("--ffmpeg-binary-path=${it.absolutePath}")
        }

        Log.i(TRANSFER_TAG, "N_m3u8DL-RE took over $taskId -> $manifestUrl")
        val process =
            runCatching {
                ProcessBuilder(args)
                    .apply { redirectErrorStream(true) }
                    .start()
            }.getOrElse {
                Log.w(TRANSFER_TAG, "N_m3u8DL-RE unavailable, falling back to yt-dlp: ${it.message}")
                workDir.deleteRecursively()
                return null
            }
        // Registered so a task cancel can terminate the process; waitFor
        // below would otherwise block until the transfer finishes.
        processes[taskId] = process
        val nreStartedAtMs = System.currentTimeMillis()

        // Stream-copy done; the produced file is the largest under workDir.
        val produced =
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val percent = Regex("\\[(\\d{1,3}(?:\\.\\d+)?)%").find(line)
                    if (percent != null) {
                        job.progressCallback?.invoke(
                            percent.groupValues[1].toFloatOrNull() ?: 0f,
                            0,
                            "N_m3u8DL-RE: ${line.takeLast(80)}",
                        )
                    }
                }
                workDir
                    .walkTopDown()
                    .filter { it.isFile && !it.relativeTo(workDir).path.startsWith("tmp") }
                    .maxByOrNull { it.length() }
            }
        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        processes.remove(taskId)
        if (code != 0 || produced == null || produced.length() == 0L) {
            Log.w(TRANSFER_TAG, "N_m3u8DL-RE failed (exit $code), falling back to yt-dlp")
            workDir.deleteRecursively()
            return null
        }

        val target = File(downloadDir, fileName)
        runCatching {
            produced.copyTo(target, overwrite = true)
            produced.delete()
        }
        workDir.deleteRecursively()

        if (preferences.embedMetadata) {
            embedMetadataWithFfmpeg(
                filePath = target.absolutePath,
                videoInfo = videoInfo,
                preferences = preferences,
            )
        }
        return onFinishDownloading(
            preferences = preferences,
            videoInfo = videoInfo,
            downloadPath = downloadDir,
            sdcardUri = preferences.sdcardUri,
            startedAtMs = nreStartedAtMs,
            produced = target,
            isActive = job.isActive,
            onNote = job.onNote,
            onLocalDurationMs = job.onLocalDurationMs,
        )
    }
}
