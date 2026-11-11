package com.junkfood.seal

import com.junkfood.seal.download.Task
import com.junkfood.seal.ghostengine.GhostEngine
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Audible language gate is only as good as the field that reaches Python.
 * These are deliberately small: they pin the `language` default on both sides
 * of the bridge so a future refactor cannot silently turn the check back into
 * "unknown language, no verdict".
 */
class AudibleLanguagePlumbingTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `spotify track carries language through json`() {
        val track =
            GhostEngine.SpotifyTrack(
                title = "Dune",
                artist = "Frank Herbert",
                album = "Audible",
                durationMs = 75_720_000L,
                authors = "Frank Herbert",
                language = "english",
            )

        val encoded = json.encodeToString(GhostEngine.SpotifyTrack.serializer(), track)
        val decoded =
            json.decodeFromString(GhostEngine.SpotifyTrack.serializer(), encoded)

        assertEquals("english", decoded.language)
        assertEquals(track, decoded)
    }

    @Test
    fun `metadata track carries language through json`() {
        val track =
            Task.TypeInfo.MetadataTrack(
                index = 0,
                provider = "Audible",
                title = "Dune",
                artist = "Frank Herbert",
                album = "Audible",
                durationMs = 75_720_000L,
                authors = "Frank Herbert",
                language = "german",
            )

        val encoded = json.encodeToString(Task.TypeInfo.MetadataTrack.serializer(), track)
        val decoded =
            json.decodeFromString(Task.TypeInfo.MetadataTrack.serializer(), encoded)

        assertEquals("german", decoded.language)
        assertEquals(track, decoded)
    }

    @Test
    fun `a pre-language payload decodes with a blank default`() {
        val legacy =
            """{"index":0,"provider":"Audible","title":"Dune","artist":"Frank Herbert","album":"Audible","durationMs":75720000,"authors":"Frank Herbert"}"""

        val decoded =
            json.decodeFromString(Task.TypeInfo.MetadataTrack.serializer(), legacy)

        assertEquals("", decoded.language)
        assertEquals("Dune", decoded.title)
    }
}
