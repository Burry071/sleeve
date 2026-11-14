package com.junkfood.seal

import com.junkfood.seal.util.isVerifiedCopy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedCopyTest {

    @Test
    fun `equal non-zero lengths are verified`() {
        assertTrue(isVerifiedCopy(sourceLength = 5L, destinationLength = 5L))
        assertTrue(isVerifiedCopy(sourceLength = 1L, destinationLength = 1L))
    }

    @Test
    fun `short and long destinations are unverified`() {
        assertFalse(isVerifiedCopy(sourceLength = 5L, destinationLength = 4L))
        assertFalse(isVerifiedCopy(sourceLength = 5L, destinationLength = 6L))
    }

    @Test
    fun `zero and unknown lengths are unverified`() {
        assertFalse(isVerifiedCopy(sourceLength = 0L, destinationLength = 0L))
        assertFalse(isVerifiedCopy(sourceLength = 5L, destinationLength = 0L))
        assertFalse(isVerifiedCopy(sourceLength = 5L, destinationLength = -1L))
        assertFalse(isVerifiedCopy(sourceLength = -1L, destinationLength = -1L))
    }
}
