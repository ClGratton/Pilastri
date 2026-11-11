package xyz.grtlabs.pilastri.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.ai.AiFixtures.objects
import xyz.grtlabs.pilastri.data.Account
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.ArchiveCodec
import xyz.grtlabs.pilastri.data.Category
import xyz.grtlabs.pilastri.data.CategoryKind
import xyz.grtlabs.pilastri.data.Evidence
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ExternalMovement
import xyz.grtlabs.pilastri.data.ExternalStatus
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.data.Installment
import xyz.grtlabs.pilastri.data.InstallmentPlan
import xyz.grtlabs.pilastri.data.InstallmentSource
import xyz.grtlabs.pilastri.data.InstallmentState
import xyz.grtlabs.pilastri.data.Planning
import xyz.grtlabs.pilastri.data.PlanReview
import java.time.LocalDate

/** Installment plans (archive v13) and PayPal through the assistant's tools. Synthetic data only. */
class InstallmentToolsTest {
    private fun d(s: String) = LocalDate.parse(s)

    private val paypalAccount = Account("acc-paypal", "PayPal sintetico", 1, "fixture")

    private fun paypal(id: String, date: String, cents: Long, currency: String = "EUR", text: String = "Movimento PayPal sintetico") =
        ExternalMovement("PAYPAL:$id", ImportProvider.PAYPAL, paypalAccount.id, d(date), cents, currency, text, ExternalKind.OTHER, null, null, null,
            ExternalStatus.PENDING, null, "2026-10-20T10:00:00Z")

    private val p1 = paypal("p1", "2026-10-01", -3_000, text = "Rata 2 di 3 Negozio sintetico")
    private val p2 = paypal("p2", "2026-10-02", -1_500, "USD", "Acquisto in dollari")
    private val p3 = paypal("p3", "2026-10-03", 3_000, text = "Rimborso sintetico")
    private val p4 = paypal("p4", "2026-10-04", -2_999, text = "Importo diverso")
    private val eurIn = AiFixtures.movement("ISYBANK:u1", AiFixtures.liquidity.id, "2026-10-02", 1_500, "Accredito sintetico", ExternalKind.DEPOSIT)

    private fun plan(id: String, source: InstallmentSource, review: PlanReview, parts: List<Installment>, currency: String = "EUR",
                     lender: String = "PayPal Paga in 3 rate", merchant: String? = "Negozio sintetico", count: Int? = 3, total: Long? = 9_000) =
        InstallmentPlan(id, lender, merchant, total, currency, count, parts, d("2026-09-01"), null, null, source,
            listOf(Evidence("paypal:TX-$id", d("2026-09-01"), "Ricevuta sintetica")), review, false, "2026-09-01T10:00:00Z")

    private val pPaypal = plan("paypal:ORDER-1", InstallmentSource.PAYPAL, PlanReview.PENDING, listOf(
        Installment(1, d("2026-09-01"), 3_000, InstallmentState.PAID, d("2026-09-01")),
        Installment(2, d("2026-10-01"), 3_000, InstallmentState.DUE),
        Installment(3, d("2026-11-01"), 3_000, InstallmentState.DUE),
    ))
    private val pManual = plan("manual:sofa", InstallmentSource.MANUAL, PlanReview.CONFIRMED, listOf(
        Installment(1, d("2026-11-15"), 10_000, InstallmentState.DUE),
        Installment(2, d("2026-12-15"), null, InstallmentState.DUE),
    ), lender = "Carta a rate", merchant = "Divano sintetico", count = 6, total = 60_000)
    private val pUsd = plan("klarna:K-9", InstallmentSource.GMAIL, PlanReview.CONFIRMED, listOf(
        Installment(1, d("2026-11-20"), 2_500, InstallmentState.DUE),
    ), currency = "USD", lender = "Klarna", count = 1, total = 2_500)
    private val pIgnored = plan("gmail:dup", InstallmentSource.GMAIL, PlanReview.IGNORED, listOf(
        Installment(1, d("2026-11-05"), 1_000, InstallmentState.DUE),
    ), count = 1, total = 1_000)

    private val base: Archive = AiFixtures.archive().let { a ->
        a.copy(
            accounts = a.accounts + paypalAccount,
            externalMovements = a.externalMovements + listOf(p1, p3, p4, eurIn),
            installments = listOf(pPaypal, pManual, pUsd, pIgnored),
            planning = Planning(categories = listOf(Category("cat-casa", "Casa", CategoryKind.SPEND), Category("cat-svago", "Svago", CategoryKind.SPEND))),
        )
    }

    /** The archive codec accepts only euro imported movements today; a USD PayPal row exists only in this unsaved archive. */
    private val withUsd: Archive = base.copy(externalMovements = base.externalMovements + p2)

