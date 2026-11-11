package xyz.grtlabs.pilastri.ui

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureHookTest {
    private val now = YearMonth.of(2026, 11)

    @Test fun rootOfATab() {
        val t = CaptureHook.target("root", "spese", null, now)
        assertEquals(Tab.SPESE, t.nav.tab); assertTrue(t.nav.depth.isEmpty()); assertNull(t.nav.month); assertEquals(now, t.month)
    }

    @Test fun routeListPushesInOrder() {
        val t = CaptureHook.target("settings,connections", null, null, now)
        assertEquals(listOf(Route.Settings, Route.Connections), t.nav.depth); assertEquals(Tab.PILASTRI, t.nav.tab)
    }

    @Test fun monthPageAndPrev() {
        val t = CaptureHook.target("month", "pilastri", "prev", now)
        assertEquals(listOf<Route>(Route.Month("2026-10")), t.nav.depth); assertEquals("2026-10", t.nav.month); assertEquals(YearMonth.of(2026, 10), t.month)
        assertEquals(listOf<Route>(Route.Month("2026-11")), CaptureHook.target("month", null, null, now).nav.depth)
    }

    @Test fun agentAndPillar() {
        assertTrue(CaptureHook.target("agent", null, null, now).agent)
        assertEquals(listOf<Route>(Route.Pillar(1)), CaptureHook.target("pillar:1", null, null, now).nav.depth)
        assertEquals(Tab.FUTURO, CaptureHook.target("pension", "futuro", null, now).nav.tab)
    }

    @Test fun speseSingleIsACaptureForcedStateOnTheSpeseTab() {
        val t = CaptureHook.target("spese-single", "spese", null, now)
        assertTrue(t.single); assertEquals(Tab.SPESE, t.nav.tab); assertTrue(t.nav.depth.isEmpty()); assertFalse(t.agent)
        assertFalse(CaptureHook.target("root", "spese", null, now).single)
        assertFalse(CaptureHook.target("agente-reply", null, null, now).single)
    }

    @Test fun framesExtra() {
        assertEquals(18 to 32L, CaptureHook.parseFrames("18:32"))
        assertEquals(10 to 32L, CaptureHook.parseFrames("10"))
        assertEquals(60 to 16L, CaptureHook.parseFrames("500:1"))
        assertEquals(null, CaptureHook.parseFrames(null))
        assertEquals(null, CaptureHook.parseFrames("x"))
    }

    /** The in-app finger uses the same points as the validated uiautomator path (viewport-point.mjs auto, cap-k mvs_, hold-point.mjs). */
    @Test fun motionPlanAndPoints() {
        val m = CaptureHook.parseMotion("fold-mese|root|PILASTRI||1|drag|0.4;phone-mese-mid|month:2026-10|PILASTRI|prev|0|hold|1;bad")
        assertEquals(2, m.size)
        assertEquals(CaptureHook.Motion("fold-mese", "root", "PILASTRI", null, 1, "drag", 0.4), m[0])
        assertEquals("prev", m[1].month)
        val x = "<node index=\"0\" text=\"\" resource-id=\"period-viewport\" bounds=\"[0,0][10,10]\"><node resource-id=\"period-viewport\" bounds=\"[42,1166][1062,1364]\">" +
            "<node resource-id=\"period-page-cur\" bounds=\"[168,1166][936,1364]\"></node></node>"
        // viewport #1: w 1020, x1 = 42 + 867 = 909, x2 = 42 + 357 = 399, y = 1265; short: 909 - trunc(510 * 0.4) = 705
        assertEquals(listOf(909, 1265, 399), CaptureHook.dragPoints(x, "period-viewport", 1, 1.0)!!.toList())
        assertEquals(listOf(909, 1265, 705), CaptureHook.dragPoints(x, "period-viewport", 1, 0.4)!!.toList())
        assertNull(CaptureHook.dragPoints(x, "period-viewport", 2, 1.0))
        // hold: 42 + 204 = 246, pitch 768 + 21 = 789
        assertEquals(listOf(246, 1265, 789), CaptureHook.holdPoint(x, "period-viewport", 1, 2.625)!!.toList())
    }
}
