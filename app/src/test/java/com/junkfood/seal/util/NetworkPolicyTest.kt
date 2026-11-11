package com.junkfood.seal.util

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
}
