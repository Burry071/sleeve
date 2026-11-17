package com.junkfood.seal.download.engine

import com.junkfood.seal.util.Format

/**
 * Decisions shared by the Ghost and N_m3u8DL-RE rungs. Both write exactly one
 * stream and cannot mux anything themselves, so the handoff must stay away
 * from a silent video strand or an .mkv remux, and the folder must follow the
 * stream that was actually picked rather than the download-type flag.
 */
internal fun singleFormatHandOffAllowed(format: Format?, mergeToMkv: Boolean): Boolean =
    format != null && !mergeToMkv && !format.isVideoOnly()

internal fun selectedFormats(formatIdString: String, formats: List<Format>): List<Format> {
    val wanted =
        formatIdString.split('+').map { it.trim() }.filter { it.isNotBlank() }.toSet()
    if (wanted.isEmpty()) return emptyList()
    return formats.filter { it.formatId != null && it.formatId in wanted }
}

internal fun outputIsAudio(extractAudio: Boolean, selected: List<Format>): Boolean =
    extractAudio || (selected.isNotEmpty() && selected.all { it.isAudioOnly() })
