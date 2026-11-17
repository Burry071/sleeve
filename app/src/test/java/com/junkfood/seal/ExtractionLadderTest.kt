package com.junkfood.seal

import com.junkfood.seal.download.engine.ExtractionClients
import com.junkfood.seal.download.engine.YOUTUBE_CLIENT_LADDER
import com.junkfood.seal.download.engine.downloadClientLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YouTube's native client set needs no JS runtime but is the first thing a bot
 * check refuses, so the ladder starts on yt-dlp's own selection and only then
 * tries the TV/embedded pair and visionOS.
 */
class ExtractionLadderTest {

    private val taskKey = "ladder-test"

    @Test
    fun `no recorded win starts on yt-dlp's own clients`() {
        assertEquals(listOf<String?>(null, "tv,web_embedded", "visionos"), YOUTUBE_CLIENT_LADDER)
        assertEquals(
            YOUTUBE_CLIENT_LADDER,
            downloadClientLadder(recorded = null, hasRecordedWin = false),
        )
    }

    @Test
    fun `a null win is remembered and replayed first`() {
        ExtractionClients.record(taskKey, null)

        assertTrue(ExtractionClients.hasWin(taskKey))
        assertNull(ExtractionClients.get(taskKey))
        assertEquals(
            listOf<String?>(null, "tv,web_embedded", "visionos"),
            downloadClientLadder(
                recorded = ExtractionClients.get(taskKey),
                hasRecordedWin = ExtractionClients.hasWin(taskKey),
            ),
        )

        ExtractionClients.clear(taskKey)
    }

    @Test
    fun `a client win goes first without repeating itself`() {
        ExtractionClients.record(taskKey, "visionos")

        assertEquals(
            listOf<String?>("visionos", null, "tv,web_embedded"),
            downloadClientLadder(
                recorded = ExtractionClients.get(taskKey),
                hasRecordedWin = ExtractionClients.hasWin(taskKey),
            ),
        )

        ExtractionClients.clear(taskKey)
    }
}
