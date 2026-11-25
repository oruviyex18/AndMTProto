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
        assertEquals(StopKind.USER, ProxyWatch.kindOf(userRequested, "stop io.github.oruviyex18.andmtproto due to from pid 2231"))
        // An update's install is "user requested" too, but nothing the user meant as a stop.
        assertEquals(
            StopKind.UPDATE,
            ProxyWatch.kindOf(userRequested, "stop io.github.oruviyex18.andmtproto due to installPackageLI"),
        )
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

    @Test
    fun theWantCountsOnlyInItsBoot() {
        assertTrue(ProxyWatch.wantedNow(flag = true, wantedBoot = 37, boot = 37))
        assertFalse("rebooted since", ProxyWatch.wantedNow(flag = true, wantedBoot = 37, boot = 38))
        assertFalse(ProxyWatch.wantedNow(flag = false, wantedBoot = 37, boot = 37))
    }

    @Test
    fun reviveOnlyWhenWantedStoppedAndNoStartIsUnderWay() {
        val grace = ProxyWatch.START_GRACE_MS
        assertTrue(ProxyWatch.shouldRevive(wanted = true, running = false, sinceStartRequestMs = grace))
        assertFalse(ProxyWatch.shouldRevive(wanted = false, running = false, sinceStartRequestMs = grace))
        assertFalse(ProxyWatch.shouldRevive(wanted = true, running = true, sinceStartRequestMs = grace))
        // The tile just asked the app to start it: the service isn't up yet.
        assertFalse(ProxyWatch.shouldRevive(wanted = true, running = false, sinceStartRequestMs = 300))
    }

    private val pkg = "io.github.oruviyex18.andmtproto"

    @Test
    fun theNewestExitOfTheMainProcessIsRecordedOnce() {
        val exits = listOf(
            ProxyWatch.Exit(1000, pkg, lowMemory, null),
            ProxyWatch.Exit(3000, pkg, crash, "crash"),
            ProxyWatch.Exit(4000, "$pkg:other", other, null), // another process of the app
        )
        val d = ProxyWatch.decideExit(exits, pkg, seenAt = 0, wanted = true)!!
        assertEquals(3000, d.seenAt)
        assertEquals(ProxyWatch.Stop(StopKind.CRASH, 3000, "crash"), d.stop) // the description repeats the reason
        assertFalse(d.clearWant)
        assertEquals("already seen", null, ProxyWatch.decideExit(exits, pkg, seenAt = 3000, wanted = true))
        assertEquals(null, ProxyWatch.decideExit(emptyList(), pkg, seenAt = 0, wanted = true))
    }

    @Test
    fun anExitWhileTheProxyWasOffIsOnlyMarkedSeen() {
        val d = ProxyWatch.decideExit(listOf(ProxyWatch.Exit(5000, pkg, lowMemory, null)), pkg, 0, wanted = false)!!
        assertEquals(5000, d.seenAt)
        assertEquals(null, d.stop)
        assertFalse(d.clearWant)
    }

    @Test
    fun aForceStopDropsTheWantButAnUpdateDoesNot() {
        val force = ProxyWatch.decideExit(
            listOf(ProxyWatch.Exit(6000, pkg, userRequested, "stop $pkg due to from process:10464")), pkg, 0, true)!!
        assertTrue(force.clearWant)
        assertEquals(StopKind.USER, force.stop?.kind)
        val update = ProxyWatch.decideExit(
            listOf(ProxyWatch.Exit(7000, pkg, userRequested, "stop $pkg due to installPackageLI")), pkg, 0, true)!!
        assertFalse(update.clearWant)
        assertEquals(StopKind.UPDATE, update.stop?.kind)
    }
}