    private val ref = InstallmentTools::planRef

    private fun failure(name: String, json: String, archive: Archive = base): String =
        (AiFixtures.run(name, json, archive) as? AiToolResult.Failure)?.message ?: error("Atteso Failure per $name $json")

    private fun applyAndUndo(change: AiChange, original: Archive = base): Archive {
        val applied = change.applyTo(original)
        ArchiveCodec.encode(applied)
        assertEquals("assistente", applied.audit.last().kind)
        val undone = change.undoFrom(applied)
        ArchiveCodec.encode(undone)
        assertEquals(original.installments, undone.installments)
        return applied
    }

    @Test fun theFixtureIsAValidArchive() { ArchiveCodec.encode(base) }

    // ---- reads ----

    @Test fun plansFromMailOrPaypalAreThirdPartyTextAndTaintTheTurn() {
        val turn = AiTurnState()
        val out = AiFixtures.read("elenca_piani_rate", archive = base, turn = turn)
        assertEquals(4, out.getInt("totale"))
        val paypalPlan = out.getJSONArray("elementi").objects().single { it.getString("rif") == ref(pPaypal.id) }
        assertEquals("PayPal Paga in 3 rate", paypalPlan.getString("testo_esterno_finanziatore"))
        assertFalse(paypalPlan.has("finanziatore"))
        assertEquals("60.00", paypalPlan.getString("residuo_noto"))
        assertEquals(3, paypalPlan.getJSONArray("rate").length())
        assertFalse(out.toString().contains("ORDER-1"))
        assertTrue(turn.tainted)
    }

    @Test fun aManualPlanIsNotThirdPartyText() {
        val turn = AiTurnState()
        val out = AiFixtures.read("elenca_piani_rate", archive = base.copy(installments = listOf(pManual)), turn = turn)
        val only = out.getJSONArray("elementi").getJSONObject(0)
        assertEquals("Carta a rate", only.getString("finanziatore"))
        assertEquals(5, only.getInt("rate_aperte_senza_importo_o_non_ancora_viste")) // one part without amount, four not yet seen
        assertFalse(turn.tainted)
    }

    @Test fun reviewAndActiveFiltersWork() {
        assertEquals(1, AiFixtures.read("elenca_piani_rate", """{"revisione":"pending"}""", base).getInt("totale"))
        assertEquals(3, AiFixtures.read("elenca_piani_rate", """{"solo_attivi":true}""", base).getInt("totale"))
        assertTrue(AiFixtures.run("elenca_piani_rate", """{"revisione":"BOH"}""", base) is AiToolResult.Failure)
    }

    @Test fun duePartsSkipIgnoredPlansAndDefaultToTheNextSixtyDays() {
        val rows = AiFixtures.read("rate_in_scadenza", archive = base).getJSONArray("elementi").objects()
        assertEquals(listOf("2026-11-01", "2026-11-15", "2026-11-20", "2026-12-15"), rows.map { it.getString("scadenza") })
        assertTrue(rows.none { it.getString("rif_piano") == ref(pIgnored.id) })
        val overdue = AiFixtures.read("rate_in_scadenza", """{"da":"2026-09-15","a":"2026-10-31"}""", base).getJSONArray("elementi").objects()
        assertEquals(listOf("2026-10-01"), overdue.map { it.getString("scadenza") })
        assertTrue(AiFixtures.run("rate_in_scadenza", """{"da":"2026-10-31","a":"2026-10-01"}""", base) is AiToolResult.Failure)
    }

    @Test fun monthlyCommitmentKeepsCurrenciesApartAndCountsUnknownAmounts() {
        val turn = AiTurnState()
        val months = AiFixtures.read("impegno_rate_mese", """{"mese":"2026-11","mesi":2}""", base, turn).getJSONArray("mesi").objects()
        val nov = months[0]
        assertEquals("2026-11", nov.getString("mese"))
        val amounts = nov.getJSONArray("importi_noti").objects().associate { it.getString("valuta") to it.getString("importo") }
        assertEquals(mapOf("EUR" to "130.00", "USD" to "25.00"), amounts)
        assertEquals(3, nov.getInt("rate"))
        assertEquals(1, nov.getInt("rate_di_piani_da_rivedere"))
        assertEquals(1, months[1].getInt("rate_senza_importo"))
        assertFalse(turn.tainted)
        assertTrue(AiFixtures.run("impegno_rate_mese", """{"mesi":13}""", base) is AiToolResult.Failure)
    }

    @Test fun categoriesAreListed() {
        val rows = AiFixtures.read("elenca_categorie", archive = base).getJSONArray("elementi").objects()
        assertEquals(setOf("cat-casa", "cat-svago"), rows.map { it.getString("id") }.toSet())
    }

