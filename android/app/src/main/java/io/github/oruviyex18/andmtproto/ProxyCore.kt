package io.github.oruviyex18.andmtproto

import android.content.Context
import com.chaquo.python.Python
import org.json.JSONObject
import java.io.File

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
    val error: ProxyError? = null,
    val coreVersion: String = "",
    val active: Int = 0,
    val total: Int = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
)

/** Kotlin side of android_entry.py. Calls may block briefly; keep them off the main thread. */
object ProxyCore {
    private val module by lazy { Python.getInstance().getModule("android_entry") }

    fun logDir(context: Context): File = File(context.filesDir, "logs")

    /** Returns the config error, or null when the proxy thread was started. */
    fun start(context: Context, settings: ProxySettings): ProxyError? =
        module.callAttr("start", settings.toJson().toString(), logDir(context).absolutePath)
            ?.let { ProxyError.fromJson(JSONObject(it.toString())) }

    fun stop() {
        module.callAttr("stop")
    }

    fun status(): ProxyStatus {
        val o = JSONObject(module.callAttr("status").toString())
        return ProxyStatus(
            running = o.getBoolean("running"),
            error = if (o.isNull("error")) null else ProxyError.fromJson(o.getJSONObject("error")),
            coreVersion = o.getString("core_version"),
            active = o.getInt("active"),
            total = o.getInt("total"),
            bytesUp = o.getLong("bytes_up"),
            bytesDown = o.getLong("bytes_down"),
        )
    }

    fun link(settings: ProxySettings): String =
        module.callAttr("proxy_link", settings.toJson().toString()).toString()

    fun readLogTail(context: Context, maxLines: Int = 300): String {
        val file = File(logDir(context), "proxy.log")
        if (!file.exists()) return ""
        return file.readLines().takeLast(maxLines).joinToString("\n")
    }
}
