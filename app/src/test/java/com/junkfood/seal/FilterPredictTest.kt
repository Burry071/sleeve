package com.junkfood.seal

import com.junkfood.seal.download.BatchInfo
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.RestartableAction
import com.junkfood.seal.ui.page.downloadv2.Filter
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterPredictTest {

    private val batch = BatchInfo(id = "b", title = "T", provider = "P")

    private fun task(batch: BatchInfo? = null): Task =
        Task(
            url = "https://example.com/v",
            type = Task.TypeInfo.URL,
            preferences = DownloadPreferences.EMPTY,
            batch = batch,
        )

    private fun state(downloadState: Task.DownloadState) =
        Task.State(downloadState, videoInfo = null, viewState = Task.ViewState())

    /** Filters that accept the entry, in declaration order. */
    private fun matching(entry: Pair<Task, Task.State>): List<Filter> =
        Filter.entries.filter { it.predict(entry) }

    @Test
    fun `batched task is excluded from every filter`() {
        val entry = task(batch) to state(Idle)
        assertEquals(emptyList<Filter>(), matching(entry))
    }

    @Test
    fun `single idle task is downloading and unfiltered`() {
        val entry = task() to state(Idle)
        assertEquals(listOf(Filter.All, Filter.Downloading), matching(entry))
    }

    @Test
    fun `single completed task is finished and unfiltered`() {
        val entry = task() to state(Completed("/tmp/v.mp4"))
        assertEquals(listOf(Filter.All, Filter.Finished), matching(entry))
    }

    @Test
    fun `single errored task is canceled and unfiltered`() {
        val entry = task() to state(Error(Throwable("boom"), RestartableAction.Download))
        assertEquals(listOf(Filter.All, Filter.Canceled), matching(entry))
    }
}