    // ---- writes and tiers ----

    @Test fun confirmingAFoundPlanIsTierTwoAndUndoable() {
        val p = AiFixtures.proposal("conferma_piano_rate", """{"rif":"${ref(pPaypal.id)}"}""", base)
        assertEquals(AiChangeKind.CONFIRM_INSTALLMENT_PLAN, p.change.kind)
        assertTrue(AiPolicy.requiresTap(p.change.kind))
        val applied = applyAndUndo(p.change)
        val after = applied.installments.single { it.id == pPaypal.id }
        assertEquals(PlanReview.CONFIRMED, after.review)
        assertEquals(AiFixtures.fixedNow.toString(), after.updatedAt)
        assertTrue(p.change.sourceRows.single().contains("PayPal"))
        assertTrue(failure("conferma_piano_rate", """{"rif":"${ref(pManual.id)}"}""").contains("non è da rivedere"))
    }

    @Test fun ignoringAPendingPlanIsTierOneButNeverAConfirmedOne() {
        val p = AiFixtures.proposal("ignora_piano_rate", """{"rif":"${ref(pPaypal.id)}"}""", base)
        assertEquals(AiChangeKind.IGNORE_INSTALLMENT_PLAN, p.change.kind)
        assertFalse(AiPolicy.requiresTap(p.change.kind))
        assertEquals(PlanReview.IGNORED, applyAndUndo(p.change).installments.single { it.id == pPaypal.id }.review)
        assertTrue(failure("ignora_piano_rate", """{"rif":"${ref(pManual.id)}"}""").contains("già confermato"))
        assertTrue(failure("ignora_piano_rate", """{"rif":"r00000000"}""").contains("non trovato"))
    }

    @Test fun categoryIsTierOneAndMustExist() {
        val p = AiFixtures.proposal("imposta_categoria_piano_rate", """{"rif":"${ref(pManual.id)}","categoria_id":"cat-casa"}""", base)
        assertEquals(AiChangeKind.SET_INSTALLMENT_CATEGORY, p.change.kind)
        assertFalse(AiPolicy.requiresTap(p.change.kind))
        assertEquals("cat-casa", applyAndUndo(p.change).installments.single { it.id == pManual.id }.categoryId)
        assertTrue(failure("imposta_categoria_piano_rate", """{"rif":"${ref(pManual.id)}","categoria_id":"cat-inesistente"}""").contains("non trovata"))
        assertTrue(failure("imposta_categoria_piano_rate", """{"rif":"${ref(pManual.id)}","categoria_id":""}""").contains("già"))
    }

    @Test fun aPartIsMarkedPaidWithTheMovementThatPaidIt() {
        val p = AiFixtures.proposal("segna_rata_pagata", """{"rif":"${ref(pPaypal.id)}","n":2,"movimento_rif":"${movementRef(p1.id)}"}""", base)
        assertEquals(AiChangeKind.MARK_INSTALLMENT_PAID, p.change.kind)
        assertTrue(AiPolicy.requiresTap(p.change.kind))
        val applied = applyAndUndo(p.change)
        val plan = applied.installments.single { it.id == pPaypal.id }
        val part = plan.installments.single { it.seq == 2 }
        assertEquals(InstallmentState.PAID, part.state)
        assertEquals(d("2026-10-01"), part.paidOn)
        assertEquals(p1.id, part.movementId)
        assertTrue(plan.evidence.any { it.ref == "import:${movementRef(p1.id)}" })
        assertEquals(3_000L, plan.remainingCents)
    }

    @Test fun aUserDeclarationIsEvidenceToo() {
        val p = AiFixtures.proposal("segna_rata_pagata", """{"rif":"${ref(pPaypal.id)}","n":3,"pagata_il":"2026-10-20","dichiarato_dall_utente":true}""", base)
        val plan = applyAndUndo(p.change).installments.single { it.id == pPaypal.id }
        assertTrue(plan.evidence.last().ref.startsWith("utente:"))
        assertTrue(plan.evidence.last().summary.contains("Dichiarato dall'utente"))
    }

