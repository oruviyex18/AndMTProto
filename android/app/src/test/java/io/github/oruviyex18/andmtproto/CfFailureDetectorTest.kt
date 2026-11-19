package io.github.oruviyex18.andmtproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CfFailureDetectorTest {
    private fun at(ok: Int, failed: Int) = ProxyStatus(cfOk = ok, cfFailed = failed)

    @Test
    fun mostCfConnectionsFailingIsNoticed() {
        // MTS Wi-Fi, 19 Nov 2026: each request tried every CF domain and nearly all timed out.
        val d = CfFailureDetector()
        assertFalse(d.update(at(0, 0), 0))
        assertFalse(d.update(at(1, 10), 30_000))
        assertTrue(d.update(at(2, 40), 60_000))
        assertEquals(40, d.failed)
        assertEquals(2, d.opened)
    }

    @Test
    fun aFewSlowDomainsAreNotABreak() {
        // The shared domains are often overloaded: some hosts fail before one opens.
        val d = CfFailureDetector()
        d.update(at(0, 0), 0)
        assertFalse(d.update(at(5, 25), 60_000))
        // Too few failures to judge, even with nothing opening.
        val quiet = CfFailureDetector()
        quiet.update(at(0, 0), 0)
        assertFalse(quiet.update(at(0, 19), 60_000))
    }

    @Test
    fun onlyTheLastTwoMinutesCount() {
        val d = CfFailureDetector()
        d.update(at(0, 0), 0)
        d.update(at(0, 15), 60_000)
        // 30 failures in total, but only 15 within the last two minutes.
        assertFalse(d.update(at(0, 30), 180_000))
        assertTrue(d.update(at(0, 50), 200_000))
    }
}
