package com.junkfood.seal

import com.junkfood.seal.util.ContainerLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContainerLabelTest {

    @Test
    fun `known extensions are labelled upper case`() {
        assertEquals("MP4", ContainerLabel.containerLabel("mp4"))
        assertEquals("MKV", ContainerLabel.containerLabel("mkv"))
        assertEquals("WEBM", ContainerLabel.containerLabel("webm"))
        assertEquals("M4A", ContainerLabel.containerLabel("m4a"))
        assertEquals("XYZ", ContainerLabel.containerLabel("xyz"))
    }

    @Test
    fun `a finished plain path yields its container`() {
        assertEquals("MKV", ContainerLabel.of("/storage/emulated/0/Download/Clip.mkv"))
    }

    @Test
    fun `an sdcard document uri has no container`() {
        assertNull(
            ContainerLabel.of(
                "content://com.android.externalstorage.documents/tree/primary%3ADownload/" +
                    "document/primary%3ADownload%2Fclip.mkv"
            )
        )
    }

    @Test
    fun `a private mode null path has no container`() {
        assertNull(ContainerLabel.of(null))
    }

    @Test
    fun `an unknown or missing extension has no container`() {
        assertNull(ContainerLabel.of("/storage/emulated/0/Download/Clip.part"))
        assertNull(ContainerLabel.of("/storage/emulated/0/Download/Clip"))
    }

    @Test
    fun `a dot in a directory is not an extension`() {
        assertNull(ContainerLabel.of("/storage/emulated/0/my.folder/Clip"))
    }
}
