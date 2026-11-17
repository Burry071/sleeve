package com.junkfood.seal.download

import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.util.ContainerLabel

/**
 * Video or audio, for the queue's counts. A finished file answers for itself;
 * a transfer that has not written one yet follows what the task asked for, so
 * an "Audio only" pick is never counted as a video while it runs.
 */
internal object TaskKind {

    fun isAudio(task: Task, state: Task.State): Boolean {
        val fileAudio = ContainerLabel.isAudio((state.downloadState as? Completed)?.filePath)
        return fileAudio ?: task.preferences.extractAudio
    }
}
