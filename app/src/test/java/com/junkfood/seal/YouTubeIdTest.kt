package com.junkfood.seal

import com.junkfood.seal.util.youTubeIdFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeIdTest {

    @Test
    fun `watch url`() {
        assertEquals(
            "dQw4w9WgXcQ",
            youTubeIdFrom("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30"),
        )
    }

    @Test
    fun `short url`() {
        assertEquals("dQw4w9WgXcQ", youTubeIdFrom("https://youtu.be/dQw4w9WgXcQ?si=abc"))
    }

    @Test
    fun `short url without a scheme`() {
        assertNull(youTubeIdFrom("youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun `shorts url`() {
        assertEquals("dQw4w9WgXcQ", youTubeIdFrom("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
    }

    @Test
    fun `live url`() {
        assertEquals("dQw4w9WgXcQ", youTubeIdFrom("https://www.youtube.com/live/dQw4w9WgXcQ"))
    }

    @Test
    fun `embed url`() {
        assertEquals("dQw4w9WgXcQ", youTubeIdFrom("https://www.youtube.com/embed/dQw4w9WgXcQ"))
    }

    @Test
    fun `v url`() {
        assertEquals("dQw4w9WgXcQ", youTubeIdFrom("https://www.youtube.com/v/dQw4w9WgXcQ"))
    }

    @Test
    fun `a playlist url names no video`() {
        assertNull(youTubeIdFrom("https://www.youtube.com/playlist?list=PL123"))
    }

    @Test
    fun `a non-youtube url names no video`() {
        assertNull(youTubeIdFrom("https://vimeo.com/123456"))
        assertNull(youTubeIdFrom("https://example.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `a malformed id is refused`() {
        assertNull(youTubeIdFrom("https://www.youtube.com/watch?v=too-short"))
        assertNull(youTubeIdFrom("https://youtu.be/"))
    }
}
