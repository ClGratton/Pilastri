package xyz.grtlabs.pilastri.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavTest {
    @Test fun phoneAndWideShowTheSameLogicalStack() {
        val nav = NavState().open(Route.Pillar(4)).push(Route.Pension)
        assertEquals(listOf(Route.Root(Tab.PILASTRI), Route.Pillar(4), Route.Pension), nav.phoneEntries())
        assertEquals(listOf(Route.PaneRoot(Tab.PILASTRI), Route.Pillar(4), Route.Pension), nav.rightEntries())
        assertEquals(4, nav.openPillar())
    }

    @Test fun choosingAnotherPillarSwapsInsteadOfStacking() {
        val nav = NavState().open(Route.Pillar(1)).open(Route.Pillar(3))
        assertEquals(listOf(Route.Pillar(3)), nav.depth)
        val deeper = nav.push(Route.Pension).open(Route.Pillar(2))
        assertEquals("Below the first level a new page pushes", listOf(Route.Pillar(3), Route.Pension, Route.Pillar(2)), deeper.depth)
    }

    @Test fun backPopsThenGoesHomeThenExits() {
        var nav: NavState? = NavState().select(Tab.SPESE).push(Route.Review)
        nav = nav!!.back(); assertEquals(Tab.SPESE, nav!!.tab); assertEquals(emptyList<Route>(), nav.depth)
        nav = nav.back(); assertEquals(Tab.PILASTRI, nav!!.tab)
        assertNull(nav.back())
    }

    @Test fun eachTabKeepsItsOwnStack() {
        val nav = NavState().open(Route.Pillar(2)).select(Tab.FUTURO).push(Route.Pension).select(Tab.PILASTRI)
        assertEquals(listOf(Route.Pillar(2)), nav.depth)
        assertEquals(listOf(Route.Pension), nav.stacks.getValue(Tab.FUTURO))
    }
}
