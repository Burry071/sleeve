package com.junkfood.seal

import com.junkfood.seal.download.BatchInfo
import com.junkfood.seal.download.BatchNotificationState
import com.junkfood.seal.download.BatchOps
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.download.Task.RestartableAction
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchDismissPlanTest {

    private val batch = BatchInfo(id = "b", title = "Mix", provider = "Spotify")

    private fun task(index: Int): Task =
        Task(
            url = "metadata://Spotify/b/$index",
            type = Task.TypeInfo.MetadataTrack(index = index, title = "Song $index"),
            preferences = DownloadPreferences.EMPTY,
            batch = batch,
        )

    private fun state(downloadState: Task.DownloadState) =
        Task.State(downloadState, null, Task.ViewState())

    @Test
    fun `dismiss stops only unfinished songs`() {
        val map =
            mapOf(
                task(0) to state(Running(Job(), "0", 0.5f)),
                task(1) to state(Canceled(RestartableAction.Download)),
                task(2) to state(Error(Throwable(), RestartableAction.FetchInfo)),
                task(3) to state(Completed("/tmp/3.m4a")),
            )
        val plan = BatchOps.dismissPlan(map, batch.id)
        assertEquals(listOf(task(0)), plan.cancel)
    }

    @Test
    fun `dismiss removes every song without a downloaded file`() {
        val map =
            mapOf(
                task(0) to state(Idle),
                task(1) to state(FetchingInfo(Job(), "1")),
                task(2) to state(Canceled(RestartableAction.Download)),
                task(3) to state(Error(Throwable(), RestartableAction.Download)),
                task(4) to state(Completed("/tmp/4.m4a")),
            )
        val plan = BatchOps.dismissPlan(map, batch.id)
        assertEquals(listOf(task(0), task(1), task(2), task(3)), plan.remove)
    }

    @Test
    fun `dismiss keeps completed songs for the finished filter`() {
        val completedState = state(Completed("/tmp/4.m4a"))
        val map =
            mapOf(
                task(4) to completedState,
                task(5) to state(Idle),
            )
        val plan = BatchOps.dismissPlan(map, batch.id)
        assertEquals(listOf(task(4) to completedState), plan.completed)
    }

    @Test
    fun `dismiss ignores tasks from other batches and singles`() {
        val single = Task(url = "https://youtu.be/x", preferences = DownloadPreferences.EMPTY)
        val map =
            mapOf(
                single to state(Completed(null)),
                task(0) to state(Completed(null)),
            )
        val plan = BatchOps.dismissPlan(map, batch.id)
        assertEquals(listOf(task(0)), plan.completed.map { it.first })
        assertEquals(emptyList<Task>(), plan.cancel)
    }

    @Test
    fun `notification shows counts and progress while a song is active`() {
        val map =
            mapOf(
                task(0) to state(Completed("/tmp/0.m4a")),
                task(1) to state(Completed("/tmp/1.m4a")),
                task(2) to state(Running(Job(), "2", 0.5f)),
                task(3) to state(Canceled(RestartableAction.Download)),
                task(4) to state(Idle),
            )
        val summary = BatchOps.summary(map, batch.id)
        assertEquals(
            BatchNotificationState(
                completed = summary.completed,
                total = summary.total,
                progress = summary.completed * 100 / summary.total,
            ),
            BatchOps.notificationState(summary),
        )
        assertEquals(2, summary.completed)
        assertEquals(5, summary.total)
    }

    @Test
    fun `notification is cancelled when no song is active`() {
        val map =
            mapOf(
                task(0) to state(Canceled(RestartableAction.Download)),
                task(1) to state(Completed("/tmp/1.m4a")),
                task(2) to state(Error(Throwable(), RestartableAction.Download)),
                task(3) to state(Idle),
                task(4) to state(ReadyWithInfo),
            )
        assertNull(BatchOps.notificationState(BatchOps.summary(map, batch.id)))
    }

    @Test
    fun `notification starts at zero progress with one running song`() {
        val map = mapOf(task(0) to state(Running(Job(), "0", 0f)))
        assertEquals(
            BatchNotificationState(completed = 0, total = 1, progress = 0),
            BatchOps.notificationState(BatchOps.summary(map, batch.id)),
        )
    }
}
