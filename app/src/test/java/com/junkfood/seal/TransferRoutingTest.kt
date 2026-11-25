package com.junkfood.seal

import com.junkfood.seal.download.Task
import com.junkfood.seal.download.backupComparisonKey
import com.junkfood.seal.download.engine.GhostHttpTransferEngine
import com.junkfood.seal.download.engine.Nm3u8TransferEngine
import com.junkfood.seal.download.engine.outputIsAudio
import com.junkfood.seal.download.engine.selectedFormats
import com.junkfood.seal.download.engine.singleFormatHandOffAllowed
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.Format
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The embedded engines only ever write one progressive stream. Handing them a
 * silent video strand loses the audio yt-dlp would have muxed, and an .mkv
 * remux is something they cannot do at all, so both must stay on yt-dlp.
 */
class TransferRoutingTest {

    private fun audio(id: String) =
        Format(formatId = id, acodec = "opus", vcodec = "none", ext = "webm")

    private fun silent(id: String) = Format(formatId = id, acodec = "none", vcodec = "avc1", ext = "mp4")

    private fun muxed(id: String) = Format(formatId = id, acodec = "aac", vcodec = "avc1", ext = "mp4")

    @Test
    fun `a single progressive stream is eligible`() {
        assertTrue(singleFormatHandOffAllowed(muxed("18"), mergeToMkv = false))
        assertTrue(singleFormatHandOffAllowed(audio("140"), mergeToMkv = false))
    }

    @Test
    fun `a video-only stream stays on yt-dlp`() {
        assertFalse(singleFormatHandOffAllowed(silent("137"), mergeToMkv = false))
    }

    @Test
    fun `an mkv remux stays on yt-dlp`() {
        assertFalse(singleFormatHandOffAllowed(muxed("18"), mergeToMkv = true))
    }

    @Test
    fun `nothing resolved means no handoff`() {
        assertFalse(singleFormatHandOffAllowed(null, mergeToMkv = false))
    }

    @Test
    fun `the format string resolves back to the picked formats`() {
        val formats = listOf(silent("137"), audio("251"), muxed("18"))

        assertEquals(listOf("137", "251"), selectedFormats("137+251", formats).map { it.formatId })
        assertEquals(listOf("18"), selectedFormats("18", formats).map { it.formatId })
        assertTrue(selectedFormats("", formats).isEmpty())
        assertTrue(selectedFormats("999", formats).isEmpty())
    }

    @Test
    fun `the audio folder follows the picked formats not the type flag alone`() {
        assertTrue(outputIsAudio(extractAudio = false, selected = listOf(audio("140"))))
        assertTrue(outputIsAudio(extractAudio = true, selected = emptyList()))
    }

    @Test
    fun `a video stream keeps the video folder even when it carries audio`() {
        assertFalse(outputIsAudio(extractAudio = false, selected = listOf(muxed("18"))))
        assertFalse(outputIsAudio(extractAudio = false, selected = listOf(silent("137"), audio("140"))))
        assertFalse(outputIsAudio(extractAudio = false, selected = emptyList()))
    }

    @Test
    fun `proxy and forceIpv4 disable Ghost and N_m3u8DL-RE handoff so yt-dlp honors routing`() {
        val base = DownloadUtil.DownloadPreferences.EMPTY
        assertTrue(GhostHttpTransferEngine.canUseGhost(base))
        assertTrue(Nm3u8TransferEngine.canUseNm3u8(base))

        val proxied = base.copy(proxy = true, proxyUrl = "http://127.0.0.1:8080")
        assertFalse(GhostHttpTransferEngine.canUseGhost(proxied))
        assertFalse(Nm3u8TransferEngine.canUseNm3u8(proxied))

        val ipv4Only = base.copy(forceIpv4 = true)
        assertFalse(GhostHttpTransferEngine.canUseGhost(ipv4Only))
        assertFalse(Nm3u8TransferEngine.canUseNm3u8(ipv4Only))
    }

    @Test
    fun `backupComparisonKey coalesces running progress ticks while preserving interrupted boundary`() {
        val job = Job()
        val view = Task.ViewState(url = "https://example.com/v", title = "Video")
        val indeterminate = Task.State(Task.DownloadState.Running(job, "t1", -1f, "starting"), null, view)
        val tick1 = Task.State(Task.DownloadState.Running(job, "t1", 0.12f, "12% 1.2 MB/s"), null, view)
        val tick2 = Task.State(Task.DownloadState.Running(job, "t1", 0.87f, "87% 4.5 MB/s"), null, view)
        val completed = Task.State(Task.DownloadState.Completed("/tmp/v.mp4"), null, view)

        assertNotEquals(indeterminate.backupComparisonKey(), tick1.backupComparisonKey())
        assertEquals(tick1.backupComparisonKey(), tick2.backupComparisonKey())
        assertNotEquals(tick2.backupComparisonKey(), completed.backupComparisonKey())
    }
}
