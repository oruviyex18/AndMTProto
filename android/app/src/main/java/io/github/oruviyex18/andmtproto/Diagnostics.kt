package io.github.oruviyex18.andmtproto

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import java.net.Inet4Address
import java.net.Inet6Address

/*
 * Lines for the log at every core start, so a log a user sends says what it ran
 * on. Nothing here identifies the user: no Wi-Fi name (Android hides it without
 * the location permission anyway), no domains, no secrets, no DNS server name.
 */

/** "App 1.11.4-android.7 (17); Xiaomi M2101K6G, Android 13 (SDK 33), build V14.0.6.0.TKFMIXM; …" */
internal fun Context.deviceSummary(): String {
    val power = getSystemService(PowerManager::class.java)
    val unrestricted = power?.isIgnoringBatteryOptimizations(packageName) == true
    val notifications = NotificationManagerCompat.from(this).areNotificationsEnabled()
    return "App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); " +
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
        "build ${buildText(Build.VERSION.INCREMENTAL)}; battery optimisation ${if (unrestricted) "off" else "on"}, " +
        "notifications ${if (notifications) "on" else "off"}"
}

/**
 * "V14.0.6.0.TKFRUXM" -> "V14.0.6.0 (TKFRUXM)": the log hides anything that looks
 * like a domain (DomainCensorFilter), and a build ending in letters does.
 */
internal fun buildText(incremental: String): String =
    incremental.replace(Regex("""\.([A-Za-z][A-Za-z0-9]*)$"""), " ($1)").replace(Regex("""\.(?=[A-Za-z])"""), " ")

/** The settings that change how traffic goes, without domains or secrets. */
internal fun settingsSummary(s: ProxySettings, noSecureAuto: Boolean): String {
    val cf = if (!s.cfproxy) "off" else when (s.cfTransport) {
        CfTransport.BYPASS -> "TLS with the bypass (${s.tlsStrategy})"
        CfTransport.TLS -> "plain TLS"
        CfTransport.PLAIN -> "no TLS" + if (noSecureAuto) ", set automatically" else ", set by the user"
    }
    fun count(enabled: Boolean, list: String): String {
        val n = list.split(',', '\n', ' ').count { it.isNotBlank() }
        return if (enabled && n > 0) "$n" else "off"
    }
    return "Settings: CF proxy $cf; own CF domains ${count(s.cfUserDomainEnabled, s.cfUserDomains)}, " +
        "CF Worker ${count(s.cfWorkerEnabled, s.cfWorkerDomains)}, " +
        "Fake TLS ${if (s.fakeTlsDomain.isBlank()) "off" else "on"}, " +
        "DC routes ${s.dcIp.size}, pool ${s.poolSize}, buffer ${s.bufKb} KB" +
        (if (s.forceTestDc) ", test DCs" else "") + (if (s.verbose) ", verbose" else "")
}

/**
 * "validated, Wi-Fi 5 GHz -54 dBm, IPv4 192.168.50.0/24, IPv6 yes, Private DNS off,
 * MTU 1500": what tells one network from another and what often breaks connections.
 */
internal fun Context.networkDetails(network: Network?): String {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val caps = network?.let { connectivity.getNetworkCapabilities(it) } ?: return "Network details: none"
    val link = connectivity.getLinkProperties(network)
    val parts = mutableListOf<String>()
    parts += if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "validated" else "not validated"
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) parts += "metered"
    if (Build.VERSION.SDK_INT >= 29) {
        (caps.transportInfo as? WifiInfo)?.let { wifi ->
            val band = when (wifi.frequency) {
                in 2400..2500 -> "2.4 GHz"
                in 4900..5900 -> "5 GHz"
                in 5925..7125 -> "6 GHz"
                else -> null
            }
            parts += listOfNotNull("Wi-Fi", band, wifi.rssi.takeIf { it in -126..0 }?.let { "$it dBm" })
                .joinToString(" ")
        }
    }
    link?.linkAddresses.orEmpty().forEach { la ->
        val a = la.address
        if (a is Inet4Address) {
            // A public address would identify the user: only private ranges are shown.
            parts += if (isPrivateIpv4(a.address)) {
                "IPv4 ${ipv4Network(a.address, la.prefixLength)}/${la.prefixLength}"
            } else {
                "IPv4 public"
            }
        }
    }
    val ipv6 = link?.linkAddresses.orEmpty().any {
        val a = it.address
        a is Inet6Address && !a.isLinkLocalAddress && !a.isSiteLocalAddress && !a.isLoopbackAddress
    }
    parts += "IPv6 ${if (ipv6) "yes" else "no"}"
    if (Build.VERSION.SDK_INT >= 28 && link != null) {
        parts += "Private DNS " + when {
            link.privateDnsServerName != null -> "strict"
            link.isPrivateDnsActive -> "automatic"
            else -> "off"
        }
    }
    if (Build.VERSION.SDK_INT >= 29 && (link?.mtu ?: 0) > 0) parts += "MTU ${link?.mtu}"
    return "Network details: " + parts.joinToString(", ")
}

/** 10/8, 172.16/12, 192.168/16, and the carriers' shared 100.64/10. */
internal fun isPrivateIpv4(a: ByteArray): Boolean {
    val b0 = a[0].toInt() and 0xFF
    val b1 = a[1].toInt() and 0xFF
    return b0 == 10 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168) || (b0 == 100 && b1 in 64..127)
}

/** The network part of an IPv4 address: 192.168.50.17/24 -> "192.168.50.0". */
internal fun ipv4Network(address: ByteArray, prefix: Int): String {
    val bits = address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    val mask = if (prefix <= 0) 0L else (0xFFFFFFFFL shl (32 - prefix.coerceAtMost(32))) and 0xFFFFFFFFL
    val net = bits and mask
    return (3 downTo 0).joinToString(".") { ((net shr (it * 8)) and 0xFF).toString() }
}
