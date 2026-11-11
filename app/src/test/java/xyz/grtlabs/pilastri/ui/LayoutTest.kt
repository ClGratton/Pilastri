package xyz.grtlabs.pilastri.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    @Test fun phonesAndCoverAreCompact() {
        assertEquals(AppLayout.Compact, appLayout(360f, 800f, null, null, false))
        assertEquals(AppLayout.Compact, appLayout(400f, 960f, null, null, false))
    }

    @Test fun wideButShortOrNarrowStaysSingle() {
        assertEquals(AppLayout.RailSingle, appLayout(700f, 900f, null, null, false))
        assertEquals("landscape phone", AppLayout.RailSingle, appLayout(800f, 360f, null, null, false))
        assertEquals("horizontal hinge never splits", AppLayout.RailSingle, appLayout(830f, 750f, null, null, true))
    }

    @Test fun openFoldSplitsAtTheHingeWhenBothSidesFit() {
        val layout = appLayout(830f, 750f, 415f, 0f, false)
        // No rail on two panes (floating dock): the left pane reaches the hinge.
        assertTrue(layout is AppLayout.TwoPane && layout.onHinge && layout.left.value == 415f && layout.right.value == 415f)
    }

    @Test fun hingeThatLeavesTooLittleFallsBackToHalves() {
        val layout = appLayout(830f, 750f, 280f, 0f, false)
        assertTrue(layout is AppLayout.TwoPane && !layout.onHinge && layout.left.value == 415f && layout.right.value == 415f)
    }

    @Test fun openFoldWithoutAFoldFeatureSplitsInHalf() {
        val layout = appLayout(832f, 750f, null, null, false)
        assertTrue(layout is AppLayout.TwoPane && layout.left.value == 416f && layout.right.value == 416f && layout.gap.value == 0f)
    }

    @Test fun minimaNotMetIsSingleColumn() {
        assertEquals(AppLayout.RailSingle, appLayout(739f, 600f, null, null, false))
        assertEquals(AppLayout.RailSingle, appLayout(740f, 470f, null, null, false))
        assertTrue(appLayout(1000f, 600f, null, null, false) is AppLayout.TwoPane)
    }
}
