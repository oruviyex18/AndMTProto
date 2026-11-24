package io.github.oruviyex18.andmtproto

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkerSetupTest {
    private class FakeCloudflare(
        var subdomain: String? = "alice",
        var uploadCode: Int = 200,
        /** The code of a Worker already named andmtproto, or null for none. */
        val existingScript: String? = null,
    ) : WorkerSetup.Api {
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
                method == "GET" && path == "/accounts/acc1/workers/scripts/andmtproto" && existingScript != null ->
                    200 to existingScript
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
                "GET /accounts/acc1/workers/scripts/andmtproto",
                "PUT /accounts/acc1/workers/scripts/andmtproto", "POST /accounts/acc1/workers/scripts/andmtproto/subdomain"),
            cf.calls.map { "${it.first} ${it.second}" },
        )
        val upload = cf.calls[3].third
        assertTrue(upload.contains("\"main_module\":\"worker.js\""))
        assertTrue(upload.contains("name=\"worker.js\"; filename=\"worker.js\"\r\nContent-Type: application/javascript+module"))
        assertTrue(upload.contains("export default {}"))
        assertEquals(true, JSONObject(cf.calls[4].third).getBoolean("enabled"))
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

    @Test
    fun tokensThatCantGoIntoAHeaderAreRefusedUpFront() {
        assertTrue(WorkerSetup.isWellFormedToken("  aB3_-xYz09  "))
        assertEquals(false, WorkerSetup.isWellFormedToken(""))
        assertEquals(false, WorkerSetup.isWellFormedToken("abc" + Char(0x200B) + "def")) // zero-width space
        assertEquals(false, WorkerSetup.isWellFormedToken("ab" + Char(0x0441) + "d")) // Cyrillic letter
        assertEquals(false, WorkerSetup.isWellFormedToken("ab cd"))
    }

    @Test
    fun theAccountIsNamedWhenTheTokenReachesSeveral() {
        val two = object : WorkerSetup.Api {
            val cf = FakeCloudflare()
            override fun call(method: String, path: String, contentType: String?, body: ByteArray?): Pair<Int, String> =
                if (path.startsWith("/accounts?")) 200 to ("{\"success\":true,\"result\":[{\"id\":\"acc1\",\"name\":\"Work\"},{\"id\":\"acc2\",\"name\":\"Home\"}]}")
                else cf.call(method, path, contentType, body)
        }
        assertEquals(WorkerSetup.Result.Created("andmtproto.alice.workers.dev", "Work"), WorkerSetup.createBlocking(two, "x"))
        assertEquals(null, (WorkerSetup.createBlocking(FakeCloudflare(), "x") as WorkerSetup.Result.Created).account)
    }

    @Test
    fun theBundledWorkerCarriesTheMarkerTheAppLooksFor() {
        // Gradle passes the script's path (app/build.gradle.kts); runners that don't get the
        // two usual working directories, android/app and the repository root.
        val candidates = listOfNotNull(System.getProperty("andmtproto.workerScript"))
            .plus(listOf("../cf-worker/worker.js", "android/cf-worker/worker.js"))
            .map(::File)
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("android/cf-worker/worker.js not found; tried ${candidates.map { it.absolutePath }}")
        val script = file.readText()
        assertTrue("worker.js must keep \"${WorkerSetup.SCRIPT_MARKER}\"", script.contains(WorkerSetup.SCRIPT_MARKER))
    }

    @Test
    fun ourOwnWorkerIsUpdatedButSomeoneElsesIsLeftAlone() {
        val ours = FakeCloudflare(existingScript = "// AndMTProto Cloudflare Worker: carries the proxy's connections\nexport default {}")
        assertEquals(WorkerSetup.Result.Created("andmtproto.alice.workers.dev"), WorkerSetup.createBlocking(ours, "new"))
        val theirs = FakeCloudflare(existingScript = "export default { fetch() { return new Response('mine') } }")
        assertEquals(WorkerSetup.Error.NAME_TAKEN, (WorkerSetup.createBlocking(theirs, "new") as WorkerSetup.Result.Failed).error)
        assertTrue("nothing uploaded", theirs.calls.none { it.first == "PUT" && it.second.endsWith("/scripts/andmtproto") })
        // A sign-in page that answers the script check mid-setup isn't someone's Worker.
        val portal = FakeCloudflare(existingScript = "\n  <!DOCTYPE html><html><body>Sign in to Wi-Fi</body></html>")
        assertEquals(WorkerSetup.Error.NETWORK, (WorkerSetup.createBlocking(portal, "new") as WorkerSetup.Result.Failed).error)
    }

    @Test
    fun anAnswerThatIsntCloudflaresMeansTheNetworkIsInTheWay() {
        // A provider's block page with 403, a Wi-Fi sign-in page with 200: not about the token.
        for (code in listOf(403, 200)) {
            val page = WorkerSetup.Api { _, _, _, _ -> code to "<html><body>Access denied</body></html>" }
            assertEquals(WorkerSetup.Error.NETWORK, (WorkerSetup.createBlocking(page, "x") as WorkerSetup.Result.Failed).error)
        }
    }
}
