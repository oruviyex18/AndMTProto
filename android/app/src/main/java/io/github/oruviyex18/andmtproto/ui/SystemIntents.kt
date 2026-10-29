package io.github.oruviyex18.andmtproto.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import io.github.oruviyex18.andmtproto.R

/** Starts [intent]; false when no app handles it or the ROM refuses it. */
internal fun Context.startActivitySafely(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

private fun Context.appDetailsIntent() =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())

internal fun Context.toast(resId: Int, long: Boolean = false) {
    Toast.makeText(this, resId, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

internal fun Context.toast(text: String) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

internal fun Context.copyToClipboard(text: String) {
    getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("AndMTProto", text))
    toast(R.string.link_copied)
}

/** Opens [url] in a browser, or copies it when there is none. */
internal fun Context.openUrl(url: String) {
    if (!startActivitySafely(Intent(Intent.ACTION_VIEW, url.toUri()))) copyToClipboard(url)
}

internal fun Context.openInTelegram(link: String) {
    if (!startActivitySafely(Intent(Intent.ACTION_VIEW, link.toUri()))) {
        toast(R.string.telegram_not_found, long = true)
    }
}

/**
 * The system list of battery-optimised apps, where the user exempts AndMTProto.
 * Not the direct exemption request: that needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
 * a permission Play Protect counts against apps installed outside Google Play.
 */
internal fun Context.openBatteryOptimizationSettings() {
    startActivitySafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
        startActivitySafely(appDetailsIntent())
}

/** MIUI's Autostart screen, or the app's system settings page where it doesn't exist. */
internal fun Context.openMiuiAutostart() {
    val autostart = Intent().setComponent(
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
    )
    startActivitySafely(autostart) || startActivitySafely(appDetailsIntent())
}

/** Xiaomi, Redmi and POCO phones run MIUI / HyperOS, which stops background apps on its own. */
internal val isXiaomiDevice: Boolean =
    listOf(Build.MANUFACTURER, Build.BRAND).any { brand ->
        listOf("Xiaomi", "Redmi", "POCO").any { brand.equals(it, ignoreCase = true) }
    }
