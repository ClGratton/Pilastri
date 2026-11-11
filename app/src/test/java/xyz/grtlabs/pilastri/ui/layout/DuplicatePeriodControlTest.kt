package xyz.grtlabs.pilastri.ui.layout

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Positive control of CR-70 / CR-71 (mirrors `tools/fixtures/dup/{bad,good}`): a synthetic tree with the period in subtitle + chip + axis label,
 * a swipe page without `period-title`, and a header text larger than the period-title MUST be flagged by the engine the layout gate uses
 * (`Rules.check`, families "duplicate" and "period"); the good trees MUST NOT. Pure engine, no Compose.
 */
class DuplicatePeriodControlTest {
    private fun n(id: String, text: String, l: Float, t: Float, r: Float, b: Float, fs: Float = 0f) = N(id, text, l, t, r, b, fs = fs)

    private fun rows(vararg shots: Shot, family: String) = Rules.check(shots.toList()).filter { it.rule.startsWith(family) }

    private fun page(name: String, vararg extra: N, top: Boolean = false) = Shot(name, "phone", top,
        listOf(n("page-title", "Spese", 16f, 40f, 200f, 70f, fs = 26f), n("period-viewport", "", 0f, 120f, 411f, 600f)) + extra, 411f, 891f)

    // ---- CR-70 (whole screen; mirrors tools/fixtures/dup/{bad,bad-filtered,good,addon}) ----
    /** The duplicate check is scoped by config to single-screen layouts (tools/duplicate-scope.json, phone-*): control screens are named phone-*. */
    private fun scoped(name: String) = if (name.startsWith("phone-") || name.startsWith("fold-")) name else "phone-$name"
    private fun shot(name: String, vararg nodes: N) = Shot(scoped(name), "phone", true, nodes.toList(), 400f, 800f)
    private fun dup(s: Shot, filters: List<Rules.DupFilter> = emptyList(), hits: MutableMap<Rules.DupFilter, Rules.DupHit> = HashMap()) = Rules.duplicates(s, filters, hits).map { it.el + " " + it.note }

    /** tools/fixtures/dup/bad/phone-top-spese-facts.xml */
    private fun facts(name: String) = shot(name,
        n("page-content", "", 0f, 100f, 400f, 800f), n("page-title", "Spese", 16f, 110f, 200f, 134f), n("accent-card", "", 16f, 150f, 384f, 250f),
        n("stat-label", "Spesa", 150f, 160f, 200f, 172f), n("primary-figure", "429 €", 150f, 172f, 230f, 200f), n("", "budget n/d", 150f, 204f, 230f, 216f), n("stat-label", "Entrate", 30f, 160f, 80f, 172f),
        n("card", "", 16f, 260f, 384f, 400f), n("card-title", "Ritmo di spesa", 30f, 270f, 150f, 290f), n("card-trailing", "budget n/d", 300f, 270f, 370f, 290f), n("", "429 €", 320f, 340f, 370f, 356f),
        n("card", "", 16f, 410f, 384f, 520f), n("card-title", "Flusso", 30f, 420f, 150f, 440f), n("", "Spese 441 €", 30f, 450f, 150f, 466f), n("", "dal saldo 441 €", 200f, 450f, 370f, 466f),
        n("card", "", 16f, 530f, 384f, 700f), n("card-title", "Versamenti", 30f, 540f, 150f, 560f), n("", "Versato tuo nel mese 150 €", 30f, 570f, 250f, 586f), n("", "", 30f, 600f, 370f, 650f),
        n("", "Lungo termine", 30f, 606f, 150f, 620f), n("", "150 €", 320f, 620f, 370f, 636f))

