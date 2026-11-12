package com.junkfood.seal

import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.download.Task.RestartableAction
import com.junkfood.seal.download.TaskTransitions
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTransitionsTest {

    private val fetching = FetchingInfo(Job(), "fetch")
    private val running = Running(Job(), "run", 0.5f)
    private val canceledFetch = Canceled(RestartableAction.FetchInfo)
    private val canceledDownload = Canceled(RestartableAction.Download, 0.25f)
    private val errorFetch = Error(Throwable("boom"), RestartableAction.FetchInfo)
    private val errorDownload = Error(Throwable("boom"), RestartableAction.Download)
    private val completed = Completed("/tmp/song.m4a")

    /** One representative instance of each of the seven states. */
    private val allStates =
        listOf(
            Idle,
            fetching,
            ReadyWithInfo,
            running,
            canceledFetch,
            errorFetch,
            completed,
        )

    @Test
    fun `every state is legal to itself only where the table says so`() {
        // Idle is not a sink, Completed is not a source.
        assertFalse(TaskTransitions.isLegal(Idle, Idle))
        assertFalse(TaskTransitions.isLegal(completed, Idle))
    }

    @Test
    fun `idle edges`() {
        assertTrue(TaskTransitions.isLegal(Idle, FetchingInfo(Job(), "t")))
        assertTrue(TaskTransitions.isLegal(Idle, Running(Job(), "t")))
        assertTrue(TaskTransitions.isLegal(Idle, Canceled(RestartableAction.FetchInfo)))
        assertTrue(TaskTransitions.isLegal(Idle, ReadyWithInfo))
        assertFalse(TaskTransitions.isLegal(Idle, Completed(null)))
        assertFalse(TaskTransitions.isLegal(Idle, Error(Throwable(), RestartableAction.FetchInfo)))
    }

    @Test
    fun `fetching edges`() {
        assertTrue(TaskTransitions.isLegal(fetching, ReadyWithInfo))
        assertTrue(TaskTransitions.isLegal(fetching, Running(Job(), "t")))
        assertTrue(TaskTransitions.isLegal(fetching, Canceled(RestartableAction.FetchInfo)))
        assertTrue(TaskTransitions.isLegal(fetching, Error(Throwable(), RestartableAction.FetchInfo)))
        assertFalse(TaskTransitions.isLegal(fetching, Idle))
        assertFalse(TaskTransitions.isLegal(fetching, Completed(null)))
    }

    @Test
    fun `ready with info edges`() {
        assertTrue(TaskTransitions.isLegal(ReadyWithInfo, Running(Job(), "t")))
        assertTrue(TaskTransitions.isLegal(ReadyWithInfo, Canceled(RestartableAction.Download)))
        assertTrue(TaskTransitions.isLegal(ReadyWithInfo, Error(Throwable(), RestartableAction.Download)))
        // A swapped pick can land on a song that has not started transferring.
        assertTrue(TaskTransitions.isLegal(ReadyWithInfo, ReadyWithInfo))
        assertFalse(TaskTransitions.isLegal(ReadyWithInfo, Idle))
        assertFalse(TaskTransitions.isLegal(ReadyWithInfo, Completed(null)))
    }

    @Test
    fun `running edges`() {
        assertTrue(TaskTransitions.isLegal(running, Running(Job(), "t", 0.9f)))
        assertTrue(TaskTransitions.isLegal(running, Completed(null)))
        assertTrue(TaskTransitions.isLegal(running, Error(Throwable(), RestartableAction.Download)))
        assertTrue(TaskTransitions.isLegal(running, Canceled(RestartableAction.Download)))
        assertFalse(TaskTransitions.isLegal(running, Idle))
        assertFalse(TaskTransitions.isLegal(running, ReadyWithInfo))
    }

    @Test
    fun `a finished or failed task is not restarted by a late writer`() {
        // The cancel-vs-worker race: a worker completing after Cancel must win,
        // and a Cancel landing after Completed must be refused.
        assertFalse(TaskTransitions.isLegal(completed, Canceled(RestartableAction.Download)))
        assertFalse(TaskTransitions.isLegal(completed, Error(Throwable(), RestartableAction.Download)))
        assertFalse(TaskTransitions.isLegal(completed, Running(Job(), "t")))
        assertFalse(TaskTransitions.isLegal(completed, Completed(null)))
        assertTrue(TaskTransitions.isLegal(completed, ReadyWithInfo))
    }

    @Test
    fun `a canceled task only leaves through idle or a swap`() {
        assertTrue(TaskTransitions.isLegal(canceledDownload, Idle))
        assertTrue(TaskTransitions.isLegal(canceledDownload, ReadyWithInfo))
        assertFalse(TaskTransitions.isLegal(canceledDownload, Running(Job(), "t")))
        assertFalse(TaskTransitions.isLegal(canceledDownload, Canceled(RestartableAction.Download)))
        assertFalse(TaskTransitions.isLegal(canceledDownload, Completed(null)))
    }

    @Test
    fun `an errored task only leaves through idle or a swap`() {
        assertTrue(TaskTransitions.isLegal(errorDownload, Idle))
        assertTrue(TaskTransitions.isLegal(errorDownload, ReadyWithInfo))
        assertFalse(TaskTransitions.isLegal(errorDownload, Running(Job(), "t")))
        assertFalse(TaskTransitions.isLegal(errorDownload, Error(Throwable(), RestartableAction.Download)))
        assertFalse(TaskTransitions.isLegal(errorDownload, Completed(null)))
    }

    @Test
    fun `no transition reaches a state outside the table`() {
        // Exhaustive sanity net: anything the table accepts is one of the
        // targets named above. Catches a new state class added without a rule.
        allStates.forEach { from ->
            allStates.forEach { to ->
                if (TaskTransitions.isLegal(from, to)) {
                    assertTrue(
                        "unexpected legal edge ${from.label} -> ${to.label}",
                        from.isLegal(to),
                    )
                }
            }
        }
    }

    private fun DownloadState.isLegal(to: DownloadState) =
        when (this) {
            Idle -> to is FetchingInfo || to is Running || to is Canceled || to is ReadyWithInfo
            is FetchingInfo ->
                to is ReadyWithInfo || to is Running || to is Canceled || to is Error
            ReadyWithInfo ->
                to is Running || to is Canceled || to is Error || to is ReadyWithInfo
            is Running -> to is Running || to is Completed || to is Error || to is Canceled
            is Canceled -> to is Idle || to is ReadyWithInfo
            is Error -> to is Idle || to is ReadyWithInfo
            is Completed -> to is ReadyWithInfo
        }

    @Test
    fun `restore cancels every in-flight state and keeps terminal proof`() {
        assertEquals(Canceled(RestartableAction.FetchInfo), TaskTransitions.restore(Idle))
        assertEquals(Canceled(RestartableAction.FetchInfo), TaskTransitions.restore(fetching))
        assertEquals(Canceled(RestartableAction.Download, null), TaskTransitions.restore(ReadyWithInfo))
        assertEquals(Canceled(RestartableAction.Download, 0.5f), TaskTransitions.restore(running))
        assertEquals(canceledDownload, TaskTransitions.restore(canceledDownload))
        assertEquals(errorFetch, TaskTransitions.restore(errorFetch))
        assertEquals(completed, TaskTransitions.restore(completed))
    }

    @Test
    fun `restore covers every state without an else branch`() {
        assertEquals(7, allStates.map { TaskTransitions.restore(it) }.size)
    }

    @Test
    fun `enqueue keeps in-flight work and replaces everything else`() {
        assertTrue(TaskTransitions.enqueueReplaces(null))
        assertTrue(TaskTransitions.enqueueReplaces(Idle))
        assertTrue(TaskTransitions.enqueueReplaces(canceledFetch))
        assertTrue(TaskTransitions.enqueueReplaces(errorDownload))
        assertTrue(TaskTransitions.enqueueReplaces(completed))
        assertFalse(TaskTransitions.enqueueReplaces(fetching))
        assertFalse(TaskTransitions.enqueueReplaces(ReadyWithInfo))
        assertFalse(TaskTransitions.enqueueReplaces(running))
    }

    @Test
    fun `restart sends a failed fetch back through idle`() {
        assertEquals(Idle, TaskTransitions.restartTarget(canceledFetch, isCustomCommand = false))
        assertEquals(Idle, TaskTransitions.restartTarget(errorFetch, isCustomCommand = true))
    }

    @Test
    fun `restart sends a failed download to ready with info`() {
        assertEquals(
            ReadyWithInfo,
            TaskTransitions.restartTarget(canceledDownload, isCustomCommand = false),
        )
        assertEquals(
            ReadyWithInfo,
            TaskTransitions.restartTarget(errorDownload, isCustomCommand = false),
        )
    }

    @Test
    fun `a custom command whose download failed restarts through idle`() {
        // Regression: ReadyWithInfo with a null info dead-ended in download(),
        // and execute() only accepts Idle, so Resume had to re-enter at Idle.
        assertEquals(
            Idle,
            TaskTransitions.restartTarget(canceledDownload, isCustomCommand = true),
        )
        assertEquals(
            Idle,
            TaskTransitions.restartTarget(errorDownload, isCustomCommand = true),
        )
    }

    @Test
    fun `unfinished states are not restartable`() {
        assertNull(TaskTransitions.restartTarget(Idle, isCustomCommand = false))
        assertNull(TaskTransitions.restartTarget(fetching, isCustomCommand = false))
        assertNull(TaskTransitions.restartTarget(ReadyWithInfo, isCustomCommand = false))
        assertNull(TaskTransitions.restartTarget(running, isCustomCommand = false))
        assertNull(TaskTransitions.restartTarget(completed, isCustomCommand = false))
    }

    @Test
    fun `cancel targets every unfinished state and no terminal one`() {
        assertEquals(Canceled(RestartableAction.FetchInfo), TaskTransitions.cancelTarget(Idle))
        assertEquals(
            Canceled(RestartableAction.Download),
            TaskTransitions.cancelTarget(ReadyWithInfo),
        )
        assertEquals(
            Canceled(RestartableAction.FetchInfo),
            TaskTransitions.cancelTarget(fetching),
        )
        assertEquals(
            Canceled(RestartableAction.Download, 0.5f),
            TaskTransitions.cancelTarget(running),
        )
        assertNull(TaskTransitions.cancelTarget(canceledFetch))
        assertNull(TaskTransitions.cancelTarget(errorDownload))
        assertNull(TaskTransitions.cancelTarget(completed))
    }

    @Test
    fun `scheduler rank only separates ready from idle from the rest`() {
        assertEquals(0, ReadyWithInfo.schedulerRank)
        assertEquals(1, Idle.schedulerRank)
        assertEquals(2, fetching.schedulerRank)
        assertEquals(2, running.schedulerRank)
        assertEquals(2, canceledFetch.schedulerRank)
        assertEquals(2, errorFetch.schedulerRank)
        assertEquals(2, completed.schedulerRank)
    }

    @Test
    fun `display rank keeps active cards above finished ones`() {
        // The order the downloads list has always shown.
        val expected =
            listOf(
                running to 0,
                ReadyWithInfo to 1,
                fetching to 2,
                Idle to 3,
                canceledFetch to 4,
                errorFetch to 5,
                completed to 6,
            )
        expected.forEach { (state, rank) -> assertEquals(rank, state.displayRank) }
        val sorted = expected.map { it.first }.sortedBy { it.displayRank }
        assertEquals(expected.map { it.first }, sorted)
    }

    @Test
    fun `labels are distinct for every state`() {
        assertEquals(7, allStates.map { it.label }.toSet().size)
    }
}
