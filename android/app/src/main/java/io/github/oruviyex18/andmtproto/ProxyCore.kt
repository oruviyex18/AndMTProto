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

    /** Starts the proxy thread; returns the config error, or null once it is starting. */
    suspend fun start(context: Context, settings: ProxySettings): ProxyError? = withContext(dispatcher) {
        val logDir = logDir(context).absolutePath
        val error = module.callAttr("start", settings.toJson().toString(), logDir)
            ?.let { ProxyError.fromJson(JSONObject(it.toString())) }
        readStatus()
        error
    }

    suspend fun stop() = withContext(dispatcher) {
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
            bytesUp = o.getLong("bytes_up"),
            bytesDown = o.getLong("bytes_down"),
        ).also { _state.value = it }
    }
}
