package io.github.oruviyex18.andmtproto

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Creates the user's own Cloudflare Worker with AndMTProto's Worker code
 * (android/cf-worker/worker.js, bundled as an asset) through Cloudflare's API, so
 * nobody has to edit code on Cloudflare's site. The user makes an API token from
 * the "Edit Cloudflare Workers" template plus "Account Settings: Read" (the template
 * lacks it, and listing the token's account needs it) and pastes it; the token is
 * used for these few requests only and never stored or logged.
 *
 * Steps: the token's account → its workers.dev subdomain (made if the account has
 * none) → upload the Worker as [SCRIPT_NAME] (again = update its code) → turn on
 * its workers.dev address. The result is that address, for the CF Worker setting.
 */
object WorkerSetup {
    const val SCRIPT_NAME = "andmtproto"

    /** The first line of worker.js, kept in the uploaded code: tells our Worker from another. */
    internal const val SCRIPT_MARKER = "AndMTProto Cloudflare Worker"
    const val TOKENS_PAGE = "https://dash.cloudflare.com/profile/api-tokens"
    private const val API = "https://api.cloudflare.com/client/v4"
    private const val ASSET = "cf-worker/worker.js"
    // Any date Cloudflare's runtime already supports; the Worker uses no newer features.
    private const val COMPATIBILITY_DATE = "2025-01-01"

    sealed interface Result {
        /** [account] is named only when the token reaches several accounts. */
        data class Created(val domain: String, val account: String? = null) : Result
        data class Failed(val error: Error, val detail: String? = null) : Result
    }

    enum class Error {
        /** Cloudflare refused the token: mistyped, expired or revoked. */
        BAD_TOKEN,

        /** The token works but lists no account: "Account Settings: Read" is missing. */
        NO_ACCOUNT,

        /** The token lacks a permission: "Workers Scripts" edit or "Account Settings" read. */
        NO_PERMISSION,

        /**
         * api.cloudflare.com didn't answer, or something else answered in its place
         * (a provider's block page, a captive portal): no network, or blocked on this one.
         */
        NETWORK,

        /** The account already has a Worker named [SCRIPT_NAME] that isn't AndMTProto's. */
        NAME_TAKEN,

        /** Anything else Cloudflare answered; [Result.Failed.detail] has its message. */
        API,
    }

    /** One API call; [WorkerSetup.create] takes another in tests. */
    fun interface Api {
        /** HTTP status and body; throws IOException when nothing came back. */
        fun call(method: String, path: String, contentType: String?, body: ByteArray?): Pair<Int, String>
    }

