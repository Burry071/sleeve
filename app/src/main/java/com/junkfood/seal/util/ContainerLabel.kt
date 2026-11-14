package com.junkfood.seal.util

/** The container a finished file actually has, read back from its name. */
object ContainerLabel {

    private val knownExtensions =
        setOf(
            "mp4",
            "mkv",
            "webm",
            "mov",
            "avi",
            "m4v",
            "3gp",
            "ts",
            "flv",
            "wmv",
            "mp3",
            "m4a",
            "aac",
            "opus",
            "ogg",
            "oga",
            "flac",
            "wav",
            "wma",
            "alac",
            "aiff",
        )

    fun containerLabel(extension: String): String = extension.uppercase()

    /**
     * Null for a private-mode download (no path) and for a SAF move (`content://`), where a
     * document id is not a file name.
     */
    fun of(filePath: String?): String? {
        val path = filePath ?: return null
        if (path.contains("://")) return null
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        val extension = name.substring(dot + 1).lowercase()
        if (extension !in knownExtensions) return null
        return containerLabel(extension)
    }
}
