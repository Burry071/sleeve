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

    @Test
    fun `a tree-scoped document uri resolves to the external storage path`() {
        assertEquals(
            "/storage/1234-5678/Music/track.mp3",
            SafDocumentPaths.externalStoragePath(
                "content://com.android.externalstorage.documents/tree/1234-5678%3AMusic/document/1234-5678%3AMusic%2Ftrack.mp3"
            ),
        )
        assertEquals(
            "/storage/emulated/0/Download/Sleeve/clip.mp4",
            SafDocumentPaths.externalStoragePath(
                "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2FSleeve%2Fclip.mp4"
            ),
        )
    }

    @Test
    fun `multi-byte UTF-8 percent-encoded filenames decode without corruption`() {
        assertEquals(
            "/storage/emulated/0/Music/café.mp3",
            SafDocumentPaths.externalStoragePath(prefix + "primary%3AMusic%2Fcaf%C3%A9.mp3"),
        )
        assertEquals(
            "/storage/emulated/0/Music/日本語.mp3",
            SafDocumentPaths.externalStoragePath(
                prefix + "primary%3AMusic%2F%E6%97%A5%E6%9C%AC%E8%AA%9E.mp3"
            ),
        )
        assertEquals(
            "/storage/emulated/0/Music/café+日本語.mp3",
            SafDocumentPaths.externalStoragePath(
                prefix + "primary%3AMusic%2Fcaf%C3%A9+日本語.mp3"
            ),
        )
    }

    @Test
    fun `volume traversal and invalid volume ids are rejected`() {
        assertNull(
            SafDocumentPaths.externalStoragePath(prefix + "..%2Fetc%3Apasswd")
        )
        assertNull(
            SafDocumentPaths.externalStoragePath(prefix + "..%3Asecret.mp4")
        )
        assertNull(
            SafDocumentPaths.externalStoragePath(prefix + ".%3Asecret.mp4")
        )
        assertNull(
            SafDocumentPaths.externalStoragePath(prefix + "a%2Fb%3Aclip.mp4")
        )
        assertNull(
            SafDocumentPaths.externalStoragePath(prefix + "a%5Cb%3Aclip.mp4")
        )
    }
}
