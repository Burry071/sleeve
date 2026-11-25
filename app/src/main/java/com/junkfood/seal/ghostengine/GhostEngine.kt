package com.junkfood.seal.ghostengine

import android.content.Context
import com.junkfood.seal.pythonbridge.AudibleBridge
import com.junkfood.seal.pythonbridge.ChaquopyGateway
import com.junkfood.seal.pythonbridge.GalleryBridge
import com.junkfood.seal.pythonbridge.GhostSegmentedBridge
import com.junkfood.seal.pythonbridge.SpotifyBridge
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Facade over the embedded Sleeve engine bridges. Callers keep this entry
 * point; the Chaquopy plumbing lives in `pythonbridge`.
 */
object GhostEngine {

    /** Mirrors sleeve_engine.py task states. */
    enum class TaskState { WAITING, RUNNING, COMPLETED, ERROR, CANCELLED }

    data class EngineProgress(
        val taskId: String,
        val state: TaskState,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBps: Long,
        val error: String = "",
    )

    data class SelfTestResult(
        val ok: Boolean,
        val bytes: Long,
        val seconds: Double,
        val speedBps: Long,
        val workers: Int,
        val state: String,
        val error: String,
    ) {
        fun summary(): String =
            if (ok) {
                val mbps = "%.1f MB/s".format(speedBps / 1_048_576.0)
                "OK — ${bytes / 1_048_576} MB in ${seconds}s at $mbps ($workers connections)"
            } else {
                "Failed (${state.ifEmpty { "unknown" }})${error.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""}"
            }
    }

    fun init(context: Context) {
        ChaquopyGateway.init(context)
    }

    fun isAvailable(): Boolean = GhostSegmentedBridge.isAvailable()

    val progressFlow: StateFlow<EngineProgress?> = GhostSegmentedBridge.progressFlow

    /** Aggregate per-task progress map keyed by engine task id. */
    val allProgressFlow: StateFlow<Map<String, EngineProgress>> =
        GhostSegmentedBridge.allProgressFlow

    /** Dedicated progress stream for [taskId], isolated from concurrent transfers. */
    fun progressFlowFor(taskId: String): StateFlow<EngineProgress?> =
        GhostSegmentedBridge.progressFlowFor(taskId)

    /** Remove a finished or cancelled task from tracked progress flows. */
    fun clearProgress(taskId: String) {
        GhostSegmentedBridge.clearProgress(taskId)
    }

    /** Enqueue a resolved direct URL. Returns the engine task id. */
    suspend fun download(
        url: String,
        headers: Map<String, String> = emptyMap(),
        outputDir: String? = null,
        fileName: String? = null,
        subworkerCount: Int = 8,
    ): Result<String> =
        GhostSegmentedBridge.download(url, headers, outputDir, fileName, subworkerCount)

    /**
     * Poll engine progress into [progressFlowFor] (and legacy [progressFlow])
     * until the task settles, and return that terminal snapshot.
     *
     * The return value — never a shared flow — is the caller's verdict. When
     * the engine cannot be read after its retry budget, an ERROR snapshot for
     * [taskId] is returned instead of null.
     */
    suspend fun watch(taskId: String): EngineProgress? = GhostSegmentedBridge.watch(taskId)

    internal fun unreadableSnapshot(taskId: String, lastGood: EngineProgress?): EngineProgress =
        GhostSegmentedBridge.unreadableSnapshot(taskId, lastGood)

    fun cancel(taskId: String) {
        GhostSegmentedBridge.cancel(taskId)
    }

    /** Settings action: download a test payload through the full segmented path. */
    suspend fun selfTest(context: Context): Result<SelfTestResult> =
        GhostSegmentedBridge.selfTest(context)

    /**
     * Metadata track resolved from a public catalog (Spotify/Audible pattern):
     * metadata only — audio always comes from YouTube via yt-dlp.
     */
    @Serializable
    data class SpotifyTrack(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val authors: String = "",
        val language: String = "",
    )

    @Serializable
    data class RankedCandidate(
        val index: Int,
        val id: String,
        val url: String,
        val title: String,
        val score: Double,
        val reasons: List<String> = emptyList(),
        /** Uploader/owner the ranker saw; empty when it reported none. */
        val uploader: String = "",
        /** Display channel of the candidate; empty when unknown. */
        val channel: String = "",
        /** Whole seconds; `0` means the ranker reported no duration. */
        val duration: Long = 0L,
        /**
         * Audio-track language of the upload, filled in from the full
         * extraction when the gate gets a second look. Empty means unknown and
         * must never hold a download.
         */
        @SerialName("audio_language") val audioLanguage: String = "",
    )

    /**
     * One upload of a split book: a part of the set that together is the book.
     * `durationSeconds` is whole seconds, the unit the gate compares in; the
     * download stage converts it to ms exactly once.
     */
    data class AudiblePart(
        /** Part number as the gate parsed it (1-based, except zero-based sets). */
        val index: Int = 0,
        val id: String = "",
        val url: String = "",
        val title: String = "",
        val durationSeconds: Long = 0L,
        val channel: String = "",
    )

