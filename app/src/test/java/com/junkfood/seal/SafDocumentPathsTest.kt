package com.junkfood.seal

import com.junkfood.seal.util.SafDocumentPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafDocumentPathsTest {

    private val prefix = "content://com.android.externalstorage.documents/document/"

    @Test
    fun `a primary volume document maps to the emulated path`() {
        assertEquals(
            "/storage/emulated/0/Download/Sleeve/clip.mp4",
            SafDocumentPaths.externalStoragePath(
                prefix + "primary%3ADownload%2FSleeve%2Fclip.mp4"
            ),
        )
    }

    @Test
    fun `a removable volume keeps its volume id`() {
        assertEquals(
            "/storage/1B0E-3A1F/Music/book.m4b",
            SafDocumentPaths.externalStoragePath(prefix + "1B0E-3A1F%3AMusic%2Fbook.m4b"),
        )
    }

    @Test
    fun `any other provider is not resolvable`() {
        assertNull(
            SafDocumentPaths.externalStoragePath(
                "content://com.example.drive/document/root%3AClip.mp4"
            )
        )
    }

    @Test
    fun `a traversal segment is refused`() {
        assertNull(
            SafDocumentPaths.externalStoragePath(prefix + "primary%3A..%2F..%2Fdata%2Fsecret")
        )
    }

    @Test
    fun `a plain path is not a document uri`() {
        assertNull(SafDocumentPaths.externalStoragePath("/storage/emulated/0/Download/a.mp4"))
    }
}
