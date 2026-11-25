package io.github.oruviyex18.andmtproto

import android.content.Context
import android.net.Network
import android.util.Log
import com.chaquo.python.Python
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors

/** An error from android_entry.py; [code] is one of the codes documented there. */
data class ProxyError(val code: String, val detail: String? = null, val suggestedPort: Int? = null) {
    companion object {
        fun fromJson(o: JSONObject) = ProxyError(
            code = o.getString("code"),
            detail = if (o.isNull("detail")) null else o.getString("detail"),
            suggestedPort = if (o.isNull("suggested_port")) null else o.getInt("suggested_port"),
        )
    }
}

data class ProxyStatus(
    val running: Boolean = false,
    /** False while the core prepares before binding, e.g. probing the Fake TLS site. */
    val listening: Boolean = false,
    val error: ProxyError? = null,
    val coreVersion: String = "",
    val active: Int = 0,
    /** Since the proxy last started. */
    val total: Int = 0,
    val lastHour: Int = 0,
    /** Handshakes rejected for a wrong secret in about the last 10 minutes. */
    val badRecent: Int = 0,
    /** Connections since start that went over WebSocket / Cloudflare (CF proxy and H2). */
    val ws: Int = 0,
    val cf: Int = 0,
    /** Connections that got past the handshake: Telegram is using this proxy. */
    val routed: Int = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    /** CF proxy WebSockets that opened since start, and hosts that didn't open one. */
    val cfOk: Int = 0,
    val cfFailed: Int = 0,
    /** CF proxy attempts in the last 2 minutes: route → outcome → count; see [cfTrouble]. */
    val cfRecent: Map<String, Map<String, Int>> = emptyMap(),
    /** Why the user's own Workers all fail, if they do (a [CfFailureReason] key). */
    val workerTrouble: String? = null,
)

/**
 * Kotlin side of android_entry.py and the app-wide proxy [state].
 *
 * Lifecycle calls run on one dedicated thread ([dispatcher]), so they never
 * overlap and [state] updates arrive in call order.
 */
