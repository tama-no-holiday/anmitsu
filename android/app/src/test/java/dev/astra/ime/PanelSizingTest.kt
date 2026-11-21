package dev.astra.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Panel heights over screen shapes, in dp at density 1 unless a test says otherwise. Plain arithmetic, no device claim. */
class PanelSizingTest {
    private val keys12 = 224 // four 56dp rows
    private val keysLand = 160 // four 40dp rows

    private fun sizing(screen: Int, top: Int = 40, bottom: Int = 48, chrome: Int = 44, keys: Int = keys12, density: Float = 1f) =
        PanelSizing(screen, top, bottom, chrome, keys, density)

    /** What the host keeps above the whole input view. */
    private fun hostRoom(screen: Int, panel: Int, bottom: Int = 48, chrome: Int = 44) = screen - panel - chrome - bottom

    @Test fun portraitPhone() {
        val s = sizing(892)
        assertEquals(264, s.normal)
        assertEquals("header plus half of 804", 44 + 402, s.focused)
        assertTrue(hostRoom(892, s.focused) > 300)
    }

    @Test fun densityScalesEveryLimit() {
        val dp = sizing(892)
        val px = sizing(3122, top = 140, bottom = 168, chrome = 154, keys = 784, density = 3.5f)
        assertEquals(dp.normal * 3.5f, px.normal.toFloat(), 4f)
        assertEquals(dp.focused * 3.5f, px.focused.toFloat(), 4f)
    }

    @Test fun aTallerNavigationAreaTakesFromTheUsableHeight() {
        assertTrue(sizing(892, bottom = 96).focused < sizing(892, bottom = 48).focused)
        assertTrue(sizing(892, top = 90).focused < sizing(892, top = 40).focused)
    }

    @Test fun landscapePhoneLeavesTheComposedLineVisibleAndStillEnlarges() {
        val s = sizing(411, top = 24, keys = keysLand)
        assertEquals("the minimum for two groups of two rows", PanelSizing.NORMAL_MIN_DP, s.normal)
        assertEquals("never smaller than the panel it grew from", s.normal, s.focused)
        assertTrue(hostRoom(411, s.focused) >= PanelSizing.HOST_MIN_DP)
        // One header instead of two, one group: the grid is far taller than a two-row group.
        assertTrue(s.focused - PanelSizing.HEADER_DP > 2 * (s.normal - 2 * PanelSizing.HEADER_DP) / 2)
    }

    @Test fun aRepairRowTakesFromTheEnlargedPanelNotFromTheHost() {
        val plain = sizing(700)
        val repair = sizing(700, chrome = 88)
        assertTrue(repair.focused <= plain.focused)
        assertTrue(hostRoom(700, repair.focused, chrome = 88) >= PanelSizing.HOST_MIN_DP)
    }

    @Test fun aGiantLandscapeDisplayIsNotHalvedBlindly() {
        val tablet = sizing(1600, keys = 320)
        assertEquals(PanelSizing.HEADER_DP + PanelSizing.GROUP_MAX_DP, tablet.focused)
        val wide = sizing(800, keys = 320)
        assertTrue(wide.focused - PanelSizing.HEADER_DP in 340..380)
        assertTrue(hostRoom(800, wide.focused) >= 250)
    }

    @Test fun theImeNeverTakesMoreThanItsShareOfTheUsableHeight() {
        for (screen in listOf(560, 640, 720, 800, 892, 1024, 1280, 1600)) for (chrome in listOf(44, 88)) {
            val s = sizing(screen, chrome = chrome)
            val usable = screen - 40 - 48
            assertTrue("$screen/$chrome", s.focused + chrome <= usable * PanelSizing.MAX_USABLE + 1)
            assertTrue("$screen/$chrome", s.focused >= s.normal)
        }
    }

    @Test fun keysTallerThanTheWantedPanelAreNeverShrunk() {
        val s = sizing(892, keys = 320)
        assertEquals(320, s.normal)
        assertTrue(s.focused >= 320)
    }

    @Test fun aVeryShortWindowGivesWayAtItsShareOfTheScreen() {
        val s = sizing(300, top = 24, keys = 120)
        assertEquals(180, s.normal)
        assertEquals(s.normal, s.focused)
    }

    @Test fun noInsetsAtAllStillGiveValidHeights() {
        val s = sizing(892, top = 0, bottom = 0)
        assertTrue(s.normal in 1..s.focused)
    }
}
