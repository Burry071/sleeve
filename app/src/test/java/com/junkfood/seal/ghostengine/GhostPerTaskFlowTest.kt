package com.junkfood.seal.ghostengine

import com.junkfood.seal.ghostengine.GhostEngine.EngineProgress
import com.junkfood.seal.ghostengine.GhostEngine.GalleryItem
import com.junkfood.seal.ghostengine.GhostEngine.TaskState
import com.junkfood.seal.pythonbridge.TaskProgressRegistry
import com.junkfood.seal.pythonbridge.isTerminal
import com.junkfood.seal.pythonbridge.parseEngineProgress
import com.junkfood.seal.pythonbridge.parseGalleryItems
import com.junkfood.seal.pythonbridge.parseSelfTestResult
import com.junkfood.seal.pythonbridge.parseTaskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies per-task progress isolation in [TaskProgressRegistry] and pure
 * JSON-to-model parsing helpers in `EngineModels.kt` without requiring Android
 * or Chaquopy at test time.
 */
class GhostPerTaskFlowTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `updating task B never overwrites or conflates task A flow`() {
        val registry = TaskProgressRegistry()
        val flowA = registry.flowFor("task-a")
        val flowB = registry.flowFor("task-b")

        assertNull(flowA.value)
        assertNull(flowB.value)

        val snapA1 = EngineProgress("task-a", TaskState.RUNNING, 1_000L, 10_000L, 500L)
        val snapB1 = EngineProgress("task-b", TaskState.RUNNING, 4_000L, 8_000L, 900L)
        val snapA2 = EngineProgress("task-a", TaskState.COMPLETED, 10_000L, 10_000L, 0L)

        registry.update(snapA1)
        assertEquals(snapA1, flowA.value)
        assertNull(flowB.value)

        registry.update(snapB1)
        // Task A's dedicated flow stays isolated on A's snapshot.
        assertEquals(snapA1, flowA.value)
        assertEquals(snapB1, flowB.value)
        assertEquals(mapOf("task-a" to snapA1, "task-b" to snapB1), registry.allProgressFlow.value)
        // Legacy single-slot flow continues mirroring the most recent snapshot.
        assertEquals(snapB1, registry.latestProgressFlow.value)

