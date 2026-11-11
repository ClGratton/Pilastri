package xyz.grtlabs.pilastri.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.ui.Route

/** UX-N1: tap-through, the "Altro" expand and its morph. Synthetic data only, density 1 (px = dp). */
class FlowInteractionTest {
    private val dp: (Float) -> Float = { it }
    private val w = 360f

    private fun measure() = FlowMeasure { text, style, maxWidth, maxLines ->
        val em = when (style) { FlowText.TITLE -> 12.4f; FlowText.STRONG, FlowText.SMALL -> 11.5f; FlowText.TINY -> 10.5f; FlowText.BIG -> 16f }
        val ch = em * 0.6f
        var lines = 1; var cur = 0f; var widest = 0f
        text.split(' ').forEach { word ->
            val ww = word.length * ch
            when {
                cur == 0f -> cur = ww
                cur + ch + ww <= maxWidth -> cur += ch + ww
                else -> { widest = maxOf(widest, cur); lines++; cur = ww }
            }
        }
        FlowSize(minOf(maxOf(widest, cur), maxWidth), minOf(lines, maxLines) * em * 1.3f)
    }

    private val categories = listOf(FlowCategory("Casa", 60_000, false, "casa", true), FlowCategory("Svago", 20_000, true, "svago", true)) +
        (1..16).map { FlowCategory("Categoria minuscola numero $it", 100L + it * 13, false, "t$it", true) }
    private val input = FlowInput(
        200_000, "stipendio 23 ott", listOf("Fondo pensione" to 15_000L, "Piano di accumulo" to 12_000L), categories.sumOf { it.cents }, categories, emptyList(),
        pillarNumbers = listOf(2, 4),
    )
    private val closed = layoutFlow(input, w, dp, 1f, measure())
    private val open = layoutFlow(input, w, dp, 1f, measure(), expandAltro = true)

    private fun FlowBox.centre() = (left + right) / 2 to (top + bottom) / 2

    @Test fun categoriesAndPillarsOpenTheirPageAndAltroExpands() {
        assertTrue("the many small categories fold", closed.hasGroup)
        closed.parts.forEach { p ->
            val (x, y) = p.name.box.centre()
            when (val k = p.row.key) {
                null -> {}
                FlowKey.Altro -> assertEquals(FlowKey.Altro, closed.hitAt(x, y))
                else -> assertEquals("'${p.row.name}' opens its page", k, closed.hitAt(x, y))
            }
        }
        val pillars = closed.hits.filter { it.key is FlowKey.Pillar }
        assertEquals(listOf(FlowKey.Pillar(2), FlowKey.Pillar(4)), pillars.map { it.key })
        pillars.forEach { h -> val (x, y) = h.box.centre(); assertEquals(h.key, closed.hitAt(x, y)) }
        assertEquals(Route.Category("casa"), FlowKey.Category("casa").route())
        assertEquals(Route.Pillar(4), FlowKey.Pillar(4).route())
        assertNull("Altro opens nothing, it expands", FlowKey.Altro.route())
    }

    @Test fun aBandIsHitExactlyAlongItsCurve() {
        val casa = closed.parts.single { it.row.name == "Casa" }
        val r = casa.ribbon
        val x = (r.x0 + r.x1) / 2
        val (a, b) = r.edgesAt(x)
        assertEquals(FlowKey.Category("casa"), closed.hitAt(x, (a + b) / 2))
        assertNull("far outside any target", closed.hitAt(5f, closed.height + 200f))
    }

    @Test fun rowsWithNoPageAreNotTappable() {
        assertTrue("restano is a calculation, not a category", closed.rows.any { it.kind == FlowKind.REST })
        assertTrue(closed.hits.none { it.label.contains("restano") })
        val untouched = layoutFlow(input.copy(categories = categories.map { it.copy(openable = false) }, pillarNumbers = emptyList()), w, dp, 1f, measure())
        assertTrue("with no destinations only the Altro fold is a target", untouched.hits.all { it.key == FlowKey.Altro })
    }

    @Test fun everyTargetIsAtLeast48DpTall() {
        (closed.hits + open.hits).forEach { assertTrue("${it.label}: ${it.box.bottom - it.box.top} dp", it.box.bottom - it.box.top >= 47.99f) }
    }

