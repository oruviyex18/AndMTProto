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
}
