package com.junkfood.seal.util

import java.io.ByteArrayOutputStream

/**
 * The filesystem path behind a SAF document written by the platform external
 * storage provider, or null for any other provider.
 *
 * `moveFilesToSdcard` publishes documents through SAF, which tells the gallery
 * nothing; `MediaScannerConnection` only accepts paths. The platform provider
 * encodes the volume and the relative path in the document id, so the two can
 * be mapped back without a resolver call.
 */
internal object SafDocumentPaths {

    private const val AUTHORITY_PREFIX =
        "content://com.android.externalstorage.documents/"
    private const val DOCUMENT_PREFIX = "${AUTHORITY_PREFIX}document/"
    private const val TREE_PREFIX = "${AUTHORITY_PREFIX}tree/"
    private const val DOCUMENT_SEGMENT = "/document/"
    private val VALID_VOLUME_REGEX = Regex("^[A-Za-z0-9._-]+$")

    fun externalStoragePath(uri: String): String? {
        val rawDocId =
            when {
                uri.startsWith(DOCUMENT_PREFIX) -> uri.removePrefix(DOCUMENT_PREFIX)
                uri.startsWith(TREE_PREFIX) -> {
                    val afterTree = uri.removePrefix(TREE_PREFIX)
                    val docIndex = afterTree.indexOf(DOCUMENT_SEGMENT)
                    if (docIndex <= 0) return null
                    val treeId = afterTree.substring(0, docIndex)
                    if ('/' in treeId) return null
                    afterTree.substring(docIndex + DOCUMENT_SEGMENT.length)
                }
                else -> return null
            }
        val documentId = percentDecode(rawDocId)
        val separator = documentId.indexOf(':')
        if (separator <= 0) return null
        val volume = documentId.substring(0, separator)
        if (!isValidVolume(volume)) return null
        val relative = documentId.substring(separator + 1)
        if (relative.isBlank() || '\u0000' in relative || '\\' in relative) return null
        // A document id is user-controlled through the provider; a traversal
        // segment would point the scan outside the volume it names.
        if (relative.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return "$root/$relative"
    }

    private fun isValidVolume(volume: String): Boolean =
        volume.isNotEmpty() &&
            volume != "." &&
            volume != ".." &&
            ".." !in volume &&
            VALID_VOLUME_REGEX.matches(volume)

    /** Only %XX is decoded as UTF-8 bytes; a literal '+' is a file-name character here. */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val hi = hexValue(value[index + 1])
                val lo = hexValue(value[index + 2])
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi shl 4) or lo)
                    index += 3
                    continue
                }
            }
            if (
                char.isHighSurrogate() &&
                    index + 1 < value.length &&
                    value[index + 1].isLowSurrogate()
            ) {
                bytes.write(value.substring(index, index + 2).toByteArray(Charsets.UTF_8))
                index += 2
            } else if (char.code < 0x80) {
                bytes.write(char.code)
                index++
            } else {
                bytes.write(char.toString().toByteArray(Charsets.UTF_8))
                index++
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun hexValue(c: Char): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
}