object ProxyCore {
    private const val TAG = "ProxyCore"
    private const val LOG_TAIL_BYTES = 64 * 1024

    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "python") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val module by lazy { Python.getInstance().getModule("android_entry") }

    private val _state = MutableStateFlow(ProxyStatus())
    val state: StateFlow<ProxyStatus> = _state.asStateFlow()

    private val _stalled = MutableStateFlow(false)

    /**
     * Telegram keeps connecting but nothing gets through, even after ProxyService
     * restarted the core by itself; the main screen then suggests what to try.
     */
    val stalled: StateFlow<Boolean> = _stalled.asStateFlow()

    fun setStalled(value: Boolean) {
        _stalled.value = value
    }

    /**
     * The last "Choose automatically" check of the CF connection: on which network
     * ([networkLabel], e.g. "Wi-Fi"), when (System.currentTimeMillis) and the way it
     * picked; [chosen] is null when no way worked. [cutOff]: Telegram answers, but
     * the network cuts every way off after about 16 KB, so [chosen] barely works.
     * [failed]: the check itself failed; [chosen] is then the way kept.
     * Set by ProxyService, and cleared when the network changes.
     */
    data class CfCheck(
        val network: Network?,
        val networkLabel: String,
        val atMillis: Long,
        val chosen: CfTransport?,
        val cutOff: Boolean = false,
        val failed: Boolean = false,
    )

    private val _cfCheck = MutableStateFlow<CfCheck?>(null)
    val cfCheck: StateFlow<CfCheck?> = _cfCheck.asStateFlow()

    fun setCfCheck(value: CfCheck?) {
        _cfCheck.value = value
    }

    /** Writes [message] to the proxy log (dropped before the first start sets the log up). */
    fun note(message: String) {
        scope.launch {
            try {
                module.callAttr("note", message)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot write to the proxy log", e)
            }
        }
    }

    /**
     * Pauses the core's pool of WebSockets opened ahead of time, or fills it again
     * (android_entry.set_pool_paused). Also before [start]: the core then starts
     * paused. Runs in call order with [start] and [stop].
     */
    fun setPoolPaused(paused: Boolean) {
        scope.launch {
            try {
                module.callAttr("set_pool_paused", paused)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot pause or resume the WebSocket pool", e)
            }
        }
    }

    /** Starts the proxy thread; returns the config error, or null once it is starting. */
    suspend fun start(context: Context, settings: ProxySettings): ProxyError? = withContext(dispatcher) {
        val logDir = logDir(context).absolutePath
        val error = module.callAttr("start", settings.toJson().toString(), logDir)
            ?.let { ProxyError.fromJson(JSONObject(it.toString())) }
        readStatus()
        error
    }

    /** [keepStalled]: an automatic restart, which by itself doesn't mean data flows again. */
    /**
     * The next check of the TLS strategies (the core's at start, or [probeCf]) starts
     * afresh instead of taking the last one's result: for a new network, a start by the
     * user, or when nothing gets through.
     */
    suspend fun forgetCfPick() = withContext(dispatcher) {
        try {
            module.callAttr("forget_cf_pick")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot reset the CF check", e)
        }
    }

    suspend fun stop(keepStalled: Boolean = false) = withContext(dispatcher) {
        if (!keepStalled) _stalled.value = false
        module.callAttr("stop")
        readStatus()
    }

    /**
     * Whether Telegram answers through the CF proxy over plain TLS, over TLS with
     * the bypass strategy, and without TLS; the *Full ones whether more than 24 KB
     * also got through one connection each way (some ISPs freeze connections to
     * Cloudflare after about 16 KB). Null if CF is off or the probe failed.
     */
    data class CfProbe(
        val tls: Boolean, val bypass: Boolean, val plain: Boolean,
        val tlsFull: Boolean = tls, val bypassFull: Boolean = bypass, val plainFull: Boolean = plain,
    ) {
        /** The small check passed: Telegram answers. */
        fun works(t: CfTransport) = when (t) {
            CfTransport.BYPASS -> bypass
            CfTransport.TLS -> tls
            CfTransport.PLAIN -> plain
        }

        /** The large check passed too: more than a few KB get through. */
        fun worksFully(t: CfTransport) = works(t) && when (t) {
            CfTransport.BYPASS -> bypassFull
            CfTransport.TLS -> tlsFull
            CfTransport.PLAIN -> plainFull
        }
    }

    /** Runs android_entry.probe_cf (several seconds) off the lifecycle thread, so polls go on. */
    suspend fun probeCf(): CfProbe? = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(module.callAttr("probe_cf").toString())
            if (o.has("tls")) {
                val tls = o.getBoolean("tls")
                val bypass = o.getBoolean("bypass")
                val plain = o.getBoolean("plain")
                CfProbe(
                    tls, bypass, plain,
                    o.optBoolean("tls_full", tls), o.optBoolean("bypass_full", bypass),
                    o.optBoolean("plain_full", plain),
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "CF probe failed", e)
            null
        }
    }

    /**
     * One way to Telegram from [checkRoutes]: [route] is "direct", "cf_bypass",
     * "cf_tls", "cf_plain" or "workerN"; [ms] until Telegram answered, null if it
     * didn't; [full]: the large check passed (null where it isn't run).
     */
    data class RouteCheck(val route: String, val ok: Boolean, val ms: Int?, val full: Boolean?)

    /**
     * Checks every way to Telegram with a real request (android_entry.check_routes,
     * up to ~30 s) off the lifecycle thread; empty when the core isn't running.
     */
    suspend fun checkRoutes(): List<RouteCheck> = withContext(Dispatchers.IO) {
        try {
            val a = org.json.JSONArray(module.callAttr("check_routes").toString())
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                RouteCheck(
                    o.getString("route"), o.getBoolean("ok"),
                    if (o.isNull("ms")) null else o.getInt("ms"),
                    if (o.isNull("full")) null else o.getBoolean("full"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Route check failed", e)
            emptyList()
        }
    }

    /** Reads the current status from Python and publishes it to [state]. */
    suspend fun poll(): ProxyStatus = withContext(dispatcher) { readStatus() }

    /** [poll] without waiting, e.g. when a screen or tile becomes visible. */
    fun refresh() {
        scope.launch { readStatus() }
    }

    /** [stop] that outlives the caller, e.g. a service being destroyed. */
    fun stopInBackground() {
        scope.launch { stop() }
    }

    /** The part of a TLS bypass strategy that isn't valid, or null if it all is. */
    fun badTlsStrategyPart(text: String): String? = try {
        module.callAttr("check_tls_strategy", text).toString().ifEmpty { null }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot check the TLS strategy", e)
        null // start() checks it again and reports it
    }

    /** The tg://proxy link for [settings], or null if Python couldn't build it. */
    fun link(settings: ProxySettings): String? = try {
        module.callAttr("proxy_link", settings.toJson().toString()).toString()
    } catch (e: Exception) {
        Log.w(TAG, "Cannot build proxy link", e)
        null
    }

    fun logDir(context: Context): File = File(context.filesDir, "logs")

    /**
     * The whole proxy log (the previous file, then the current one) copied into one
     * file under cache/shared-logs for sharing; null if there is no log yet.
     */
    fun fullLogSnapshot(context: Context): File? {
        val parts = listOf("proxy.log.1", "proxy.log").map { File(logDir(context), it) }.filter { it.exists() }
        if (parts.isEmpty()) return null
        val dir = File(context.cacheDir, "shared-logs").apply { mkdirs() }
        val out = File(dir, "andmtproto-log.txt")
        out.outputStream().use { sink -> parts.forEach { part -> part.inputStream().use { it.copyTo(sink) } } }
        return out
    }

    /** The last [maxLines] lines of the proxy log, reading at most the last 64 KB. */
    fun readLogTail(context: Context, maxLines: Int = 300): String {
        val file = File(logDir(context), "proxy.log")
        if (!file.exists()) return ""
        val text = RandomAccessFile(file, "r").use { raf ->
            val start = (raf.length() - LOG_TAIL_BYTES).coerceAtLeast(0)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.seek(start)
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8).let { if (start > 0) it.substringAfter('\n') else it }
        }
        return text.lines().takeLast(maxLines).joinToString("\n").trimEnd()
    }

    private fun readStatus(): ProxyStatus {
        val o = JSONObject(module.callAttr("status").toString())
        return ProxyStatus(
            running = o.getBoolean("running"),
            listening = o.getBoolean("listening"),
            error = if (o.isNull("error")) null else ProxyError.fromJson(o.getJSONObject("error")),
            coreVersion = o.getString("core_version"),
            active = o.getInt("active"),
            total = o.getInt("total"),
            lastHour = o.getInt("last_hour"),
            badRecent = o.getInt("bad_recent"),
            ws = o.getInt("ws"),
            cf = o.getInt("cf"),
            routed = o.getInt("routed"),
            bytesUp = o.getLong("bytes_up"),
            bytesDown = o.getLong("bytes_down"),
            cfOk = o.optInt("cf_ok"),
            cfFailed = o.optInt("cf_failed"),
            cfRecent = parseCfRecent(o.optJSONObject("cf_recent")),
            workerTrouble = if (o.isNull("worker_trouble")) null else o.optString("worker_trouble"),
        ).also { _state.value = it }
    }
}
