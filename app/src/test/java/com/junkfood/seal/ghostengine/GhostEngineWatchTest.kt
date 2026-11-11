package com.junkfood.seal.ghostengine

import com.junkfood.seal.ghostengine.GhostEngine.EngineProgress
import com.junkfood.seal.ghostengine.GhostEngine.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * `watch()` must always give its caller a verdict for the task it was asked
 * about. When the engine stays unreadable past the retry budget, that verdict is
 * an ERROR snapshot carrying this task's id — never a null that would send the
 * caller back to the shared progress flow.
 */
class GhostEngineWatchTest {

    @Test
    fun `an unreadable poll reports an error snapshot for its own task`() {
        val snapshot = GhostEngine.unreadableSnapshot("task-7", null)

        assertEquals("task-7", snapshot.taskId)
        assertEquals(TaskState.ERROR, snapshot.state)
        assertEquals(0L, snapshot.downloadedBytes)
        assertEquals(-1L, snapshot.totalBytes)
        assertEquals(0L, snapshot.speedBps)
        assertEquals("engine progress unavailable", snapshot.error)
    }

    @Test
    fun `an unreadable poll keeps the last observed byte counters`() {
        val lastGood = EngineProgress("task-7", TaskState.RUNNING, 3_000L, 9_000L, 500L)

        val snapshot = GhostEngine.unreadableSnapshot("task-7", lastGood)

        assertEquals(TaskState.ERROR, snapshot.state)
        assertEquals(3_000L, snapshot.downloadedBytes)
        assertEquals(9_000L, snapshot.totalBytes)
        // A failed read has no trustworthy speed; reporting the old one would
        // make the failure card look like a live transfer.
        assertEquals(0L, snapshot.speedBps)
    }

    @Test
    fun `the snapshot never borrows another task id`() {
        val otherTask = EngineProgress("task-8", TaskState.RUNNING, 1L, 2L, 3L)

        val snapshot = GhostEngine.unreadableSnapshot("task-7", otherTask)

        assertNotEquals(otherTask.taskId, snapshot.taskId)
        assertEquals("task-7", snapshot.taskId)
    }
}
