package com.junkfood.seal.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** The app's battery-optimization exemption, plus the way to ask for it. */
class BatterySettings(val isExempt: Boolean, private val request: () -> Unit) {
    fun open() = request()
}

/**
 * Both hosts that offer the exemption use this, so the availability probe and
 * the fallback cannot drift. Some OEM builds ship no handler for the request
 * screen; the app details page always resolves and carries the same toggle.
 */
@SuppressLint("BatteryLife")
@Composable
fun rememberBatterySettings(): BatterySettings {
    val context = LocalContext.current
    val pm =
        remember(context) { context.getSystemService(Context.POWER_SERVICE) as PowerManager }

    fun isExempt() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            pm.isIgnoringBatteryOptimizations(context.packageName)

    var exempt by remember { mutableStateOf(isExempt()) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            exempt = isExempt()
        }
    val requestIntent =
        remember(context) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
        }
    val detailsIntent =
        remember(context) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }

    return BatterySettings(isExempt = exempt) {
        val target = if (context.canResolve(requestIntent)) requestIntent else detailsIntent
        if (context.canResolve(target)) launcher.launch(target)
    }
}

private fun Context.canResolve(intent: Intent): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).isNotEmpty()
    else
        packageManager
            .queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong()),
            )
            .isNotEmpty()
