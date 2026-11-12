package com.junkfood.seal

import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.TaskFactory
import com.junkfood.seal.download.TaskOrigin
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.isTrustedQuickIntent
import com.junkfood.seal.util.withoutCookies
import java.io.File
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

    /**
     * Quick lane: the only sender is the nonce-fenced shortcut. It builds a plain URL task from
     * cookie-less preferences, so neither `--cookies` nor `--config-locations` can be emitted for
     * it no matter what the stored preferences say.
     */
    @Test
    fun `quick lane is a URL task that can emit neither cookies nor template config`() {
        val task =
            Task(
                url = "https://example.com/v",
                preferences =
                    DownloadUtil.DownloadPreferences.EMPTY.copy(cookies = true).withoutCookies(),
                origin = TaskOrigin.Quick,
            )

        assertTrue(task.type is Task.TypeInfo.URL)
        assertFalse(task.type is Task.TypeInfo.CustomCommand)
        assertFalse(task.preferences.cookies)
        assertEquals("cookies: none (quick mode)", task.cookieAudit)
    }

    /**
     * Metadata lane: catalogue matches become MetadataTrack tasks. Option B of the fence review
     * accepts that this lane keeps the user's cookie preference — it is an authenticated, in-app
     * lane, not the fenced one. The pinned truth is therefore: cookies only when the user asked
     * for them, and never a command template (the sole `--config-locations` path).
     */
    @Test
    fun `metadata lane tracks the user cookie preference but never carries a template`() {
        val withCookies =
            TaskFactory.createMetadataTasks(
                    provider = "Spotify",
                    playlistTitle = "P",
                    batchId = "b1",
                    tracks = listOf(Task.TypeInfo.MetadataTrack(title = "T", artist = "A")),
                    preferences = DownloadUtil.DownloadPreferences.EMPTY.copy(cookies = true),
                )
                .single()
                .task
        assertTrue(withCookies.type is Task.TypeInfo.MetadataTrack)
        assertFalse(withCookies.type is Task.TypeInfo.CustomCommand)
        assertTrue(
            "user asked for cookies, so the lane keeps them",
            withCookies.preferences.cookies,
        )
        assertEquals(TaskOrigin.User, withCookies.origin)
        assertEquals("cookies: on (user pref)", withCookies.cookieAudit)

        val withoutCookies =
            TaskFactory.createMetadataTasks(
                    provider = "Audible",
                    playlistTitle = "P",
                    batchId = "b2",
                    tracks = listOf(Task.TypeInfo.MetadataTrack(title = "T", artist = "A")),
                    preferences = DownloadUtil.DownloadPreferences.EMPTY.copy(cookies = false),
                )
                .single()
                .task
        assertFalse(withoutCookies.preferences.cookies)
        assertEquals("cookies: none (user pref)", withoutCookies.cookieAudit)
    }

    /**
     * Source tripwire for `--cookies`: one literal, inside the single helper that every lane
     * funnels through. A second literal anywhere else means a lane bypassed the gate — the
     * behavior pins above cannot see that, this can.
     */
    @Test
    fun `the cookies flag has exactly one producer`() {
        val text = downloadUtilSource()
        val needle = "addOption(\"--cookies\""

        assertEquals("--cookies must be emitted from exactly one place", 1, occurrences(text, needle))
        assertEquals("enableCookies", enclosingFunction(text, text.indexOf(needle)))
    }

    /**
     * Source tripwire for `--config-locations`: exactly one builder, and nothing else. Quick and
     * metadata lanes never reach it because they never build a CustomCommand.
     */
    @Test
    fun `template config flags live only in the command builder`() {
        val text = downloadUtilSource()
        val needle = "\"--config-locations\""

        assertEquals(
            "--config-locations must have exactly one producer",
            1,
            occurrences(text, needle),
        )

        val builders =
            Regex(Regex.escape(needle))
                .findAll(text)
                .map { enclosingFunction(text, it.range.first) }
                .toList()
        assertEquals(listOf("executeCustomCommandTask"), builders)
    }

    /**
     * The only place a CustomCommand task can be born is the factory. Both user entry points (the
     * configure sheet and the task list's new-task sheet) call it; quick and metadata lanes never
     * name the type at all.
     */
    @Test
    fun `custom command tasks have exactly one producer`() {
        val factory =
            sourceFile("src/main/java/com/junkfood/seal/download/TaskFactory.kt")
        val needle = "TypeInfo.CustomCommand("

        assertEquals(1, occurrences(factory, needle))
        assertEquals("createCustomCommand", enclosingFunction(factory, factory.indexOf(needle)))

        val configureSheet =
            sourceFile(
                "src/main/java/com/junkfood/seal/ui/page/downloadv2/configure/DownloadDialogViewModel.kt"
            )
        assertEquals(0, occurrences(configureSheet, needle))
        assertTrue(configureSheet.contains("TaskFactory.createCustomCommand("))

        val taskSheet = sourceFile("src/main/java/com/junkfood/seal/ui/page/command/TaskListPage.kt")
        assertEquals(0, occurrences(taskSheet, needle))
        assertTrue(taskSheet.contains("TaskFactory.createCustomCommand("))
    }

    @Test
    fun `download preferences cannot carry a command template`() {
        val templateField =
            DownloadUtil.DownloadPreferences::class.java.declaredFields.firstOrNull {
                it.type == CommandTemplate::class.java
            }
        assertEquals(null, templateField)
    }

    private fun downloadUtilSource() =
        sourceFile("src/main/java/com/junkfood/seal/util/DownloadUtil.kt")

    private fun sourceFile(relativePath: String): String {
        val candidates = listOf(File(relativePath), File("app/$relativePath"))
        val file =
            candidates.firstOrNull { it.isFile }
                ?: throw AssertionError(
                    "cannot locate $relativePath; tried ${candidates.joinToString { it.absolutePath }}"
                )
        return file.readText()
    }

    private fun occurrences(text: String, needle: String) =
        Regex(Regex.escape(needle)).findAll(text).count()

    private fun enclosingFunction(text: String, index: Int): String? =
        FUN_DECL.findAll(text).lastOrNull { it.range.first < index }?.groupValues?.get(1)

    private companion object {
        private val FUN_DECL =
            Regex(
                """(?m)^[ \t]*(?:(?:private|internal|public|protected|override|suspend|inline|operator|open|abstract|external|tailrec)\s+)*fun\s+(?:[A-Za-z0-9_.<>?, \[\]]+\.)?([A-Za-z_][A-Za-z0-9_]*)"""
            )
    }
}
