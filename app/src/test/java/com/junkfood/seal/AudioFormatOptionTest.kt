package com.junkfood.seal

import com.junkfood.seal.download.engine.DownloadSettingsSnapshot
import com.junkfood.seal.download.engine.addOptionsForAudioDownloads
import com.junkfood.seal.util.CONVERT_MP3
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFormatOptionTest {

    private fun command(preferences: DownloadPreferences): List<String> =
        YoutubeDLRequest("https://example.com/watch?v=abc")
            .addOptionsForAudioDownloads(
                id = "abc",
                settings =
                    DownloadSettingsSnapshot(
                        preferences = preferences,
                        engineEnabled = false,
                        preferMp4 = false,
                        mergeOutputMkv = false,
                        customUserAgentEnabled = false,
                    ),
                playlistUrl = "",
            )
            .buildCommand()

    @Test
    fun `an explicit audio pick still converts to mp3 when asked`() {
        val command =
            command(
                DownloadPreferences.EMPTY.copy(
                    formatIdString = "251",
                    convertAudio = true,
                    audioConvertFormat = CONVERT_MP3,
                )
            )

        // -x picks the audio stream; -f names the exact stream the user
        // tapped; --audio-format is a postprocessor and must survive both.
        assertTrue("missing -f 251 in $command", command.containsPair("-f", "251"))
        assertTrue(
            "missing --audio-format mp3 in $command",
            command.containsPair("--audio-format", "mp3"),
        )
    }

    private fun List<String>.containsPair(option: String, value: String): Boolean =
        withIndex().any { (index, item) -> item == option && getOrNull(index + 1) == value }
}
