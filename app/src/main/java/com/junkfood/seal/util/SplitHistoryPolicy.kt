package com.junkfood.seal.util

/**
 * A `--split-chapters` run leaves its unsplit master beside the chapter files.
 * The media scanner can still report the master (it was indexed before the
 * delete), so the split history insert filters it out by path.
 */
object SplitHistoryPolicy {

    fun chapterPaths(paths: List<String>, masterPath: String?): List<String> =
        paths.filter { it != masterPath }
}
