package io.github.oruviyex18.andmtproto

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerSetupTest {
    private class FakeCloudflare(var subdomain: String? = "alice", var uploadCode: Int = 200) : WorkerSetup.Api {
        val calls = mutableListOf<Triple<String, String, String>>()

        override fun call(method: String, path: String, contentType: String?, body: ByteArray?): Pair<Int, String> {
            val text = body?.toString(Charsets.UTF_8).orEmpty()
            calls += Triple(method, path, text)
            return when {
                path.startsWith("/accounts?") -> 200 to """{"success":true,"result":[{"id":"acc1","name":"me"}]}"""
                method == "GET" && path == "/accounts/acc1/workers/subdomain" ->
                    if (subdomain == null) 404 to """{"success":false,"errors":[{"code":10007,"message":"no subdomain"}],"result":null}"""
                    else 200 to """{"success":true,"result":{"subdomain":"$subdomain"}}"""
                method == "PUT" && path == "/accounts/acc1/workers/subdomain" -> {
                    subdomain = JSONObject(text).getString("subdomain")
                    200 to """{"success":true,"result":{"subdomain":"$subdomain"}}"""
                }
                method == "PUT" && path == "/accounts/acc1/workers/scripts/andmtproto" ->
                    if (uploadCode == 200) 200 to """{"success":true,"result":{"id":"andmtproto"}}"""
                    else uploadCode to """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}"""
                method == "POST" && path == "/accounts/acc1/workers/scripts/andmtproto/subdomain" ->
                    200 to """{"success":true,"result":{"enabled":true}}"""
                else -> 404 to """{"success":false,"errors":[{"message":"unexpected $method $path"}]}"""
            }
        }
    }

    @Test
    fun uploadsTheWorkerAndTurnsOnItsAddress() {
        val cf = FakeCloudflare()
        val result = WorkerSetup.createBlocking(cf, "export default {}")
        assertEquals(WorkerSetup.Result.Created("andmtproto.alice.workers.dev"), result)
        assertEquals(
            listOf("GET /accounts?per_page=5", "GET /accounts/acc1/workers/subdomain",
                "PUT /accounts/acc1/workers/scripts/andmtproto", "POST /accounts/acc1/workers/scripts/andmtproto/subdomain"),
            cf.calls.map { "${it.first} ${it.second}" },
        )
        val upload = cf.calls[2].third
        assertTrue(upload.contains("\"main_module\":\"worker.js\""))
        assertTrue(upload.contains("name=\"worker.js\"; filename=\"worker.js\"\r\nContent-Type: application/javascript+module"))
        assertTrue(upload.contains("export default {}"))
        assertEquals(true, JSONObject(cf.calls[3].third).getBoolean("enabled"))
    }

    @Test
    fun aNewAccountGetsAWorkersDevSubdomain() {
        val cf = FakeCloudflare(subdomain = null)
        val result = WorkerSetup.createBlocking(cf, "x") as WorkerSetup.Result.Created
        assertTrue(result.domain, Regex("andmtproto\\.andmtproto-[a-z0-9]{6}\\.workers\\.dev").matches(result.domain))
    }

    @Test
    fun cloudflaresRefusalsBecomePlainErrors() {
        val badToken = WorkerSetup.Api { _, _, _, _ ->
            401 to """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}"""
        }
        assertEquals(WorkerSetup.Error.BAD_TOKEN, (WorkerSetup.createBlocking(badToken, "x") as WorkerSetup.Result.Failed).error)
        // Seen on GPB Mobile, 24 Nov 2026: a malformed token is a 400 "Invalid request headers".
        val malformed = WorkerSetup.Api { _, _, _, _ ->
            400 to """{"success":false,"errors":[{"code":6003,"message":"Invalid request headers"}]}"""
        }
        assertEquals(WorkerSetup.Error.BAD_TOKEN, (WorkerSetup.createBlocking(malformed, "x") as WorkerSetup.Result.Failed).error)
        // The template lacks "Account Settings: Read": refused or an empty list, not a bad token.
        val cantList = WorkerSetup.Api { _, _, _, _ ->
            403 to """{"success":false,"errors":[{"code":9109,"message":"Unauthorized to access requested resource"}]}"""
        }
        assertEquals(WorkerSetup.Error.NO_PERMISSION, (WorkerSetup.createBlocking(cantList, "x") as WorkerSetup.Result.Failed).error)
        val noAccount = WorkerSetup.Api { _, _, _, _ -> 200 to """{"success":true,"result":[]}""" }
        assertEquals(WorkerSetup.Error.NO_ACCOUNT, (WorkerSetup.createBlocking(noAccount, "x") as WorkerSetup.Result.Failed).error)
        val cantUpload = FakeCloudflare(uploadCode = 403)
        val failed = WorkerSetup.createBlocking(cantUpload, "x") as WorkerSetup.Result.Failed
        assertEquals(WorkerSetup.Error.NO_PERMISSION, failed.error)
        assertEquals("Authentication error", failed.detail)
    }

    @Test
    fun theNewAddressJoinsTheWorkersAlreadySet() {
        val none = ProxySettings().withWorkerDomain("andmtproto.alice.workers.dev")
        assertEquals(true, none.cfWorkerEnabled)
        assertEquals("andmtproto.alice.workers.dev", none.cfWorkerDomains)
        val some = ProxySettings(cfWorkerDomains = "old.bob.workers.dev,  ANDMTPROTO.alice.workers.dev ")
            .withWorkerDomain("andmtproto.alice.workers.dev")
        assertEquals("old.bob.workers.dev, ANDMTPROTO.alice.workers.dev", some.cfWorkerDomains)
    }
}