    @Test fun paymentsWithoutMatchingEvidenceAreRefused() {
        val rif = ref(pPaypal.id)
        assertTrue(failure("segna_rata_pagata", """{"rif":"$rif","n":2}""").contains("Serve una prova"))
        assertTrue(failure("segna_rata_pagata", """{"rif":"$rif","n":2,"movimento_rif":"${movementRef(p4.id)}"}""").contains("non coincide"))
        assertTrue(failure("segna_rata_pagata", """{"rif":"$rif","n":2,"movimento_rif":"${movementRef(p2.id)}"}""", withUsd).contains("USD"))
        assertTrue(failure("segna_rata_pagata", """{"rif":"$rif","n":2,"movimento_rif":"${movementRef(p3.id)}"}""").contains("entrata"))
        assertTrue(failure("segna_rata_pagata", """{"rif":"$rif","n":1,"dichiarato_dall_utente":true,"pagata_il":"2026-09-01"}""").contains("già pagata"))
        assertTrue(failure("segna_rata_pagata", """{"rif":"$rif","n":4,"dichiarato_dall_utente":true,"pagata_il":"2026-10-01"}""").contains("non esiste"))
        assertTrue(failure("segna_rata_pagata", """{"rif":"$rif","n":3,"dichiarato_dall_utente":true,"pagata_il":"2026-11-30"}""").contains("futura"))
        assertTrue(failure("segna_rata_pagata", """{"rif":"${ref(pIgnored.id)}","n":1,"dichiarato_dall_utente":true,"pagata_il":"2026-10-01"}""").contains("ignorato"))
        val linked = base.copy(installments = base.installments.map { plan ->
            if (plan.id != pPaypal.id) plan else plan.copy(installments = plan.installments.map { if (it.seq == 2) it.copy(state = InstallmentState.PAID, paidOn = d("2026-10-01"), movementId = p1.id) else it })
        })
        assertTrue(failure("segna_rata_pagata", """{"rif":"$rif","n":3,"movimento_rif":"${movementRef(p1.id)}"}""", linked).contains("già collegato"))
    }

    @Test fun undoIsRefusedWhenThePlanChangedAfterwards() {
        val p = AiFixtures.proposal("imposta_categoria_piano_rate", """{"rif":"${ref(pManual.id)}","categoria_id":"cat-svago"}""", base)
        val applied = p.change.applyTo(base)
        val edited = applied.copy(installments = applied.installments.map { if (it.id == pManual.id) it.copy(categoryId = "cat-casa") else it })
        val e = runCatching { p.change.undoFrom(edited) }.exceptionOrNull()
        assertTrue(e is AiAlreadyModifiedException)
    }

    @Test fun readingPlansForcesTheTurnsWritesToWaitEvenForTierOne() {
        val host = AiFixtures.Host(base)
        val turn = AiTurnState()
        val router = AiWriteRouter(host.archive, host.commit, "Anthropic", turn, AiFixtures.clock, true, AiWriteQueue(), listOf(0L))
        AiFixtures.blocking {
            router.read("elenca_piani_rate", JSONObject(), "r1")
            val routed = router.write("ignora_piano_rate", JSONObject().put("rif", ref(pPaypal.id)), "w1", batch = false)
            assertFalse(routed.isError)
        }
        assertTrue(router.applied.isEmpty())
        assertTrue(router.pending.single().reviewNote!!.contains("testi di terzi"))
        assertEquals(base, host.archive)
    }

    // ---- PayPal in the existing tools ----

    @Test fun importsCanBeFilteredByProviderAndKeepTheirCurrency() {
        val rows = AiFixtures.read("elenca_importati", """{"fornitore":"PAYPAL","limite":50}""", withUsd).getJSONArray("elementi").objects()
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.getString("provider") == "PayPal" && it.getString("fornitore") == "PAYPAL" })
        assertEquals("USD", rows.single { it.getString("rif") == movementRef(p2.id) }.getString("valuta"))
        assertTrue(AiFixtures.run("elenca_importati", """{"fornitore":"SATISPAY"}""", base) is AiToolResult.Failure)
    }

    @Test fun theSummaryCountsPendingImportsPerProviderAndPendingPlans() {
        val counts = AiFixtures.read("riepilogo_pilastri", archive = base).getJSONObject("conteggi")
        assertEquals(3, counts.getJSONObject("importati_da_rivedere_per_fornitore").getInt("PAYPAL"))
        assertEquals(1, counts.getInt("piani_rate_da_rivedere"))
    }

    @Test fun nonEuroPaypalMovementsAreNeverBookedIntoTheEuroLedger() {
        assertTrue(failure("classifica_importato", """{"rif":"${movementRef(p2.id)}","decisione":"WITHDRAWAL"}""", withUsd).contains("non converte"))
        assertTrue(failure("classifica_importato", """{"rif":"${movementRef(eurIn.id)}","decisione":"TRANSFER","controparte":"${movementRef(p2.id)}"}""", withUsd).contains("valute diverse"))
        val ok = AiFixtures.proposal("classifica_importato", """{"rif":"${movementRef(p3.id)}","decisione":"INCOME"}""", base)
        assertEquals(AiChangeKind.CLASSIFY_IMPORT, ok.change.kind)
        ArchiveCodec.encode(ok.change.applyTo(base))
    }
}
