package com.junkfood.seal

import com.junkfood.seal.util.RedditResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-side tests for the anonymous Reddit JSON parser (no Android deps). */
class RedditResolverTest {

    private fun listing(post: String) =
        """[{"data":{"children":[{"kind":"t3","data":$post}]}}]"""

    private val videoPost =
        listing(
            """{"id":"abc123","title":"A cool clip","subreddit":"gifs",
               "permalink":"/r/gifs/comments/abc123/a_cool_clip/",
               "media":{"reddit_video":{
                   "fallback_url":"https://v.redd.it/xyz/DASH_720.mp4?source=fallback",
                   "hls_url":"https://v.redd.it/xyz/HLSPlaylist.m3u8?a=123&f=hd",
                   "dash_url":"https://v.redd.it/xyz/DASHPlaylist.mpd?a=123"}}}""",
        )

    @Test
    fun `video post prefers the hls manifest`() {
        val items = RedditResolver.parsePostJson(videoPost, "https://www.reddit.com/r/gifs/comments/abc123/")
        assertEquals(1, items.size)
        val item = items[0]
        assertTrue(item.url.startsWith("https://v.redd.it/xyz/HLSPlaylist.m3u8"))
        assertEquals("gifs-A cool clip.mp4", item.filename)
        assertEquals("reddit", item.category)
        assertEquals(
            "https://www.reddit.com/r/gifs/comments/abc123/a_cool_clip/",
            item.referer,
        )
    }

    @Test
    fun `video post falls back to dash then fallback_url`() {
        val dashOnly =
            listing(
                """{"id":"a","title":"t","subreddit":"s","media":{"reddit_video":{
                   "fallback_url":"https://v.redd.it/x/DASH_360.mp4",
                   "dash_url":"https://v.redd.it/x/DASHPlaylist.mpd"}}}""",
            )
        assertEquals("https://v.redd.it/x/DASHPlaylist.mpd", RedditResolver.parsePostJson(dashOnly, "u")[0].url)
        val fallbackOnly =
            listing(
                """{"id":"a","title":"t","subreddit":"s","media":{"reddit_video":{
                   "fallback_url":"https://v.redd.it/x/DASH_360.mp4"}}}""",
            )
        assertEquals("https://v.redd.it/x/DASH_360.mp4", RedditResolver.parsePostJson(fallbackOnly, "u")[0].url)
    }

    @Test
    fun `gallery keeps author order and decodes ampersands`() {
        val gallery =
            listing(
                """{"id":"g1","title":"Album &amp; notes","subreddit":"pics",
                   "is_gallery":true,
                   "gallery_data":{"items":[{"media_id":"b"},{"media_id":"a"}]},
                   "media_metadata":{
                       "a":{"e":"Image","s":{"u":"https://preview.redd.it/second.jpg?width=960&amp;format=pjpg"}},
                       "b":{"e":"Image","s":{"u":"https://preview.redd.it/first.png?width=960&format=png"}}}}""",
            )
        val items = RedditResolver.parsePostJson(gallery, "u")
        assertEquals(2, items.size)
        assertEquals("https://preview.redd.it/first.png?width=960&format=png", items[0].url)
        assertEquals("pics-Album & notes-1.png", items[0].filename)
        assertEquals("https://preview.redd.it/second.jpg?width=960&format=pjpg", items[1].url)
        assertEquals("pics-Album & notes-2.jpg", items[1].filename)
    }

    @Test
    fun `image post uses url_overridden_by_dest`() {
        val image =
            listing(
                """{"id":"i1","title":"Shot","subreddit":"itookapicture",
                   "url_overridden_by_dest":"https://i.redd.it/photo.jpg"}""",
            )
        val items = RedditResolver.parsePostJson(image, "u")
        assertEquals("https://i.redd.it/photo.jpg", items[0].url)
        assertEquals("itookapicture-Shot.jpg", items[0].filename)
    }

    @Test
    fun `preview rendition is the last resort with decoded url`() {
        val preview =
            listing(
                """{"id":"p1","title":"P","subreddit":"pics",
                   "preview":{"images":[{"source":{
                       "url":"https://preview.redd.it/pic.png?width=640&amp;crop=smart"}}]}}""",
            )
        val items = RedditResolver.parsePostJson(preview, "u")
        assertEquals("https://preview.redd.it/pic.png?width=640&crop=smart", items[0].url)
    }

    @Test
    fun `crosspost parent carries the media`() {
        val crosspost =
            listing(
                """{"id":"c1","title":"Look","subreddit":"bestof",
                   "crosspost_parent_list":[{
                       "id":"v1","title":"Original","subreddit":"gifs",
                       "media":{"reddit_video":{"hls_url":"https://v.redd.it/orig/HLSPlaylist.m3u8?a=1"}}}]}""",
            )
        val items = RedditResolver.parsePostJson(crosspost, "u")
        assertEquals("https://v.redd.it/orig/HLSPlaylist.m3u8?a=1", items[0].url)
        // Media comes from the parent; the referer points at the parent post.
        assertEquals("gifs-Original.mp4", items[0].filename)
    }

    @Test
    fun `text post without media fails with the no-media message`() {
        val text = listing("""{"id":"t1","title":"Just text","subreddit":"AskReddit","selftext":"hello"}""")
        val error =
            runCatching { RedditResolver.parsePostJson(text, "u") }
                .exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("no downloadable media found on this post", error?.message)
    }

    @Test
    fun `host and post url detection`() {
        assertTrue(RedditResolver.isRedditHost("https://www.reddit.com/r/x/comments/1/"))
        assertTrue(RedditResolver.isRedditHost("https://v.redd.it/xyz/HLSPlaylist.m3u8?a=1"))
        assertTrue(RedditResolver.isRedditHost("https://i.redd.it/photo.jpg"))
        assertTrue(RedditResolver.isPostUrl("https://www.reddit.com/r/x/comments/1abc/some_title/"))
        assertTrue(RedditResolver.isPostUrl("https://redd.it/1abc"))
        assertTrue(RedditResolver.isPostUrl("https://www.reddit.com/r/pics/gallery/1abc9"))
        assertFalse(RedditResolver.isPostUrl("https://v.redd.it/xyz"))
        assertFalse(RedditResolver.isPostUrl("https://youtube.com/watch?v=1"))
    }

    @Test
    fun `filenames are sanitized`() {
        val weird =
            listing(
                """{"id":"w1","title":"What/the: *wild* filename?","subreddit":"test",
                   "url_overridden_by_dest":"https://i.redd.it/x.png"}""",
            )
        assertEquals("test-What_the_ _wild_ filename_.png", RedditResolver.parsePostJson(weird, "u")[0].filename)
    }
}
