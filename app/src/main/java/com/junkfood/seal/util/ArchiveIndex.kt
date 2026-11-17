package com.junkfood.seal.util

import java.io.File

/**
 * F3: whole-line lookups against yt-dlp's download archive.
 *
 * The archive is re-read only when the file changes. One parsed version is
 * held at a time; a batch appends after every completed download, so the
 * rebuild follows each append and only repeated checks against an unchanged
 * archive skip the read.
 */
internal object ArchiveIndex {

    private class Snapshot(val length: Long, val lastModified: Long, val lines: Set<String>)

    private val lock = Any()
    private var cachedPath: String? = null
    private var cached: Snapshot? = null

    /** Whole-line match on "<extractorKey.lowercase()> <id>", the shape yt-dlp writes. */
    fun contains(archiveFile: File, extractorKey: String, id: String): Boolean =
        "${extractorKey.lowercase()} $id" in linesOf(archiveFile)

    /**
     * Drops one "<extractorKey.lowercase()> <id>" line. An explicit re-download
     * must not be refused by the archive entry the earlier run wrote.
     *
     * @return true when the file was rewritten without the line.
     */
    fun forget(archiveFile: File, extractorKey: String, id: String): Boolean =
        synchronized(lock) {
            val entry = "${extractorKey.lowercase()} $id"
            val lines =
                runCatching { archiveFile.readLines() }.getOrNull()
                    ?: return@synchronized false
            if (entry !in lines) return@synchronized false
            val rewritten =
                runCatching {
                    archiveFile.writeText(
                        lines.filter { it != entry }.joinToString("") { "$it\n" }
                    )
                }
            if (rewritten.isFailure) return@synchronized false
            // The cache is keyed on length/mtime, and dropping one line of one
            // digit can keep the length; drop it so the next lookup re-reads.
            cached = null
            cachedPath = null
            true
        }

    private fun linesOf(file: File): Set<String> {
        val length = file.length()
        val lastModified = file.lastModified()
        val path = file.absolutePath
        synchronized(lock) {
            val hit = cached
            if (hit != null && cachedPath == path && hit.length == length &&
                hit.lastModified == lastModified) {
                return hit.lines
            }
            // A missing or unreadable archive is an empty index: no skip.
            val lines =
                runCatching { file.readLines().toSet() }.getOrElse { emptySet() }
            cachedPath = path
            cached = Snapshot(length, lastModified, lines)
            return lines
        }
    }
}
