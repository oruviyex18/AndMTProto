package io.github.oruviyex18.andmtproto.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.net.toUri
import io.github.oruviyex18.andmtproto.R

/**
 * Phone makers besides Xiaomi whose system stops background apps and keeps its own
 * "autostart" list, outside Android's battery optimisation. Unlike MIUI's switch,
 * their state can't be read, so [OemAutostartCard] stays until the user closes it.
 *
 * [screens] (package, activity) are the system screens for that list, tried in order: their names
 * differ by OS version, and some builds don't export them (the start then fails
 * and the next one is tried). Collected from the makers' settings apps as listed
 * by dontkillmyapp.com and AutoStarter-style libraries; not checked on these phones.
 */
enum class OemAutostart(@StringRes val hint: Int, val screens: List<Pair<String, String>>) {
    /** Huawei (EMUI, HarmonyOS): Phone Manager → App launch. */
    HUAWEI(
        R.string.oem_autostart_huawei,
        listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        ),
    ),

    /** Honor (MagicOS): the same screen in its own package since Honor left Huawei. */
    HONOR(
        R.string.oem_autostart_huawei,
        listOf(
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
    ),

    /**
     * Oppo, Realme and OnePlus (ColorOS, realme UI, OxygenOS 12+): Auto launch. OnePlus
     * phones still on OxygenOS 11 or older have their own screen, tried first.
     */
    COLOROS(
        R.string.oem_autostart_coloros,
        listOf(
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        ),
    ),

    /** Vivo and iQOO (Funtouch OS, OriginOS): Background startup / Autostart. */
    VIVO(
        R.string.oem_autostart_vivo,
        listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        ),
    ),
    ;

    companion object {
        /** The maker's autostart list for this phone, or null (Xiaomi has its own cards; stock Android none). */
        fun forDevice(manufacturer: String, brand: String): OemAutostart? {
            val names = listOf(manufacturer, brand).map { it.trim().lowercase() }
            return when {
                "honor" in names -> HONOR
                "huawei" in names -> HUAWEI
                names.any { it in setOf("oppo", "realme", "oneplus", "oplus") } -> COLOROS
                names.any { it in setOf("vivo", "iqoo") } -> VIVO
                else -> null
            }
        }
    }
}

/** The maker's autostart list on this phone (see [OemAutostart]), or null. */
internal val oemAutostart: OemAutostart? by lazy { OemAutostart.forDevice(Build.MANUFACTURER, Build.BRAND) }

/** Opens [oem]'s autostart list, else the app's system settings page. */
internal fun Context.openOemAutostart(oem: OemAutostart) {
    val opened = oem.screens.any { (pkg, activity) ->
        startActivitySafely(Intent().setComponent(ComponentName(pkg, activity)))
    }
    if (!opened) {
        startActivitySafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    }
}
