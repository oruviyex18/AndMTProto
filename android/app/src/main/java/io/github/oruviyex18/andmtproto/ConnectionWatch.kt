package io.github.oruviyex18.andmtproto

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TelephonyNetworkSpecifier
import android.os.Build
import android.telephony.TelephonyManager

/**
 * Notices when Telegram keeps opening connections through the proxy but nothing
 * gets through: no connection is routed and no bytes come back for [windowMs]
 * while at least [minAttempts] new connections arrived. That is what a dead or
 * blocked upstream network looks like from inside the proxy.
 */
internal class StallDetector(
    private val windowMs: Long = 60_000,
    private val minAttempts: Int = 3,
) {
    private var lastProgressAt = -1L
    private var totalAtProgress = 0
    private var routed = 0
    private var bytesDown = 0L

    /** The last [update] saw traffic or a newly routed connection. */
    var progressed = false
        private set

    /** Feeds one status sample taken at [now] (ms); true while the proxy looks stuck. */
    fun update(status: ProxyStatus, now: Long): Boolean {
        progressed = lastProgressAt < 0 || status.routed != routed || status.bytesDown != bytesDown
        if (progressed) {
            lastProgressAt = now
            totalAtProgress = status.total
            routed = status.routed
            bytesDown = status.bytesDown
            return false
        }
        return now - lastProgressAt >= windowMs && status.total - totalAtProgress >= minAttempts
    }
}

/**
 * "Wi-Fi", "mobile data (Beeline)", … for the log, from the network's transports.
 * Blocking differs by provider, so mobile data names its carrier; that needs no
 * permission. A Wi-Fi network's name would need the location permission, so it is left out.
 */
internal fun Context.describeNetwork(network: Network?): String {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val caps = network?.let { connectivity.getNetworkCapabilities(it) } ?: return "no network"
    val kinds = listOf(
        NetworkCapabilities.TRANSPORT_VPN to "VPN",
        NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
        NetworkCapabilities.TRANSPORT_CELLULAR to "mobile data",
        NetworkCapabilities.TRANSPORT_ETHERNET to "Ethernet",
    ).filter { (transport, _) -> caps.hasTransport(transport) }.map { it.second }
    val name = kinds.joinToString(" over ").ifEmpty { "other network" }
    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return name
    val carrier = carrierOf(caps)
    return if (carrier.isEmpty()) name else "$name ($carrier)"
}

/** The carrier a mobile network runs on: "Beeline", plus "roaming" when it is. */
private fun Context.carrierOf(caps: NetworkCapabilities): String = try {
    var telephony = getSystemService(TelephonyManager::class.java)
    if (Build.VERSION.SDK_INT >= 30) {
        // Dual SIM: the SIM this network belongs to, not the default one.
        val subId = (caps.networkSpecifier as? TelephonyNetworkSpecifier)?.subscriptionId
        if (subId != null) telephony = telephony.createForSubscriptionId(subId)
    }
    val operator = telephony.networkOperatorName.orEmpty().ifEmpty { telephony.simOperatorName.orEmpty() }
    val roaming = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
    listOf(operator.trim(), if (roaming) "roaming" else "").filter { it.isNotEmpty() }.joinToString(", ")
} catch (e: RuntimeException) {
    "" // e.g. no telephony on this device
}
