package io.github.oruviyex18.andmtproto

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * True if only apps on this phone can connect to [host]. Same rule as _is_loopback
 * in android_entry.py; literal addresses only, so it never does a DNS lookup.
 */
fun isLoopbackHost(host: String): Boolean {
    val h = host.trim().lowercase()
    if (h == "localhost" || h == "::1" || h == "0:0:0:0:0:0:0:1") return true
    val parts = h.split(".")
    return parts.size == 4 && parts[0] == "127" &&
        parts.all { p -> p.isNotEmpty() && p.all(Char::isDigit) && p.length <= 3 && p.toInt() <= 255 }
}

/**
 * User settings. [toJson] uses the same keys as the desktop tray config
 * (utils/default_config.py) so android_entry.py can map them 1:1 to ProxyConfig.
 */
data class ProxySettings(
    val host: String = "127.0.0.1",
    val port: Int = 1443,
    val secret: String = "",
    val dcIp: List<String> = listOf("2:149.154.167.220", "4:149.154.167.220"),
    /** WebSockets per DC opened ahead of time, while the screen is on (see ws_pool_control.py). */
    val poolSize: Int = 4,
    val bufKb: Int = 256,
    val cfproxy: Boolean = true,
    val h2: Boolean = true,
    val cfUserDomainEnabled: Boolean = false,
    val cfUserDomains: String = "",
    val cfWorkerEnabled: Boolean = false,
    val cfWorkerDomains: String = "",
    val fakeTlsDomain: String = "",
    val forceTestDc: Boolean = false,
    val noSecure: Boolean = false,
    /**
     * Android-only: "Choose automatically" for the CF connection (the default).
     * ProxyService then checks the ways to reach the CF proxy and keeps the first
     * that works in [noSecure] and [tlsBypass]; off, those two are the user's choice
     * and never change by themselves.
     */
    val cfAuto: Boolean = true,
    /** Android-only: TLS-blocking bypass for the CF proxy (tls_bypass.py); on by default. */
    val tlsBypass: Boolean = true,
    /** Android-only: the bypass strategy, ByeDPI-style or "auto" ([TlsStrategies]). */
    val tlsStrategy: String = TlsStrategies.DEFAULT,
    /** Android-only: Cloudflare names through DNS over HTTPS first (cf_dns.py); else only when the system's DNS fails. */
    val cfDoh: Boolean = false,
    /** Android-only: one Cloudflare IP for all Cloudflare hosts, or empty (cf_dns.py). */
    val cfIp: String = "",
    val verbose: Boolean = false,
    /** Android-only; ignored by the Python core. On for new installs. */
    val startOnBoot: Boolean = true,
    /** Android-only: the daily [UpdateCheck]. */
    val checkUpdates: Boolean = true,
) {
    /** How the core connects to the CF proxy now, from [noSecure] and [tlsBypass]; see [cfAuto]. */
    val cfTransport: CfTransport
        get() = when {
            noSecure -> CfTransport.PLAIN
            tlsBypass -> CfTransport.BYPASS
            else -> CfTransport.TLS
        }

    /** The user picks [t] for good: no automatic switching. */
    fun withCfTransport(t: CfTransport): ProxySettings = withCurrentCfTransport(t).copy(cfAuto = false)

    /** "Choose automatically", starting from the bypass until a check picks another way. */
    fun withCfAuto(): ProxySettings = withCurrentCfTransport(CfTransport.BYPASS).copy(cfAuto = true)

    /** Uses [t] now, keeping [cfAuto]: what ProxyService does when its check picks [t]. */
    fun withCurrentCfTransport(t: CfTransport): ProxySettings = when (t) {
        CfTransport.BYPASS -> copy(noSecure = false, tlsBypass = true)
        CfTransport.TLS -> copy(noSecure = false, tlsBypass = false)
        CfTransport.PLAIN -> copy(noSecure = true) // the bypass choice is kept for later
    }

    fun toJson(): JSONObject = JSONObject()
        .put("host", host)
        .put("port", port)
        .put("secret", secret)
        .put("dc_ip", JSONArray(dcIp))
        .put("pool_size", poolSize)
        .put("buf_kb", bufKb)
        .put("cfproxy", cfproxy)
        .put("h2", h2)
        .put("cfproxy_user_domain_enabled", cfUserDomainEnabled)
        .put("cfproxy_user_domain", cfUserDomains)
        .put("cfproxy_worker_enabled", cfWorkerEnabled)
        .put("cfproxy_worker_domain", cfWorkerDomains)
        .put("fake_tls_domain", fakeTlsDomain)
        .put("force_test_dc", forceTestDc)
        .put("no_secure", noSecure)
        .put("tls_bypass", tlsBypass)
        .put("tls_strategy", tlsStrategy)
        .put("cf_doh", cfDoh)
        .put("cf_ip", cfIp)
        .put("cf_auto", cfAuto)
        .put("verbose", verbose)
        .put("start_on_boot", startOnBoot)
        .put("check_updates", checkUpdates)

    companion object {
        fun fromJson(o: JSONObject): ProxySettings {
            val d = ProxySettings()
            val dc = o.optJSONArray("dc_ip")
            return ProxySettings(
                host = o.optString("host", d.host),
                port = o.optInt("port", d.port),
                secret = o.optString("secret", d.secret),
                dcIp = if (dc == null) d.dcIp else List(dc.length()) { dc.getString(it) },
                poolSize = o.optInt("pool_size", d.poolSize),
                bufKb = o.optInt("buf_kb", d.bufKb),
                cfproxy = o.optBoolean("cfproxy", d.cfproxy),
                h2 = o.optBoolean("h2", d.h2),
                cfUserDomainEnabled = o.optBoolean("cfproxy_user_domain_enabled", d.cfUserDomainEnabled),
                cfUserDomains = o.optString("cfproxy_user_domain", d.cfUserDomains),
                cfWorkerEnabled = o.optBoolean("cfproxy_worker_enabled", d.cfWorkerEnabled),
                cfWorkerDomains = o.optString("cfproxy_worker_domain", d.cfWorkerDomains),
                fakeTlsDomain = o.optString("fake_tls_domain", d.fakeTlsDomain),
                forceTestDc = o.optBoolean("force_test_dc", d.forceTestDc),
                noSecure = o.optBoolean("no_secure", d.noSecure),
                tlsBypass = o.optBoolean("tls_bypass", d.tlsBypass),
                tlsStrategy = o.optString("tls_strategy", d.tlsStrategy),
                cfDoh = o.optBoolean("cf_doh", d.cfDoh),
                cfIp = o.optString("cf_ip", d.cfIp),
                // Before "Choose automatically" existed, the bypass (the default) already
                // switched by itself, so it becomes automatic; a choice of plain TLS or no
                // TLS stays (SettingsStore.load also moves a "no TLS" it set itself).
                cfAuto = o.optBoolean("cf_auto", !o.optBoolean("no_secure", false) && o.optBoolean("tls_bypass", true)),
                verbose = o.optBoolean("verbose", d.verbose),
                startOnBoot = o.optBoolean("start_on_boot", d.startOnBoot),
                checkUpdates = o.optBoolean("check_updates", d.checkUpdates),
            )
        }

        fun newSecret(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** How the core connects to the CF proxy and CF workers. */
enum class CfTransport {
    /** TLS with the ClientHello reshaped so DPI can't read the name in it; tried first. */
    BYPASS,
    /** Plain TLS, as upstream does. */
    TLS,
    /** No TLS, port 80 ("No TLS for CF proxy/worker" upstream). */
    PLAIN,
}

/** Ready-made bypass strategies; the syntax is described in tls_bypass.py. */
object TlsStrategies {
    /**
     * The core tries the presets below in this order (tls_bypass.CANDIDATES) at
     * every start and when it gets stuck, and uses the first that gets through.
     */
    const val AUTO = "auto"
    const val DEFAULT = AUTO

    /** Two TLS records split inside the name, sent as two TCP segments. Tested on 18 Nov 2026. */
    const val SPLIT = "--tlsrec 0+sm --split 2+sm"
    const val RECORDS_OOB = "--tlsrec 0+sm --oob 2+sm"
    const val OOB = "--oob 0+sm"
    const val PARENT_SNI = "--sni-parent"

    /** Presets in the order the settings list them. */
    val PRESETS = listOf(AUTO, SPLIT, RECORDS_OOB, OOB, PARENT_SNI)
}

/** "Choose automatically" tries the CF connections in this order and keeps the first that works. */
val CF_AUTO_ORDER = listOf(CfTransport.BYPASS, CfTransport.TLS, CfTransport.PLAIN)

/** What "Choose automatically" makes of a CF check; see [chooseCfTransport]. */
data class CfChoice(val target: CfTransport?, val cutOff: Boolean)

/**
 * The first way in [CF_AUTO_ORDER] that also carries large data, else the first
 * where Telegram at least answers; null when none does. [CfChoice.cutOff]: Telegram
 * answers, but the network cuts every way off after a few KB. Ways in [skip]
 * passed the check before but carried nothing for Telegram (see [StalledWays]).
 */
fun chooseCfTransport(probe: ProxyCore.CfProbe, skip: Set<CfTransport> = emptySet()): CfChoice {
    val order = CF_AUTO_ORDER.filter { it !in skip }
    val full = order.firstOrNull { probe.worksFully(it) }
    val target = full ?: order.firstOrNull { probe.works(it) }
    return CfChoice(target, cutOff = full == null && target != null)
}

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): ProxySettings {
        val json = prefs.getString(KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val stored = json?.let { runCatching { ProxySettings.fromJson(it) }.getOrNull() }
        if (stored == null) autoPort = true // fresh install: the default port may be taken
        var settings = stored ?: ProxySettings()
        if (json != null && !json.has("cf_auto") && prefs.getBoolean(KEY_NO_SECURE_AUTO, false)) {
            // Up to 1.11.4-android.10, "no TLS" that ProxyService set by itself; now "automatic".
            settings = settings.copy(cfAuto = true)
            prefs.edit { remove(KEY_NO_SECURE_AUTO) }
            save(settings)
        }
        if (settings.secret.isNotBlank()) return settings
        return settings.copy(secret = ProxySettings.newSecret()).also(::save)
    }

    fun save(settings: ProxySettings) {
        prefs.edit { putString(KEY, settings.toJson().toString()) }
        BootReceiver.setEnabled(appContext, settings.startOnBoot)
        UpdateCheck.schedule(appContext, settings.checkUpdates)
    }

    /** Called when the saved settings (or [autoPort]) change, e.g. from ProxyService. */
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    /**
     * From a fresh install until the proxy first listens or the user sets a port
     * themselves: a busy port is then replaced by a free one (see ProxyService).
     */
    var autoPort: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PORT, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_PORT, value) }

    private companion object {
        const val KEY = "config"
        const val KEY_AUTO_PORT = "auto_port"
        /** Up to 1.11.4-android.10: ProxyService had turned "no TLS" on by itself. Read once in [load]. */
        const val KEY_NO_SECURE_AUTO = "no_secure_auto"
    }
}
