package com.junkfood.seal.download.engine

import android.util.Log
import com.junkfood.seal.App
import com.junkfood.seal.App.Companion.audioDownloadDir
import com.junkfood.seal.App.Companion.videoDownloadDir
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.RedditResolver
import com.junkfood.seal.util.sanitizeSegment
import java.io.File
import java.security.MessageDigest
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

    /**
     * Derives an isolated scratch directory under [cacheDir] by SHA-256 hashing
     * [taskId], preventing path traversal when [taskId] contains URLs or "../".
     */
    internal fun workDirForTask(cacheDir: File, taskId: String): File {
        val hash =
            MessageDigest.getInstance("SHA-256")
                .digest(taskId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        val dir = File(cacheDir, "nm3re_$hash")
        val base =
            cacheDir.canonicalPath.let {
                if (it.endsWith(File.separator)) it else it + File.separator
            }
        require(dir.canonicalPath.startsWith(base)) {
            "N_m3u8DL-RE workDir escaped cacheDir"
        }
        return dir
    }

    /**
     * Copies [source] to [target] via a sibling .tmp file and verifies byte count
     * before replacing [target], so a failed copy never leaves a truncated output
     * or discards [source] prematurely.
     */
    internal fun copyAtomically(source: File, target: File): Boolean {
        val expectedBytes = source.length()
        if (!source.isFile || expectedBytes <= 0L) return false
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val tmp = File(parent, "${target.name}.tmp")
        return runCatching {
            tmp.delete()
            source.copyTo(tmp, overwrite = true)
            if (!tmp.isFile || tmp.length() != expectedBytes) {
                tmp.delete()
                return false
            }
            if (!replaceFileSafely(tmp, target)) {
                tmp.delete()
                return false
            }
            target.isFile && target.length() == expectedBytes
        }.getOrElse {
            tmp.delete()
            false
        }
    }

    /**
     * Formats Referer and User-Agent headers for N_m3u8DL-RE `--header` flags,
     * stripping CR/LF characters and omitting blank values.
     */
    internal fun buildHeaderArgs(referer: String, userAgent: String): List<String> =
        buildList {
            val cleanReferer = referer.replace("\r", "").replace("\n", "").trim()
            if (cleanReferer.isNotEmpty()) {
                add("--header")
                add("Referer: $cleanReferer")
            }
            val cleanUserAgent = userAgent.replace("\r", "").replace("\n", "").trim()
            if (cleanUserAgent.isNotEmpty()) {
                add("--header")
                add("User-Agent: $cleanUserAgent")
            }
        }

    /**
     * Preference-level eligibility for N_m3u8DL-RE. Anything requiring yt-dlp
     * post-processing or network routing (proxy / Force IPv4) stays on yt-dlp.
     */
    internal fun canUseNm3u8(preferences: DownloadUtil.DownloadPreferences): Boolean =
        with(preferences) {
            !(downloadPlaylist ||
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
                proxy ||
                forceIpv4 ||
                videoClips.isNotEmpty() ||
                splitByChapter)
        }

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
                if (!canUseNm3u8(this)) {
                    Log.d(
                        TRANSFER_TAG,
                        "N_m3u8DL-RE skipped: yt-dlp post-processing or network routing requested",
                    )
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

        val target = File(downloadDir, fileName)
        val workDir = workDirForTask(App.context.cacheDir, taskId).apply { mkdirs() }
        var process: Process? = null
        var nreStartedAtMs = System.currentTimeMillis()
        try {
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
            val pageReferer =
                listOfNotNull(videoInfo.webpageUrl, videoInfo.originalUrl)
                    .firstOrNull { it.isNotBlank() && it != manifestUrl }
            val referer =
                preferences.referer.ifBlank {
                    if (manifestUrl.contains("cdninstagram") || manifestUrl.contains("fbcdn")) {
                        "https://www.instagram.com/"
                    } else {
                        pageReferer.orEmpty()
                    }
                }
            val userAgent = preferences.userAgentString.ifBlank { RedditResolver.BROWSER_UA }
            args.addAll(buildHeaderArgs(referer = referer, userAgent = userAgent))

            Log.i(TRANSFER_TAG, "N_m3u8DL-RE took over $taskId -> $manifestUrl")
            process =
                runCatching {
                    ProcessBuilder(args)
                        .apply {
                            val ffmpegLibDir =
                                File(
                                    App.context.noBackupFilesDir,
                                    "youtubedl-android/packages/ffmpeg/usr/lib",
                                ).absolutePath
                            val existingLd = environment()["LD_LIBRARY_PATH"]
                            environment()["LD_LIBRARY_PATH"] =
                                if (existingLd.isNullOrBlank()) ffmpegLibDir
                                else "$ffmpegLibDir:$existingLd"
                            redirectErrorStream(true)
                        }
                        .start()
                }.getOrElse {
                    Log.w(TRANSFER_TAG, "N_m3u8DL-RE unavailable, falling back to yt-dlp: ${it.message}")
                    return null
                }
            // Registered so a task cancel can terminate the process; waitFor
            // below would otherwise block until the transfer finishes.
            processes[taskId] = process
            nreStartedAtMs = System.currentTimeMillis()

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
            if (code != 0 || produced == null || produced.length() == 0L) {
                Log.w(TRANSFER_TAG, "N_m3u8DL-RE failed (exit $code), falling back to yt-dlp")
                return null
            }

            if (!copyAtomically(produced, target)) {
                Log.w(TRANSFER_TAG, "N_m3u8DL-RE atomic copy failed, falling back to yt-dlp")
                return null
            }
            produced.delete()
        } finally {
            processes.remove(taskId)
            process?.let { proc ->
                if (proc.isAlive) {
                    proc.destroyForcibly()
                }
            }
            workDir.deleteRecursively()
        }

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
