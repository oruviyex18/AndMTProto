package io.github.oruviyex18.andmtproto

import org.json.JSONObject

/** Where the core reached Cloudflare: the shared CF proxy or the user's own Worker. */
enum class CfRoute(val key: String) { CF_PROXY("cf"), WORKER("worker") }

/**
 * Why CF attempts fail, as android_entry._cf_failure_reason sorts them, for the
 * plain-language line on the main screen ([cfTrouble]).
 */
enum class CfFailureReason(val key: String) {
    TIMEOUT("timeout"),
    RESET("reset"),
    TLS("tls"),
    DNS("dns"),
    REFUSED("refused"),
    LIMIT("limit"),
    FORBIDDEN("forbidden"),
    SERVER_ERROR("server_error"),
    HTTP("http"),
    NETWORK("network"),

    /** A Worker accepted the WebSocket but closed it before any data: it can't reach Telegram. */
    WORKER_CLOSED("worker_closed"),
    OTHER("other");

    companion object {
        fun of(key: String): CfFailureReason = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** A route that keeps failing lately, and its most common reason. */
data class CfTrouble(val route: CfRoute, val reason: CfFailureReason)

/** At least this many failures in the last 2 minutes before the app says anything. */
private const val MIN_FAILURES = 5

/** ... and this many times as many as attempts that worked: a few stray failures are normal. */
private const val FAILURE_RATIO = 3

/**
 * What to tell the user about Cloudflare, or null while things work. The user's own
 * Worker comes first, since they set it up: [workerTrouble] is status()["worker_trouble"],
 * the reason when every Worker tried is failing. Then the shared CF proxy: [recent] is
 * status()["cf_recent"], route → outcome → count over the last 2 minutes ("ok" counts
 * attempts that opened); it needs [MIN_FAILURES] and [FAILURE_RATIO] times the successes.
 */
fun cfTrouble(recent: Map<String, Map<String, Int>>, workerTrouble: String? = null): CfTrouble? {
    if (workerTrouble != null) return CfTrouble(CfRoute.WORKER, CfFailureReason.of(workerTrouble))
    val counts = recent[CfRoute.CF_PROXY.key] ?: return null
    val ok = counts["ok"] ?: 0
    val failures = counts.filterKeys { it != "ok" }
    val failed = failures.values.sum()
    if (failed < MIN_FAILURES || failed < FAILURE_RATIO * ok) return null
    val top = failures.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first()
    return CfTrouble(CfRoute.CF_PROXY, CfFailureReason.of(top.key))
}

/** status()["cf_recent"] as nested maps; empty when absent. */
internal fun parseCfRecent(o: JSONObject?): Map<String, Map<String, Int>> {
    if (o == null) return emptyMap()
    return o.keys().asSequence().associateWith { route ->
        val counts = o.optJSONObject(route) ?: JSONObject()
        counts.keys().asSequence().associateWith { counts.optInt(it) }
    }
}
