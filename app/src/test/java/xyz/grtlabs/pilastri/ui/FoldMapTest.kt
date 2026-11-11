package xyz.grtlabs.pilastri.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** G-09: folding and unfolding keep one place (DESIGN "Global frame" G-09). Pure state mapping, no window. */
class FoldMapTest {
    private val oct = "2026-10"

    /** What the open Fold shows for the current tab: the right pane's stack over its root, and the left root. */
    private fun rightPane(n: NavState) = n.rightEntries()
    /** What the cover shows: the stack over the left root. */
    private fun cover(n: NavState) = n.phoneEntries()

    @Test fun openClosedOpenKeepsTheRightPaneRootAndItsMonth() {
        // The open Fold on Pilastri | Mese(ottobre), nothing touched: Mese is "the place" (G-09 default).
        val open = NavState(month = oct)
        val closed = open.folded(oct)
        assertEquals(listOf(Route.Root(Tab.PILASTRI), Route.Month(oct)), cover(closed))
        // Back on the cover returns to the left root.
        assertEquals(listOf(Route.Root(Tab.PILASTRI)), cover(closed.pop()))
        val reopened = closed.unfolded()
        assertEquals(rightPane(open), rightPane(reopened))
        assertEquals(oct, reopened.month)
        assertTrue(Tab.PILASTRI in reopened.paneFocus)
        // And folding again lands on the same cover page.
        assertEquals(cover(closed), cover(reopened.folded(oct)))
    }

    @Test fun openClosedOpenKeepsADeeperDetailAndTheLeftSelection() {
        // A pillar opened from the left pane: the left touch makes the left root the place, the detail is the page.
        val open = NavState().touchPane(Tab.PILASTRI, right = false).push(Route.Pillar(2))
        val closed = open.folded(oct)
        assertEquals(listOf(Route.Root(Tab.PILASTRI), Route.Pillar(2)), cover(closed))
        assertEquals(listOf(Route.Root(Tab.PILASTRI)), cover(closed.pop()))
        val reopened = closed.unfolded()
        assertEquals(rightPane(open), rightPane(reopened))
        assertEquals(2, reopened.openPillar())
    }

    @Test fun aDetailOpenedFromMeseFoldsWithMeseUnderIt() {
        val open = NavState(month = oct).touchPane(Tab.PILASTRI, right = true).push(Route.Contributions)
        val closed = open.folded(oct)
        assertEquals(listOf(Route.Root(Tab.PILASTRI), Route.Month(oct), Route.Contributions), cover(closed))
        val reopened = closed.unfolded()
        assertEquals(rightPane(open), rightPane(reopened))
        assertEquals(cover(closed), cover(reopened.folded(oct)))
    }

    @Test fun closedOpenClosedKeepsTheCoverRoot() {
        // On the cover at Pilastri's root: unfolding shows Mese on the right, folding again returns to the root, not to Mese.
        val coverRoot = NavState()
        val open = coverRoot.unfolded()
        assertFalse(Tab.PILASTRI in open.paneFocus)
        assertEquals(cover(coverRoot), cover(open.folded(oct)))
        // A touch on Mese while open makes it the place.
        assertEquals(listOf(Route.Root(Tab.PILASTRI), Route.Month(oct)), cover(open.touchPane(Tab.PILASTRI, right = true).folded(oct)))
    }

    @Test fun everyTabKeepsItsOwnPlaceAndOtherSectionsLiftTheirPaneRoot() {
        val open = NavState(tab = Tab.SPESE).touchPane(Tab.SPESE, right = true)
        val closed = open.folded(oct)
        assertEquals(listOf(Route.Root(Tab.SPESE), Route.PaneRoot(Tab.SPESE)), cover(closed))
        val reopened = closed.unfolded()
        assertEquals(rightPane(open), rightPane(reopened))
        assertEquals(Tab.SPESE, reopened.tab)
        // The Pilastri stack, untouched, maps on its own.
        assertEquals(listOf(Route.Month(oct)), closed.stacks.getValue(Tab.PILASTRI))
        assertEquals(emptyList<Route>(), reopened.stacks.getValue(Tab.PILASTRI))
    }

    @Test fun theMappingSurvivesEncoding() {
        val n = NavState(month = oct).touchPane(Tab.FUTURO, right = false).folded(oct)
        val back = decodeNav(n.encode())!!
        assertEquals(n, back)
        assertEquals(n.unfolded(), back.unfolded())
    }

    @Test fun theDockPillLandsOnAgenteOnlyWhileItsCoverPageIsOpen() {
        val n = NavState(tab = Tab.SPESE)
        assertEquals(DockItem.AGENTE, dockSelection(n, agentOpen = true))
        assertEquals(DockItem.SPESE, dockSelection(n, agentOpen = false))
        assertEquals(DockItem.AGENTE, dockSelection(n.openSettings(), agentOpen = true))
    }
}
