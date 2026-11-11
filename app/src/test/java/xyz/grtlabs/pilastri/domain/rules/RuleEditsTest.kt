package xyz.grtlabs.pilastri.domain.rules

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.ChangeLayer
import xyz.grtlabs.pilastri.data.Origin
import xyz.grtlabs.pilastri.data.RulesCodec
import xyz.grtlabs.pilastri.data.UpdateWinner
import xyz.grtlabs.pilastri.data.VersionKind
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Synthetic values only: the proposed figures and sources below are invented for the tests, not law. */
class RuleEditsTest {
    private val shipped = ShippedPack(Shipped.packJson, Shipped.snapshotJson)
    private val today = LocalDate.of(2026, 11, 1)
    private val now = Instant.parse("2026-11-01T09:00:00Z")
    private val gu = "https://www.gazzettaufficiale.it/eli/id/sintetico/1"
    private val text = "Testo sintetico di prova. Il limite annuo è di 5.400 euro. L'aliquota del secondo scaglione è del 32 per cento."
    private val inputs = PersonInputs(2026, null)

    private fun ev(from: String, url: String = gu, quote: String = "Il limite annuo è di 5.400 euro.", checked: String = "2026-11-01") =
        Evidence(url, quote, text, LocalDate.parse(from), LocalDate.parse(checked))

    private fun ceiling(archive: Archive, from: String = "2026-01-01", url: String = gu, checked: String = "2026-11-01", seed: String = "a") =
        RuleEdits.lawValue(shipped, archive, "pc.deduction.ceiling", "5400", ev(from, url, checked = checked), "Tetto deduzione", today, seed, inputs)

    private fun bracket(archive: Archive, seed: String = "b") =
        RuleEdits.lawValue(shipped, archive, "irpef.bracket.2.rate", "0.32", ev("2027-01-01", quote = "secondo scaglione è del 32 per cento"), null, today, seed, inputs)

    private fun value(archive: Archive, id: String, date: String, s: ShippedPack = shipped): Any? =
        (EffectiveRules.build(s, archive.rulesData).pack.at(id, LocalDate.parse(date))?.value as? RowValue.Scalar)?.value?.let { (it as RValue.Num).amount }

    @Test fun rowsAreConfirmedOneByOneWithTheDiffOnThePersonsNumbers() {
        val base = Archive()
        val a = ceiling(base)
        val b = bracket(base)
        assertTrue(a.kind.tier3 && b.kind.tier3)
        assertEquals("5.300,00 €", a.view.before)
        assertEquals("5.400,00 €", a.view.after)
        assertEquals("1 gen 2026", a.view.validFrom)
        assertTrue(a.view.verified && a.view.confirmable)
        val tetto = a.view.outputs.first { it.label == "tetto annuo" }
        assertEquals("Deduzione", tetto.card)
        assertEquals("5.300,00 €" to "5.400,00 €", tetto.before to tetto.after)
        assertTrue(a.view.cards.contains("Deduzione"))
        // A new version from 2027 closes the one in force the day before.
        assertEquals(2, b.changes.size)
        assertTrue(b.view.outputs.any { it.card == "Imposta" })

        val afterA = RulesHistory.confirm(base, a, shipped, now, "pv:a")
        assertEquals(BigDecimal("540000"), value(afterA, "pc.deduction.ceiling", "2026-06-01"))
        // The other row is untouched until it is confirmed on its own.
        assertEquals(BigDecimal("0.33"), value(afterA, "irpef.bracket.2.rate", "2027-06-01"))
        val afterB = RulesHistory.confirm(afterA, b, shipped, now, "pv:b")
        assertEquals(BigDecimal("0.32"), value(afterB, "irpef.bracket.2.rate", "2027-06-01"))
        assertEquals(BigDecimal("0.33"), value(afterB, "irpef.bracket.2.rate", "2026-06-01"))
        // Confirming twice writes nothing more.
        assertTrue(RulesHistory.confirm(afterB, b, shipped, now, "pv:b") === afterB)
        val book = RulesCodec.decode(afterB.rulesData)
        assertEquals(listOf("pv:a", "pv:b"), book.versions.map { it.id })
        assertEquals("pv:a", book.versions[1].parentId)
        assertEquals(1, book.snapshots.size)
        // A card made on rows that changed since is refused.
        try { RulesHistory.confirm(afterA, ceiling(base, seed = "a2"), shipped, now, "pv:a2"); fail() } catch (e: RuleConflictException) { assertTrue(e.message!!.contains("pc.deduction.ceiling")) }
    }

