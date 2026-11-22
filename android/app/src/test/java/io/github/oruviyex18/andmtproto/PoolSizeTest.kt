package io.github.oruviyex18.andmtproto

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The WebSocket pool size: 2 by default since android.12, 4 before. */
class PoolSizeTest {
    @Test
    fun newSettingsUseTwo() {
        assertEquals(2, ProxySettings().poolSize)
        assertEquals(2, ProxySettings.fromJson(JSONObject("{}")).poolSize)
    }

    @Test
    fun theOldDefaultMovesAndOtherSizesStay() {
        assertEquals(2, ProxySettings(poolSize = 4).withNewPoolDefault().poolSize)
        for (size in listOf(0, 1, 3, 8)) {
            assertEquals(size, ProxySettings(poolSize = size).withNewPoolDefault().poolSize)
        }
    }
}
