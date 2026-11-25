package com.junkfood.seal

import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.download.MatchCandidate
import com.junkfood.seal.download.MatchResult
import com.junkfood.seal.download.SwapOutcome
import com.junkfood.seal.download.SwapResult
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.RestartableAction
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SelectionState
import com.junkfood.seal.ui.page.downloadv2.configure.galleryCandidateUrl
import com.junkfood.seal.ui.page.downloadv2.configure.startsDownload
import com.junkfood.seal.ui.page.downloadv2.hasResumableBatchEntries
import com.junkfood.seal.ui.page.downloadv2.runningTransferLine
import com.junkfood.seal.ui.page.downloadv2.showDonePrimaryCta
import com.junkfood.seal.ui.page.videolist.archiveKeyFor
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.PlaylistResult
import com.junkfood.seal.util.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUiParityTest {

    private fun task(id: String) =
        Task(url = "https://example.com/$id", preferences = DownloadUtil.DownloadPreferences.EMPTY)

    // --- runningTransferLine --------------------------------------------------------

    @Test
    fun runningTransferLineFormatsYtDlpBytesSpeedAndEta() {
        assertEquals(
            "yt-dlp · 125.40MiB · 3.45MiB/s · ETA 00:21",
            runningTransferLine("[download]  42.3% of  125.40MiB at    3.45MiB/s ETA 00:21"),
        )
        assertEquals(
            "yt-dlp · 84.10MiB · 2.10MiB/s · ETA 00:14",
            runningTransferLine("[download]  65.0% of ~ 84.10MiB at  2.10MiB/s ETA 00:14 (frag 12/20)"),
        )
        assertEquals(
            "yt-dlp · 125.40MiB · 3.45MiB/s",
            runningTransferLine("[download] 100% of  125.40MiB in 00:00:36 at 3.45MiB/s"),
        )
    }

    @Test
    fun runningTransferLineFormatsGhostNm3u8AndAria2c() {
        assertEquals(
            "Ghost: 12.4 / 48.0 MB · 6.2 MB/s",
            runningTransferLine("Ghost: 12.4 / 48.0 MB • 6.2 MB/s"),
        )
        assertEquals(
            "Ghost: downloading…",
            runningTransferLine("Ghost: downloading…"),
        )
        assertEquals(
            "N_m3u8DL-RE · 12.34MB/27.40MB · 5.12MB/s · ETA 00:03",
            runningTransferLine("N_m3u8DL-RE: Vid 1920x1080 [45.0%] 12.34MB/27.40MB 5.12MB/s 00:03"),
        )
        assertEquals(
            "aria2c · 12MiB/48MiB · 5.2MiB/s · ETA 7s",
            runningTransferLine("[#1abc23 12MiB/48MiB(25%) CN:4 DL:5.2MiB ETA:7s]"),
        )
    }

    @Test
    fun runningTransferLineDropsBlankPostProcessingAndDestinationChatter() {
        assertNull(runningTransferLine(""))
        assertNull(runningTransferLine("   \n  "))
        assertNull(runningTransferLine("[Merger] Merging formats into \"clip.mp4\""))
        assertNull(runningTransferLine("[ExtractAudio] Destination: /tmp/song.mp3"))
        assertNull(runningTransferLine("[download] Destination: /storage/emulated/0/Download/v.mp4"))
    }

    // --- PlaylistBatchCard held-only Resume visibility ------------------------------

    @Test
    fun heldOnlyBatchHasNoResumableEntries() {
        val heldState =
            Task.State(
                downloadState = DownloadState.Canceled(RestartableAction.Download),
                videoInfo = null,
                viewState = Task.ViewState(),
                match = MatchResult(held = true, gateReason = "Duration mismatch"),
            )
        val canceledState =
            Task.State(
                downloadState = DownloadState.Canceled(RestartableAction.Download),
                videoInfo = null,
                viewState = Task.ViewState(),
            )
        val failedState =
            Task.State(
                downloadState = DownloadState.Error(Throwable("net"), RestartableAction.Download),
                videoInfo = null,
                viewState = Task.ViewState(),
            )

        assertFalse(hasResumableBatchEntries(listOf(task("1") to heldState)))
        assertTrue(hasResumableBatchEntries(listOf(task("1") to heldState, task("2") to canceledState)))
        assertTrue(hasResumableBatchEntries(listOf(task("3") to failedState)))
        assertFalse(
            hasResumableBatchEntries(
                listOf(
                    task("4") to
                        Task.State(
                            downloadState = DownloadState.Completed("/tmp/a.m4a"),
                            videoInfo = null,
                            viewState = Task.ViewState(),
                        )
                )
            )
        )
    }

    // --- MatchReviewSheet post-swap Done CTA ----------------------------------------

    @Test
    fun matchReviewPrimaryCtaDoesNotLockOutRemainingSwaps() {
        val queued =
            listOf(
                SwapResult(
                    taskId = "t1",
                    uiKey = "k1",
                    title = "Track A",
                    candidateUrl = "https://youtube.com/watch?v=aaaaaaaaaaa",
                    candidateTitle = "Alt A",
                    outcome = SwapOutcome.Queued,
                )
            )
        val failed =
            listOf(
                SwapResult(
                    taskId = "t2",
                    uiKey = "k2",
                    title = "Track B",
                    candidateUrl = "https://youtube.com/watch?v=bbbbbbbbbbb",
                    candidateTitle = "Alt B",
                    outcome = SwapOutcome.FetchFailed,
                )
            )

        // All swaps done and no new selection -> Done
        assertTrue(showDonePrimaryCta(results = queued, pendingSwapsCount = 0, running = false))
        // User selected a replacement for another remaining flagged row -> keep Apply active
        assertFalse(showDonePrimaryCta(results = queued, pendingSwapsCount = 1, running = false))
        // Swap currently running -> never Done
        assertFalse(showDonePrimaryCta(results = queued, pendingSwapsCount = 0, running = true))
        // Only failed swaps -> never Done
        assertFalse(showDonePrimaryCta(results = failed, pendingSwapsCount = 0, running = false))
        // No swaps run yet -> never Done
        assertFalse(showDonePrimaryCta(results = emptyList(), pendingSwapsCount = 0, running = false))
    }

    // --- Manual URL paste gallery-dl routing & PendingMetered contract --------------

    @Test
    fun galleryCandidateSkipsVideoFirstHostsAndFetchGalleryStartsDownload() {
        assertNull(galleryCandidateUrl(listOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ")))
        assertNull(galleryCandidateUrl(listOf("https://www.instagram.com/reel/C123456789/")))
        assertNull(galleryCandidateUrl(listOf("https://www.tiktok.com/@u/video/123456789")))
        assertEquals(
            "https://www.pixiv.net/en/artworks/12345678",
            galleryCandidateUrl(listOf("https://www.pixiv.net/en/artworks/12345678")),
        )
        assertTrue(Action.FetchGallery("https://www.pixiv.net/en/artworks/12345678").startsDownload())
    }

    // --- SelectionState carries session preferences with EMPTY default --------------

    @Test
    fun selectionStatesCarrySessionPreferencesWithBackwardCompatibleDefaults() {
        val customPrefs =
            DownloadUtil.DownloadPreferences.EMPTY.copy(
                cookies = true,
                downloadSubtitle = true,
                extractAudio = true,
            )
        assertEquals(
            DownloadUtil.DownloadPreferences.EMPTY,
            SelectionState.FormatSelection(VideoInfo(id = "v1")).preferences,
        )
        assertEquals(
            DownloadUtil.DownloadPreferences.EMPTY,
            SelectionState.PlaylistSelection(PlaylistResult(title = "p1")).preferences,
        )
        assertEquals(
            customPrefs,
            SelectionState.FormatSelection(VideoInfo(id = "v1"), preferences = customPrefs).preferences,
        )
        assertEquals(
            customPrefs,
            SelectionState.PlaylistSelection(PlaylistResult(title = "p1"), preferences = customPrefs)
                .preferences,
        )
    }

    // --- VideoDetailDrawer archive key extraction & blank URL guard -----------------

    @Test
    fun archiveKeyExtractsPlatformIdsAndReturnsNullForBlankOrBackfilledUrls() {
        assertEquals(
            "Youtube" to "dQw4w9WgXcQ",
            archiveKeyFor(
                DownloadedVideoInfo(
                    id = 1,
                    videoTitle = "t",
                    videoAuthor = "a",
                    videoUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    thumbnailUrl = "",
                    videoPath = "/tmp/a.mp4",
                    extractor = "Youtube",
                )
            ),
        )
        assertEquals(
            "Instagram" to "C9AbCdEf123",
            archiveKeyFor(
                DownloadedVideoInfo(
                    id = 2,
                    videoTitle = "t",
                    videoAuthor = "a",
                    videoUrl = "https://www.instagram.com/reel/C9AbCdEf123/",
                    thumbnailUrl = "",
                    videoPath = "/tmp/b.mp4",
                    extractor = "Instagram",
                )
            ),
        )
        assertNull(
            archiveKeyFor(
                DownloadedVideoInfo(
                    id = 3,
                    videoTitle = "local",
                    videoAuthor = "",
                    videoUrl = "",
                    thumbnailUrl = "",
                    videoPath = "/tmp/local.mp4",
                    extractor = "file",
                )
            )
        )
    }
}
