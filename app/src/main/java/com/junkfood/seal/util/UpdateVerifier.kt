package com.junkfood.seal.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** The digest GitHub publishes for a release asset, if any. */
internal sealed interface PublishedDigest {
    /** The release carries no digest; verification falls back to signatures. */
    data object Absent : PublishedDigest

    data class Sha256(val hex: String) : PublishedDigest

    /** A digest is published but not in a form this build understands. */
    data object Unsupported : PublishedDigest
}

internal fun parsePublishedDigest(raw: String?): PublishedDigest {
    if (raw.isNullOrBlank()) return PublishedDigest.Absent
    val hex = raw.removePrefix("sha256:")
    val isSha256 =
        raw.startsWith("sha256:") &&
            hex.length == 64 &&
            hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    return if (isSha256) PublishedDigest.Sha256(hex.lowercase()) else PublishedDigest.Unsupported
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Integrity gate for the update APK: the published SHA-256 when the release has
 * one, plus signing-certificate continuity with the installed app. Returns null
 * when the file is safe to hand to the installer, otherwise the reason it is not.
 */
object UpdateVerifier {

    fun verify(context: Context, file: File, expectedDigest: String?): String? {
        if (expectedDigest != null && !sha256Hex(file).equals(expectedDigest, ignoreCase = true)) {
            return "Update APK checksum mismatch"
        }

        val archiveInfo = archiveInfo(context, file) ?: return "Update is not a valid APK"

        // A debug build installs the release app side by side: the package names
        // differ, so the system applies no signature continuity and neither do we.
        if (archiveInfo.packageName != context.packageName) return null

        val installedInfo =
            installedInfo(context) ?: return "Cannot read the installed signing certificate"
        val installedCerts =
            signerCertificates(installedInfo)
                ?: return "Cannot read the installed signing certificate"
        val archiveCerts =
            signerCertificates(archiveInfo) ?: return "Cannot read the update signing certificate"

        return if (installedCerts == archiveCerts) null else "Update is signed by a different key"
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(context: Context, file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNATURES,
            )
        }

    @Suppress("DEPRECATION")
    private fun installedInfo(context: Context): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun signerCertificates(info: PackageInfo): Set<String>? {
        val certs: Array<Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                info.signatures
            }
        return certs?.takeIf { it.isNotEmpty() }?.map { sha256Hex(it.toByteArray()) }?.toSet()
    }
}
