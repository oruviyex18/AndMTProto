package io.github.oruviyex18.andmtproto.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramCardStateTest {
    @Test
    fun noRejectionsFollowsLinked() {
        assertEquals(TelegramCardState.CONNECT, telegramCardState(linked = false, badRecent = 0))
        assertEquals(TelegramCardState.LINKED, telegramCardState(linked = true, badRecent = 1))
    }

    @Test
    fun rejectionsWithAWorkingEntryMeanExtraEntries() {
        assertEquals(TelegramCardState.EXTRA_ENTRIES, telegramCardState(linked = true, badRecent = 2))
    }

    @Test
    fun rejectionsWithoutAWorkingEntryMeanAnOldKey() {
        // 20 Nov 2026: one entry in Telegram, the app got a new secret: ~12 rejections a minute.
        assertEquals(TelegramCardState.OLD_KEY, telegramCardState(linked = false, badRecent = 12))
    }

    @Test
    fun oldRejectionsAfterTheFixDontWarn() {
        // Re-added and old entry deleted: the old key's rejections are still in the window.
        assertEquals(TelegramCardState.LINKED, telegramCardState(linked = true, badRecent = 52, badSinceLinked = 1))
        // But an old entry left in place keeps being rejected.
        assertEquals(TelegramCardState.EXTRA_ENTRIES, telegramCardState(linked = true, badRecent = 60, badSinceLinked = 8))
    }
}
