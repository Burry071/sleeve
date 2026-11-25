package com.junkfood.seal

import com.junkfood.seal.ui.common.Route
import com.junkfood.seal.ui.page.settings.network.isAllowedWebViewScheme
import com.junkfood.seal.ui.page.settings.network.isAllowedWebViewUrl
import com.junkfood.seal.util.CookieStore
import com.junkfood.seal.util.DownloadUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HardeningWave1Test {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `sanitizeCustomCommandUrls rejects flag injection and non-http tokens`() {
        val raw =
            """
            --exec whoami
            -o /tmp/pwn
            --config-locations /etc/passwd
            file:///etc/passwd
            javascript:alert(1)
            https://
            https://example.com/watch?v=1
            http://192.168.1.10:8080/media.mp4
            """
                .trimIndent()

        assertEquals(
            listOf(
                "https://example.com/watch?v=1",
                "http://192.168.1.10:8080/media.mp4",
            ),
            DownloadUtil.sanitizeCustomCommandUrls(raw),
        )
    }

    @Test
    fun `sanitizeCustomCommandUrls returns empty list when only flags are supplied`() {
        assertTrue(DownloadUtil.sanitizeCustomCommandUrls("--exec id --newline").isEmpty())
        assertTrue(DownloadUtil.sanitizeCustomCommandUrls("   \n\t  ").isEmpty())
    }

    @Test
    fun `customCommandConfigFile isolates concurrent tasks into distinct files`() {
        val cacheDir = tmp.newFolder("cache")
        val first = DownloadUtil.customCommandConfigFile(cacheDir, "task-a")
        val second = DownloadUtil.customCommandConfigFile(cacheDir, "task-b")

        assertEquals(cacheDir, first.parentFile)
        assertEquals(cacheDir, second.parentFile)
        assertNotEquals(first, second)
        assertTrue(first.name.matches(Regex("""config_[0-9a-f]{16}\.txt""")))
    }

    @Test
    fun `allowedExternalRouteOrNull permits safe public routes and blocks internal or unknown ones`() {
        assertEquals(Route.HOME, MainActivity.allowedExternalRouteOrNull(Route.HOME))
        assertEquals(Route.DOWNLOADS, MainActivity.allowedExternalRouteOrNull(Route.DOWNLOADS))
        assertEquals(Route.TASK_LIST, MainActivity.allowedExternalRouteOrNull(Route.TASK_LIST))
        assertEquals(
            Route.SETTINGS_PAGE,
            MainActivity.allowedExternalRouteOrNull(Route.SETTINGS_PAGE),
        )

        assertNull(MainActivity.allowedExternalRouteOrNull(Route.COOKIE_PROFILE))
        assertNull(MainActivity.allowedExternalRouteOrNull(Route.COOKIE_GENERATOR_WEBVIEW))
        assertNull(MainActivity.allowedExternalRouteOrNull("webview"))
        assertNull(MainActivity.allowedExternalRouteOrNull(Route.TASK_LOG))
        assertNull(MainActivity.allowedExternalRouteOrNull(Route.TEMPLATE_EDIT))
        assertNull(MainActivity.allowedExternalRouteOrNull("unknown/route"))
        assertNull(MainActivity.allowedExternalRouteOrNull(""))
        assertNull(MainActivity.allowedExternalRouteOrNull(null))
    }

    @Test
    fun `WebView scheme helpers allow only http and https`() {
        assertTrue(isAllowedWebViewScheme("http"))
        assertTrue(isAllowedWebViewScheme("https"))
        assertTrue(isAllowedWebViewScheme("HTTPS"))
        assertFalse(isAllowedWebViewScheme("http-evil"))
        assertFalse(isAllowedWebViewScheme("file"))
        assertFalse(isAllowedWebViewScheme("javascript"))
        assertFalse(isAllowedWebViewScheme("content"))
        assertFalse(isAllowedWebViewScheme(""))
        assertFalse(isAllowedWebViewScheme(null))

        assertTrue(isAllowedWebViewUrl("https://example.com/login"))
        assertTrue(isAllowedWebViewUrl("http://example.com"))
        assertFalse(isAllowedWebViewUrl("http-evil://example.com"))
        assertFalse(isAllowedWebViewUrl("file:///etc/passwd"))
        assertFalse(isAllowedWebViewUrl("javascript:alert(1)"))
        assertFalse(isAllowedWebViewUrl(""))
        assertFalse(isAllowedWebViewUrl(null))
    }

    @Test
    fun `CookieStore normalizeCookieHost guards against null and empty hostKey`() {
        assertEquals(".example.com", CookieStore.normalizeCookieHost("example.com"))
        assertEquals(".example.com", CookieStore.normalizeCookieHost(".example.com"))
        assertNull(CookieStore.normalizeCookieHost(""))
        assertNull(CookieStore.normalizeCookieHost("   "))
        assertNull(CookieStore.normalizeCookieHost(null))
    }
}
