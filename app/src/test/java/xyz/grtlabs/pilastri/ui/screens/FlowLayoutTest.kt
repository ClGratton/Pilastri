package xyz.grtlabs.pilastri.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flusso labels never overlap (user, 2026-10-31): synthetic data only, density 1 (px = dp). */
class FlowLayoutTest {
    private val dp: (Float) -> Float = { it }
    private val widths = listOf(360f, 344f, 320f)
    private val scales = listOf(1.0f, 1.3f, 2.0f)

    /** Stand-in for the text measurer: word wrap at 0.6 em per character, 1.3 em lines, "…" past the last line. */
    private fun measure(fs: Float) = FlowMeasure { text, style, maxWidth, maxLines ->
        val em = fs * when (style) { FlowText.TITLE -> 12.4f; FlowText.STRONG, FlowText.SMALL -> 11.5f; FlowText.TINY -> 10.5f; FlowText.BIG -> 16f }
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

    private fun cat(name: String, cents: Long, over: Boolean = false) = FlowCategory(name, cents, over)

    private val longNamesTinyValues = listOf(
        cat("Casa", 52_000), cat("Spesa alimentare e supermercato di quartiere", 30_600), cat("Trasporti e carburante", 15_800),
        cat("Ristoranti, bar e consegne a domicilio", 18_800, over = true), cat("Svago", 13_000),
        cat("Abbonamenti digitali e servizi in streaming", 900), cat("Commissioni bancarie", 120), cat("Altro", 700),
        cat("Regali", 40),
    )

    private fun input(income: Long?, pillars: List<Pair<String, Long>>, cats: List<FlowCategory>, extras: List<Pair<String, Long>> = emptyList()) =
        FlowInput(income, "stipendio 23 ott", pillars, cats.sumOf { it.cents }, cats, extras)

    private val scenarios = mapOf(
        "long names, tiny values, two pillars, a separate source" to input(
            184_200, listOf("Fondo pensione negoziale di categoria" to 15_000, "Piano di accumulo ETF globale" to 15_000), longNamesTinyValues,
            listOf("Acquisti dentro un pilastro, dati di esempio" to 5_000),
        ),
        "spending above what is left: from the balance" to input(100_000, listOf("Fondo pensione" to 30_000), longNamesTinyValues),
        "income not in the data" to input(null, emptyList(), longNamesTinyValues.take(4)),
        "one pillar, one category" to input(150_000, listOf("Fondo pensione" to 10_000), listOf(cat("Un nome di categoria davvero molto lungo e senza fine", 25))),
        "many tiny categories" to input(200_000, emptyList(), listOf(cat("Casa", 60_000)) + (1..16).map { cat("Categoria minuscola numero $it", 100L + it * 13) }),
    )

    private fun assertClean(c: FlowChart, w: Float, what: String) {
        val boxes = c.labels.map { it.text to it.box }
        boxes.forEachIndexed { i, (t, a) ->
            assertTrue("$what: '$t' $a leaves the card", a.left >= -0.01f && a.right <= w + 0.01f && a.top >= -0.01f && a.bottom <= c.height + 0.01f)
            boxes.drop(i + 1).forEach { (u, b) -> assertFalse("$what: '$t' $a overlaps '$u' $b", a.overlaps(b)) }
            c.ribbons.forEach { r -> assertFalse("$what: '$t' $a is drawn over a band", r.hits(a)) }
            c.bars.forEach { bar -> assertFalse("$what: '$t' $a is drawn over a node ${bar.box}", bar.box.overlaps(a)) }
        }
        val values = c.labels.filter { it.alignEnd }
        values.forEach { assertEquals("$what: value '${it.text}' right-aligned", w, it.box.right, 0.01f) }
        assertTrue("$what: one value column", values.map { it.left }.distinct().size <= 1)
        // Category nodes keep a minimum gap.
        val nodes = c.bars.filter { b -> b.fill != null && c.ribbons.any { r -> r.x1 == b.x && r.x0 > 0f } }
        nodes.groupBy { it.x }.values.forEach { column ->
            column.sortedBy { it.y }.zipWithNext().forEach { (a, b) -> assertTrue("$what: nodes too close ${a.box} ${b.box}", b.y - (a.y + a.h) >= 2.99f) }
        }
    }

    @Test fun noTextOverlapsTextNodesOrBandsAtEveryWidthAndFontScale() {
        for ((what, inp) in scenarios) for (w in widths) for (fs in scales) {
            assertClean(layoutFlow(inp, w, dp, fs, measure(fs)), w, "$what @${w}dp ×$fs")
        }
    }

    /** D-03: the card body owns the inset, so the canvas starts at its first drawn row and ends at its last (no blank margin). */
    @Test fun canvasEqualsDrawnExtentsTopAndBottom() {
        for ((what, inp) in scenarios) for (w in widths) for (fs in scales) {
            val c = layoutFlow(inp, w, dp, fs, measure(fs))
            val tops = c.labels.map { it.box.top } + c.bars.map { it.y } + c.ribbons.map { minOf(it.a0, it.b0) }
            val bottoms = c.labels.map { it.box.bottom } + c.bars.map { it.y + it.h } + c.ribbons.map { maxOf(it.a1, it.b1) }
            assertEquals("$what @$w ×$fs: first row touches the top", 0f, tops.min(), 0.01f)
            assertEquals("$what @$w ×$fs: last row touches the bottom", c.height, bottoms.max(), 0.01f)
        }
    }

    @Test fun ribbonHitTestFollowsTheDrawnCurve() {
        val r = FlowRibbon(0f, 0f, 10f, 100f, 50f, 60f, FlowFill.RIBBON_OUT)
        assertTrue(r.hits(FlowBox(0f, 5f, 4f, 8f)))
        assertFalse(r.hits(FlowBox(0f, 20f, 10f, 30f)))
        assertTrue(r.hits(FlowBox(45f, 28f, 55f, 32f)))
        assertFalse(r.hits(FlowBox(90f, 0f, 100f, 40f)))
        assertFalse("outside its x range", r.hits(FlowBox(101f, 0f, 120f, 100f)))
    }

    @Test fun smallestCategoriesFoldIntoAltroWithTheirSumAndNothingIsLost() {
        val inp = scenarios.getValue("many tiny categories")
        for (w in widths) for (fs in scales) {
            val c = layoutFlow(inp, w, dp, fs, measure(fs))
            val altro = c.rows.single { it.name == "Altro" }
            assertTrue("several categories in Altro", altro.members.size >= 2)
            assertEquals(inp.categories.sumOf { it.cents }, c.rows.filter { it.kind != FlowKind.REST }.sumOf { it.cents })
            assertEquals(inp.categories.size, c.rows.filter { it.kind != FlowKind.REST }.sumOf { it.members.size })
            assertTrue("Casa keeps its own row", c.rows.any { it.name == "Casa" })
            // The folded ones are the smallest.
            val kept = c.rows.filter { it.name != "Altro" && it.kind == FlowKind.NORMAL }.minOf { it.cents }
            val folded = inp.categories.filter { it.name in altro.members }.maxOf { it.cents }
            assertTrue(folded <= kept)
        }
    }


    /** Q1 (R1 item 4, polish 2): node labels keep a clearance: the gap between any two label boxes is at least the in-row gap (4 dp), on the axis where they are closest. Any data, widths and text scales. */
    @Test fun nodeLabelsKeepAClearanceAtEveryWidthAndFontScale() {
        val sampleLike = input(null, listOf("Lungo termine" to 15_000), listOf(cat("Casa", 20_000), cat("Spesa", 14_000), cat("Altro", 10_100)))
        for ((what, inp) in scenarios + ("sample-like month: income unknown, one pillar, deficit" to sampleLike)) for (w in widths) for (fs in scales) {
            val c = layoutFlow(inp, w, dp, fs, measure(fs))
            val l = c.labels
            for (i in l.indices) for (j in i + 1 until l.size) {
                val a = l[i].box; val b = l[j].box
                val sep = maxOf(maxOf(a.left - b.right, b.left - a.right), maxOf(a.top - b.bottom, b.top - a.bottom))
                // a row's own sub-line ("oltre il budget" under its name, 1 dp by design) is part of that item, not a neighbouring label
                val subLine = l[j].style == FlowText.TINY && b.top - a.bottom >= 0.99f
                if (subLine) continue
                assertTrue("$what @${w}dp x$fs: '${l[i].text}' $a and '${l[j].text}' $b are ${sep} dp apart (min 4)", sep >= 3.99f)
            }
        }
    }

    @Test fun overBudgetAndRemainingRowsAreNeverFolded() {
        val rows = listOf(FlowRow("A", 10, FlowKind.OVER), FlowRow("B", 20, FlowKind.NORMAL), FlowRow("C", 30, FlowKind.NORMAL), FlowRow("D", 5000, FlowKind.NORMAL), FlowRow("restano", 1, FlowKind.REST))
        val out = groupRows(rows, budget = 4f) { it.size.toFloat() }
        assertEquals(listOf("A", "D", "Altro", "restano"), out.map { it.name })
        assertEquals(50L, out.single { it.name == "Altro" }.cents)
        assertEquals(rows, groupRows(rows, budget = 10f) { it.size.toFloat() })
    }

    @Test fun anExistingAltroJoinsTheGroupSoThereIsOnlyOne() {
        val rows = listOf(FlowRow("Casa", 9000, FlowKind.NORMAL), FlowRow("Altro", 4000, FlowKind.NORMAL), FlowRow("x", 1, FlowKind.NORMAL), FlowRow("y", 2, FlowKind.NORMAL))
        val out = groupRows(rows, budget = 2f) { it.size.toFloat() }
        assertEquals(1, out.count { it.name == "Altro" })
        assertEquals(4003L, out.single { it.name == "Altro" }.cents)
    }

    @Test fun missingIncomeIsADashNeverZero() {
        val c = layoutFlow(scenarios.getValue("income not in the data"), 360f, dp, 1f, measure(1f))
        assertTrue(c.labels.any { it.text.startsWith("Entrate —") })
        assertFalse(c.labels.any { it.text == "0 €" || it.text.contains(" 0 €") })
    }

    @Test fun valuesNeverShareTheNameColumn() {
        val c = layoutFlow(scenarios.getValue("long names, tiny values, two pillars, a separate source"), 360f, dp, 1f, measure(1f))
        val valueLeft = c.labels.filter { it.alignEnd }.minOf { it.box.left }
        val names = c.labels.filter { !it.alignEnd && it.box.left >= valueLeft - 200f && it.text in c.rows.map { r -> r.name } }
        names.forEach { assertTrue("'${it.text}' ends before the values", it.box.right <= valueLeft - 7.99f) }
    }
}
