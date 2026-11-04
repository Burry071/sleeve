package com.junkfood.seal.download

import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.download.Task.DownloadState.Running

data class BatchSummary(
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val canceled: Int = 0,
    val unfinished: Int = 0,
    val active: Int = 0,
)

data class BatchGroup(
    val batch: BatchInfo,
    val entries: List<Pair<Task, Task.State>>,
) {
    val summary: BatchSummary
        get() = BatchOps.summary(entries)
}

/** What the single batch notification should show, or null when nothing is active. */
data class BatchNotificationState(
    val completed: Int,
    val total: Int,
    val progress: Int,
)

data class DismissPlan(
    /** Unfinished songs to stop before removal; a subset of [remove], not disjoint. */
    val cancel: List<Task>,
    /** Every song not in a `Completed` state. */
    val remove: List<Task>,
    /** Completed songs to keep, re-keyed with a null batch. */
    val completed: List<Pair<Task, Task.State>>,
)

object BatchOps {

    fun isUnfinished(state: DownloadState): Boolean =
        state is Idle || state is ReadyWithInfo || state is FetchingInfo || state is Running

    fun isResumable(state: DownloadState): Boolean = state is Canceled || state is Error

    fun isActive(state: DownloadState): Boolean = state is FetchingInfo || state is Running

    fun entries(
        map: Map<Task, Task.State>,
        batchId: String,
    ): List<Pair<Task, Task.State>> =
        map.entries.filter { it.key.batch?.id == batchId }.map { it.toPair() }

    fun stopTargets(map: Map<Task, Task.State>, batchId: String): List<Task> =
        entries(map, batchId).filter { isUnfinished(it.second.downloadState) }.map { it.first }

    fun resumeTargets(map: Map<Task, Task.State>, batchId: String): List<Task> =
        entries(map, batchId).filter { isResumable(it.second.downloadState) }.map { it.first }

    fun summary(entries: List<Pair<Task, Task.State>>): BatchSummary =
        BatchSummary(
            total = entries.size,
            completed = entries.count { it.second.downloadState is Completed },
            failed = entries.count { it.second.downloadState is Error },
            canceled = entries.count { it.second.downloadState is Canceled },
            unfinished = entries.count { isUnfinished(it.second.downloadState) },
            active = entries.count { isActive(it.second.downloadState) },
        )

    fun summary(map: Map<Task, Task.State>, batchId: String): BatchSummary =
        summary(entries(map, batchId))

    /** Null means the aggregate notification must be cancelled, not re-posted. */
    fun notificationState(summary: BatchSummary): BatchNotificationState? =
        if (summary.active == 0) {
            null
        } else {
            BatchNotificationState(
                completed = summary.completed,
                total = summary.total,
                progress = if (summary.total == 0) 0 else summary.completed * 100 / summary.total,
            )
        }

    /** One group per batch, in task-map insertion order; dismissed batches are hidden. */
    fun groups(
        map: Map<Task, Task.State>,
        dismissed: Set<String> = emptySet(),
    ): List<BatchGroup> =
        map.keys
            .mapNotNull { it.batch }
            .distinctBy { it.id }
            .filter { it.id !in dismissed }
            .map { batch -> BatchGroup(batch, entries(map, batch.id)) }

    fun dismissPlan(map: Map<Task, Task.State>, batchId: String): DismissPlan {
        val all = entries(map, batchId)
        return DismissPlan(
            cancel = all.filter { isUnfinished(it.second.downloadState) }.map { it.first },
            remove = all.filter { it.second.downloadState !is Completed }.map { it.first },
            completed = all.filter { it.second.downloadState is Completed },
        )
    }
}