    @Test fun badFixtureIsFlaggedByTheBaseRule() {
        val r = dup(shot("phone-top-spese", n("page-title", "Spese", 10f, 20f, 100f, 60f), n("period-viewport", "", 0f, 300f, 400f, 700f), n("page-subtitle", "9 set – 8 ott", 10f, 60f, 200f, 80f),
            n("chip", "9 set – 8 ott", 250f, 40f, 390f, 70f), n("", "9 set", 10f, 310f, 60f, 330f), n("", "8 ott", 300f, 310f, 350f, 330f)))
        assertTrue("identical text in subtitle and chip (rows: $r)", r.any { it.contains("\"9 set – 8 ott\" + \"9 set – 8 ott\"") })
        assertTrue("the token '9 set' in two nodes (rows: $r)", r.any { it.contains("\"9 set\"") })
        val f = dup(facts("phone-top-spese-facts"))
        assertTrue("same amount in hero and chart end label (rows: $f)", f.any { Regex("429 €.*429 €").containsMatchIn(it) })
        assertTrue("identical label+value in two nodes (rows: $f)", f.any { it.contains("\"budget n/d\" + \"budget n/d\"") })
        assertTrue("an amount repeated under different labels (rows: $f)", f.any { it.contains("\"Spese 441 €\"") && it.contains("\"dal saldo 441 €\"") })
        assertTrue("a movement amount repeating a monthly stat (rows: $f)", f.any { it.contains("\"Versato tuo nel mese 150 €\"") && it.contains("\"150 €\"") })
    }

    @Test fun filterSilencesOnlyItsOwnElementAndTheCountIsKept() {
        val flt = Rules.DupFilter("phone-top-spese-facts", "", "^budget n/d$", "fixture")
        val hits = HashMap<Rules.DupFilter, Rules.DupHit>()
        val r = dup(facts("phone-top-spese-facts"), listOf(flt), hits)
        assertTrue("the filtered budget n/d pair is silenced (rows: $r)", r.none { it.contains("\"budget n/d\" + \"budget n/d\"") })
        assertTrue("every other repetition stays flagged (rows: $r)", r.any { it.contains("\"Spese 441 €\"") } && r.any { it.contains("\"429 €\"") })
        assertTrue("the applied count is 2 and names the screen (hits: ${hits.values.map { it.n }})", hits[flt]?.n == 2 && hits[flt]!!.screens.contains("phone-top-spese-facts"))
        val other = dup(facts("phone-top-futuro"), listOf(flt), hits)
        assertTrue("a filter for another screen glob does not apply (rows: $other)", other.any { it.contains("\"budget n/d\" + \"budget n/d\"") })
    }

    @Test fun goodFixtureIsCleanSymbolsAreNotCompared() {
        val flt = Rules.DupFilter("phone-top-spese-facts", "", "budget n/d$", "per-row budget state of sibling category rows (fixture)")
        val g = shot("phone-top-spese-facts", n("page-content", "", 0f, 100f, 400f, 800f), n("page-title", "Spese", 16f, 110f, 200f, 134f), n("", "Entrate 1.700 €", 30f, 150f, 200f, 166f), n("", "Spesa 429 €", 30f, 170f, 200f, 186f),
            n("", "‹", 30f, 118f, 50f, 130f), n("", "›", 300f, 118f, 320f, 130f), n("", "‹", 30f, 300f, 50f, 312f), n("", "Casa budget n/d", 30f, 330f, 200f, 346f), n("", "Svago budget n/d", 30f, 350f, 200f, 366f))
        val hits = HashMap<Rules.DupFilter, Rules.DupHit>()
        val rows = dup(g, listOf(flt), hits)
        assertTrue("unique values, chevron glyphs and a filtered per-row budget flag nothing (rows: $rows)", rows.isEmpty())
        assertTrue("the filter silenced both rows (count ${hits[flt]?.n})", hits[flt]?.n == 2)
        assertTrue("without the filter the same screen is still clean (rows: ${dup(g)})", dup(g).isEmpty())
    }

