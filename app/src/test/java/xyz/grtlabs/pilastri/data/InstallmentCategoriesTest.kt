package xyz.grtlabs.pilastri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Synthetic fixtures shared by the category tests (data and ai). */
internal object CategoryFixtures {
    val paypal = Account("acc-pp", "Portafoglio sintetico", 1, "fixture")
    const val AT = "2026-03-10T10:00:00Z"

    fun mv(
        id: String, description: String, cents: Long, date: String = "2026-03-10", categoryId: String? = null, source: CategorySource? = null,
        provider: ImportProvider = ImportProvider.PAYPAL,
    ) = ExternalMovement(
        id, provider, paypal.id, LocalDate.parse(date), cents, "EUR", description, ExternalKind.CARD_PAYMENT, null, null, null,
        ExternalStatus.PENDING, null, AT, null, categoryId, source,
    )

    fun part(seq: Int, movementId: String?, date: String = "2026-03-10", cents: Long = 1_000) =
        Installment(seq, LocalDate.parse(date), cents, if (movementId != null) InstallmentState.PAID else InstallmentState.DUE, if (movementId != null) LocalDate.parse(date) else null, movementId)

    fun plan(id: String, merchant: String?, parts: List<Installment>, categoryId: String? = null, evidence: List<Evidence> = emptyList()) = InstallmentPlan(
        id, "Paga in 3 rate", merchant, 3_000, "EUR", 3, parts, LocalDate.parse("2026-03-10"), categoryId = categoryId, source = InstallmentSource.PAYPAL,
        evidence = evidence, review = PlanReview.CONFIRMED, extractedByAgent = false, updatedAt = AT,
    )

    fun spend(id: String, name: String) = Category(id, name, CategoryKind.SPEND)
}

class InstallmentCategoriesTest {
    private val f = CategoryFixtures
    private val month = YearMonth.of(2026, 3)
    private val shop = f.spend("c:acquisti", "Acquisti")
    private val home = f.spend("c:casa", "Casa")

    private fun archive(movements: List<ExternalMovement>, plans: List<InstallmentPlan>, planning: Planning = Planning(categories = listOf(shop, home))) =
        Archive(accounts = listOf(f.paypal), externalMovements = movements, installments = plans, planning = planning).also { ArchiveCodec.encode(it) }

    @Test fun aPlanTypePayeeIsRecognizedOnlyOnOutflows() {
        assertTrue(f.mv("PAYPAL:a", "Paga in 3 rate", -1_000).hasPlanTypePayee())
        assertTrue(f.mv("PAYPAL:b", "PayPal Paga in 3 rate", -1_000).hasPlanTypePayee())
        assertTrue(f.mv("PAYPAL:c", "Pagamento a rate", -1_000).hasPlanTypePayee())
        assertFalse(f.mv("PAYPAL:d", "Paga in 3 rate", 1_000).hasPlanTypePayee())
        assertFalse(f.mv("PAYPAL:e", "Rata mutuo", -1_000).hasPlanTypePayee())
        assertFalse(f.mv("PAYPAL:f", "Negozio sintetico", -1_000).hasPlanTypePayee())
    }

    @Test fun aLinkedPartInheritsThePlansCategoryAndSpeseCountsItThere() {
        val p1 = f.mv("PAYPAL:p1", "Paga in 3 rate", -1_000)
        val a = archive(listOf(p1), listOf(f.plan("paypal-web:k1", "Negozio sintetico", listOf(f.part(1, p1.id)), categoryId = shop.id)))
        assertEquals(InstallmentPaymentState.Inherited("paypal-web:k1", shop.id, InheritedFrom.PLAN), a.installmentPaymentState(p1.id))
        assertEquals(1_000L, a.monthSpending(month).byCategory[shop.id])
        assertEquals(listOf(p1 to shop.id), a.spendingMovements(month))
    }

