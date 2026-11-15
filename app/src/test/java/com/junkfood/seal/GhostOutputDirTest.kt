package com.junkfood.seal

import com.junkfood.seal.download.engine.ghostOutputDir
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GhostOutputDirTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun dir(
        sdcardStagingDir: File? = null,
        privateDirectory: Boolean = false,
        isAudioOnly: Boolean = false,
    ) =
        ghostOutputDir(
            sdcardStagingDir = sdcardStagingDir,
            privateDirectory = privateDirectory,
            privateDir = "/private",
            audioDir = "/audio",
            videoDir = "/video",
            isAudioOnly = isAudioOnly,
        )

    @Test
    fun `a save-to-sd-card download stages in the sdcard temp dir`() {
        // The finish line moves files from this folder; anywhere else and the
        // transfer completes with nothing to publish.
        val staging = tmp.newFolder("dcim-qa-saf").resolve("B002V1OF70")

        assertEquals(staging.absolutePath, dir(sdcardStagingDir = staging))
    }

    @Test
    fun `the staging folder wins over the private and local folders`() {
        val staging = tmp.newFolder("dcim-qa-saf").resolve("id")

        assertEquals(
            staging.absolutePath,
            dir(sdcardStagingDir = staging, privateDirectory = true, isAudioOnly = true),
        )
    }

    @Test
    fun `without a card the lane keeps its own folder`() {
        assertEquals("/video", dir())
        assertEquals("/audio", dir(isAudioOnly = true))
        assertEquals("/private", dir(privateDirectory = true))
    }
}
