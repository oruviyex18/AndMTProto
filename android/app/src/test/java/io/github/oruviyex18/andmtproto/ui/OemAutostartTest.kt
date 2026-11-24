package io.github.oruviyex18.andmtproto.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OemAutostartTest {
    @Test
    fun makersWithTheirOwnAutostartList() {
        assertEquals(OemAutostart.HUAWEI, OemAutostart.forDevice("HUAWEI", "HUAWEI"))
        // Honor phones made before the split still report Huawei as the maker.
        assertEquals(OemAutostart.HONOR, OemAutostart.forDevice("HONOR", "HONOR"))
        assertEquals(OemAutostart.HONOR, OemAutostart.forDevice("HUAWEI", "HONOR"))
        assertEquals(OemAutostart.COLOROS, OemAutostart.forDevice("OPPO", "OPPO"))
        assertEquals(OemAutostart.COLOROS, OemAutostart.forDevice("realme", "realme"))
        assertEquals(OemAutostart.COLOROS, OemAutostart.forDevice("OnePlus", "OnePlus"))
        assertEquals(OemAutostart.VIVO, OemAutostart.forDevice("vivo", "vivo"))
        assertEquals(OemAutostart.VIVO, OemAutostart.forDevice("vivo", "iQOO"))
    }

    @Test
    fun xiaomiAndStockAndroidHaveNone() {
        // Xiaomi has its own cards (MIUI's Autostart switch can be read).
        assertNull(OemAutostart.forDevice("Xiaomi", "Redmi"))
        assertNull(OemAutostart.forDevice("Google", "google"))
        assertNull(OemAutostart.forDevice("samsung", "samsung"))
        assertNull(OemAutostart.forDevice("", ""))
    }

    @Test
    fun everyMakerHasScreensToTry() {
        for (oem in OemAutostart.entries) {
            assertTrue(oem.name, oem.screens.isNotEmpty())
            assertEquals(oem.name, oem.screens.size, oem.screens.toSet().size)
        }
    }
}