    @Test fun expandingListsEveryFoldedCategoryAndKeepsTextApart() {
        assertFalse(open.hasGroup)
        assertEquals(closed.rows.sumOf { it.cents }, open.rows.sumOf { it.cents })
        assertEquals(categories.size, open.rows.count { it.kind != FlowKind.REST })
        val folded = closed.rows.single { it.key == FlowKey.Altro }
        assertTrue(folded.children.size >= 2 && folded.children.map { it.name } == folded.members)
        assertTrue("members keep their own page", folded.children.all { it.key is FlowKey.Category })
        val boxes = open.labels.map { it.text to it.box }
        boxes.forEachIndexed { i, (t, a) ->
            assertTrue("'$t' leaves the card", a.left >= -0.01f && a.right <= w + 0.01f && a.bottom <= open.height + 0.01f)
            boxes.drop(i + 1).forEach { (u, b) -> assertFalse("'$t' overlaps '$u'", a.overlaps(b)) }
            open.ribbons.forEach { r -> assertFalse("'$t' is drawn over a band", r.hits(a)) }
            open.bars.forEach { bar -> assertFalse("'$t' is drawn over a node", bar.box.overlaps(a)) }
        }
        open.labels.filter { it.alignEnd }.forEach { assertEquals(w, it.box.right, 0.01f) }
        assertTrue("opening makes the chart taller", open.height > closed.height)
    }

    @Test fun theStaticPartIsTheSameShapeFoldedAndOpen() {
        assertEquals(closed.staticRibbons, open.staticRibbons); assertEquals(closed.staticBars, open.staticBars); assertEquals(closed.staticLabels, open.staticLabels)
        assertEquals(closed.header?.text, open.header?.text)
    }

    @Test fun morphStartsAtTheFoldedChartAndEndsAtTheOpenOne() {
        val s = morphFrame(closed, open, 0f, 0f)
        assertEquals(closed.height, s.height, 0.001f)
        assertEquals(closed.bars.size, s.bars.count { it.second > 0.999f }); assertEquals("nothing else visible", closed.bars.size, s.bars.count { it.second > 0f })
        assertEquals(closed.labels.size, s.labels.count { it.second > 0f })
        val e = morphFrame(closed, open, 1f, 1f)
        assertEquals(open.height, e.height, 0.001f)
        assertEquals(open.bars.size, e.bars.count { it.second > 0.999f }); assertEquals(open.bars.size, e.bars.count { it.second > 0f })
        assertEquals(open.labels.size, e.labels.count { it.second > 0f })
        val ys = e.bars.filter { it.second > 0f }.map { it.first.y }.sorted()
        open.bars.map { it.y }.sorted().zip(ys).forEach { (want, got) -> assertEquals(want, got, 0.001f) }
    }

    @Test fun midMorphMembersFadeInWhileAltroFadesOut() {
        val m = morphFrame(closed, open, 0.5f, 0.5f)
        assertEquals(lerp(closed.height, open.height, 0.5f), m.height, 0.001f)
        assertTrue(m.labels.all { it.second in 0f..1f } && m.bars.all { it.second in 0f..1f } && m.ribbons.all { it.second in 0f..1f })
        val altro = closed.parts.single { it.row.key == FlowKey.Altro }
        assertEquals("Altro fades out where it was", 0.5f, m.labels.single { it.first === altro.name }.second, 0.0001f)
        val member = open.parts.first { it.row.name in altro.row.members }
        assertTrue("a member grows out of it", m.labels.any { it.first.text == member.name.text && it.second == 0.5f })
        val kept = open.parts.first { it.row.name == "Casa" }
        assertEquals("a row present in both stays opaque", 1f, m.labels.first { it.first.text == "Casa" }.second, 0f)
        assertNotNull(kept)
    }

    @Test fun reducedMotionSnapsGeometryAndOnlyCrossfades() {
        val f = morphFrame(closed, open, 1f, 0.5f)
        assertEquals(open.height, f.height, 0.001f)
        val fading = f.bars.filter { it.second == 0.5f }.map { it.first }
        val atFinal = fading.filter { b -> open.bars.any { Math.abs(it.y - b.y) < 0.001f && Math.abs(it.x - b.x) < 0.001f } }
        val folded = closed.rows.single { it.key == FlowKey.Altro }
        assertEquals("every member is at its final place", folded.children.size, atFinal.size)
        assertEquals("plus the fading Altro bar where it was", folded.children.size + 1, fading.size)
    }

    @Test fun withoutAGroupThereIsNothingToMorph() {
        val few = layoutFlow(input.copy(categories = categories.take(2), spend = categories.take(2).sumOf { it.cents }), w, dp, 1f, measure())
        assertFalse(few.hasGroup)
        val f = morphFrame(few, few, 0f, 0f)
        assertEquals(few.bars.size, f.bars.size); assertTrue(f.labels.all { it.second == 1f })
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
