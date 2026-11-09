package com.junkfood.seal.download

/**
 * Bounded, in-memory per-task audit lines. The task log shows them at runtime,
 * so the cookie fence and every match score are auditable on the device and
 * not only from build-time tests.
 */
object TaskAuditLog {

    private const val MAX_LINES_PER_TASK = 200
    private const val MAX_TASKS = 500

    private val lines = LinkedHashMap<String, MutableList<String>>()

    @Synchronized
    fun append(taskId: String, line: String) {
        val bucket = lines.getOrPut(taskId) { ArrayList() }
        bucket += line
        if (bucket.size > MAX_LINES_PER_TASK) bucket.removeAt(0)
        while (lines.size > MAX_TASKS) {
            lines.remove(lines.keys.first())
        }
    }

    @Synchronized
    fun read(taskId: String): List<String> = lines[taskId]?.toList().orEmpty()

    @Synchronized
    fun clear(taskId: String) {
        lines.remove(taskId)
    }

    @Synchronized
    fun clearAll() {
        lines.clear()
    }
}
