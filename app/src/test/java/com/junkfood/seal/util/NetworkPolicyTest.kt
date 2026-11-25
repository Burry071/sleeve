package com.junkfood.seal.util

import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.RestartableAction
import com.junkfood.seal.ui.page.downloadv2.configure.applyAllowAlways
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate is a pure predicate on purpose: no ConnectivityManager, no MMKV, no
 * clock. Every row of the truth table is pinned here so a later "simplification"
 * cannot quietly let a metered network start a transfer.
 */
class NetworkPolicyTest {

    @After
    fun tearDown() {
        NetworkPolicy.resetForTest()
    }

    @Test
    fun thePermitTableMatchesTheContract() {
        // (cellular preference, metered, permit armed) -> allowed
        val table =
            listOf(
                Triple(Triple(false, false, false), true, "unmetered always allows"),
                Triple(Triple(false, false, true), true, "unmetered with a permit"),
                Triple(Triple(true, false, false), true, "cellular allowed, unmetered"),
                Triple(Triple(true, false, true), true, "cellular allowed, unmetered, permit"),
                Triple(Triple(false, true, false), false, "metered blocks by default"),
                Triple(Triple(false, true, true), true, "armed permit allows metered"),
                Triple(Triple(true, true, false), true, "cellular preference allows metered"),
                Triple(Triple(true, true, true), true, "both reasons allow metered"),
            )
        table.forEach { (input, expected, label) ->
            val (cellularAllowed, metered, permitArmed) = input
            assertEquals(
                label,
                expected,
                NetworkPolicy.permits(
                    cellularAllowed = cellularAllowed,
                    metered = metered,
                    permitArmed = permitArmed,
                ),
            )
        }
    }

    @Test
    fun aMeteredNetworkBlocksWithoutThePreferenceOrAPermit() {
        assertFalse(
            NetworkPolicy.permits(cellularAllowed = false, metered = true, permitArmed = false)
        )
    }

    @Test
    fun anUnmeteredNetworkNeverNeedsAPermit() {
        assertTrue(
            NetworkPolicy.permits(cellularAllowed = false, metered = false, permitArmed = false)
        )
    }

    @Test
    fun thePermitIsOnlyAHintWhenThePreferenceAlreadyAllows() {
        assertTrue(NetworkPolicy.permits(cellularAllowed = true, metered = true, permitArmed = false))
        assertTrue(NetworkPolicy.permits(cellularAllowed = true, metered = true, permitArmed = true))
    }

    @Test
    fun thePermitWindowIsFiveMinutes() {
        // The window is the "Allow once" promise: this burst, not forever.
        assertEquals(5L * 60L * 1000L, NetworkPolicy.PERMIT_WINDOW_MS)
    }

    @Test
    fun aTaskMayStartOnlyWhenThePermitCoversIt() {
        // Same table as [NetworkPolicy.permits], with the permit resolved per
        // task: a live permit for someone else's burst must not let this task
        // through.
        assertTrue(NetworkPolicy.permitsTask(cellularAllowed = true, metered = true, permitCovers = false))
        assertTrue(NetworkPolicy.permitsTask(cellularAllowed = false, metered = false, permitCovers = false))
        assertFalse(NetworkPolicy.permitsTask(cellularAllowed = false, metered = true, permitCovers = false))
        assertTrue(NetworkPolicy.permitsTask(cellularAllowed = false, metered = true, permitCovers = true))
    }

    @Test
    fun anUnboundPermitCoversWhicheverBurstStartsFirst() {
        // "Allow once" from the dialog has no task list: it binds to the queue
        // on the first start attempt, so the burst the user approved runs and
        // a link shared afterwards meets the gate again.
        assertTrue(NetworkPolicy.scopeCovers(emptySet(), "task-1"))
        assertTrue(NetworkPolicy.scopeCovers(setOf("task-1", "task-2"), "task-2"))
        assertFalse(NetworkPolicy.scopeCovers(setOf("task-1"), "task-2"))
    }

    @Test
    fun isMeteredParkedCoversBothIdleAndReadyWithInfoOnlyWhenDownloadsCannotStart() {
        assertTrue(NetworkPolicy.isMeteredParked(DownloadState.Idle, canStart = false))
        assertTrue(NetworkPolicy.isMeteredParked(DownloadState.ReadyWithInfo, canStart = false))

        assertFalse(NetworkPolicy.isMeteredParked(DownloadState.Idle, canStart = true))
        assertFalse(NetworkPolicy.isMeteredParked(DownloadState.ReadyWithInfo, canStart = true))

        val nonParked =
            listOf(
                DownloadState.FetchingInfo(Job(), "t1"),
                DownloadState.Running(Job(), "t1", 0.5f, "50%"),
                DownloadState.Completed("/tmp/a.mp4"),
                DownloadState.Canceled(RestartableAction.Download),
                DownloadState.Error(Throwable("boom"), RestartableAction.Download),
            )
        for (state in nonParked) {
            assertFalse(
                "Expected $state not to be metered-parked when blocked",
                NetworkPolicy.isMeteredParked(state, canStart = false),
            )
            assertFalse(
                "Expected $state not to be metered-parked when allowed",
                NetworkPolicy.isMeteredParked(state, canStart = true),
            )
        }
    }

    @Test
    fun applyAllowAlwaysRefreshesCanStartFlowBeforeResuming() {
        var cellularAllowed = false
        NetworkPolicy.cellularAllowedProvider = { cellularAllowed }
        NetworkPolicy.setMeteredForTest(true)
        assertFalse(NetworkPolicy.canStartFlow.value)

        var canStartDuringResume = false
        applyAllowAlways(
            persistCellularAllowed = { cellularAllowed = true },
            resume = { canStartDuringResume = NetworkPolicy.canStartFlow.value },
        )

        assertTrue(NetworkPolicy.canStartFlow.value)
        assertTrue(canStartDuringResume)
    }

    @Test
    fun meteredPermitExpiresAfterWindowAndBlocksAgain() {
        var nowMs = 1_000L
        NetworkPolicy.cellularAllowedProvider = { false }
        NetworkPolicy.clockMsProvider = { nowMs }
        NetworkPolicy.setMeteredForTest(true)
        assertFalse(NetworkPolicy.canStartFlow.value)

        NetworkPolicy.allowMeteredOnce(setOf("task-1"))
        assertTrue(NetworkPolicy.canStartTask("task-1"))
        assertFalse(NetworkPolicy.canStartTask("task-2"))

        nowMs += NetworkPolicy.PERMIT_WINDOW_MS + 1L
        assertFalse(NetworkPolicy.canStartTask("task-1"))
        assertFalse(NetworkPolicy.canStartFlow.value)
    }
}
