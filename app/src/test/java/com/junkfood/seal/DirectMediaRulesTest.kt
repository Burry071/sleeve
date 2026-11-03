package com.junkfood.seal

import com.junkfood.seal.util.DirectMediaRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The direct-media routing table decides which URLs bypass yt-dlp entirely.
 * The user's real Instagram share was a `.heic` CDN URL — not in the table —
 * so it fell into the generic extractor and hit the thumbnail-postprocessing
 * failure instead of the fast ghost lane.
 */
class DirectMediaRulesTest {

    private val igHeic =
        "https://instagram.fbhv1-1.fna.fbcdn.net/v/t51.82787-15/" +
            "882747304_17918658012469098_9128993473450977144_n.heic?stp=dst-jpg" +
            "&ig_cache_key=NDAyMDgxMzU5MjA2ODIzNzI5MQ&oe=6AEFBC7C"

    @Test
    fun `instagram heic CDN url routes to the direct lane`() {
        assertTrue(DirectMediaRules.isDirectMediaUrl(igHeic))
    }

    @Test
    fun `heic family and svg-less image formats are covered`() {
        for (ext in listOf("heic", "heif", "tif", "tiff", "jpg", "jpeg", "png", "webp")) {
            assertTrue(ext, DirectMediaRules.isDirectMediaUrl("https://cdn.example/a/file.$ext"))
        }
    }

    @Test
    fun `audio and video extensions are covered`() {
        for (ext in listOf("mp4", "webm", "mkv", "m4a", "mp3", "opus", "flac")) {
            assertTrue(ext, DirectMediaRules.isDirectMediaUrl("https://cdn.example/a/f.$ext"))
        }
    }

    @Test
    fun `uppercase extensions are covered`() {
        assertTrue(DirectMediaRules.isDirectMediaUrl("https://cdn.example/a/F.JPG"))
        assertTrue(DirectMediaRules.isDirectMediaUrl("https://cdn.example/a/F.HEIC"))
    }

    @Test
    fun `query strings and fragments do not break matching`() {
        assertTrue(
            DirectMediaRules.isDirectMediaUrl("https://cdn.example/a/f.jpg?stp=x&oe=1#frag"),
        )
    }

    @Test
    fun `extensionless page urls are NOT direct media`() {
        // https://site.com/jpg has no dot in the filename segment: a page,
        // not a file. Sending it to the direct lane would download HTML.
        assertFalse(DirectMediaRules.isDirectMediaUrl("https://site.com/jpg"))
        assertFalse(DirectMediaRules.isDirectMediaUrl("https://site.com/page"))
    }

    @Test
    fun `html and manifest urls are NOT direct media`() {
        assertFalse(DirectMediaRules.isDirectMediaUrl("https://x.com/a/page.html"))
        assertFalse(DirectMediaRules.isDirectMediaUrl("https://x.com/a/stream.m3u8"))
        assertFalse(DirectMediaRules.isDirectMediaUrl("https://x.com/a/stream.mpd"))
    }

    @Test
    fun `image predicate is the image subset`() {
        assertTrue(DirectMediaRules.isDirectImageUrl("https://cdn.example/a/f.jpg"))
        assertTrue(DirectMediaRules.isDirectImageUrl("https://cdn.example/a/f.heic"))
        assertFalse(DirectMediaRules.isDirectImageUrl("https://cdn.example/a/f.mp4"))
    }
}