    @Test fun thePurchaseRowsCategoryIsInheritedWhenThePlanHasNone() {
        val purchase = f.mv("PAYPAL:web:buy1", "Negozio sintetico", -3_000, categoryId = home.id, source = CategorySource.OVERRIDE)
        val p1 = f.mv("PAYPAL:p1", "Paga in 3 rate", -1_000)
        val plan = f.plan("paypal-web:k1", null, listOf(f.part(1, p1.id)), evidence = listOf(Evidence("paypal:web:buy1", LocalDate.parse("2026-03-10"), "acquisto sintetico")))
        val a = archive(listOf(purchase, p1), listOf(plan))
        assertEquals(InstallmentPaymentState.Inherited(plan.id, home.id, InheritedFrom.PURCHASE), a.installmentPaymentState(p1.id))
    }

    @Test fun aRuleOrTheMerchantsOtherPurchasesGiveTheCategory() {
        val p1 = f.mv("PAYPAL:p1", "Paga in 3 rate", -1_000)
        val rule = CategoryRule("r1", "Negozio sintetico", shop.id, RuleOrigin.USER)
        val byRule = archive(listOf(p1), listOf(f.plan("paypal-web:k1", "Negozio Sintetico Srl", listOf(f.part(1, p1.id)))), Planning(categories = listOf(shop, home), rules = listOf(rule)))
        assertEquals(InheritedFrom.MERCHANT_RULE, (byRule.installmentPaymentState(p1.id) as InstallmentPaymentState.Inherited).from)

        val other = f.mv("PAYPAL:o1", "Bottega Alfa", -2_000, "2026-02-01", home.id, CategorySource.OVERRIDE)
        val byMerchant = archive(listOf(other, p1), listOf(f.plan("paypal-web:k1", "Bottega Alfa", listOf(f.part(1, p1.id)))))
        assertEquals(InstallmentPaymentState.Inherited("paypal-web:k1", home.id, InheritedFrom.MERCHANT), byMerchant.installmentPaymentState(p1.id))

        val disagree = f.mv("PAYPAL:o2", "Bottega Alfa", -2_500, "2026-02-03", shop.id, CategorySource.OVERRIDE)
        val split = archive(listOf(other, disagree, p1), listOf(f.plan("paypal-web:k1", "Bottega Alfa", listOf(f.part(1, p1.id)))))
        assertEquals(InstallmentPaymentState.ToClassify("paypal-web:k1"), split.installmentPaymentState(p1.id))
    }

    @Test fun anUnknownPurchaseLeavesThePartUncategorizedWithTheLabelEvenIfTheAssistantPutItSomewhere() {
        val bogus = f.spend("c:pagamenti-a-rate", "Pagamenti a rate")
        val p1 = f.mv("PAYPAL:p1", "Paga in 3 rate", -1_000, categoryId = shop.id, source = CategorySource.AGENT)
        val loose = f.mv("PAYPAL:p2", "Paga in 3 rate", -1_000, "2026-03-12")
        val plan = f.plan("paypal-web:k1", null, listOf(f.part(1, p1.id)), categoryId = bogus.id) // a method "category" is never inherited
        val a = archive(listOf(p1, loose), listOf(plan), Planning(categories = listOf(shop, home, bogus)))
        val state = a.installmentPaymentState(p1.id) as InstallmentPaymentState.ToClassify
        assertEquals("paypal-web:k1", state.planId)
        assertEquals("rata · acquisto da classificare", state.label)
        assertEquals(InstallmentPaymentState.LABEL, state.label)
        assertEquals(InstallmentPaymentState.ToClassify(null), a.installmentPaymentState(loose.id))
        assertEquals(2_000L, a.monthSpending(month).byCategory[null])
        assertNull(a.monthSpending(month).byCategory[shop.id])
    }

    @Test fun thePersonsOwnChoiceOnAPartStands() {
        val p1 = f.mv("PAYPAL:p1", "Paga in 3 rate", -1_000, categoryId = home.id, source = CategorySource.OVERRIDE)
        val a = archive(listOf(p1), listOf(f.plan("paypal-web:k1", null, listOf(f.part(1, p1.id)), categoryId = shop.id)))
        assertNull(a.installmentPaymentState(p1.id))
        assertTrue(p1.id in a.installmentPaymentIds())
        assertEquals(1_000L, a.monthSpending(month).byCategory[home.id])
    }
}
