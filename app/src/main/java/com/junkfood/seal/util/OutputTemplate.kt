package com.junkfood.seal.util

import com.junkfood.seal.R

/**
 * Validation and preview for the user-supplied output template. Pure so the dialog and the tests
 * share one rule set.
 */
object OutputTemplate {

    enum class Severity {
        BLOCK,
        WARN,
    }

    data class Issue(
        val start: Int,
        val length: Int,
        val messageRes: Int,
        val severity: Severity,
    )

    data class Sample(
        val title: String = "Example video",
        val id: String = "dQw4w9WgXcQ",
        val uploader: String = "Example channel",
        val ext: String = "mp4",
    )

    private val knownFields =
        setOf(
            "autonumber",
            "autonumber_start",
            "title",
            "id",
            "ext",
            "uploader",
            "uploader_id",
            "channel",
            "channel_id",
            "playlist",
            "playlist_index",
            "playlist_title",
            "playlist_id",
            "playlist_count",
            "playlist_autonumber",
            "duration",
            "duration_string",
            "resolution",
            "width",
            "height",
            "fps",
            "format",
            "format_id",
            "filesize",
            "filesize_approx",
            "view_count",
            "like_count",
            "comment_count",
            "upload_date",
            "release_date",
            "timestamp",
            "epoch",
            "creator",
            "artist",
            "album",
            "album_artist",
            "track",
            "track_number",
            "disc_number",
            "release_year",
            "genre",
            "description",
            "thumbnail",
            "webpage_url",
            "original_url",
            "extractor",
            "extractor_key",
            "chapter",
            "chapter_number",
            "section_number",
            "section_title",
            "section_start",
            "section_end",
            "categories",
            "tags",
            "license",
            "language",
            "n_entries",
            "season",
            "season_number",
            "episode",
            "episode_number",
            "series",
            "was_live",
            "live_status",
            "is_live",
            "age_limit",
            "display_id",
            "fulltitle",
            "webpage_url_basename",
        )

    private val illegalFileNameChars = setOf('<', '>', ':', '"', '|', '?', '*')

    private val numericFields =
        setOf(
            "autonumber",
            "autonumber_start",
            "playlist_index",
            "playlist_count",
            "playlist_autonumber",
            "duration",
            "width",
            "height",
            "fps",
            "filesize",
            "filesize_approx",
            "view_count",
            "like_count",
            "comment_count",
            "timestamp",
            "epoch",
            "track",
            "track_number",
            "disc_number",
            "release_year",
            "chapter",
            "chapter_number",
            "section_number",
            "section_start",
            "section_end",
            "n_entries",
            "season",
            "season_number",
            "episode",
            "episode_number",
            "age_limit",
        )

    fun validate(template: String): List<Issue> {
        val issues = mutableListOf<Issue>()
        if (!template.contains(DownloadUtil.BASENAME)) {
            issues +=
                Issue(
                    start = 0,
                    length = 0,
                    messageRes = R.string.output_template_issue_basename,
                    severity = Severity.BLOCK,
                )
        }
        if (!template.endsWith(DownloadUtil.EXTENSION)) {
            issues +=
                Issue(
                    start = 0,
                    length = 0,
                    messageRes = R.string.output_template_issue_extension,
                    severity = Severity.BLOCK,
                )
        }

        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c != '%') {
                if (c in illegalFileNameChars || c.isISOControl()) {
                    issues +=
                        Issue(
                            start = i,
                            length = 1,
                            messageRes = R.string.output_template_issue_illegal_char,
                            severity = Severity.WARN,
                        )
                }
                i++
                continue
            }
            when {
                i + 1 < template.length && template[i + 1] == '%' -> i += 2
                i + 1 < template.length && template[i + 1] == '(' -> {
                    val close = template.indexOf(')', i + 2)
                    if (close < 0) {
                        issues += strayPercent(i)
                        i++
                    } else {
                        val name = template.substring(i + 2, close)
                        val end = tokenEnd(template, close + 1)
                        if (name !in knownFields) {
                            issues +=
                                Issue(
                                    start = i,
                                    length = end - i,
                                    messageRes = R.string.output_template_issue_unknown_field,
                                    severity = Severity.WARN,
                                )
                        }
                        i = end
                    }
                }
                i + 1 < template.length && template[i + 1].isAsciiLetter() -> i += 2
                else -> {
                    issues += strayPercent(i)
                    i++
                }
            }
        }
        return issues
    }

    /** Null unless the template can produce a name: the two requirements must hold. */
    fun preview(template: String, sample: Sample = Sample()): String? {
        if (!template.contains(DownloadUtil.BASENAME)) return null
        if (!template.endsWith(DownloadUtil.EXTENSION)) return null
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c != '%') {
                out.append(c)
                i++
                continue
            }
            when {
                i + 1 < template.length && template[i + 1] == '%' -> {
                    out.append('%')
                    i += 2
                }
                i + 1 < template.length && template[i + 1] == '(' -> {
                    val close = template.indexOf(')', i + 2)
                    if (close < 0) {
                        out.append(c)
                        i++
                    } else {
                        out.append(valueOf(template.substring(i + 2, close), sample))
                        i = tokenEnd(template, close + 1)
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun strayPercent(start: Int) =
        Issue(
            start = start,
            length = 1,
            messageRes = R.string.output_template_issue_stray_percent,
            severity = Severity.BLOCK,
        )

    /** Index just past a field token: its conversion letter when there is one. */
    private fun tokenEnd(template: String, from: Int): Int {
        var j = from
        while (j < template.length && !template[j].isAsciiLetter()) j++
        return if (j < template.length) j + 1 else j
    }

    private fun valueOf(name: String, sample: Sample): String =
        when (name) {
            "title", "fulltitle" -> sample.title
            "id", "display_id" -> sample.id
            "ext" -> sample.ext
            "uploader", "channel", "creator" -> sample.uploader
            in numericFields -> "1"
            else -> name
        }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
}
