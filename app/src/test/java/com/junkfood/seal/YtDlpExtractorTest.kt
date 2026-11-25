package com.junkfood.seal

import com.junkfood.seal.download.engine.YtDlpExtractor
import com.junkfood.seal.download.engine.formatSorter
import com.junkfood.seal.download.model.DownloadPreferences
import com.junkfood.seal.download.model.DownloadPreferencesFactory
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FORMAT_COMPATIBILITY
import com.junkfood.seal.util.FORMAT_QUALITY
import com.junkfood.seal.util.HIGH
import com.junkfood.seal.util.M4A
import com.junkfood.seal.util.OPUS
import com.junkfood.seal.util.RedditResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class YtDlpExtractorTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `buildYoutubeExtractorArgs merges client and subtitle skip into one youtube arg`() {
        assertEquals(
            "",
            YtDlpExtractor.buildYoutubeExtractorArgs(
                clients = null,
                autoSubtitle = false,
                autoTranslatedSubtitles = false,
            ),
        )
        assertEquals(
            "",
            YtDlpExtractor.buildYoutubeExtractorArgs(
                clients = "   ",
                autoSubtitle = true,
                autoTranslatedSubtitles = true,
            ),
        )
        assertEquals(
            "youtube:skip=translated_subs",
            YtDlpExtractor.buildYoutubeExtractorArgs(
                clients = null,
                autoSubtitle = true,
                autoTranslatedSubtitles = false,
            ),
        )
        assertEquals(
            "youtube:player_client=tv,web_embedded",
            YtDlpExtractor.buildYoutubeExtractorArgs(
                clients = "tv,web_embedded",
                autoSubtitle = false,
                autoTranslatedSubtitles = false,
            ),
        )
        assertEquals(
            "youtube:player_client=tv,web_embedded;skip=translated_subs",
            YtDlpExtractor.buildYoutubeExtractorArgs(
                clients = "tv,web_embedded",
                autoSubtitle = true,
                autoTranslatedSubtitles = false,
            ),
        )
        assertEquals(
            "youtube:player_client=visionos",
            YtDlpExtractor.buildYoutubeExtractorArgs(
                clients = "visionos",
                autoSubtitle = true,
                autoTranslatedSubtitles = true,
            ),
        )
    }

    @Test
    fun `buildVideoInfoRequest delegates proxy sorter cookies and merged youtube extractor args`() {
        var cookieUserAgent: String? = null
        val prefs =
            DownloadPreferencesFactory.EMPTY.copy(
                extractAudio = true,
                autoSubtitle = true,
                autoTranslatedSubtitles = false,
                cookies = true,
                userAgentString = "CustomUA/1.0",
                proxy = true,
                proxyUrl = "http://127.0.0.1:8080",
                forceIpv4 = true,
                restrictFilenames = true,
                videoFormat = FORMAT_COMPATIBILITY,
                videoResolution = 3,
                useCustomAudioPreset = true,
                audioFormat = OPUS,
                audioQuality = HIGH,
            )

        val command =
            YtDlpExtractor.buildVideoInfoRequest(
                    url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    clients = "tv,web_embedded",
                    playlistIndex = null,
                    preferences = prefs,
                    customUserAgentEnabled = true,
                    applyCookies = { ua ->
                        cookieUserAgent = ua
                        addOption("--cookies", "/tmp/test-cookies.txt")
                    },
                )
                .buildCommand()

        assertEquals("CustomUA/1.0", cookieUserAgent)
        assertTrue(command.containsPair("-o", DownloadUtil.BASENAME))
        assertTrue(
            command.containsPair(
                "--extractor-args",
                "youtube:player_client=tv,web_embedded;skip=translated_subs",
            )
        )
        assertEquals(1, command.count { it == "--extractor-args" })
        assertTrue(command.contains("-x"))
        assertTrue(command.contains("--restrict-filenames"))
        assertTrue(command.contains("-4"))
        assertTrue(command.contains("--write-auto-subs"))
        assertTrue(command.contains("--dump-single-json"))
        assertTrue(command.contains("--no-playlist"))
        assertTrue(command.containsPair("-R", "1"))
        assertTrue(command.containsPair("--socket-timeout", "5"))
        assertTrue(command.containsPair("--proxy", "http://127.0.0.1:8080"))
        assertTrue(command.containsPair("-S", formatSorter(prefs)))
        assertTrue(command.containsPair("--cookies", "/tmp/test-cookies.txt"))
    }

    @Test
    fun `buildVideoInfoRequest uses playlist-items and dump-json when playlistIndex is provided`() {
        var cookiesCalled = false
        val command =
            YtDlpExtractor.buildVideoInfoRequest(
                    url = "https://www.reddit.com/r/videos/comments/abc123/test/",
                    clients = null,
                    playlistIndex = 4,
                    preferences =
                        DownloadPreferencesFactory.EMPTY.copy(
                            cookies = false,
                            formatSorting = true,
                            sortingFields = "res:720,+size",
                        ),
                    customUserAgentEnabled = false,
                    applyCookies = {
                        cookiesCalled = true
                        this
                    },
                )
                .buildCommand()

        assertFalse("cookies hook must not run when cookies=false", cookiesCalled)
        assertTrue(command.containsPair("--playlist-items", "4"))
        assertTrue(command.contains("--dump-json"))
        assertFalse(command.contains("--dump-single-json"))
        assertTrue(command.containsPair("-S", "res:720,+size"))
        assertTrue(command.containsPair("--user-agent", RedditResolver.BROWSER_UA))
        assertFalse(command.contains("--extractor-args"))
    }

    @Test
    fun `buildPlaylistOrVideoInfoRequest delegates flat-playlist options and sorter`() {
        var cookieUserAgent: String? = null
        val prefs =
            DownloadPreferencesFactory.EMPTY.copy(
                extractAudio = true,
                proxy = true,
                proxyUrl = "socks5://127.0.0.1:1080",
                forceIpv4 = true,
                cookies = true,
                userAgentString = "PlaylistUA/2.0",
                restrictFilenames = true,
                videoFormat = FORMAT_QUALITY,
                supportAv1HardwareDecoding = true,
                videoResolution = 1,
            )

        val command =
            YtDlpExtractor.buildPlaylistOrVideoInfoRequest(
                    playlistURL = "https://www.reddit.com/r/videos/comments/xyz/item/",
                    preferences = prefs,
                    customUserAgentEnabled = false,
                    applyCookies = { ua ->
                        cookieUserAgent = ua
                        addOption("--cookies", "/tmp/playlist-cookies.txt")
                    },
                )
                .buildCommand()

        assertEquals("PlaylistUA/2.0", cookieUserAgent)
        assertTrue(command.contains("--flat-playlist"))
        assertTrue(command.contains("--dump-single-json"))
        assertTrue(command.containsPair("-o", DownloadUtil.BASENAME))
        assertTrue(command.containsPair("--proxy", "socks5://127.0.0.1:1080"))
        assertTrue(command.containsPair("-S", "res:2160,vcodec:av01"))
        assertTrue(command.containsPair("--user-agent", RedditResolver.BROWSER_UA))
        assertTrue(command.contains("-x"))
        assertTrue(command.contains("-4"))
        assertTrue(command.contains("--restrict-filenames"))
    }

    @Test
    fun `buildCustomCommandBaseRequest applies base flags without emitting config-locations`() {
        var archiveApplied = false
        val command =
            YtDlpExtractor.buildCustomCommandBaseRequest(
                    urlList = listOf("https://example.com/v1"),
                    preferences =
                        DownloadPreferencesFactory.EMPTY.copy(
                            commandDirectory = "/tmp/cmd",
                            aria2c = true,
                            useDownloadArchive = true,
                            restrictFilenames = true,
                        ),
                    applyArchive = {
                        archiveApplied = true
                        addOption("--download-archive", "/tmp/archive.txt")
                    },
                )
                .buildCommand()

        assertTrue(archiveApplied)
        assertTrue(command.containsPair("-P", "/tmp/cmd"))
        assertTrue(command.contains("--newline"))
        assertTrue(command.containsPair("--downloader", "libaria2c.so"))
        assertTrue(command.containsPair("--download-archive", "/tmp/archive.txt"))
        assertTrue(command.contains("--restrict-filenames"))
        assertFalse(command.contains("--config-locations"))
    }

    @Test
    fun `DownloadUtil toFormatSorter delegates to canonical YtDlpRequestOptions formatSorter`() {
        val prefs =
            DownloadPreferencesFactory.EMPTY.copy(
                videoFormat = FORMAT_QUALITY,
                supportAv1HardwareDecoding = false,
                videoResolution = 2,
                useCustomAudioPreset = true,
                audioFormat = M4A,
                audioQuality = HIGH,
            )

        val expected = formatSorter(prefs)
        val actual = with(DownloadUtil) { prefs.toFormatSorter() }

        assertEquals("res:1440,vcodec:vp9.2,acodec:aac,abr~192", expected)
        assertEquals(expected, actual)
    }

    @Test
    fun `DownloadPreferences model alias and factory match DownloadUtil nested type`() {
        val viaAlias: DownloadPreferences = DownloadPreferencesFactory.EMPTY
        val viaDownloadUtil: DownloadUtil.DownloadPreferences = DownloadUtil.DownloadPreferences.EMPTY

        assertEquals(DownloadUtil.DownloadPreferences::class.java, DownloadPreferences::class.java)
        assertSame(viaAlias, viaDownloadUtil)

        assertTrue(DownloadPreferencesFactory.shouldMergeToMkv(true, true, false))
        assertTrue(DownloadPreferencesFactory.shouldMergeToMkv(false, false, true))
        assertFalse(DownloadPreferencesFactory.shouldMergeToMkv(true, false, false))
        assertFalse(DownloadPreferencesFactory.shouldMergeToMkv(false, true, false))

        var supplierInvoked = false
        assertEquals(
            "",
            DownloadPreferencesFactory.resolveUserAgentString(false) {
                supplierInvoked = true
                "ShouldNotBeRead"
            },
        )
        assertFalse(supplierInvoked)
        assertEquals(
            "Agent/3.0",
            DownloadPreferencesFactory.resolveUserAgentString(true) {
                supplierInvoked = true
                "Agent/3.0"
            },
        )
        assertTrue(supplierInvoked)
    }

    @Test
    fun `DownloadUtil custom command helpers delegate identically to YtDlpExtractor`() {
        val raw = "--exec id https://example.com/a http://example.org/b file:///etc/passwd"
        assertEquals(
            YtDlpExtractor.sanitizeCustomCommandUrls(raw),
            DownloadUtil.sanitizeCustomCommandUrls(raw),
        )

        val cacheDir = tmp.newFolder("cmd-cache")
        assertEquals(
            YtDlpExtractor.customCommandConfigFile(cacheDir, "task-42"),
            DownloadUtil.customCommandConfigFile(cacheDir, "task-42"),
        )
    }

    private fun List<String>.containsPair(option: String, value: String): Boolean =
        withIndex().any { (index, item) -> item == option && getOrNull(index + 1) == value }
}
