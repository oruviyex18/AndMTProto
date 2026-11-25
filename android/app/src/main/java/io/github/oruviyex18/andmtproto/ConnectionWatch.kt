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
    private var cfOkAtProgress = 0

    /** The last [update] saw bytes coming back. */
    var progressed = false
        private set

    /** CF proxy connections that opened since bytes last came back. */
    var cfOpened = 0
        private set

    /** Feeds one status sample taken at [now] (ms); true while the proxy looks stuck. */
    fun update(status: ProxyStatus, now: Long): Boolean {
        progressed = lastProgressAt < 0 || status.bytesDown != bytesDown
        if (progressed) {
            lastProgressAt = now
            totalAtProgress = status.total
            bytesDown = status.bytesDown
            cfOkAtProgress = status.cfOk
            cfOpened = 0
            return false
        }
        cfOpened = status.cfOk - cfOkAtProgress
        return now - lastProgressAt >= windowMs && status.total - totalAtProgress >= minAttempts
    }
}

/**
 * The CF ways that "Choose automatically" saw carry nothing on a network although
 * its check passed them. On GPB Mobile on 25 Nov 2026 the check kept passing TLS
 * with the bypass (Telegram answered, and over 24 KB went each way), but Telegram's
 * own connections got nothing back for 20 minutes, while no TLS carried 700 KB in a
 * minute: the watchdog restarted the core three times and the check picked the
 * bypass again each time (on 24 Nov the same stall lasted two hours, with nine
 * restarts). Kept for one network ([on]); another network starts empty. A way stays
 * set aside for [keepMs], also across the user's stops and starts, so each start
 * doesn't wait through the stall again; a reboot starts afresh.
 */
internal class StalledWays(private val keepMs: Long = 6 * 60 * 60_000L) {
    private var network: Any? = null
    private val since = mutableMapOf<CfTransport, Long>()

    /** The ways set aside on [on] at [now] (ms, elapsedRealtime). */
    fun on(on: Any, now: Long): Set<CfTransport> =
        if (on != network) emptySet() else since.filterValues { now - it < keepMs }.keys

    /** Sets [way] aside on [on] at [now]; false if it already was. */
    fun add(on: Any, way: CfTransport, now: Long): Boolean {
        if (on != network) {
            network = on
            since.clear()
        }
        if (way in on(on, now)) return false
        since[way] = now
        return true
    }

    /** Takes [way] back on [on]: it's the only way the check still passes. */
    fun remove(on: Any, way: CfTransport) {
        if (on == network) since.remove(way)
    }
}

/**
 * What a CF check leads to: the way to use ([target], null when none works),
 * whether the network cuts every way off ([cutOff], from the check alone), and
 * whether [target] was set aside and is taken back ([takeBack]).
 */
internal data class CfOutcome(val target: CfTransport?, val cutOff: Boolean, val takeBack: Boolean = false)

/**
 * The way "Choose automatically" uses after a check: the first that works of those
 * not set aside ([stalled]). When only set-aside ways pass, it keeps [current] if the
 * check passes it, else takes back the first set-aside way that works: a way that
 * stalled once beats one the check fails now (port 80 blocked later, say).
 */
internal fun cfCheckOutcome(probe: ProxyCore.CfProbe, stalled: Set<CfTransport>, current: CfTransport): CfOutcome {
    val all = chooseCfTransport(probe)
    val rest = chooseCfTransport(probe, stalled).target
    return when {
        rest != null -> CfOutcome(rest, all.cutOff)
        all.target == null -> CfOutcome(null, all.cutOff)
        probe.works(current) -> CfOutcome(current, all.cutOff)
        else -> CfOutcome(all.target, all.cutOff, takeBack = true)
    }
}

/** What "Choose automatically" does first about a stall; see [cfStallStep]. */
internal enum class CfStallStep {
    /** Check the CF connection again: blocking can start mid-session. */
    RECHECK,

    /** The way in use carries nothing although CF connections open: try another. */
    SET_ASIDE,

    /** Nothing for the CF choice to do: the watchdog goes on (restart, then the card). */
    NONE,
}

/**
 * The first step for a stall in "Choose automatically" ([auto]: on, with the CF
 * proxy and a network): a recheck once per network ([checkedHere]: done on this
 * one), then, if CF connections opened during the stall ([cfOpened]) and still
 * nothing came back, setting the way in use ([current]) aside unless it already is.
 */
internal fun cfStallStep(
    auto: Boolean, checkedHere: Boolean, cfOpened: Int,
    current: CfTransport, stalled: Set<CfTransport>,
): CfStallStep = when {
    !auto -> CfStallStep.NONE
    !checkedHere -> CfStallStep.RECHECK
    cfOpened > 0 && current !in stalled -> CfStallStep.SET_ASIDE
    else -> CfStallStep.NONE
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
