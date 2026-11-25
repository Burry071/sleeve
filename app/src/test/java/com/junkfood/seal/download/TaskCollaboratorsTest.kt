package com.junkfood.seal.download

import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.download.Task.RestartableAction
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskCollaboratorsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun task(id: String, url: String = "https://example.com/$id"): Task =
        Task(url = url, preferences = DownloadPreferences.EMPTY, id = id)

    private fun state(
        downloadState: DownloadState,
        match: MatchResult? = null,
        url: String = "https://example.com/v",
    ): Task.State =
        Task.State(
            downloadState = downloadState,
            videoInfo = null,
            viewState = Task.ViewState(url = url, title = "Title"),
            match = match,
        )

    private fun candidate(index: Int, score: Double): MatchCandidate =
        MatchCandidate(
            index = index,
            id = "cand-$index",
            url = "https://www.youtube.com/watch?v=cand$index",
            title = "Candidate $index",
            score = score,
            duration = 200L + index,
        )

    private fun flaggedMatch(): MatchResult =
        MatchResult(
            candidates = listOf(candidate(0, 62.0), candidate(1, 45.0)),
            selectedUrl = "https://www.youtube.com/watch?v=cand0",
            selectedScore = 62.0,
            flagged = true,
        )

    // --- TaskStore ---

    @Test
    fun `TaskStore enforces legal transitions and rejects illegal or stale-guard writes`() {
        val observed = mutableListOf<DownloadState>()
        val store =
            TaskStore(
                onTransition = { _, next -> observed += next },
                sendApplyNotifications = {},
            )
        val t = task("t-legal")
        store.put(t, state(Idle))

        val fetchJob = Job()
        val fetching = FetchingInfo(job = fetchJob, taskId = t.id)
        assertTrue(store.applyTransition(t, fetching) { it == Idle })
        assertEquals(fetching, store.downloadStateOf(t))

        // Illegal edge: FetchingInfo -> Completed must be rejected without notifying.
        assertFalse(store.applyTransition(t, Completed("/tmp/a.mp4")) { true })
        assertEquals(fetching, store.downloadStateOf(t))
        assertEquals(1, observed.size)

        // Stale guard on a legal edge must be rejected.
        assertFalse(store.applyTransition(t, ReadyWithInfo) { it == Idle })
        assertEquals(fetching, store.downloadStateOf(t))

        // Legal chain: FetchingInfo -> ReadyWithInfo -> Running -> Completed.
        assertTrue(store.applyTransition(t, ReadyWithInfo) { it == fetching })
        val running = Running(job = Job(), taskId = t.id, progress = 0.5f)
        assertTrue(store.applyTransition(t, running) { it == ReadyWithInfo })
        val completed = Completed("/tmp/a.mp4")
        assertTrue(store.applyTransition(t, completed) { it == running })

        // Late cancel after completion is illegal and must be refused.
        assertFalse(
            store.applyTransition(t, Canceled(RestartableAction.Download, byUser = true)) { true }
        )
        assertEquals(completed, store.downloadStateOf(t))

        // Removing the task blocks any subsequent transition without resurrecting it.
        store.remove(t)
        assertFalse(store.applyTransition(t, ReadyWithInfo) { true })
        assertNull(store.get(t))
    }

    @Test
    fun `TaskStore serialises racing terminal transitions under stateLock`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(20) { attempt ->
                val store = TaskStore(sendApplyNotifications = {})
                val t = task("race-$attempt")
                val runJob = Job()
                val running = Running(job = runJob, taskId = t.id, progress = 0.9f)
                store.put(t, state(running))

                val ready = CountDownLatch(2)
                val go = CountDownLatch(1)
                val winners = AtomicInteger(0)

                val f1 =
                    pool.submit {
                        ready.countDown()
                        go.await(5, TimeUnit.SECONDS)
                        if (
                            store.applyTransition(t, Completed("/tmp/ok-$attempt.mp4")) {
                                it is Running && it.job === runJob
                            }
                        ) {
                            winners.incrementAndGet()
                        }
                    }
                val f2 =
                    pool.submit {
                        ready.countDown()
                        go.await(5, TimeUnit.SECONDS)
                        if (
                            store.applyTransition(
                                t,
                                Canceled(RestartableAction.Download, progress = 0.9f, byUser = true),
                            ) { it is Running && it.job === runJob }
                        ) {
                            winners.incrementAndGet()
                        }
                    }

                assertTrue(ready.await(5, TimeUnit.SECONDS))
                go.countDown()
                f1.get(5, TimeUnit.SECONDS)
                f2.get(5, TimeUnit.SECONDS)

                assertEquals(1, winners.get())
                val finalState = store.downloadStateOf(t)
                assertTrue(finalState is Completed || finalState is Canceled)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `TaskStore backup coalescing collapses progress ticks and caps completed tail`() = runBlocking {
        val t = task("t-backup")
        val job = Job()
        val emissions =
            flow {
                    emit(mapOf(t to state(Running(job, t.id, 0.10f, "10% 1 MB/s"))))
                    emit(mapOf(t to state(Running(job, t.id, 0.45f, "45% 2 MB/s"))))
                    emit(mapOf(t to state(Running(job, t.id, 0.90f, "90% 3 MB/s"))))
                    delay(60L)
                    emit(mapOf(t to state(Completed("/tmp/done.mp4"))))
                    delay(60L)
                }
                .coalesceForBackup(debounceMs = 20L, maxCompleted = 3)
                .toList()

        // The three >0 progress ticks share one backupComparisonKey -> 1 emission,
        // followed by 1 emission when the task transitions to Completed.
        assertEquals(2, emissions.size)
        assertTrue(emissions[0][t]?.downloadState is Running)
        assertEquals(Completed("/tmp/done.mp4"), emissions[1][t]?.downloadState)

        // capTaskBackup keeps every active task plus only the trailing maxCompleted completed entries.
        val active1 = task("a1")
        val active2 = task("a2")
        val done1 = task("d1")
        val done2 = task("d2")
        val done3 = task("d3")
        val done4 = task("d4")
        val full =
            linkedMapOf(
                done1 to state(Completed("/tmp/1.mp4")),
                active1 to state(Idle),
                done2 to state(Completed("/tmp/2.mp4")),
                done3 to state(Completed("/tmp/3.mp4")),
                active2 to state(ReadyWithInfo),
                done4 to state(Completed("/tmp/4.mp4")),
            )

        val capped = capTaskBackup(full, maxCompleted = 2)
        assertEquals(setOf(active1, active2, done3, done4), capped.keys)
    }

    @Test
    fun `TaskStore restoreFromBackup flags interrupted runs and resolves pending swaps`() {
        val pendingMatch = flaggedMatch().withSelection(candidate(1, 45.0))
        val interruptedTask = task("t-interrupted")
        val completedSwapTask = task("t-completed-swap")
        val userCanceledTask = task("t-user-canceled")

        val backup =
            linkedMapOf(
                interruptedTask to
                    state(Running(Job(), interruptedTask.id, progress = 0.4f), match = pendingMatch),
                completedSwapTask to
                    state(Completed("/tmp/new.m4a"), match = pendingMatch),
                userCanceledTask to
                    state(Canceled(RestartableAction.Download, progress = 0.2f, byUser = true)),
            )

        val store =
            TaskStore(
                decodeBackup = { backup },
                sendApplyNotifications = {},
            )

        val interruptedIds = store.restoreFromBackup()

        assertEquals(setOf(interruptedTask.id), interruptedIds)
        assertTrue(store.interruptedRestore)

        // Interrupted swap rolls back to previous pick; completed swap promotes the new pick.
        val restoredInterrupted = store.get(interruptedTask)!!
        assertEquals(
            Canceled(RestartableAction.Download, progress = 0.4f, byUser = false),
            restoredInterrupted.downloadState,
        )
        assertFalse(restoredInterrupted.match!!.pending)
        assertEquals(flaggedMatch().selectedUrl, restoredInterrupted.match!!.selectedUrl)

        val restoredCompleted = store.get(completedSwapTask)!!
        assertEquals(Completed("/tmp/new.m4a"), restoredCompleted.downloadState)
        assertFalse(restoredCompleted.match!!.pending)
        assertTrue(restoredCompleted.match!!.acknowledged)
        assertEquals(candidate(1, 45.0).url, restoredCompleted.match!!.selectedUrl)

        store.dismissInterruptedRestore()
        assertFalse(store.interruptedRestore)
    }

    // --- TaskScheduler ---

    @Test
    fun `TaskScheduler never exceeds maxConcurrency even under concurrent wakeups`() {
        val store = TaskStore(sendApplyNotifications = {})
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val tasks = (1..6).map { idx -> task("t-$idx") }
            tasks.forEach { store.put(it, state(Idle)) }

            val maxSlots = 2
            val peakRunning = AtomicInteger(0)
            val dispatched = AtomicInteger(0)
            val scheduler =
                TaskScheduler(
                    store = store,
                    scope = scope,
                    maxConcurrencyProvider = { maxSlots },
                    canStartDownloads = { true },
                    canStartTask = { true },
                    startService = {},
                    stopService = {},
                    onPrepare = { t ->
                        if (
                            store.applyTransition(t, FetchingInfo(Job(), t.id)) { it == Idle }
                        ) {
                            dispatched.incrementAndGet()
                            val runningNow = store.taskStateMap.countRunning()
                            peakRunning.updateAndGet { prev -> maxOf(prev, runningNow) }
                        }
                    },
                    onDownload = {},
                    onRestart = {},
                )

            val threads = 8
            val pool = Executors.newFixedThreadPool(threads)
            try {
                val ready = CountDownLatch(threads)
                val go = CountDownLatch(1)
                val futures =
                    (1..threads).map {
                        pool.submit {
                            ready.countDown()
                            go.await(5, TimeUnit.SECONDS)
                            repeat(5) { scheduler.doYourWork() }
                        }
                    }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                go.countDown()
                futures.forEach { it.get(5, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

            assertEquals(maxSlots, dispatched.get())
            assertEquals(maxSlots, store.taskStateMap.countRunning())
            assertTrue("peak concurrency ${peakRunning.get()} exceeded $maxSlots", peakRunning.get() <= maxSlots)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `TaskScheduler respects metered gate and prioritizes ReadyWithInfo over Idle`() {
        val store = TaskStore(sendApplyNotifications = {})
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val idleTask = task("idle-1")
            val readyBlocked = task("ready-blocked")
            val readyAllowed = task("ready-allowed")
            store.put(idleTask, state(Idle))
            store.put(readyBlocked, state(ReadyWithInfo))
            store.put(readyAllowed, state(ReadyWithInfo))

            var networkAllowed = false
            val permittedIds = setOf(idleTask.id, readyAllowed.id)
            val events = mutableListOf<String>()

            val scheduler =
                TaskScheduler(
                    store = store,
                    scope = scope,
                    maxConcurrencyProvider = { 3 },
                    canStartDownloads = { networkAllowed },
                    canStartTask = { it in permittedIds },
                    startService = {},
                    stopService = {},
                    onPrepare = { t ->
                        events += "prepare:${t.id}"
                        store.applyTransition(t, FetchingInfo(Job(), t.id)) { it == Idle }
                    },
                    onDownload = { t ->
                        events += "download:${t.id}"
                        store.applyTransition(t, Running(Job(), t.id)) { it == ReadyWithInfo }
                    },
                    onRestart = {},
                )

            // Blocked globally on metered network.
            assertFalse(scheduler.doYourWork())
            assertTrue(events.isEmpty())

            // Allow network; per-task permit still blocks readyBlocked.
            networkAllowed = true
            assertTrue(scheduler.doYourWork())
            assertTrue(scheduler.doYourWork())
            assertFalse(scheduler.doYourWork())

            assertEquals(listOf("download:ready-allowed", "prepare:idle-1"), events)
            assertEquals(ReadyWithInfo, store.downloadStateOf(readyBlocked))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `TaskScheduler pendingResume waits for network and skips held or pending matches`() {
        val store = TaskStore(sendApplyNotifications = {})
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val normal = task("resume-ok")
            val held = task("resume-held")
            val pending = task("resume-pending")
            store.put(normal, state(Canceled(RestartableAction.Download)))
            store.put(
                held,
                state(
                    Canceled(RestartableAction.Download),
                    match = flaggedMatch().copy(held = true),
                ),
            )
            store.put(
                pending,
                state(
                    Canceled(RestartableAction.Download),
                    match = flaggedMatch().withSelection(candidate(1, 45.0)),
                ),
            )

            var networkAllowed = false
            val restarted = mutableListOf<String>()
            val scheduler =
                TaskScheduler(
                    store = store,
                    scope = scope,
                    maxConcurrencyProvider = { 3 },
                    canStartDownloads = { networkAllowed },
                    canStartTask = { true },
                    startService = {},
                    stopService = {},
                    onPrepare = {},
                    onDownload = {},
                    onRestart = { restarted += it.id },
                )

            scheduler.recordPendingResume(listOf(normal.id, held.id, pending.id))
            assertTrue(restarted.isEmpty())

            networkAllowed = true
            scheduler.resumePending()
            assertEquals(listOf(normal.id), restarted)
        } finally {
            scope.cancel()
        }
    }

    // --- MatchSwapCoordinator ---

    @Test
    fun `MatchSwapCoordinator promotes pick and deletes history row before old local file`() =
        runBlocking {
            val store = TaskStore(sendApplyNotifications = {})
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val oldFile = tmp.newFile("old.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                val newFile = tmp.newFile("new.m4a").apply { writeBytes(byteArrayOf(4, 5, 6, 7)) }
                val t = task("swap-local")
                val alt = candidate(1, 45.0)
                store.put(t, state(Running(Job(), t.id), match = flaggedMatch().withSelection(alt)))

                val ops = mutableListOf<String>()
                val coordinator =
                    MatchSwapCoordinator(
                        store = store,
                        scope = scope,
                        liveRefusal = { null },
                        setLiveNotice = { _, _ -> },
                        shortReason = { it.message.orEmpty() },
                        isDocumentValid = { false },
                        deleteHistoryByPath = { ops += "db:$it" },
                        deleteFile = { path ->
                            ops += "file:$path"
                            Result.success(Unit)
                        },
                        onEnqueuePart = { _, _ -> },
                        onRemoveTask = { false },
                        ioDispatcher = Dispatchers.Unconfined,
                        sendApplyNotifications = {},
                    )

                coordinator.parkSwapCleanup(t.id, oldFile.absolutePath)
                coordinator.cleanupSwappedFile(t.id, newFile.absolutePath)

                assertNull(coordinator.parkedPathFor(t.id))
                assertEquals(
                    listOf("db:${oldFile.absolutePath}", "file:${oldFile.absolutePath}"),
                    ops,
                )
                val updatedMatch = store.stateOf(t).match!!
                assertFalse(updatedMatch.pending)
                assertTrue(updatedMatch.acknowledged)
                assertEquals(alt.url, updatedMatch.selectedUrl)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `MatchSwapCoordinator promotes pending pick for valid SAF content URI without File probe`() =
        runBlocking {
            val store = TaskStore(sendApplyNotifications = {})
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val oldUri =
                    "content://com.android.externalstorage.documents/tree/1234/document/old.m4a"
                val newUri =
                    "content://com.android.externalstorage.documents/tree/1234/document/new.m4a"
                val t = task("swap-saf-ok")
                val alt = candidate(1, 45.0)
                store.put(t, state(Running(Job(), t.id), match = flaggedMatch().withSelection(alt)))

                val deleted = mutableListOf<String>()
                val coordinator =
                    MatchSwapCoordinator(
                        store = store,
                        scope = scope,
                        liveRefusal = { null },
                        setLiveNotice = { _, _ -> },
                        shortReason = { it.message.orEmpty() },
                        isDocumentValid = { it == newUri },
                        isLocalValid = { error("local File probe must not run for SAF URI") },
                        deleteHistoryByPath = { deleted += "db:$it" },
                        deleteFile = { path ->
                            deleted += "file:$path"
                            Result.success(Unit)
                        },
                        onEnqueuePart = { _, _ -> },
                        onRemoveTask = { false },
                        ioDispatcher = Dispatchers.Unconfined,
                        sendApplyNotifications = {},
                    )

                coordinator.parkSwapCleanup(t.id, oldUri)
                coordinator.cleanupSwappedFile(t.id, newUri)

                assertEquals(listOf("db:$oldUri", "file:$oldUri"), deleted)
                val updatedMatch = store.stateOf(t).match!!
                assertFalse(updatedMatch.pending)
                assertTrue(updatedMatch.acknowledged)
                assertEquals(alt.url, updatedMatch.selectedUrl)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `MatchSwapCoordinator rolls back pending pick when replacement output is missing, empty, or invalid SAF`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val emptyFile = tmp.newFile("empty.m4a")
                val missingPath = tmp.root.resolve("does-not-exist.m4a").absolutePath
                val invalidSafUri =
                    "content://com.android.externalstorage.documents/tree/1234/document/broken.m4a"
                val oldFile = tmp.newFile("kept.m4a").apply { writeBytes(byteArrayOf(9, 9, 9)) }

                listOf(missingPath, emptyFile.absolutePath, invalidSafUri, null).forEachIndexed {
                    idx,
                    badOutput ->
                    val store = TaskStore(sendApplyNotifications = {})
                    val original = flaggedMatch()
                    val alt = candidate(1, 45.0)
                    val t = task("swap-bad-$idx")
                    store.put(t, state(Running(Job(), t.id), match = original.withSelection(alt)))

                    var deleteCalled = false
                    val coordinator =
                        MatchSwapCoordinator(
                            store = store,
                            scope = scope,
                            liveRefusal = { null },
                            setLiveNotice = { _, _ -> },
                            shortReason = { it.message.orEmpty() },
                            isDocumentValid = { false },
                            deleteHistoryByPath = { deleteCalled = true },
                            deleteFile = {
                                deleteCalled = true
                                Result.success(Unit)
                            },
                            onEnqueuePart = { _, _ -> },
                            onRemoveTask = { false },
                            ioDispatcher = Dispatchers.Unconfined,
                            sendApplyNotifications = {},
                        )

                    coordinator.parkSwapCleanup(t.id, oldFile.absolutePath)
                    coordinator.cleanupSwappedFile(t.id, badOutput)

                    assertFalse("old file must not be deleted for badOutput=$badOutput", deleteCalled)
                    assertNull(coordinator.parkedPathFor(t.id))
                    val rolledBack = store.stateOf(t).match!!
                    assertFalse(rolledBack.pending)
                    assertFalse(rolledBack.acknowledged)
                    assertEquals(original.selectedUrl, rolledBack.selectedUrl)
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `MatchSwapCoordinator abandonSwappedFile restores original pick without deleting old file`() {
        val store = TaskStore(sendApplyNotifications = {})
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val original = flaggedMatch()
            val alt = candidate(1, 45.0)
            val t = task("swap-abandon")
            store.put(t, state(Running(Job(), t.id), match = original.withSelection(alt)))

            var deleteCalled = false
            val coordinator =
                MatchSwapCoordinator(
                    store = store,
                    scope = scope,
                    liveRefusal = { null },
                    setLiveNotice = { _, _ -> },
                    shortReason = { it.message.orEmpty() },
                    isDocumentValid = { false },
                    deleteHistoryByPath = { deleteCalled = true },
                    deleteFile = {
                        deleteCalled = true
                        Result.success(Unit)
                    },
                    onEnqueuePart = { _, _ -> },
                    onRemoveTask = { false },
                    ioDispatcher = Dispatchers.Unconfined,
                    sendApplyNotifications = {},
                )

            coordinator.parkSwapCleanup(t.id, "/tmp/old.m4a")
            coordinator.abandonSwappedFile(t.id)

            assertNull(coordinator.parkedPathFor(t.id))
            assertFalse(deleteCalled)
            val restored = store.stateOf(t).match!!
            assertFalse(restored.pending)
            assertFalse(restored.acknowledged)
            assertEquals(original.selectedUrl, restored.selectedUrl)
        } finally {
            scope.cancel()
        }
    }
}