    @Test fun addOnDateRangeAndItsEndpointIsOneFact() {
        val r = dup(shot("phone-top-spese", n("page-title", "Spese", 10f, 20f, 100f, 60f), n("period-viewport", "", 0f, 300f, 400f, 700f), n("page-subtitle", "1 – 9 nov", 10f, 60f, 200f, 80f),
            n("chip", "1 nov", 250f, 40f, 390f, 70f), n("", "Ottobre", 10f, 310f, 60f, 330f), n("", "Altro", 300f, 310f, 350f, 330f)))
        assertTrue("a range and its own endpoint (rows: $r)", r.any { it.contains("\"1 – 9 nov\"") && it.contains("\"1 nov\"") })
    }

    @Test fun anEchoOfOneElementIsNotAnotherElement() {
        // collectNodes gives a TAGGED container the text of its first descendant: the container and its Text are one on-screen element
        val r = dup(shot("x", N("period-title", "Settembre", 16f, 80f, 300f, 120f, echo = true), n("", "Settembre", 16f, 80f, 300f, 120f)))
        assertTrue("a tagged container and its own Text are one element (rows: $r)", r.isEmpty())
        val two = dup(shot("x", n("period-title", "Settembre", 16f, 80f, 300f, 120f), n("", "Settembre", 16f, 500f, 300f, 520f)))
        assertTrue("two separate elements with the same text are a duplicate (rows: $two)", two.isNotEmpty())
    }

    // ---- CR-71 ----
    @Test fun swipePageWithoutPeriodTitleIsFlagged() {
        val r = rows(page("control-notitle"), family = "period")
        assertTrue("a swipe page without a period-title node must be flagged (rows: $r)", r.any { it.rule.contains("exposes its current period") })
    }

    @Test fun pageTitleAbovePeriodTitleIsTheHierarchyNotADeviation() {
        val r = rows(page("control-small", n("period-title", "Settembre", 16f, 90f, 300f, 110f, 14f)), family = "period")
        assertTrue("wave 11a (CR-83): the page-title is the level above the period-title, not flagged as tallest text (rows: $r)", r.none { it.rule.contains("tallest text") })
    }

    @Test fun periodTitleHeightDiffersBetweenPagesIsFlagged() {
        val a = page("control-h1", n("period-title", "Settembre", 16f, 80f, 300f, 120f, 30f))
        val b = page("control-h2", n("period-title", "Ottobre", 16f, 80f, 300f, 100f, 30f))
        val c = page("control-h3", n("period-title", "Ottobre", 16f, 80f, 300f, 120f, 30f))
        val r = rows(a, b, c, family = "period")
        assertTrue("one page whose period-title is 20 dp high against two of 40 dp must be flagged (rows: $r)", r.any { it.rule.contains("height") && it.screen == "control-h2" })
    }

    @Test fun periodTitleSharingBoundsWithPageTitleOnMeseIsAccepted() {
        val mese = Shot("control-mese-ok", "phone", false, listOf(
            n("page-title", "Settembre", 16f, 40f, 300f, 80f, 30f), n("period-title", "Settembre", 16f, 40f, 300f, 80f, 30f), n("period-viewport", "", 0f, 120f, 411f, 600f)), 411f, 891f)
        val spese = page("control-spese-ok", n("period-title", "Settembre", 16f, 72f, 300f, 112f, 30f)).copy(nodes = listOf(n("page-title", "Spese", 16f, 40f, 200f, 70f, 26f), n("period-title", "Settembre", 16f, 72f, 300f, 112f, 30f), n("period-viewport", "", 0f, 120f, 411f, 600f)))
        val r = rows(mese, spese, family = "period")
        assertTrue("shared bounds on Mese and a smaller page-title on Spese, same period-title height, must pass (rows: $r)", r.isEmpty())
    }

