package com.junkfood.seal

import com.junkfood.seal.util.mimeTypeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MimeTypeForTest {

    @Test
    fun `m4b is MPEG-4 audio`() {
        assertEquals("audio/mp4", mimeTypeFor("book.m4b"))
        assertEquals("audio/mp4", mimeTypeFor("BOOK.M4B"))
    }

    @Test
    fun `known extensions keep the system answer`() {
        // A null here means "ask the platform", which already answers for
        // these; overriding them would change existing video intents.
        assertNull(mimeTypeFor("video.mp4"))
        assertNull(mimeTypeFor("audio.mp3"))
        assertNull(mimeTypeFor("audio.m4a"))
    }

    @Test
    fun `unknown names and no extension return null`() {
        assertNull(mimeTypeFor("file.weird"))
        assertNull(mimeTypeFor("noext"))
        assertNull(mimeTypeFor(null))
    }
}
