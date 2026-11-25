package io.github.oruviyex18.andmtproto

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Choose automatically" for the CF connection: the settings it migrates and what a check picks. */
class CfAutoTest {
    private fun load(json: String) = ProxySettings.fromJson(JSONObject(json))

    @Test
    fun oldConfigOnTheBypassBecomesAutomatic() {
        // The bypass was the default and already switched by itself.
        assertTrue(load("{}").cfAuto)
        assertTrue(load("""{"tls_bypass": true, "no_secure": false}""").cfAuto)
        assertEquals(CfTransport.BYPASS, load("{}").cfTransport)
    }

    @Test
    fun oldConfigWithAWayTheUserPickedKeepsIt() {
        val tls = load("""{"tls_bypass": false, "no_secure": false}""")
        assertFalse(tls.cfAuto)
        assertEquals(CfTransport.TLS, tls.cfTransport)
        val plain = load("""{"tls_bypass": true, "no_secure": true}""")
        assertFalse(plain.cfAuto)
        assertEquals(CfTransport.PLAIN, plain.cfTransport)
    }

    @Test
    fun savedChoiceWinsOverTheMigration() {
        assertFalse(load("""{"cf_auto": false, "tls_bypass": true}""").cfAuto)
        assertTrue(load("""{"cf_auto": true, "no_secure": true}""").cfAuto)
    }

    @Test
    fun choiceSurvivesSaving() {
        for (s in listOf(
            ProxySettings().withCfAuto(),
            ProxySettings().withCfAuto().withCurrentCfTransport(CfTransport.PLAIN),
            ProxySettings().withCfTransport(CfTransport.TLS),
        )) {
            val back = ProxySettings.fromJson(s.toJson())
            assertEquals(s.cfAuto, back.cfAuto)
            assertEquals(s.cfTransport, back.cfTransport)
        }
    }

    @Test
    fun pickingAWayTurnsAutomaticOffButTheServiceKeepsIt() {
        val auto = ProxySettings().withCfAuto()
        assertTrue(auto.cfAuto)
        assertEquals(CfTransport.BYPASS, auto.cfTransport)
        val fixed = auto.withCfTransport(CfTransport.TLS)
        assertFalse(fixed.cfAuto)
        assertEquals(CfTransport.TLS, fixed.cfTransport)
        val moved = auto.withCurrentCfTransport(CfTransport.PLAIN)
        assertTrue(moved.cfAuto)
        assertEquals(CfTransport.PLAIN, moved.cfTransport)
    }

    private fun probe(
        bypass: Boolean, tls: Boolean, plain: Boolean,
        bypassFull: Boolean = bypass, tlsFull: Boolean = tls, plainFull: Boolean = plain,
    ) = ProxyCore.CfProbe(tls, bypass, plain, tlsFull, bypassFull, plainFull)

    @Test
    fun picksTheFirstWayThatWorks() {
        assertEquals(CfChoice(CfTransport.BYPASS, false), chooseCfTransport(probe(true, true, true)))
        assertEquals(CfChoice(CfTransport.TLS, false), chooseCfTransport(probe(false, true, true)))
        assertEquals(CfChoice(CfTransport.PLAIN, false), chooseCfTransport(probe(false, false, true)))
    }

    @Test
    fun aWayThatCarriesLargeDataBeatsOneEarlierThatIsCutOff() {
        // MTS Wi-Fi, 19 Nov 2026: the bypass and plain TLS froze at about 12 KB, port 80 didn't.
        assertEquals(
            CfChoice(CfTransport.PLAIN, false),
            chooseCfTransport(probe(true, true, true, bypassFull = false, tlsFull = false)),
        )
    }

    @Test
    fun everyWayCutOffKeepsTheFirstThatAnswers() {
        assertEquals(
            CfChoice(CfTransport.TLS, true),
            chooseCfTransport(probe(false, true, true, tlsFull = false, plainFull = false)),
        )
    }

    @Test
    fun nothingAnswering() {
        assertEquals(CfChoice(null, false), chooseCfTransport(probe(false, false, false)))
    }

