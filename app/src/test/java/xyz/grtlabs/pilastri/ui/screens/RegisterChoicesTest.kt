package xyz.grtlabs.pilastri.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import xyz.grtlabs.pilastri.data.Account
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.domain.AccountTransfer
import xyz.grtlabs.pilastri.domain.AccountValuation
import xyz.grtlabs.pilastri.domain.Confidence
import xyz.grtlabs.pilastri.domain.EventType
import xyz.grtlabs.pilastri.domain.LedgerEvent
import xyz.grtlabs.pilastri.domain.Money

/** The date pills and the recent-source chips of "Registra" (lazy-UX 11). Synthetic data only. */
class RegisterChoicesTest {
    private val today = LocalDate.of(2026, 11, 1)

    @Test fun datesMapToTheirPill() {
        assertEquals(DateChoice.TODAY, dateChoice(today, today))
        assertEquals(DateChoice.YESTERDAY, dateChoice(today.minusDays(1), today))
        assertEquals(DateChoice.OTHER, dateChoice(today.minusDays(2), today))
        assertEquals(DateChoice.OTHER, dateChoice(today.plusDays(1), today))
    }

    @Test fun pillsGiveBackTheirDate() {
        assertEquals(today, dateForChoice(DateChoice.TODAY, today))
        assertEquals(LocalDate.of(2026, 10, 31), dateForChoice(DateChoice.YESTERDAY, today))
        assertEquals(LocalDate.of(2026, 9, 3), dateForChoice(DateChoice.OTHER, today, LocalDate.of(2026, 9, 3)))
        assertEquals(today, dateForChoice(DateChoice.OTHER, today))
        for (c in DateChoice.entries.filter { it != DateChoice.OTHER }) assertEquals(c, dateChoice(dateForChoice(c, today), today))
    }

    @Test fun lastThreeSourcesByRecencyWithoutDuplicates() {
        val uses = listOf(
            SourceUse(LocalDate.of(2026, 5, 1), "Estratto maggio"),
            SourceUse(LocalDate.of(2026, 9, 1), "Busta paga"),
            SourceUse(LocalDate.of(2026, 7, 1), "App del fornitore"),
            SourceUse(LocalDate.of(2026, 10, 1), "  busta PAGA "),
            SourceUse(LocalDate.of(2026, 6, 1), "Bonifico"),
            SourceUse(LocalDate.of(2026, 8, 1), "   "),
        )
        assertEquals(listOf("busta PAGA", "App del fornitore", "Bonifico"), recentSources(uses))
        assertEquals(listOf("busta PAGA"), recentSources(uses, 1))
        assertEquals(emptyList<String>(), recentSources(emptyList()))
    }

    @Test fun sameDayKeepsTheLaterEntryFirst() {
        val d = LocalDate.of(2026, 10, 10)
        assertEquals(listOf("Seconda", "Prima"), recentSources(listOf(SourceUse(d, "Prima"), SourceUse(d, "Seconda"))))
    }

    @Test fun noPastSourceFallsBackToTheHonestDefault() {
        assertEquals(listOf("Inserito a mano"), sourceSuggestions(emptyList()))
        assertEquals(listOf("Busta paga"), sourceSuggestions(listOf(SourceUse(today, "Busta paga"))))
    }

    @Test fun sourcesComeFromThePersonsOwnEntriesOfThatAccount() {
        val a1 = Account("a1", "Fondo", 4, "fixture")
        val a2 = Account("a2", "Conto", 1, "fixture")
        fun v(acc: String, d: String, src: String, c: Confidence) = AccountValuation(acc, LocalDate.parse(d), Money(10_000), src, c)
        val archive = Archive(
            accounts = listOf(a1, a2),
            accountValuations = listOf(
                v("a1", "2026-09-01", "Estratto del fondo", Confidence.USER_REPORTED),
                v("a1", "2026-10-01", "Dal fornitore", Confidence.DOCUMENT),
                v("a2", "2026-10-02", "Altro conto", Confidence.USER_REPORTED),
            ),
            events = listOf(
                LedgerEvent("e1", LocalDate.parse("2026-10-05"), 4, EventType.PERSONAL_CONTRIBUTION, Money(5_000), Confidence.USER_REPORTED, "Busta paga", accountId = "a1"),
                LedgerEvent("e2", LocalDate.parse("2026-10-06"), 1, EventType.INCOME, Money(5_000), Confidence.USER_REPORTED, "Stipendio", accountId = "a2"),
            ),
            transfers = listOf(AccountTransfer("t1", LocalDate.parse("2026-10-07"), "a2", "a1", Money(2_000), "Bonifico interno")),
        )
        val forA1 = archive.sourceUsesFor("a1").map { it.source }.toSet()
        assertEquals(setOf("Estratto del fondo", "Busta paga", "Bonifico interno"), forA1)
        assertTrue("provider data is not a typed source", "Dal fornitore" !in forA1)
        assertEquals(setOf("Altro conto", "Stipendio", "Bonifico interno"), archive.sourceUsesFor("a2").map { it.source }.toSet())
        assertEquals(5, archive.sourceUsesFor(null).size)
        assertEquals(listOf("Bonifico interno", "Busta paga", "Estratto del fondo"), sourceSuggestions(archive.sourceUsesFor("a1")))
    }
}
