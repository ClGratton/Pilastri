package xyz.grtlabs.pilastri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic data only. The 2026-11-01 rule: a category is what the money was spent on, never how it was paid. */
class CategoryTaxonomyTest {
    private val liquidity = Account("acc-liq", "Conto sintetico", 1, "fixture")

    @Test fun paymentMethodsAndInstallmentLabelsAreMethodLike() {
        listOf(
            "Pagamenti a rate", "pagamento a rate", "Paga in 3 rate", "PAGA IN 3", "Rate", "Rata", "Rateizzazioni", "Paga dopo",
            "PayPal", "Pay Pal", "Spese PayPal", "Klarna", "Scalapay", "Satispay", "Apple Pay", "Carta", "Carta di credito",
            "Carta di debito", "carta prepagata", "Carte", "Bancomat", "Pagobancomat", "POS", "Bonifico", "Bonifici in uscita",
            "Addebito", "Addebiti diretti", "SDD", "RID", "Addebito SDD", "Domiciliazioni", "Contanti", "Prelievi", "F24",
            "Giroconto", "Giroconti", "Trasferimenti", "Ricariche", "Pagamenti con carta", "Pagamenti PayPal / Klarna", "Pagamënti à ratè",
        ).forEach { assertTrue(it, PaymentMethodNames.isMethodLike(it)) }
    }

    @Test fun realSpendingPurposesAreNotMethodLike() {
        listOf(
            "Cartoleria", "Carburante", "Carta e cancelleria", "Cartolibreria", "Rata mutuo", "Tasse F24", "Tasse", "Ricariche telefoniche",
            "Bollette", "Ratatouille", "Alimentari", "Ristoranti e bar", "Trasporti", "Abbonamenti", "Acquisti", "Spese mediche",
            "Debiti", "Credito d'imposta", "Assegno unico", "Pagamenti", "Spese", "Altro", "Posta", "Bancarelle", "Contabilità", "",
        ).forEach { assertFalse(it, PaymentMethodNames.isMethodLike(it)) }
    }

    @Test fun theDefaultTaxonomyIsGenericAndHasNoMethodNames() {
        assertTrue(CategoryTaxonomy.DEFAULT_SPEND.size >= 10)
        assertTrue(CategoryTaxonomy.DEFAULT_SPEND.none(PaymentMethodNames::isMethodLike))
        assertEquals(CategoryTaxonomy.DEFAULT_SPEND.size, CategoryTaxonomy.DEFAULT_SPEND.map(CategoryTaxonomy::key).distinct().size)
    }

    @Test fun aProposedNameMapsOntoTheExistingSetBeforeAnythingIsCreated() {
        val cats = listOf(
            Category("c:a", "Alimentari", CategoryKind.SPEND), Category("c:r", "Ristoranti e bar", CategoryKind.SPEND),
            Category("c:t", "Trasporti", CategoryKind.SPEND), Category("c:i", "Stipendio", CategoryKind.INCOME),
        )
        assertEquals("c:a", CategoryTaxonomy.match("ALIMENTARI", cats)?.id)
        assertEquals("c:a", CategoryTaxonomy.match("Supermercato", cats)?.id)
        assertEquals("c:r", CategoryTaxonomy.match("ristorante", cats)?.id)
        assertEquals("c:r", CategoryTaxonomy.match("Bar e ristoranti", cats)?.id)
        assertEquals("c:t", CategoryTaxonomy.match("Carburante", cats)?.id)
        assertEquals("c:t", CategoryTaxonomy.match("Mobilità", cats)?.id)
        assertNull(CategoryTaxonomy.match("Stipendio", cats)) // only spending categories
        assertNull(CategoryTaxonomy.match("Giardinaggio", cats))
        assertEquals("Bollette", CategoryTaxonomy.nameToCreate("utenze"))
        assertEquals("Giardinaggio", CategoryTaxonomy.nameToCreate("  Giardinaggio "))
    }

    @Test fun theModelSeesThePersonsCategoriesWithoutMethodOnesOrTheDefaultsWhenThereAreNone() {
        assertEquals(CategoryTaxonomy.DEFAULT_SPEND, CategoryTaxonomy.namesFor(Archive()))
        val a = Archive(planning = Planning(categories = listOf(Category("c:x", "Casa", CategoryKind.SPEND), Category("c:p", "Pagamenti a rate", CategoryKind.SPEND))))
        assertEquals(listOf("Casa"), CategoryTaxonomy.namesFor(a))
    }

    @Test fun seedingAddsTheDefaultsOnceAndIsIdempotent() {
        val base = Archive(accounts = listOf(liquidity))
        val seeded = base.withDefaultSpendCategories("2026-11-01T10:00:00Z")
        ArchiveCodec.encode(seeded)
        assertEquals(CategoryTaxonomy.DEFAULT_SPEND, seeded.planning.categories.map { it.name })
        assertTrue(seeded.planning.categories.all { it.kind == CategoryKind.SPEND && it.essential == Essential.UNSET })
        assertEquals(CategoryTaxonomy.SEEDED_AUDIT_KIND, seeded.audit.last().kind)
        assertSame(seeded, seeded.withDefaultSpendCategories("2026-11-02T10:00:00Z"))
        // The person emptied the list on purpose: not seeded again.
        val emptied = seeded.copy(planning = seeded.planning.copy(categories = emptyList()))
        assertSame(emptied, emptied.withDefaultSpendCategories("2026-11-03T10:00:00Z"))
    }

    @Test fun seedingNeverTouchesAnArchiveWithSpendCategoriesAndNeverDuplicatesAName() {
        val own = Archive(planning = Planning(categories = listOf(Category("c:mine", "Giardinaggio", CategoryKind.SPEND))))
        assertSame(own, own.withDefaultSpendCategories("2026-11-01T10:00:00Z"))
        val income = Archive(planning = Planning(categories = listOf(Category("c:casa", "Casa", CategoryKind.INCOME), Category("c:x", "Stipendio", CategoryKind.INCOME))))
        val seeded = income.withDefaultSpendCategories("2026-11-01T10:00:00Z")
        ArchiveCodec.encode(seeded)
        assertEquals(1, seeded.planning.categories.count { it.name == "Casa" })
        assertTrue(seeded.planning.categories.containsAll(income.planning.categories))
        assertEquals(CategoryTaxonomy.DEFAULT_SPEND.size - 1, seeded.planning.categories.count { it.kind == CategoryKind.SPEND })
        assertEquals(seeded.planning.categories.size, seeded.planning.categories.map { it.id }.distinct().size)
    }
}
