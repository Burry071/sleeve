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

    private const val DOCUMENT_PREFIX =
        "content://com.android.externalstorage.documents/document/"

    fun externalStoragePath(uri: String): String? {
        if (!uri.startsWith(DOCUMENT_PREFIX)) return null
        val documentId = percentDecode(uri.removePrefix(DOCUMENT_PREFIX))
        val separator = documentId.indexOf(':')
        if (separator <= 0) return null
        val volume = documentId.substring(0, separator)
        val relative = documentId.substring(separator + 1)
        if (relative.isBlank()) return null
        // A document id is user-controlled through the provider; a traversal
        // segment would point the scan outside the volume it names.
        if (relative.split('/').any { it == ".." || it.isEmpty() }) return null
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return "$root/$relative"
    }

    /** Only %XX is decoded; a literal '+' is a file-name character here. */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            val hex =
                if (char == '%' && index + 2 < value.length) value.substring(index + 1, index + 3)
                else null
            val code = hex?.toIntOrNull(16)
            if (code != null) {
                bytes.write(code)
                index += 3
            } else {
                bytes.write(char.code)
                index++
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
