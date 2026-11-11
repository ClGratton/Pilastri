package xyz.grtlabs.pilastri.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp

/** Slice 5a: Agente is an action, not a section; Impostazioni is pushed from its own corner button. */
class IslandNavTest {
    @Test fun threeSectionsOnly() {
        assertEquals(listOf(Tab.PILASTRI, Tab.SPESE, Tab.FUTURO), Tab.entries)
    }

    @Test fun savedAgenteSectionDecodesToPilastriAndKeepsTheOtherStacks() {
        val saved = """{"tab":"AGENTE","stacks":{"PILASTRI":["pillar:2"],"SPESE":["review"],"AGENTE":["settings"]}}"""
        val nav = decodeNav(saved)!!
        assertEquals(Tab.PILASTRI, nav.tab)
        assertEquals(listOf<Route>(Route.Pillar(2)), nav.stacks.getValue(Tab.PILASTRI))
        assertEquals(listOf<Route>(Route.Review), nav.stacks.getValue(Tab.SPESE))
        assertEquals(emptyList<Route>(), nav.stacks.getValue(Tab.FUTURO))
    }

    @Test fun unknownOrMissingSectionFallsBackToPilastri() {
        assertEquals(Tab.PILASTRI, tabOf("AGENTE"))
        assertEquals(Tab.PILASTRI, tabOf(null))
        assertEquals(Tab.SPESE, tabOf("SPESE"))
    }

    @Test fun roundTripKeepsTheStack() {
        val nav = NavState().select(Tab.SPESE).push(Route.Review).openSettings()
        assertEquals(nav, decodeNav(nav.encode()))
    }

    @Test fun impostazioniPushesLikeADetailAndReturnsToItselfWhenAlreadyOpen() {
        val pillar = NavState().open(Route.Pillar(1))
        val settings = pillar.openSettings()
        assertEquals(listOf(Route.Pillar(1), Route.Settings), settings.depth)
        assertTrue(settings.settingsOpen)
        assertFalse(pillar.settingsOpen)
        val deeper = settings.push(Route.Backup)
        assertTrue(deeper.settingsOpen)
        assertEquals(listOf(Route.Pillar(1), Route.Settings), deeper.openSettings().depth)
        assertEquals(settings, settings.openSettings())
        assertEquals(pillar, settings.pop())
    }

    /* Cover and phone: one dock with all five items (user, 2026-10-31); open Fold: island + corner Impostazioni. */

    @Test fun coverAndPhoneShowOneDockWithAllFiveItems() {
        val cover = frameSpec(AppLayout.Compact)
        assertEquals(listOf(DockItem.PILASTRI, DockItem.SPESE, DockItem.FUTURO, DockItem.AGENTE, DockItem.IMPOSTAZIONI), cover.dock)
        assertTrue("no island on the cover", cover.island.isEmpty())
        assertFalse("no corner button on the cover", cover.cornerSettings)
    }

    @Test fun openFoldKeepsTheIslandAndTheCornerImpostazioni() {
        for (layout in listOf(AppLayout.RailSingle, AppLayout.TwoPane(416.dp, 0.dp, 416.dp, onHinge = true))) {
            val f = frameSpec(layout)
            assertTrue(f.dock.isEmpty())
            assertEquals(listOf(DockItem.PILASTRI, DockItem.SPESE, DockItem.FUTURO, DockItem.AGENTE), f.island)
            assertTrue(f.cornerSettings)
        }
    }

    @Test fun agenteNeverTakesThePill() {
        val states = Tab.entries.flatMap { t ->
            val n = NavState().select(t)
            listOf(n, n.push(Route.Review), n.openSettings(), n.openSettings().push(Route.Backup), n.openSettings().pop())
        }
        states.forEach { assertTrue(dockSelection(it) != DockItem.AGENTE) }
        Tab.entries.forEach { assertEquals(DockItem.of(it), dockSelection(NavState().select(it))) }
    }

