package io.github.oruviyex18.andmtproto

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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

    /** Starts the proxy thread; returns the config error, or null once it is starting. */
    suspend fun start(context: Context, settings: ProxySettings): ProxyError? = withContext(dispatcher) {
        val logDir = logDir(context).absolutePath
        _stalled.value = false
        val error = module.callAttr("start", settings.toJson().toString(), logDir)
            ?.let { ProxyError.fromJson(JSONObject(it.toString())) }
        readStatus()
        error
    }

    suspend fun stop() = withContext(dispatcher) {
        _stalled.value = false
        module.callAttr("stop")
        readStatus()
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
        ).also { _state.value = it }
    }
}
