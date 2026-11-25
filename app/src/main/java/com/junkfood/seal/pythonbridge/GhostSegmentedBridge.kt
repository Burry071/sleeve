package com.junkfood.seal.pythonbridge

import android.content.Context
import android.util.Log
import com.junkfood.seal.ghostengine.GhostEngine
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Thread-safe registry of per-task segmented transfer progress.
 *
 * Isolates each engine task in its own [StateFlow] so concurrent transfers
 * never overwrite or conflate one another's UI mirror, while still feeding the
 * aggregate [allProgressFlow] and the legacy single-slot [latestProgressFlow].
 * Terminal snapshots are retained up to [maxCompletedHistory] entries (FIFO
 * eviction) and can also be removed eagerly via [remove].
 */
internal class TaskProgressRegistry(
    private val maxCompletedHistory: Int = DEFAULT_MAX_COMPLETED_HISTORY,
) {
    init {
        require(maxCompletedHistory >= 1) { "maxCompletedHistory must be at least 1" }
    }

    private val lock = Any()
    private val taskFlows = ConcurrentHashMap<String, MutableStateFlow<EngineProgress?>>()
    private val completedOrder = ArrayDeque<String>()

    private val _latestProgress = MutableStateFlow<EngineProgress?>(null)
    val latestProgressFlow: StateFlow<EngineProgress?> = _latestProgress.asStateFlow()

    private val _allProgress = MutableStateFlow<Map<String, EngineProgress>>(emptyMap())
    val allProgressFlow: StateFlow<Map<String, EngineProgress>> = _allProgress.asStateFlow()

    /** Dedicated progress stream for [taskId], isolated from all other tasks. */
    fun flowFor(taskId: String): StateFlow<EngineProgress?> =
        synchronized(lock) {
            taskFlows.computeIfAbsent(taskId) { MutableStateFlow(null) }.asStateFlow()
        }

    /** Record [progress] for its task and enforce bounded terminal history. */
    fun update(progress: EngineProgress) {
        val taskId = progress.taskId
        synchronized(lock) {
            val slot = taskFlows.computeIfAbsent(taskId) { MutableStateFlow(progress) }
            slot.value = progress
            _latestProgress.value = progress

            val next = LinkedHashMap(_allProgress.value)
            next[taskId] = progress

            if (progress.isTerminal()) {
                completedOrder.remove(taskId)
                completedOrder.addLast(taskId)
                while (completedOrder.size > maxCompletedHistory) {
                    val evicted = completedOrder.removeFirst()
                    if (evicted != taskId) {
                        taskFlows.remove(evicted)
                        next.remove(evicted)
                    }
                }
            } else {
                completedOrder.remove(taskId)
            }
            _allProgress.value = next.toMap()
        }
    }

    /** Remove [taskId] from per-task and aggregate tracking (e.g., on cleanup or cancel). */
    fun remove(taskId: String) {
        synchronized(lock) {
            taskFlows.remove(taskId)
            completedOrder.remove(taskId)
            val current = _allProgress.value
            if (taskId in current) {
                val next = LinkedHashMap(current)
                next.remove(taskId)
                _allProgress.value = next.toMap()
            }
            if (_latestProgress.value?.taskId == taskId) {
                _latestProgress.value = null
            }
        }
    }

    /** Number of currently tracked per-task flows (active + retained terminal history). */
    fun trackedCount(): Int = synchronized(lock) { taskFlows.size }

    companion object {
        const val DEFAULT_MAX_COMPLETED_HISTORY = 32
    }
}

/**
 * Segmented HTTP transfers through `sleeve_engine.py`, which follows the Ghost
 * Downloader (GPL-3.0) task/step model. Page-resolution bridges live in
 * [GalleryBridge].
 */
object GhostSegmentedBridge {
    private const val TAG = "GhostEngine"

    /** Poll interval while a transfer is live. */
    private const val POLL_INTERVAL_MS = 500L

    /** Gap between retries of a poll that could not be parsed. */
    private const val RETRY_DELAY_MS = 500L

    /** How many times one unreadable poll is retried before [watch] gives up. */
    private const val MAX_POLL_RETRIES = 3

    fun isAvailable(): Boolean = ChaquopyGateway.engineAvailable()

    private val progressRegistry = TaskProgressRegistry()

    val progressFlow: StateFlow<EngineProgress?> = progressRegistry.latestProgressFlow

    val allProgressFlow: StateFlow<Map<String, EngineProgress>> =
        progressRegistry.allProgressFlow

    fun progressFlowFor(taskId: String): StateFlow<EngineProgress?> =
        progressRegistry.flowFor(taskId)

    fun clearProgress(taskId: String) {
        progressRegistry.remove(taskId)
    }