    @Test fun impostazioniTakesThePillOnTheCoverWhileOpen() {
        val spese = NavState().select(Tab.SPESE).push(Route.Review)
        val settings = spese.openSettings()
        assertEquals(DockItem.IMPOSTAZIONI, dockSelection(settings))
        assertEquals("deeper inside Impostazioni", DockItem.IMPOSTAZIONI, dockSelection(settings.push(Route.Backup)))
        assertEquals(DockItem.SPESE, dockSelection(settings.pop()))
        // Leaving it from the dock returns the stack to where it was opened.
        assertEquals(spese, settings.leaveSettings())
        assertEquals(spese, settings.push(Route.Backup).leaveSettings())
        assertEquals(spese, spese.leaveSettings())
    }

    @Test fun dockFitsAndStaysCentredAtCoverWidths() {
        for (w in listOf(320f, 360f, 412f)) {
            val g = dockGeometry(w)
            assertTrue("$w: items ≥ 48 dp", g.item >= 48f)
            assertTrue("$w: items ≤ 76 dp", g.item <= 76f)
            assertEquals("$w: five items + 4 dp padding", g.item * 5 + 8f, g.width, 0.001f)
            assertTrue("$w: 12 dp margins", g.start >= 12f - 0.001f && g.start + g.width <= w - 12f + 0.001f)
            assertEquals("$w: centred", w - g.start - g.width, g.start, 0.001f)
            assertEquals(64f, g.height, 0.001f)
        }
        assertEquals(57.6f, dockGeometry(320f).item, 0.01f)
        assertEquals(65.6f, dockGeometry(360f).item, 0.01f)
        assertEquals("the board's 76 dp", 76f, dockGeometry(412f).item, 0.01f)
        assertEquals("wider phones keep 76 dp items, centred", 388f, dockGeometry(560f).width, 0.01f)
    }

    /** User, 2026-11-01: controls never move under the finger. The capsule and Agente have ONE place; Back only fills a reserved slot above. */
    @Test fun foldIslandCapsuleStaysPutWhetherOrNotBackShows() {
        val p = foldIslandPlan()
        val back = IslandSpec.Back.value; val gap = IslandSpec.GroupGap.value
        assertEquals("the plan takes no Back state: the capsule's bounds are the same with and without Back", foldIslandPlan(), p)
        assertEquals("Back sits at the top of its slot", 0f, p.backTop, 0.001f)
        assertEquals("Back + gap fill the slot exactly: the capsule starts right below it", p.backTop + back + gap, p.capsuleTop, 0.001f)
        assertEquals("the capsule is 3 items + 2 gaps + padding", 160f, p.capsuleHeight, 0.001f)
        assertEquals("Agente sits one gap below the capsule", p.capsuleTop + p.capsuleHeight + gap, p.agentTop, 0.001f)
        val groupHeight = p.capsuleHeight + gap + IslandSpec.AgentFold.value
        assertEquals("the mirror slot below keeps the group centred as it was without Back", p.height / 2f, p.capsuleTop + groupHeight / 2f, 0.001f)
        assertEquals("island height = slot + group + mirror slot", (back + gap) * 2f + groupHeight, p.height, 0.001f)
    }

    @Test fun islandColumnIs80AndTheIslandIsCentredInIt() {
        assertEquals(80f, IslandSpec.ColumnWidth.value, 0.001f)
        // The island is placed Margin from the screen edge (12 dp; K measured 12.2 dp on the 09:38 APK at 2.625): its left edge is Margin + 56 from it; the column's left edge is 80.
        val gapRight = IslandSpec.Margin.value
        val gapLeft = IslandSpec.ColumnWidth.value - IslandSpec.Margin.value - IslandSpec.AgentFold.value
        assertEquals("12 dp either side of the island (column 80 = 56 + 2 x 12)", gapRight, gapLeft, 0.001f)
    }

    @Test fun backOnlyWithADetailOpen_notOnAnImpostazioniRoot() {
        val root = NavState()
        assertFalse(root.islandBackShown(false))
        val settings = root.openSettings()
        assertFalse("Impostazioni is a section root opened as a page: no Back, the gear leaves it", settings.islandBackShown(false))
        assertTrue("a page over Impostazioni is a detail", settings.push(Route.Backup).islandBackShown(false))
        assertTrue(root.push(Route.Pillar(1)).islandBackShown(false))
        assertFalse("Agente open hides Back", root.push(Route.Pillar(1)).islandBackShown(true))
        assertTrue(Route.Settings.isSectionRoot())
        assertFalse(Route.Review.isSectionRoot())
    }
}
