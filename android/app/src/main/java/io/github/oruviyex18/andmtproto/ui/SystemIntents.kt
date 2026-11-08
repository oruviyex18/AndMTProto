package io.github.oruviyex18.andmtproto.ui

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.net.toUri
import io.github.oruviyex18.andmtproto.R
import java.lang.reflect.Method

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

internal fun Context.copyToClipboard(text: String, @StringRes copied: Int = R.string.link_copied) {
    getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("AndMTProto", text))
    toast(copied)
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

/** MIUI's app op for the Autostart switch ("MIUIOP(10008)" in `cmd appops get`). */
private const val MIUI_OP_AUTO_START = 10008

/**
 * The hidden AppOpsManager.checkOpNoThrow(int, int, String), looked up once: MIUI keeps
 * Autostart as its own app op, and the public API takes only op names.
 */
private val checkOpNoThrow: Method? by lazy {
    runCatching {
        AppOpsManager::class.java.getMethod(
            "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
        )
    }.getOrNull()
}

/**
 * Whether MIUI's Autostart is on for this app, or null where it can't be read
 * (including MODE_ERRORED, which says the check failed, not that the switch is off).
 */
internal fun Context.miuiAutostartEnabled(): Boolean? {
    val check = checkOpNoThrow ?: return null
    return try {
        val ops = getSystemService(AppOpsManager::class.java)
        when (check.invoke(ops, MIUI_OP_AUTO_START, applicationInfo.uid, packageName) as Int) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_IGNORED -> false
            else -> null
        }
    } catch (_: ReflectiveOperationException) {
        null // e.g. "Bad operation #10008" on a ROM without MIUI's ops
    } catch (_: RuntimeException) {
        null
    }
}

/** A system property ("" if unset), or null where it can't be read (android.os.SystemProperties is hidden). */
@SuppressLint("PrivateApi") // the MIUI version properties have no public API; failure is handled
private fun systemProperty(name: String): String? = runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        .invoke(null, name) as String
}.getOrNull()

/**
 * The phone runs MIUI or HyperOS, which stop background apps on their own and have
 * the Autostart switch. Xiaomi phones on stock Android (Android One Mi A series,
 * custom ROMs) don't, so the brand alone isn't enough. If the properties can't be
 * read at all, assume MIUI on Xiaomi brands, as the brand check did before.
 */
internal val isMiui: Boolean by lazy {
    val xiaomiBrand = listOf(Build.MANUFACTURER, Build.BRAND).any { brand ->
        listOf("Xiaomi", "Redmi", "POCO").any { brand.equals(it, ignoreCase = true) }
    }
    if (!xiaomiBrand) return@lazy false
    // MIUI sets the first, HyperOS both (e.g. "V816" and "OS3.0").
    val versions = listOf("ro.miui.ui.version.name", "ro.mi.os.version.name").map(::systemProperty)
    versions.all { it == null } || versions.any { !it.isNullOrEmpty() }
}
