package com.junkfood.seal

import com.junkfood.seal.util.DownloadErrorMessages.StorageMoveError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageMoveErrorTest {

    @Test
    fun `the typed fields survive construction`() {
        val error =
            StorageMoveError(
                copied = 2,
                failed = listOf("b.mp4", "c.mp4"),
                retainedDir = "/storage/emulated/0/Download/Sleeve/tmp/abc",
            )

        assertEquals(2, error.copied)
        assertEquals(listOf("b.mp4", "c.mp4"), error.failed)
        assertEquals("/storage/emulated/0/Download/Sleeve/tmp/abc", error.retainedDir)
    }

    @Test
    fun `the message is built from the fields`() {
        val error = StorageMoveError(1, listOf("b.mp4"), "/tmp/abc")

        assertEquals("SD card copy failed for 1 of 2 file(s): b.mp4", error.message)
        assertTrue(error.message!!.contains("1 of 2"))
    }

    @Test
    fun `a null cause is constructible`() {
        // createDocument and openOutputStream return null without throwing.
        val error = StorageMoveError(0, listOf("a.mp4"), "/tmp/abc", cause = null)

        assertNull(error.cause)
    }
}
