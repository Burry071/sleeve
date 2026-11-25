package com.junkfood.seal.util

import com.junkfood.seal.BuildConfig
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    private val arm64 = "Sleeve-arm64-release.apk" to "sha256:" + "aa".repeat(32)
    private val x86_64 = "Sleeve-x86_64-release.apk" to "sha256:" + "bb".repeat(32)
    private val generic = "Sleeve-generic-release.apk" to "sha256:" + "cc".repeat(32)

    private fun release(vararg assets: Pair<String, String>): JsonObject =
        Json.parseToJsonElement(
                """
                {"assets":[
                  ${assets.joinToString(",") { (name, digest) ->
                      """{"name":"$name","browser_download_url":"https://example.invalid/$name","digest":"$digest"}"""
                  }}
                ]}
                """
            )
            .jsonObject

    private fun JsonObject.assetName(): String? = this["name"]?.jsonPrimitive?.content

    private fun JsonObject.assetDigest(): String? = this["digest"]?.jsonPrimitive?.content

    @Test
    fun arm64DeviceSelectsTheArm64AssetAndItsDigest() {
        val asset =
            AppUpdateManager.releaseAsset(
                release(generic, x86_64, arm64),
                listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
            )

        assertEquals(arm64.first, asset?.assetName())
        assertEquals(arm64.second, asset?.assetDigest())
    }

    @Test
    fun x86_64DeviceSelectsTheX86_64AssetAndItsDigest() {
        val asset =
            AppUpdateManager.releaseAsset(
                release(generic, x86_64, arm64),
                listOf("x86_64", "x86"),
            )

        assertEquals(x86_64.first, asset?.assetName())
        assertEquals(x86_64.second, asset?.assetDigest())
    }

    @Test
    fun theFirstSupportedAbiWins() {
        val asset =
            AppUpdateManager.releaseAsset(release(arm64, x86_64), listOf("x86_64", "arm64-v8a"))

        assertEquals(x86_64.first, asset?.assetName())
    }

    @Test
    fun unknownAbiFallsBackToTheUniversalAsset() {
        val asset =
            AppUpdateManager.releaseAsset(
                release(arm64, generic),
                listOf("armeabi-v7a", "armeabi"),
            )

        assertEquals(generic.first, asset?.assetName())
        assertEquals(generic.second, asset?.assetDigest())
    }

    @Test
    fun missingPreferredAssetFallsBackToTheUniversalAsset() {
        val asset = AppUpdateManager.releaseAsset(release(generic), listOf("arm64-v8a"))

        assertEquals(generic.first, asset?.assetName())
        assertEquals(generic.second, asset?.assetDigest())
    }

    @Test
    fun releaseWithoutAUsableAssetIsSkipped() {
        assertNull(AppUpdateManager.releaseAsset(release(arm64), listOf("armeabi-v7a")))
    }

    @Test
    fun theStableChannelRefusesPrereleases() {
        // A beta tag must never be offered to someone who asked for stable.
        assertFalse(AppUpdateManager.channelAccepts(prerelease = true, channel = STABLE))
        assertTrue(AppUpdateManager.channelAccepts(prerelease = true, channel = PRE_RELEASE))
    }

    @Test
    fun theStableChannelAcceptsFullReleases() {
        assertTrue(AppUpdateManager.channelAccepts(prerelease = false, channel = STABLE))
    }

    @Test
    fun thePrereleaseChannelTakesBothKinds() {
        assertTrue(AppUpdateManager.channelAccepts(prerelease = false, channel = PRE_RELEASE))
        assertTrue(AppUpdateManager.channelAccepts(prerelease = true, channel = PRE_RELEASE))
    }

    @Test
    fun inAppUpdaterBuildConfigFlagMatchesProductFlavor() {
        if (BuildConfig.FLAVOR == "fdroid") {
            assertFalse(BuildConfig.ENABLE_IN_APP_UPDATER)
        } else {
            assertTrue(BuildConfig.ENABLE_IN_APP_UPDATER)
        }
    }

    @Test
    fun sanitizeReleaseTagAcceptsValidSemverTags() {
        assertEquals("2.1.2", AppUpdateManager.sanitizeReleaseTag("v2.1.2"))
        assertEquals("2.1.2", AppUpdateManager.sanitizeReleaseTag("V2.1.2"))
        assertEquals("2.1.2", AppUpdateManager.sanitizeReleaseTag("2.1.2"))
        assertEquals("2.2.0-beta.1", AppUpdateManager.sanitizeReleaseTag("v2.2.0-beta.1"))
        assertEquals("2.2.0-alpha.9", AppUpdateManager.sanitizeReleaseTag("2.2.0-alpha.9"))
        assertEquals("2.2.0-rc1", AppUpdateManager.sanitizeReleaseTag("v2.2.0-rc1"))
    }

    @Test
    fun sanitizeReleaseTagRejectsPathTraversalAndArbitrarySuffixes() {
        val maliciousTags =
            listOf(
                "",
                "vv2.1.2",
                "vV2.1.2",
                "VV2.1.2",
                "v9.9.9/../../evil",
                "9.9.9/../../evil",
                "../2.1.2",
                "2.1.2..",
                "2.1.2\\evil",
                "2.1.2-beta.1/nested",
                "2.1.2-beta.1..evil",
                "v2.1.2\u0000evil",
                "2.1.2;rm -rf /",
            )
        for (tag in maliciousTags) {
            assertNull("Expected tag to be rejected: $tag", AppUpdateManager.sanitizeReleaseTag(tag))
        }
    }

    @Test
    fun apkFileForVersionConfinesFileToUpdatesDirAndRejectsTraversal() {
        val updatesDir = Files.createTempDirectory("sleeve-updates-test").toFile()
        try {
            val valid = AppUpdateManager.apkFileForVersion(updatesDir, "v2.1.2")
            assertNotNull(valid)
            assertEquals("Sleeve-2.1.2-release.apk", valid!!.name)
            assertEquals(updatesDir.canonicalFile, valid.parentFile?.canonicalFile)

            assertNull(AppUpdateManager.apkFileForVersion(updatesDir, "2.1.2/../../evil"))
            assertNull(AppUpdateManager.apkFileForVersion(updatesDir, "../evil"))
            assertNull(AppUpdateManager.apkFileForVersion(updatesDir, "2.1.2\\..\\evil"))
        } finally {
            updatesDir.deleteRecursively()
        }
    }
}