    /** Real calls to api.cloudflare.com with [token]. */
    fun api(token: String): Api = Api { method, path, contentType, body ->
        val conn = URL(API + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    /** The bundled Worker code. */
    fun workerScript(context: Context): String =
        context.assets.open(ASSET).bufferedReader().use { it.readText() }

    /**
     * Cloudflare's tokens are printable ASCII. Anything else (a zero-width space
     * copied from a web page, a Cyrillic letter) can't go into an HTTP header:
     * Android's HTTP client would throw IllegalArgumentException.
     */
    fun isWellFormedToken(token: String): Boolean {
        val t = token.trim()
        return t.isNotEmpty() && t.all { it in '!'..'~' }
    }

    suspend fun create(api: Api, script: String): Result = withContext(Dispatchers.IO) {
        try {
            createBlocking(api, script)
        } catch (e: IOException) {
            Result.Failed(Error.NETWORK, e.message)
        } catch (e: IllegalArgumentException) {
            Result.Failed(Error.BAD_TOKEN) // a header value the HTTP client refuses; not shown: it may quote the token
        }
    }

    internal fun createBlocking(api: Api, script: String): Result {
        val accounts = api.json("GET", "/accounts?per_page=5")
        accounts.failure(firstCall = true)?.let { return it }
        val list = accounts.result<JSONArray>()
        val accountId = list?.optJSONObject(0)?.optString("id").orEmpty()
        if (accountId.isEmpty()) return Result.Failed(Error.NO_ACCOUNT)
        // With several accounts the first one listed is used; the result names it.
        val accountName = if ((list?.length() ?: 0) > 1) list?.optJSONObject(0)?.optString("name") else null
        val account = "/accounts/$accountId/workers"

        val sub = api.json("GET", "$account/subdomain")
        var subdomain = sub.result<JSONObject>()?.optString("subdomain").orEmpty()
        if (subdomain.isEmpty()) {
            // A new account has no workers.dev subdomain yet: make one.
            val made = api.json(
                "PUT", "$account/subdomain", "application/json",
                JSONObject().put("subdomain", "andmtproto-${randomSuffix()}").toString().toByteArray(),
            )
            made.failure()?.let { return it }
            subdomain = made.result<JSONObject>()?.optString("subdomain").orEmpty()
            if (subdomain.isEmpty()) return Result.Failed(Error.API, "no workers.dev subdomain")
        }

        // Uploading replaces a Worker of the same name: fine for ours (that's how the code
        // is updated), not for one the user made themselves and happened to call that.
        val (existingCode, existing) = api.call("GET", "$account/scripts/$SCRIPT_NAME", null, null)
        if (existingCode == 200 && !existing.contains(SCRIPT_MARKER)) {
            // The download is raw code, not Cloudflare's JSON envelope: a web page here is a
            // block or sign-in page answering in Cloudflare's place, not someone's Worker.
            if (looksLikeWebPage(existing)) return Result.Failed(Error.NETWORK, "HTTP $existingCode")
            return Result.Failed(Error.NAME_TAKEN)
        }

        val boundary = "andmtproto-${randomSuffix()}"
        val upload = api.json("PUT", "$account/scripts/$SCRIPT_NAME", "multipart/form-data; boundary=$boundary",
            multipart(boundary, script))
        upload.failure()?.let { return it }

        val enable = api.json("POST", "$account/scripts/$SCRIPT_NAME/subdomain", "application/json",
            JSONObject().put("enabled", true).put("previews_enabled", false).toString().toByteArray())
        enable.failure()?.let { return it }

        return Result.Created("$SCRIPT_NAME.$subdomain.workers.dev", accountName?.takeIf { it.isNotBlank() })
    }

    internal fun looksLikeWebPage(text: String): Boolean {
        val start = text.trimStart().take(15).lowercase()
        return start.startsWith("<!doctype") || start.startsWith("<html")
    }

    /** The upload's body: metadata naming worker.js the main ES module, then worker.js. */
    internal fun multipart(boundary: String, script: String): ByteArray {
        val metadata = JSONObject()
            .put("main_module", "worker.js")
            .put("compatibility_date", COMPATIBILITY_DATE)
        return buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"metadata\"\r\n")
            append("Content-Type: application/json\r\n\r\n")
            append(metadata.toString()).append("\r\n")
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"worker.js\"; filename=\"worker.js\"\r\n")
            append("Content-Type: application/javascript+module\r\n\r\n")
            append(script).append("\r\n")
            append("--$boundary--\r\n")
        }.toByteArray()
    }

    private fun randomSuffix(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    /** Cloudflare's answer: HTTP status and its JSON envelope ({success, errors, result}). */
    private class Reply(val code: Int, val body: JSONObject?) {
        inline fun <reified T> result(): T? = body?.opt("result") as? T

        /** Null when the call worked, else what to tell the user. */
        fun failure(firstCall: Boolean = false): Result.Failed? {
            if (code in 200..299 && body?.optBoolean("success", false) == true) return null
            // Not Cloudflare's JSON envelope: a block page or captive portal answered, and
            // its status code says nothing about the token.
            if (body == null || !body.has("success")) return Result.Failed(Error.NETWORK, "HTTP $code")
            val message = body.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
            return when {
                // 401: Cloudflare doesn't know the token; 403: it does, but lacks a permission.
                // 400 on the first call: "Invalid request headers", a malformed token.
                code == 401 || (firstCall && code == 400) -> Result.Failed(Error.BAD_TOKEN, message)
                code == 403 -> Result.Failed(Error.NO_PERMISSION, message)
                else -> Result.Failed(Error.API, message ?: "HTTP $code")
            }
        }
    }

    private fun Api.json(method: String, path: String, contentType: String? = null, body: ByteArray? = null): Reply {
        val (code, text) = call(method, path, contentType, body)
        return Reply(code, runCatching { JSONObject(text) }.getOrNull())
    }
}

/** Turns the CF Worker setting on with [domain] added to the Workers already there. */
fun ProxySettings.withWorkerDomain(domain: String): ProxySettings {
    val domains = (cfWorkerDomains.split(',').map { it.trim() } + domain)
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
    return copy(cfWorkerEnabled = true, cfWorkerDomains = domains.joinToString(", "))
}