    /** Enqueue a resolved direct URL. Returns the engine task id. */
    suspend fun download(
        url: String,
        headers: Map<String, String> = emptyMap(),
        outputDir: String? = null,
        fileName: String? = null,
        subworkerCount: Int = 8,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Options cross as a JSON string: Chaquopy's Java Map proxies are
                // not iterable from Python, and the JSON-string bridge is the
                // established contract for this boundary.
                val optionsJson =
                    """{"headers":${ChaquopyGateway.json.encodeToString<Map<String, String>>(headers)},"outputDir":${ChaquopyGateway.json.encodeToString<String>(outputDir ?: "")},"fileName":${ChaquopyGateway.json.encodeToString<String>(fileName ?: "")},"subworkerCount":$subworkerCount}"""
                val taskId =
                    call("add_uri", url, optionsJson) { it["taskId"]!!.jsonPrimitive.content }
                progressRegistry.update(
                    EngineProgress(
                        taskId,
                        GhostEngine.TaskState.RUNNING,
                        0,
                        0,
                        0,
                    )
                )
                taskId
            }
        }

    /**
     * Poll engine progress into [progressFlowFor] (and legacy [progressFlow])
     * until the task settles, and return that terminal snapshot.
     *
     * The return value — never a shared flow — is the caller's verdict. When
     * the engine cannot be read after [MAX_POLL_RETRIES] retries, an ERROR
     * snapshot for [taskId] is returned instead of null: a caller must never
     * have to guess which task a shared flow currently holds.
     */
    suspend fun watch(taskId: String): EngineProgress? =
        withContext<EngineProgress?>(Dispatchers.IO) {
            var lastGood: EngineProgress? = null
            var consecutiveFailures = 0
            var terminal: EngineProgress? = null
            try {
                pollLoop@ while (true) {
                    ensureActive()
                    val snap = poll(taskId)
                    if (snap == null) {
                        // One unreadable poll is not a verdict: the engine can be
                        // busy rewriting its state file. Retry a bounded number of
                        // times, then report only what was actually observed.
                        if (consecutiveFailures >= MAX_POLL_RETRIES) {
                            // Retries exhausted: report a verdict for THIS task.
                            // Returning null would push the caller back onto the
                            // shared flow, where a newer task's snapshot decides.
                            terminal =
                                lastGood?.takeIf { it.isTerminal() }
                                    ?: unreadableSnapshot(taskId, lastGood)
                            progressRegistry.update(terminal)
                            break@pollLoop
                        }
                        consecutiveFailures++
                        delay(RETRY_DELAY_MS)
                        continue@pollLoop
                    }
                    consecutiveFailures = 0
                    lastGood = snap
                    // UI-only mirror: callers use the returned snapshot, not this.
                    progressRegistry.update(snap)
                    if (snap.isTerminal()) {
                        terminal = snap
                        break@pollLoop
                    }
                    delay(POLL_INTERVAL_MS)
                }
            } catch (ce: CancellationException) {
                cancel(taskId)
                throw ce
            }
            terminal
        }

    /**
     * Terminal verdict for a task whose progress stayed unreadable past the
     * retry budget. The id is carried through so the caller can never mistake
     * it for another transfer's snapshot; observed bytes are kept for the log.
     */
    fun unreadableSnapshot(
        taskId: String,
        lastGood: EngineProgress?,
    ): EngineProgress =
        EngineProgress(
            taskId = taskId,
            state = GhostEngine.TaskState.ERROR,
            downloadedBytes = lastGood?.downloadedBytes ?: 0L,
            totalBytes = lastGood?.totalBytes ?: -1L,
            speedBps = 0L,
            error = "engine progress unavailable",
        )

    /** One read of the engine; null when this poll could not be parsed. */
    private fun poll(taskId: String): EngineProgress? =
        runCatching {
                call("progress", taskId) { obj ->
                    parseEngineProgress(taskId, obj) { raw ->
                        Log.w(TAG, "unknown engine state '$raw' for task $taskId; treating as ERROR")
                    }
                }
            }
            .getOrNull()

    fun cancel(taskId: String) {
        runCatching {
            ChaquopyGateway.ensureStarted()
            ChaquopyGateway.module().callAttr("cancel", taskId)
        }
        progressRegistry.remove(taskId)
    }

    /** Settings action: download a test payload through the full segmented path. */
    suspend fun selfTest(context: Context): Result<SelfTestResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = context.cacheDir.resolve("engine-selftest").apply { mkdirs() }
                call("self_test", dir.absolutePath) { obj -> parseSelfTestResult(obj) }
            }
        }

    private fun <T> call(
        function: String,
        vararg args: Any?,
        parse: (JsonObject) -> T,
    ): T = ChaquopyGateway.call(ChaquopyGateway.ENGINE_MODULE, function, *args, parse = parse)
}
