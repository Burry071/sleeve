package com.junkfood.seal

import com.junkfood.seal.util.resolveClipboardOffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The clipboard banner must mirror the clipboard's CURRENT content: it
 * appears when a link is copied, and disappears when the clipboard moves on
 * or empties, or the link was already handled (dismissed or downloaded).
 * The user-visible symptom it locks down: a stale "Link detected" banner
 * outliving the clipboard content it advertises.
 */
class ClipboardOfferTest {

    private fun resolve(detected: String?, handled: Set<String>) =
        resolveClipboardOffer(detected, handled)

    @Test
    fun `fresh link is offered`() {
        assertEquals("https://x.com/1", resolve("https://x.com/1", handled = emptySet()))
    }

    @Test
    fun `emptied or non-link clipboard clears the offer`() {
        // THE reported symptom: the banner outlived its clipboard content.
        assertEquals(null, resolve(null, handled = emptySet()))
    }

    @Test
    fun `changed link replaces the offer`() {
        assertEquals(
            "https://y.com/2",
            resolve("https://y.com/2", handled = emptySet()),
        )
    }

    @Test
    fun `dismissed link stays dismissed`() {
        assertEquals(
            null,
            resolve("https://x.com/1", handled = setOf("https://x.com/1")),
        )
    }

    @Test
    fun `downloaded link is never re-offered even on a fresh copy`() {
        assertEquals(
            null,
            resolve("https://x.com/1", handled = setOf("https://x.com/1")),
        )
    }

    @Test
    fun `a different link is still offered after one was handled`() {
        assertEquals(
            "https://y.com/2",
            resolve("https://y.com/2", handled = setOf("https://x.com/1")),
        )
    }
}
