package com.junkfood.seal

import com.junkfood.seal.util.PublishedDigest
import com.junkfood.seal.util.parsePublishedDigest
import com.junkfood.seal.util.sha256Hex
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateVerifierTest {

    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun hashesBytesAsLowercaseSha256() {
        assertEquals(abcSha256, sha256Hex("abc".toByteArray()))
    }

    @Test
    fun hashesAFileThroughTheSameDigest() {
        val file = File.createTempFile("update-verify", ".bin")
        try {
            file.writeBytes("abc".toByteArray())
            assertEquals(abcSha256, sha256Hex(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun publishedSha256IsAcceptedInAnyCase() {
        assertEquals(
            PublishedDigest.Sha256(abcSha256),
            parsePublishedDigest("sha256:${abcSha256.uppercase()}"),
        )
    }

    @Test
    fun missingDigestIsAbsentRatherThanAFailure() {
        assertEquals(PublishedDigest.Absent, parsePublishedDigest(null))
        assertEquals(PublishedDigest.Absent, parsePublishedDigest(""))
    }

    @Test
    fun otherAlgorithmsAndMalformedHashesAreUnsupported() {
        assertEquals(
            PublishedDigest.Unsupported,
            parsePublishedDigest("md5:d41d8cd98f00b204e9800998ecf8427e"),
        )
        assertEquals(PublishedDigest.Unsupported, parsePublishedDigest("sha256:abc"))
        assertEquals(PublishedDigest.Unsupported, parsePublishedDigest(abcSha256))
    }
}
