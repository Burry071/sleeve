package com.junkfood.seal.util

/**
 * Which subtitle codes start selected and where a code belongs when the user
 * picks it. Automatic captions are a different yt-dlp request (`--write-auto-subs`)
 * than real subtitles, so a code that only exists as a caption must not travel
 * in the subtitle list.
 */
object SubtitleSelectionPolicy {

    data class InitialSelection(val subtitles: List<String>, val autoCaptions: List<String>)

    fun goesToAutoCaptions(
        subtitles: Map<String, List<SubtitleFormat>>,
        autoCaptions: Map<String, List<SubtitleFormat>>,
        code: String,
    ): Boolean = code !in subtitles && code in autoCaptions

    fun initialSelection(
        subtitles: Map<String, List<SubtitleFormat>>,
        autoCaptions: Map<String, List<SubtitleFormat>>,
        enabled: Boolean,
        patterns: String,
    ): InitialSelection {
        if (!enabled) return InitialSelection(emptyList(), emptyList())
        return InitialSelection(
            subtitles = subtitles.keys.filter { matchesAny(it, patterns) },
            autoCaptions = autoCaptions.keys.filter { matchesAny(it, patterns) },
        )
    }

    /** A broken regex must not swallow a literal code the user typed. */
    fun matchesAny(language: String, patterns: String): Boolean =
        patterns
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .any { pattern ->
                runCatching { Regex(pattern).matches(language) }.getOrNull()
                    ?: (pattern == language)
            }

    fun buildLanguagePatterns(current: String, added: Collection<String>): String =
        (current.split(',') + added)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(separator = ",")
}