    @Test
    fun aWayThatCarriedNothingIsSkippedThoughTheCheckPassesIt() {
        // GPB Mobile, 25 Nov 2026: the check passed the bypass, Telegram got nothing over it.
        val all = probe(true, false, true)
        assertEquals(CfChoice(CfTransport.PLAIN, false), chooseCfTransport(all, setOf(CfTransport.BYPASS)))
        assertEquals(CfChoice(null, false), chooseCfTransport(all, setOf(CfTransport.BYPASS, CfTransport.PLAIN)))
    }

    @Test
    fun stalledWaysAreKeptPerNetwork() {
        val ways = StalledWays(keepMs = 1_000)
        assertEquals(emptySet<CfTransport>(), ways.on("mobile", 0))
        assertTrue(ways.add("mobile", CfTransport.BYPASS, 0))
        assertFalse("once per way", ways.add("mobile", CfTransport.BYPASS, 10))
        assertTrue(ways.add("mobile", CfTransport.PLAIN, 500))
        assertEquals(setOf(CfTransport.BYPASS, CfTransport.PLAIN), ways.on("mobile", 600))
        assertEquals("the bypass's time is up", setOf(CfTransport.PLAIN), ways.on("mobile", 1_200))
        assertTrue("set aside again once its time is up", ways.add("mobile", CfTransport.BYPASS, 1_200))
        ways.remove("mobile", CfTransport.PLAIN)
        assertEquals("taken back", setOf(CfTransport.BYPASS), ways.on("mobile", 1_300))
        assertEquals(emptySet<CfTransport>(), ways.on("wifi", 1_300))
        assertTrue("another network starts afresh", ways.add("wifi", CfTransport.BYPASS, 1_300))
        assertEquals(emptySet<CfTransport>(), ways.on("mobile", 1_300))
    }

    @Test
    fun aCheckWithWaysSetAside() {
        val bypass = CfTransport.BYPASS
        val plain = CfTransport.PLAIN
        val aside = setOf(bypass)
        // Another way works: use it; the cut-off state comes from the check alone.
        assertEquals(CfOutcome(plain, false), cfCheckOutcome(probe(true, false, true), aside, bypass))
        assertEquals(
            CfOutcome(CfTransport.TLS, false),
            cfCheckOutcome(probe(true, true, false, tlsFull = false), aside, bypass),
        )
        // Only the set-aside way passes and the current one does too: keep it.
        assertEquals(CfOutcome(plain, false), cfCheckOutcome(probe(true, false, true), setOf(bypass, plain), plain))
        // Only the set-aside way passes, the current one doesn't (port 80 blocked later): take it back.
        assertEquals(CfOutcome(bypass, false, takeBack = true), cfCheckOutcome(probe(true, false, false), aside, plain))
        // Nothing works.
        assertEquals(CfOutcome(null, false), cfCheckOutcome(probe(false, false, false), aside, plain))
    }

    @Test
    fun onlyCfConnectionsOpenedDuringTheStallCount() {
        val stalls = StallDetector()
        // CF connections that carried data earlier don't make a later stall a CF one.
        stalls.update(ProxyStatus(total = 5, cfOk = 4, bytesDown = 100), 0)
        assertTrue(stalls.update(ProxyStatus(total = 9, cfOk = 4, bytesDown = 100), 61_000))
        assertEquals(0, stalls.cfOpened)
        assertTrue(stalls.update(ProxyStatus(total = 10, cfOk = 6, bytesDown = 100), 62_000))
        assertEquals(2, stalls.cfOpened)
        stalls.update(ProxyStatus(total = 11, cfOk = 7, bytesDown = 200), 63_000)
        assertEquals("bytes came back", 0, stalls.cfOpened)
    }

    @Test
    fun stallStepsInOrder() {
        val bypass = CfTransport.BYPASS
        assertEquals(CfStallStep.NONE, cfStallStep(false, false, 5, bypass, emptySet()))
        assertEquals(CfStallStep.RECHECK, cfStallStep(true, false, 5, bypass, emptySet()))
        assertEquals(CfStallStep.SET_ASIDE, cfStallStep(true, true, 5, bypass, emptySet()))
        assertEquals("no CF connection during the stall", CfStallStep.NONE, cfStallStep(true, true, 0, bypass, emptySet()))
        assertEquals("already set aside", CfStallStep.NONE, cfStallStep(true, true, 5, bypass, setOf(bypass)))
        assertEquals(CfStallStep.SET_ASIDE, cfStallStep(true, true, 5, CfTransport.PLAIN, setOf(bypass)))
    }
}
