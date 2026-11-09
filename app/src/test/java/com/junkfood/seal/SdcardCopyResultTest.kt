package com.junkfood.seal

import com.junkfood.seal.util.sdcardCopyResult
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdcardCopyResultTest {

    @Test
    fun `all files copied is a success with their uris`() {
        val result = sdcardCopyResult(copied = listOf("a.mp3", "b.mp3"), failed = emptyList())

        assertEquals(listOf("a.mp3", "b.mp3"), result.getOrThrow())
    }

    @Test
    fun `any failed file fails the whole move`() {
        val result = sdcardCopyResult(copied = listOf("a.mp3"), failed = listOf("b.mp3"))

        val error = result.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("1 of 2"))
        assertTrue(error.message!!.contains("b.mp3"))
    }

    @Test
    fun `the original cause is kept for diagnostics`() {
        val cause = IllegalStateException("tree uri revoked")

        val result = sdcardCopyResult(copied = emptyList(), failed = listOf("a.mp3"), cause = cause)

        assertEquals(cause, result.exceptionOrNull()?.cause)
    }

    @Test
    fun `an empty temp directory is not a copy failure`() {
        val result = sdcardCopyResult(copied = emptyList(), failed = emptyList())

        assertTrue(result.isSuccess)
        assertEquals(emptyList<String>(), result.getOrThrow())
    }
}
