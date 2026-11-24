package io.github.oruviyex18.andmtproto

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CfTroubleTest {
    @Test
    fun aFewStrayFailuresSayNothing() {
        assertNull(cfTrouble(emptyMap()))
        assertNull(cfTrouble(mapOf("cf" to mapOf("timeout" to 4))))
        // Most attempts work: failures fall short of 3x the successes.
        assertNull(cfTrouble(mapOf("cf" to mapOf("ok" to 10, "timeout" to 20))))
    }

    @Test
    fun mostlyFailingShowsTheCommonestReason() {
        assertEquals(
            CfTrouble(CfRoute.CF_PROXY, CfFailureReason.RESET),
            cfTrouble(mapOf("cf" to mapOf("ok" to 1, "reset" to 6, "timeout" to 3))),
        )
    }

    @Test
    fun theUsersWorkerComesFirst() {
        val recent = mapOf("cf" to mapOf("timeout" to 30))
        assertEquals(CfTrouble(CfRoute.WORKER, CfFailureReason.LIMIT), cfTrouble(recent, workerTrouble = "limit"))
        // Workers that work hand over to the CF proxy's trouble.
        assertEquals(CfTrouble(CfRoute.CF_PROXY, CfFailureReason.TIMEOUT), cfTrouble(recent, workerTrouble = null))
    }

    @Test
    fun unknownReasonsFromANewerCoreReadAsOther() {
        assertEquals(CfFailureReason.OTHER, cfTrouble(mapOf("cf" to mapOf("weird" to 7)))?.reason)
    }

    @Test
    fun parsesTheStatusField() {
        val o = JSONObject("""{"cf": {"ok": 2, "timeout": 9}, "worker": {"limit": 5}}""")
        assertEquals(
            mapOf("cf" to mapOf("ok" to 2, "timeout" to 9), "worker" to mapOf("limit" to 5)),
            parseCfRecent(o),
        )
        assertEquals(emptyMap<String, Map<String, Int>>(), parseCfRecent(null))
    }
}
