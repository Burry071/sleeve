package com.junkfood.seal

import com.junkfood.seal.download.BatchInfo
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
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchOpsTest {

    private val batch = BatchInfo(id = "batch-1", title = "Mix", provider = "Spotify")
    private val otherBatch = BatchInfo(id = "batch-2", title = "Other", provider = "YouTube")

    private fun task(batch: BatchInfo?, index: Int = 0): Task =
        Task(
            url = "metadata://Spotify/${batch?.id}/$index",
            type = Task.TypeInfo.MetadataTrack(index = index, title = "Song $index"),
            preferences = DownloadPreferences.EMPTY,
            batch = batch,
        )

    private val single =
        Task(url = "https://youtu.be/single", preferences = DownloadPreferences.EMPTY)

    private fun state(downloadState: Task.DownloadState) =
        Task.State(downloadState, null, Task.ViewState(title = "Song"))

    private val map: Map<Task, Task.State> =
        linkedMapOf(
            task(batch, 0) to state(Idle),
            task(batch, 1) to state(ReadyWithInfo),
            task(batch, 2) to state(FetchingInfo(Job(), "id-2")),
            task(batch, 3) to state(Running(Job(), "id-3", 0.4f)),
            task(batch, 4) to state(Canceled(RestartableAction.Download, 0.2f)),
            task(batch, 5) to state(Error(Throwable("boom"), RestartableAction.FetchInfo)),
            task(batch, 6) to state(Completed("/tmp/song6.m4a")),
            task(otherBatch, 0) to state(Completed(null)),
            single to state(Completed(null)),
        )

    @Test
    fun `stop targets only unfinished songs`() {
        val targets = BatchOps.stopTargets(map, batch.id)
        assertEquals(
            listOf(0, 1, 2, 3),
            targets.map { (it.type as Task.TypeInfo.MetadataTrack).index },
        )
    }

    @Test
    fun `resume targets only canceled and failed songs`() {
        val targets = BatchOps.resumeTargets(map, batch.id)
        assertEquals(listOf(4, 5), targets.map { (it.type as Task.TypeInfo.MetadataTrack).index })
    }

    @Test
    fun `summary counts each state once and excludes other batches`() {
        val summary = BatchOps.summary(map, batch.id)
        assertEquals(7, summary.total)
        assertEquals(1, summary.completed)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.canceled)
        assertEquals(4, summary.unfinished)
        assertEquals(2, summary.active)
    }

    @Test
    fun `groups contain one entry per batch and exclude singles`() {
        val groups = BatchOps.groups(map)
        assertEquals(listOf("batch-1", "batch-2"), groups.map { it.batch.id })
        assertTrue(groups.flatMap { it.entries }.none { it.first == single })
        assertEquals(7, groups.first().summary.total)
    }

    @Test
    fun `dismissed batches are filtered out`() {
        val groups = BatchOps.groups(map, dismissed = setOf("batch-1"))
        assertEquals(listOf("batch-2"), groups.map { it.batch.id })
    }

    @Test
    fun `activity order puts batches with work left first`() {
        val done = BatchInfo(id = "done", title = "Done", provider = "YouTube")
        val failed = BatchInfo(id = "failed", title = "Failed", provider = "YouTube")
        val active = BatchInfo(id = "active", title = "Active", provider = "YouTube")
        val map =
            linkedMapOf(
                task(done, 0) to state(Completed(null)),
                task(failed, 0) to state(Error(Throwable("boom"), RestartableAction.Download)),
                task(active, 0) to state(Running(Job(), "id", 0.5f)),
            )
        assertEquals(
            listOf("active", "failed", "done"),
            BatchOps.activityOrder(BatchOps.groups(map)).map { it.batch.id },
        )
    }

    @Test
    fun `an empty batch has a zero summary`() {
        val summary = BatchOps.summary(emptyList())
        assertEquals(0, summary.total)
        assertEquals(0, summary.active)
    }
}
