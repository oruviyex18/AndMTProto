package io.github.oruviyex18.andmtproto

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

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

/** "Wi-Fi", "mobile data", … for the log, from the network's transports. */
internal fun ConnectivityManager.describe(network: Network?): String {
    val caps = network?.let { getNetworkCapabilities(it) } ?: return "no network"
    val kinds = listOf(
        NetworkCapabilities.TRANSPORT_VPN to "VPN",
        NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
        NetworkCapabilities.TRANSPORT_CELLULAR to "mobile data",
        NetworkCapabilities.TRANSPORT_ETHERNET to "Ethernet",
    ).filter { (transport, _) -> caps.hasTransport(transport) }.map { it.second }
    return kinds.joinToString(" over ").ifEmpty { "other network" }
}
