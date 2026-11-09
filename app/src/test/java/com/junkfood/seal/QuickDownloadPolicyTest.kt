package com.junkfood.seal

import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.download.Task
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.isTrustedQuickIntent
import com.junkfood.seal.util.withoutCookies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickDownloadPolicyTest {

    private val nonce = "2f8c4a6e-nonce"

    @Test
    fun `quick extra without a nonce is not trusted`() {
        assertFalse(isTrustedQuickIntent(true, false, providedNonce = null, expectedNonce = nonce))
    }

    @Test
    fun `quick extra with no stored nonce is not trusted`() {
        assertFalse(isTrustedQuickIntent(true, false, providedNonce = nonce, expectedNonce = null))
    }

    @Test
    fun `quick extra with a mismatched nonce is not trusted`() {
        assertFalse(isTrustedQuickIntent(true, false, providedNonce = "other", expectedNonce = nonce))
    }

    @Test
    fun `share actions always fall back to the sheet`() {
        assertFalse(isTrustedQuickIntent(true, true, providedNonce = nonce, expectedNonce = nonce))
    }

    @Test
    fun `absent quick extra is never trusted`() {
        assertFalse(isTrustedQuickIntent(false, false, providedNonce = nonce, expectedNonce = nonce))
    }

    @Test
    fun `in-app nonce match is trusted`() {
        assertTrue(isTrustedQuickIntent(true, false, providedNonce = nonce, expectedNonce = nonce))
    }

    @Test
    fun `anonymous preferences drop the cookie session and keep everything else`() {
        val prefs =
            DownloadUtil.DownloadPreferences.EMPTY.copy(
                cookies = true,
                privateDirectory = true,
                newTitle = "keep me",
            )
        val anonymous = prefs.withoutCookies()
        assertEquals(false, anonymous.cookies)
        assertEquals(true, anonymous.privateDirectory)
        assertEquals("keep me", anonymous.newTitle)
    }

    @Test
    fun `already cookie-less preferences are unchanged`() {
        val prefs = DownloadUtil.DownloadPreferences.EMPTY
        assertEquals(prefs, prefs.withoutCookies())
    }

    @Test
    fun `the gate is idempotent`() {
        val prefs = DownloadUtil.DownloadPreferences.EMPTY.copy(cookies = true)
        assertEquals(prefs.withoutCookies(), prefs.withoutCookies().withoutCookies())
    }

    @Test
    fun `quick tasks are plain URL tasks so no template can reach them`() {
        val prefs = DownloadUtil.DownloadPreferences.EMPTY.copy(cookies = true)
        val task = Task(url = "https://example.com/v", preferences = prefs.withoutCookies())

        assertTrue(task.type is Task.TypeInfo.URL)
        assertFalse(task.type is Task.TypeInfo.CustomCommand)
        assertFalse(task.preferences.cookies)
    }

    @Test
    fun `download preferences cannot carry a command template`() {
        val templateField =
            DownloadUtil.DownloadPreferences::class.java.declaredFields.firstOrNull {
                it.type == CommandTemplate::class.java
            }
        assertEquals(null, templateField)
    }
}