    @Test fun aNonPrimarySourceMayProposeButNeverConfirm() {
        val p = ceiling(Archive(), url = "https://www.example.org/articolo")
        assertFalse(p.primary)
        assertFalse(p.view.confirmable)
        assertFalse(p.view.verified)
        try { RulesHistory.confirm(Archive(), p, shipped, now, "pv:x"); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("non confermabile")) }
        // A payroll portal under the MEF host is not a primary source; the Gazzetta and the Agenzia are.
        assertFalse(PrimarySources.isPrimary("https://noipa.mef.gov.it/documento"))
        assertFalse(PrimarySources.isPrimary("http://www.gazzettaufficiale.it/x"))
        assertTrue(PrimarySources.isPrimary("https://www.agenziaentrate.gov.it/portale/x"))
        assertTrue(PrimarySources.isPrimary("https://www.mef.gov.it/x"))
    }

    @Test fun aQuoteNotFoundVerbatimInItsSnapshotIsRefused() {
        try {
            RuleEdits.lawValue(shipped, Archive(), "pc.deduction.ceiling", "5400", ev("2026-01-01", quote = "Il limite annuo è di 5400 euro."), null, today, "q", inputs)
            fail()
        } catch (e: RuleEditException) { assertTrue(e.message!!.contains("letterale")) }
        try {
            RuleEdits.lawValue(shipped, Archive(), "pc.deduction.ceiling", "5400", ev("2026-01-01", quote = "x".repeat(201)), null, today, "q", inputs)
            fail()
        } catch (e: RuleEditException) { assertTrue(e.message!!.contains("200")) }
    }

    @Test fun anInvalidResultingPackIsRefused() {
        // A RATIO row cannot take a step list, and a step of an unknown kind is an app update, not an edit.
        try {
            RuleEdits.lawSteps(shipped, Archive(), "irpef.gross", org.json.JSONArray("""[{"out":"tax","kind":"POWER","input":"in:taxable"}]"""),
                ev("2026-01-01"), null, today, "s", inputs)
            fail()
        } catch (e: RuleEditException) { assertTrue(e.message, e.message!!.contains("non è valida")) }
        try {
            RuleEdits.lawSteps(shipped, Archive(), "irpef.gross", org.json.JSONArray("""[{"out":"tax","kind":"SUM","rounding":"NONE","terms":["val:later"]}]"""),
                ev("2026-01-01"), null, today, "s", inputs)
            fail()
        } catch (e: RuleEditException) { assertTrue(e.message, e.message!!.contains("non è valida")) }
    }

    @Test fun undoRePointsToTheParentAndKeepsEveryVersion() {
        val a = RulesHistory.confirm(Archive(), ceiling(Archive()), shipped, now, "pv:a")
        val b = RulesHistory.confirm(a, bracket(a), shipped, now, "pv:b")
        try { RulesHistory.undo(b, "pv:a", shipped, now); fail() } catch (_: RuleConflictException) {}
        val undoneB = RulesHistory.undo(b, "pv:b", shipped, now)
        val book = RulesCodec.decode(undoneB.rulesData)
        assertEquals("pv:a", EffectiveRules.headId(book))
        assertEquals(3, book.versions.size)
        assertEquals(BigDecimal("0.33"), value(undoneB, "irpef.bracket.2.rate", "2027-06-01"))
        assertEquals(BigDecimal("540000"), value(undoneB, "pc.deduction.ceiling", "2026-06-01"))
        assertTrue(RulesHistory.undo(undoneB, "pv:b", shipped, now) === undoneB)
        val undoneA = RulesHistory.undo(undoneB, "pv:a", shipped, now)
        assertNull(EffectiveRules.headId(RulesCodec.decode(undoneA.rulesData)))
        assertEquals(BigDecimal("530000"), value(undoneA, "pc.deduction.ceiling", "2026-06-01"))
        val lines = RulesHistory.lines(undoneA)
        assertEquals(listOf(HistoryKind.UNDONE, HistoryKind.UNDONE, HistoryKind.CONFIRMED, HistoryKind.CONFIRMED).toSet(), lines.map { it.kind }.toSet())
        val undoLine = lines.first { it.kind == HistoryKind.UNDONE && it.rowId == "pc.deduction.ceiling" }
        assertEquals("5.400,00 €" to "5.300,00 €", undoLine.before to undoLine.after)
        assertEquals(gu, undoLine.source)
    }

    private fun updated(value: String, checkedOn: String): ShippedPack {
        val root = JSONObject(Shipped.packJson)
        val rows = root.getJSONArray("rows")
        for (i in 0 until rows.length()) rows.getJSONObject(i).let { r ->
            if (r.getString("id") == "pc.deduction.ceiling" && r.optString("validFrom") == "2026-01-01") r.put("value", value).put("checkedOn", checkedOn)
        }
        return ShippedPack(root.toString(), Shipped.snapshotJson)
    }

    @Test fun anAppUpdateResolvesByTheLaterPrimaryCheckedOnAndLogsIt() {
        val mine = RulesHistory.confirm(Archive(), ceiling(Archive(), checked = "2026-11-01"), shipped, now, "pv:a")
        val withB = RulesHistory.confirm(mine, bracket(mine), shipped, now, "pv:b")
        val later = Instant.parse("2026-12-05T09:00:00Z")
        // The update was checked later: it wins.
        val newer = updated("5350", "2026-12-01")
        assertEquals(BigDecimal("535000"), value(withB, "pc.deduction.ceiling", "2026-06-01", newer))
        assertEquals(1, EffectiveRules.build(newer, withB.rulesData).pendingResolutions.size)
        val resolved = RulesHistory.resolveUpdate(withB, newer, later)
        val line = RulesHistory.lines(resolved).first()
        assertEquals(HistoryKind.UPDATE_RESOLVED, line.kind)
        assertEquals(UpdateWinner.SHIPPED, line.winner)
        assertEquals("pc.deduction.ceiling", line.rowId)
        assertEquals("5.400,00 €" to "5.350,00 €", line.before to line.after)
        assertTrue(EffectiveRules.build(newer, resolved.rulesData).pendingResolutions.isEmpty())
        assertTrue(RulesHistory.resolveUpdate(resolved, newer, later) === resolved)
        // The untouched row keeps the person's version.
        assertEquals(BigDecimal("0.32"), value(resolved, "irpef.bracket.2.rate", "2027-06-01", newer))
        // A tie goes to the shipped pack.
        val tie = updated("5350", "2026-11-01")
        assertEquals(BigDecimal("535000"), value(withB, "pc.deduction.ceiling", "2026-06-01", tie))
        assertEquals(UpdateWinner.SHIPPED, EffectiveRules.build(tie, withB.rulesData).pendingResolutions.single().winner)
        // The person's primary source was checked later: it stays, and that is logged too.
        val older = updated("5350", "2026-10-31")
        assertEquals(BigDecimal("540000"), value(withB, "pc.deduction.ceiling", "2026-06-01", older))
        val kept = RulesHistory.resolveUpdate(withB, older, later)
        val keptLine = RulesHistory.lines(kept).first()
        assertEquals(UpdateWinner.KEPT, keptLine.winner)
        assertEquals("5.350,00 €" to "5.400,00 €", keptLine.before to keptLine.after)
        // Resolutions are history only: they are never rows to confirm.
        assertTrue(RulesCodec.decode(kept.rulesData).versions.last().kind.known == VersionKind.UPDATE_RESOLVED)
        // A confirmation after the update records the resolution first.
        val next = RulesHistory.confirm(withB, RuleEdits.lawValue(newer, withB, "irpef.bracket.1.rate", "0.22", ev("2027-01-01", quote = "Testo sintetico di prova."), null, today, "c", inputs), newer, later, "pv:c")
        assertEquals(listOf(VersionKind.CONFIRMED, VersionKind.CONFIRMED, VersionKind.UPDATE_RESOLVED, VersionKind.CONFIRMED),
            RulesCodec.decode(next.rulesData).versions.map { it.kind.known })
    }

    @Test fun personalValuesFillOnlyDeclaredSlotsAndNeverALawRow() {
        val s = synthetic()
        try { RuleEdits.personal(s, Archive(), "t.rule", null, "0.05", Origin.DICHIARATO, null, null, null, null, today, now, "p", inputs); fail() }
        catch (e: RuleEditException) { assertTrue(e.message!!.contains("riga di legge")) }
        try { RuleEdits.personal(s, Archive(), "fund_pct", null, "0.05", Origin.DICHIARATO, null, null, null, null, today, now, "p", inputs); fail() }
        catch (e: RuleEditException) { assertTrue(e.message!!.contains("non è un parametro personale")) }
        try { RuleEdits.personal(s, Archive(), "quota", null, "0.05", Origin.DOCUMENTO, "doc-1", null, null, null, today, now, "p", inputs); fail() }
        catch (e: RuleEditException) { assertTrue(e.message!!.contains("campo")) }
        val hash = "a".repeat(64)
        val p = RuleEdits.personal(s, Archive(), "quota", null, "0.05", Origin.DOCUMENTO, "doc-1", "Quota fondo", hash, LocalDate.of(2026, 9, 30), today, now, "p", inputs)
        assertEquals(EditKind.PERSONAL, p.kind)
        assertEquals(ChangeLayer.PERSONAL, p.view.layer)
        val confirmed = RulesHistory.confirm(Archive(), p, s, now, "pv:p")
        val eff = EffectiveRules.build(s, confirmed.rulesData)
        assertEquals(BigDecimal("0.05"), (eff.personal["quota"]?.value as RValue.Num).amount)
        assertEquals(RuleStatus.EXACT, run(eff, Mode.ACTUALS).status)
        // A newer document with the same value only refreshes the date; the same document again proposes nothing.
        val refresh = RuleEdits.personal(s, confirmed, "quota", null, "0.05", Origin.DOCUMENTO, "doc-2", "Quota fondo", hash, LocalDate.of(2026, 10, 31), today, now, "r", inputs)
        assertEquals(EditKind.REFRESH_PERSONAL, refresh.kind)
        assertFalse(refresh.kind.tier3)
        try { RuleEdits.personal(s, confirmed, "quota", null, "0.05", Origin.DOCUMENTO, "doc-1", "Quota fondo", hash, LocalDate.of(2026, 9, 30), today, now, "r", inputs); fail() }
        catch (_: RuleEditException) {}
        val refreshed = RulesHistory.confirm(confirmed, refresh, s, now, "pv:r")
        assertEquals(VersionKind.REFRESHED, RulesCodec.decode(refreshed.rulesData).versions.last().kind.known)
        assertEquals(HistoryKind.REFRESHED, RulesHistory.lines(refreshed).first().kind)
        // The interpreter itself refuses a personal key equal to a law row.
        try { Interpreter(eff.pack).evaluate("t.rule", EvalContext(today, personal = mapOf("t.rule" to eff.personal.getValue("quota")))); fail() }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun scenarioValuesAreReadOnlyByFuturo() {
        val s = synthetic()
        val edit = RuleEdits.scenario(s, Archive(), "quota", "0.10", now, "sc")
        assertFalse(edit.kind.tier3)
        val withScenario = RulesHistory.addScenario(Archive(), edit.param!!)
        val eff = EffectiveRules.build(s, withScenario.rulesData)
        assertTrue(eff.personal.isEmpty())
        assertTrue(eff.context(today).scenario.isEmpty())
        assertEquals(RuleStatus.ND, run(eff, Mode.ACTUALS).status)
        val futuro = run(eff, Mode.FUTURO)
        assertEquals(RuleStatus.STIMA, futuro.status)
        assertEquals(BigDecimal("10000"), (futuro.value as RValue.Num).amount.stripTrailingZeros().let { BigDecimal(it.toPlainString()) })
        // With a confirmed personal value, actuals read it and Futuro reads the scenario over it.
        val p = RuleEdits.personal(s, withScenario, "quota", null, "0.05", Origin.DICHIARATO, null, null, null, null, today, now, "p", inputs)
        val both = EffectiveRules.build(s, RulesHistory.confirm(withScenario, p, s, now, "pv:p").rulesData)
        assertEquals(BigDecimal("5000"), (run(both, Mode.ACTUALS).value as RValue.Num).amount.setScale(0))
        assertEquals(BigDecimal("10000"), (run(both, Mode.FUTURO).value as RValue.Num).amount.setScale(0))
        // Undo of a scenario (Tier 1) removes that record only.
        val removed = RulesHistory.removeScenario(withScenario, edit.param!!.id)
        assertTrue(EffectiveRules.build(s, removed.rulesData).scenario.isEmpty())
        try { RulesHistory.removeScenario(removed, edit.param!!.id); fail() } catch (_: RuleConflictException) {}
    }

    private fun run(eff: EffectivePack, mode: Mode): Res =
        Interpreter(eff.pack).evaluate("t.rule", eff.context(today, mapOf("base" to RuleInput.cents(100_000)), mode)).output

    /** A one-rule pack whose rule reads the declared slot `quota`. */
    private fun synthetic(): ShippedPack {
        val url = "https://www.gazzettaufficiale.it/eli/id/sintetico/2"
        val snap = "Testo sintetico: la quota del fondo si applica alla base."
        val hash = SnapshotStore.sha256(snap)
        val pack = """{"format":1,"packId":"sintetico","checkedOn":"2026-10-31","sources":{"T1":{"url":"$url","read":"PRIMARY"}},"rows":[
            {"id":"t.rule","unit":"EUR","shape":"SCALAR","value":null,"validFrom":null,"validTo":null,"appliesTo":{"kinds":[],"gestioni":[],"otherCover":null},
             "sources":["T1"],"sourceUrl":"$url","quote":"la quota del fondo","snapshotHash":"$hash","checkedOn":"2026-10-31","status":"VERIFIED",
             "scope":"PERSON_YEAR","inputs":{"base":"EUR"},"personalSlots":{"quota":"RATIO"},"requires":[],"caveats":[],"output":"x",
             "steps":[{"out":"x","kind":"RATE","rounding":"HALF_UP_CENT","input":"in:base","rate":"slot:quota"}]}],"retired":[]}"""
        val snaps = """{"snapshots":[{"hash":"$hash","url":"$url","recordedOn":"2026-10-31","kind":"TEST","summaryOnly":false,"text":"$snap"}]}"""
        return ShippedPack(pack, snaps).also { assertNotNull(it.pack) }
    }
}
