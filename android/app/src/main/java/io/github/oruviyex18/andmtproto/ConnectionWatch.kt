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
 * gets through: no bytes come back for [windowMs] while at least [minAttempts]
 * new connections arrived. That is what a dead or blocked upstream network looks
 * like from inside the proxy. A connection routed upstream is not progress by
 * itself: on 18 Nov 2026 Beeline LTE let the CF proxy's WebSockets open over
 * the TLS split, and then nothing came back on them.
 */
internal class StallDetector(
    private val windowMs: Long = 60_000,
    private val minAttempts: Int = 3,
) {
    private var lastProgressAt = -1L
    private var totalAtProgress = 0
    private var bytesDown = 0L

    /** The last [update] saw bytes coming back. */
    var progressed = false
        private set

    /** Feeds one status sample taken at [now] (ms); true while the proxy looks stuck. */
    fun update(status: ProxyStatus, now: Long): Boolean {
        progressed = lastProgressAt < 0 || status.bytesDown != bytesDown
        if (progressed) {
            lastProgressAt = now
            totalAtProgress = status.total
            bytesDown = status.bytesDown
            return false
        }
        return now - lastProgressAt >= windowMs && status.total - totalAtProgress >= minAttempts
    }
}

/**
 * Notices when connections to the CF proxy keep failing although other traffic
 * flows, which [StallDetector] can't see. On an MTS Wi-Fi on 19 Nov 2026 the
 * bypass stopped getting through mid-session: over an hour, ~2300 CF hosts didn't
 * open while 20 did, so photos from DC1 and the CDN never loaded, while DC2 and DC4
 * worked over direct WebSockets. Within [windowMs]: at least [minFailed] failures
 * and [ratio] times as many as CF connections that opened.
 */
internal class CfFailureDetector(
    private val windowMs: Long = 120_000,
    private val minFailed: Int = 20,
    private val ratio: Int = 10,
) {
    private val samples = ArrayDeque<Triple<Long, Int, Int>>() // (time, cfOk, cfFailed)

    /** The failures and openings in the window, from the last [update]. */
    var failed = 0
        private set
    var opened = 0
        private set

    /** Feeds one status sample taken at [now] (ms); true while CF keeps failing. */
    fun update(status: ProxyStatus, now: Long): Boolean {
        samples.addLast(Triple(now, status.cfOk, status.cfFailed))
        // Keep the newest sample that is at least the window old as the baseline.
        while (samples.size > 1 && now - samples[1].first >= windowMs) samples.removeFirst()
        val (_, ok, failedThen) = samples.first()
        failed = status.cfFailed - failedThen
        opened = status.cfOk - ok
        return failed >= minFailed && failed >= ratio * opened
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
    val roaming = Build.VERSION.SDK_INT >= 28 && // older versions lack the capability
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
    listOf(operator.trim(), if (roaming) "roaming" else "").filter { it.isNotEmpty() }.joinToString(", ")
} catch (e: RuntimeException) {
    "" // e.g. no telephony on this device
}
