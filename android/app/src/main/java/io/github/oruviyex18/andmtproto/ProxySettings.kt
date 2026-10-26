package io.github.oruviyex18.andmtproto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * User settings. [toJson] uses the same keys as the desktop tray config
 * (utils/default_config.py) so android_entry.py can map them 1:1 to ProxyConfig.
 */
data class ProxySettings(
    val host: String = "127.0.0.1",
    val port: Int = 1443,
    val secret: String = "",
    val dcIp: List<String> = listOf("2:149.154.167.220", "4:149.154.167.220"),
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
    val verbose: Boolean = false,
) {
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
        .put("verbose", verbose)

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
                verbose = o.optBoolean("verbose", d.verbose),
            )
        }

        fun newSecret(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): ProxySettings {
        val stored = prefs.getString(KEY, null)?.let {
            runCatching { ProxySettings.fromJson(JSONObject(it)) }.getOrNull()
        }
        val settings = stored ?: ProxySettings()
        if (settings.secret.isNotBlank()) return settings
        return settings.copy(secret = ProxySettings.newSecret()).also(::save)
    }

    fun save(settings: ProxySettings) {
        prefs.edit().putString(KEY, settings.toJson().toString()).apply()
    }

    private companion object {
        const val KEY = "config"
    }
}