    /**
     * Verdict of the deterministic audible pre-download gate
     * (`sleeve_audible_gate.gate_audible`). It is not a score: `pass` is the
     * only value that may download one file, and `split` the only other value
     * that may spend bytes — on the complete part set it names.
     */
    data class AudibleGateResult(
        val verdict: String = VERDICT_HOLD,
        val reason: String = "",
        /** The passing candidate; empty unless [passed]. */
        val candidateId: String = "",
        /** Passing candidate's duration divided by expected runtime; 0 when held. */
        val ratio: Double = 0.0,
        /** Audible runtime in ms, carried for the audit line and the UI. */
        val expectedMs: Long = 0L,
        /** Closest candidate to the expected runtime, for display on a hold. */
        val bestId: String = "",
        val bestRatio: Double = 0.0,
        /** The split plan, in part order; empty for every other verdict. */
        val parts: List<AudiblePart> = emptyList(),
        /** Number of parts the gate reported; 0 when the verdict is not a split. */
        val partCount: Int = 0,
    ) {
        /** Only a proven full-length single upload may start a download. */
        val passed: Boolean
            get() = verdict == VERDICT_PASS

        /** No upload is the book, but a complete part set is: download all parts. */
        val splittable: Boolean
            get() = verdict == VERDICT_SPLIT

        companion object {
            const val VERDICT_PASS = "pass"
            const val VERDICT_SPLIT = "split"
            const val VERDICT_HOLD = "hold"
            const val VERDICT_NO_SOURCE = "no_source"
        }
    }

    data class GalleryItem(
        val url: String,
        val filename: String,
        val referer: String,
        val category: String,
    )

    /** True when a gallery-dl site module claims this URL. Loads lazily; call on IO. */
    fun galleryMatches(url: String): Boolean = GalleryBridge.galleryMatches(url)

    /**
     * Preload the gallery-dl extractor registry once per install (cold import
     * of ~300 site modules) and surface any import error to the log.
     */
    fun preloadGallery() {
        GalleryBridge.preloadGallery()
    }

    /** Resolve a page into direct media items (image sites, albums, sniffed pages). */
    fun galleryResolve(url: String, cookiesFile: String = ""): Result<Pair<String, List<GalleryItem>>> =
        GalleryBridge.galleryResolve(url, cookiesFile)

    /** Anonymous Instagram fallback: public post/reel embed page. */
    fun instagramEmbed(url: String): Result<List<GalleryItem>> =
        GalleryBridge.instagramEmbed(url)

    /** Last-resort HTML pass: og:video / twitter:player:stream / video src / m3u8+mpd. */
    fun sniffMedia(url: String): Result<List<GalleryItem>> = GalleryBridge.sniffMedia(url)

    /**
     * Quality-ranked candidate picking (spotDL-adapted scoring): name/artist
     * fuzzy ratios, duration decay, forbidden-keyword penalties, "- Topic"
     * channel bonus. Candidates come from a flat ytsearch extraction.
     */
    fun rankCandidates(
        track: SpotifyTrack,
        candidatesJson: String,
        strictness: Int = 1,
    ): Result<RankedCandidate> = SpotifyBridge.rankCandidates(track, candidatesJson, strictness)

    /**
     * Same ranker, but keeps the top [topN] candidates with their reason
     * components so the task can cache them for the review sheet. Fails when
     * no candidate passes the floor, exactly like [rankCandidates].
     */
    fun rankCandidatesWithAlternatives(
        track: SpotifyTrack,
        candidatesJson: String,
        strictness: Int = 1,
        topN: Int = 3,
    ): Result<List<RankedCandidate>> =
        SpotifyBridge.rankCandidatesWithAlternatives(track, candidatesJson, strictness, topN)

    /**
     * Deterministic audible identity gate: title F1 plus a single full-length
     * duration test, before any media request. Kept separate from
     * [rankCandidatesWithAlternatives] so a fuzzy score never decides
     * identity. Pure Python; a JSON-string bridge like the rest.
     */
    fun gateAudibleCandidates(
        track: SpotifyTrack,
        candidates: List<RankedCandidate>,
    ): Result<AudibleGateResult> = AudibleBridge.gateAudibleCandidates(track, candidates)

    /** Audible catalog metadata (public API, Libation-derived — no account). */
    fun audibleResolve(url: String, keywords: String = ""): Result<Pair<String, List<SpotifyTrack>>> =
        AudibleBridge.audibleResolve(url, keywords)

    fun spotifyResolve(
        url: String,
        clientId: String,
        clientSecret: String,
    ): Result<Pair<String, List<SpotifyTrack>>> =
        SpotifyBridge.spotifyResolve(url, clientId, clientSecret)
}