    @Test fun pilastriHeroWithPeriodLabelCurIsExempt() {
        val hero = Shot("control-hero", "phone", false, listOf(n("page-title", "Pilastri", 16f, 40f, 200f, 70f, 26f), n("period-viewport", "", 0f, 120f, 411f, 600f), n("period-label-cur", "Settembre", 16f, 90f, 200f, 110f, 14f)), 411f, 891f)
        assertTrue("the hero names its month with period-label-cur and needs no period-title", rows(hero, family = "period").isEmpty())
    }

    @Test fun factsNormalisation() {
        val f = Rules.dateKeys("9 set – 8 ott")
        assertTrue("range and endpoints (rows: $f)", f.containsAll(listOf("R:9/9-8/10", "E:9/9", "E:8/10")))
        assertTrue("a sentence date is D, not an endpoint", Rules.dateKeys("lun 9 nov") == setOf("D:9/11"))
        assertTrue("a bare date is an endpoint", Rules.dateKeys("9 set", isBare = true) == setOf("E:9/9"))
        assertTrue("base tokens: amount, percent, word; bare integers skipped, trailing ,00 stripped (rows: ${Rules.factsOf("429 € 17% 12 mesi 3 1.200,00 €")})",
            Rules.factsOf("429 € 17% 12 mesi 3 1.200,00 €") == setOf("V:429€", "V:17%", "V:12mesi", "V:1.200€"))
    }

    // ---- Wave 7: the app joins figure and currency with U+00A0 (mirrors tools/fixtures/w7/nbsp) ----
    @Test fun w7NbspRepetitionIsStillFlaggedAndPlainSpaceFiltersStillMatch() {
        val s = shot("phone-mese-nbsp", n("page-title", "Novembre", 10f, 20f, 200f, 60f), n("", "150 €", 10f, 300f, 80f, 320f), n("", "150 €", 10f, 400f, 80f, 420f),
            n("", "Spese 441 €", 10f, 500f, 150f, 520f), n("", "dal saldo 441 €", 200f, 500f, 390f, 520f))
        val r = dup(s)
        assertTrue("the same amount with U+00A0 and with U+0020 is one repetition (rows: $r)", r.any { it.contains("150 €") })
        assertTrue("the value token 441 € with U+202F and U+00A0 is flagged (rows: $r)", r.any { it.contains("441 €") })
        val flt = Rules.DupFilter("phone-mese-nbsp", "", "^150 .$", "plain-space filter as in tools/duplicate-filters.json (fixture)")
        val hits = HashMap<Rules.DupFilter, Rules.DupHit>()
        val f = dup(s, listOf(flt), hits)
        assertTrue("a plain-space filter silences the U+00A0 element too (rows: $f)", f.none { it.contains("150 €") } && (hits[flt]?.n ?: 0) == 2)
    }

    // ---- Wave 7 loosening: at font scale 2 a wrapped page subtitle is compared per line; a wrapped title still flags ----
    @Test fun w7WrappedSubtitleAtLargeFontIsComparedPerLineButAWrappedTitleIsNot() {
        fun hdr(name: String, sub: Float, title: Float) = Shot(name, "phone-fs2", true,
            listOf(n("page-title", "Titolo", 16f, 40f, 200f, 40f + title), n("page-subtitle", "Dati di esempio", 16f, 40f + title, 200f, 40f + title + sub)), 411f, 891f)
        val rows = Rules.check(listOf(hdr("phone-fs2-top-a", 27f, 40.8f), hdr("phone-fs2-top-b", 27f, 40.8f), hdr("phone-fs2-top-c", 55.2f, 40.8f), hdr("phone-fs2-mese", 27f, 82.3f)))
            .filter { it.rule.startsWith("page-header") && it.rule.contains("height") }.map { it.screen + " " + it.rule }
        assertTrue("a two-line subtitle is not a height deviation at fs2 (rows: $rows)", rows.none { it.contains("page-subtitle height") })
        assertTrue("a two-line title still is (rows: $rows)", rows.any { it.startsWith("phone-fs2-mese") && it.contains("page-title height") })
    }
}
