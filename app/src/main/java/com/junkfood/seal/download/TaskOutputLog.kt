package com.junkfood.seal.download

/**
 * Bounded, in-memory command output per task. The log page is the only place a
 * custom command's output is readable once the lines scroll past, and it is
 * kept apart from [TaskAuditLog] so a noisy command cannot push the state
 * evidence (cookie fence, gate decisions) out of its bucket.
 */
object TaskOutputLog {

    private const val MAX_LINES_PER_TASK = 2000
    private const val MAX_TASKS = 200

    private val lines = LinkedHashMap<String, MutableList<String>>()

    @Synchronized
    fun append(taskId: String, text: String) {
        if (text.isEmpty()) return
        val bucket = lines.getOrPut(taskId) { ArrayList() }
        text.lineSequence().forEach { line -> bucket += line }
        val overflow = bucket.size - MAX_LINES_PER_TASK
        if (overflow > 0) bucket.subList(0, overflow).clear()
        while (lines.size > MAX_TASKS) lines.remove(lines.keys.first())
    }

    @Synchronized
    fun isEmpty(taskId: String): Boolean = lines[taskId].isNullOrEmpty()

    @Synchronized
    fun read(taskId: String): String = lines[taskId]?.joinToString("\n").orEmpty()

    /** The newest line, or "" — what a finished card keeps on screen. */
    @Synchronized
    fun lastLine(taskId: String): String =
        lines[taskId]?.lastOrNull { it.isNotBlank() }.orEmpty()
}
