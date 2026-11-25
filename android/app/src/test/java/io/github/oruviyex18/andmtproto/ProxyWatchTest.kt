package io.github.oruviyex18.andmtproto

import io.github.oruviyex18.andmtproto.ProxyWatch.StopKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyWatchTest {
    // ApplicationExitInfo.REASON_* values (Android 11+); plain ints, as the class isn't in unit tests.
    private val lowMemory = 3
    private val signaled = 2
    private val other = 13
    private val crash = 4
    private val anr = 6
    private val userRequested = 10
    private val packageUpdated = 16
    private val exitSelf = 1

    @Test
    fun firmwareKillsCountAsTheSystem() {
        // MIUI reports its background-app killer as "other" or "signaled".
        for (reason in listOf(lowMemory, signaled, other, 0, 9, 14)) {
            assertEquals("reason $reason", StopKind.SYSTEM, ProxyWatch.kindOf(reason))
        }
    }

    @Test
    fun crashesUserStopsAndUpdatesAreToldApart() {
        assertEquals(StopKind.CRASH, ProxyWatch.kindOf(crash))
        assertEquals(StopKind.CRASH, ProxyWatch.kindOf(anr))
        assertEquals(StopKind.USER, ProxyWatch.kindOf(userRequested))
        assertEquals(StopKind.UPDATE, ProxyWatch.kindOf(packageUpdated))
        assertEquals(StopKind.OTHER, ProxyWatch.kindOf(exitSelf))
        assertEquals(StopKind.OTHER, ProxyWatch.kindOf(99))
    }

    @Test
    fun onlyTheSystemAndCrashesGetACard() {
        assertTrue(ProxyWatch.worthACard(StopKind.SYSTEM))
        assertTrue(ProxyWatch.worthACard(StopKind.CRASH))
        // The user did it, or an update did: nothing to explain.
        assertFalse(ProxyWatch.worthACard(StopKind.USER))
        assertFalse(ProxyWatch.worthACard(StopKind.UPDATE))
        assertFalse(ProxyWatch.worthACard(StopKind.OTHER))
    }
}