        registry.update(snapA2)
        assertEquals(snapA2, flowA.value)
        assertEquals(snapB1, flowB.value)
        assertEquals(mapOf("task-a" to snapA2, "task-b" to snapB1), registry.allProgressFlow.value)
    }

    @Test
    fun `concurrent multi-task updates stay isolated per task`() = runBlocking {
        val registry = TaskProgressRegistry()
        val taskIds = (1..8).map { "task-$it" }
        val flows = taskIds.associateWith { registry.flowFor(it) }

        val jobs =
            taskIds.mapIndexed { index, taskId ->
                async(Dispatchers.Default) {
                    val total = (index + 1) * 1_000L
                    for (step in 1..49) {
                        registry.update(
                            EngineProgress(
                                taskId = taskId,
                                state = TaskState.RUNNING,
                                downloadedBytes = step * 10L,
                                totalBytes = total,
                                speedBps = 100L + index,
                            )
                        )
                    }
                    registry.update(
                        EngineProgress(
                            taskId = taskId,
                            state = TaskState.COMPLETED,
                            downloadedBytes = total,
                            totalBytes = total,
                            speedBps = 0L,
                        )
                    )
                }
            }
        jobs.awaitAll()

        val aggregate = registry.allProgressFlow.value
        assertEquals(taskIds.size, aggregate.size)
        taskIds.forEachIndexed { index, taskId ->
            val expectedTotal = (index + 1) * 1_000L
            val snap = flows[taskId]!!.value
            assertEquals(taskId, snap?.taskId)
            assertEquals(TaskState.COMPLETED, snap?.state)
            assertEquals(expectedTotal, snap?.downloadedBytes)
            assertEquals(snap, aggregate[taskId])
        }
    }

    @Test
    fun `terminal removal cleans up finished entry without disturbing active sibling`() {
        val registry = TaskProgressRegistry()
        val active = EngineProgress("task-active", TaskState.RUNNING, 2_000L, 9_000L, 400L)
        val done = EngineProgress("task-done", TaskState.COMPLETED, 5_000L, 5_000L, 0L)

        registry.update(active)
        registry.update(done)
        assertEquals(2, registry.trackedCount())
        assertEquals(done, registry.latestProgressFlow.value)

        registry.remove("task-done")

        assertEquals(1, registry.trackedCount())
        assertEquals(mapOf("task-active" to active), registry.allProgressFlow.value)
        assertEquals(active, registry.flowFor("task-active").value)
        assertNull(registry.latestProgressFlow.value)
    }

    @Test
    fun `bounded terminal history evicts oldest completed tasks while keeping active ones`() {
        val registry = TaskProgressRegistry(maxCompletedHistory = 2)
        val live = EngineProgress("live", TaskState.RUNNING, 100L, 1_000L, 50L)
        val done1 = EngineProgress("done-1", TaskState.COMPLETED, 100L, 100L, 0L)
        val done2 = EngineProgress("done-2", TaskState.ERROR, 20L, 100L, 0L, "boom")
        val done3 = EngineProgress("done-3", TaskState.CANCELLED, 0L, 100L, 0L)

        registry.update(live)
        registry.update(done1)
        registry.update(done2)
        registry.update(done3)

        val snapshot = registry.allProgressFlow.value
        assertFalse("done-1" in snapshot)
        assertEquals(live, snapshot["live"])
        assertEquals(done2, snapshot["done-2"])
        assertEquals(done3, snapshot["done-3"])
        assertEquals(3, registry.trackedCount())
    }

    @Test
    fun `parseTaskState normalises known values and reports unknown ones as ERROR`() {
        assertEquals(TaskState.WAITING, parseTaskState("waiting"))
        assertEquals(TaskState.RUNNING, parseTaskState("RUNNING"))
        assertEquals(TaskState.COMPLETED, parseTaskState(" completed "))
        assertEquals(TaskState.ERROR, parseTaskState("error"))
        assertEquals(TaskState.CANCELLED, parseTaskState("Cancelled"))

        var reported: String? = null
        assertEquals(TaskState.ERROR, parseTaskState("stalled") { reported = it })
        assertEquals("stalled", reported)
        assertEquals(TaskState.ERROR, parseTaskState(""))
    }

    @Test
    fun `isTerminal distinguishes active and settled task states`() {
        assertFalse(EngineProgress("t", TaskState.WAITING, 0, 0, 0).isTerminal())
        assertFalse(EngineProgress("t", TaskState.RUNNING, 0, 0, 0).isTerminal())
        assertTrue(EngineProgress("t", TaskState.COMPLETED, 0, 0, 0).isTerminal())
        assertTrue(EngineProgress("t", TaskState.ERROR, 0, 0, 0).isTerminal())
        assertTrue(EngineProgress("t", TaskState.CANCELLED, 0, 0, 0).isTerminal())
    }

    @Test
    fun `parseEngineProgress parses full snapshots, unknown-task errors, and rejects missing state`() {
        val full =
            json.parseToJsonElement(
                    """{"state":"running","downloaded":2048,"total":4096,"speedBps":1024,"error":null}"""
                )
                .jsonObject
        assertEquals(
            EngineProgress("t-1", TaskState.RUNNING, 2048L, 4096L, 1024L, ""),
            parseEngineProgress("t-1", full),
        )

        val unknownTask =
            json.parseToJsonElement("""{"state":"error","error":"unknown task"}""").jsonObject
        assertEquals(
            EngineProgress("t-2", TaskState.ERROR, 0L, -1L, 0L, "unknown task"),
            parseEngineProgress("t-2", unknownTask),
        )

        val missingState = json.parseToJsonElement("""{"downloaded":12}""").jsonObject
        assertTrue(runCatching { parseEngineProgress("t-3", missingState) }.isFailure)
    }

    @Test
    fun `parseSelfTestResult decodes ok and failure payloads`() {
        val okObj =
            json.parseToJsonElement(
                    """{"ok":true,"bytes":10485760,"seconds":1.25,"speedBps":8388608,"workers":8,"state":"completed","error":""}"""
                )
                .jsonObject
        val parsed = parseSelfTestResult(okObj)
        assertTrue(parsed.ok)
        assertEquals(10_485_760L, parsed.bytes)
        assertEquals(1.25, parsed.seconds, 0.0001)
        assertEquals(8_388_608L, parsed.speedBps)
        assertEquals(8, parsed.workers)
        assertEquals("completed", parsed.state)
        assertEquals("", parsed.error)
    }

    @Test
    fun `parseGalleryItems preserves per-item category, supports override, and skips malformed items`() {
        val payload =
            json.parseToJsonElement(
                    """
                    {
                      "ok": true,
                      "category": "reddit",
                      "items": [
                        {"url": "https://cdn.example/1.jpg", "filename": "1.jpg", "referer": "https://example/", "category": "reddit"},
                        {"filename": "missing-url.jpg"},
                        {"url": "   "},
                        {"url": "https://cdn.example/2.mp4", "filename": null}
                      ]
                    }
                    """
                        .trimIndent()
                )
                .jsonObject

        assertEquals(
            listOf(
                GalleryItem("https://cdn.example/1.jpg", "1.jpg", "https://example/", "reddit"),
                GalleryItem("https://cdn.example/2.mp4", "", "", ""),
            ),
            parseGalleryItems(payload),
        )

        assertEquals(
            listOf(
                GalleryItem(
                    "https://cdn.example/1.jpg",
                    "1.jpg",
                    "https://example/",
                    "instagram-embed",
                ),
                GalleryItem("https://cdn.example/2.mp4", "", "", "instagram-embed"),
            ),
            parseGalleryItems(payload, overrideCategory = "instagram-embed"),
        )

        val noItems = json.parseToJsonElement("""{"ok": true}""").jsonObject
        assertEquals(emptyList<GalleryItem>(), parseGalleryItems(noItems))
    }
}
