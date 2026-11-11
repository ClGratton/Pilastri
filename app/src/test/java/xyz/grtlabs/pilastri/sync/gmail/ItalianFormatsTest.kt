package xyz.grtlabs.pilastri.sync.gmail

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItalianFormatsTest {
    private val ref = LocalDate.of(2026, 11, 5)

    @Test fun amountsInItalianAndEnglishNotation() {
        assertEquals(3333L, ItalianFormats.parseCents("33,33"))
        assertEquals(3333L, ItalianFormats.parseCents("33.33"))
        assertEquals(123456L, ItalianFormats.parseCents("1.234,56"))
        assertEquals(123456L, ItalianFormats.parseCents("1,234.56"))
        assertEquals(123400L, ItalianFormats.parseCents("1.234"))
        assertEquals(700L, ItalianFormats.parseCents("7"))
        assertEquals(150L, ItalianFormats.parseCents("1,5"))
        assertEquals(1_234_567_00L, ItalianFormats.parseCents("1.234.567,00"))
        assertNull(ItalianFormats.parseCents("0,00"))
        assertNull(ItalianFormats.parseCents("abc"))
        assertNull(ItalianFormats.parseCents("-5,00"))
        assertNull(ItalianFormats.parseCents("12345678901"))
    }

    private fun amounts(s: String) = ItalianFormats.findAmounts(ItalianFormats.blankDates(s, ref)).map { it.cents }

    @Test fun amountsAreFoundWithCurrencySignBeforeOrAfter() {
        assertEquals(listOf(3333L), amounts("€ 33,33"))
        assertEquals(listOf(3333L), amounts("33,33 €"))
        assertEquals(listOf(3333L), amounts("€33,33"))
        assertEquals(listOf(3333L), amounts("33,33€"))
        assertEquals(listOf(3333L), amounts("EUR 33,33"))
        assertEquals(listOf(3333L), amounts("33,33 euro"))
        assertEquals(listOf(123456L), amounts("Totale € 1.234,56."))
        assertEquals(listOf(5000L, 1000L), amounts("3 rate da € 50,00 e commissione 10 €"))
        assertTrue("a number with no currency is not an amount", amounts("rata 2 di 3, ordine 123456").isEmpty())
    }

    @Test fun anEuroSignBelongsToTheFollowingNumber() {
        assertEquals(listOf(3333L), amounts("Rata 2 € 33,33"))
    }

    @Test fun aYearBeforeAnAmountIsNotAnAmount() {
        assertEquals(listOf(3333L), amounts("5 dic 2026 € 33,33"))
        assertEquals(listOf(3333L), amounts("05/12/2026 - 33,33 €"))
    }

    @Test fun dateFormats() {
        fun one(s: String) = ItalianFormats.findDates(s, ref).single().date
        assertEquals(LocalDate.of(2026, 11, 5), one("5 nov 2026"))
        assertEquals(LocalDate.of(2026, 11, 5), one("5 novembre 2026"))
        assertEquals(LocalDate.of(2026, 11, 5), one("5 Nov. 2026"))
        assertEquals(LocalDate.of(2026, 11, 5), one("lunedì 5 novembre 2026"))
        assertEquals(LocalDate.of(2026, 11, 5), one("05/11/2026"))
        assertEquals(LocalDate.of(2026, 11, 5), one("05-11-2026"))
        assertEquals(LocalDate.of(2026, 11, 5), one("05.11.2026"))
        assertEquals(LocalDate.of(2026, 11, 5), one("05/11/26"))
        assertEquals(LocalDate.of(2026, 11, 5), one("2026-11-05"))
        assertEquals(LocalDate.of(2026, 11, 5), one("Nov 5, 2026"))
        assertEquals(LocalDate.of(2026, 9, 1), one("1 sett 2026"))
        assertEquals(LocalDate.of(2027, 1, 12), one("12 gen 2027"))
    }

    @Test fun datesWithoutYearLookAheadOfTheMailDate() {
        assertEquals(LocalDate.of(2026, 12, 5), ItalianFormats.findDates("scade il 5 dic", ref).single().date)
        assertEquals(LocalDate.of(2027, 1, 5), ItalianFormats.findDates("scade il 5 gen", ref).single().date)
        assertEquals(LocalDate.of(2026, 10, 20), ItalianFormats.findDates("pagata il 20 ott", ref).single().date)
    }

    @Test fun relativeDates() {
        assertEquals(ref, ItalianFormats.findDates("Pagato oggi", ref).single().date)
        assertEquals(ref.plusDays(1), ItalianFormats.findDates("scade domani", ref).single().date)
        assertEquals(ref.plusDays(2), ItalianFormats.findDates("addebitato tra 2 giorni", ref).single().date)
        assertTrue(ItalianFormats.findDates("oggi", ref, relative = false).isEmpty())
    }

    @Test fun impossibleDatesAndLookAlikesAreIgnored() {
        assertTrue(ItalianFormats.findDates("31/02/2026", ref).isEmpty())
        assertTrue(ItalianFormats.findDates("32 nov 2026", ref).isEmpty())
        assertTrue("novità is not novembre", ItalianFormats.findDates("5 novità in negozio", ref).isEmpty())
        assertTrue(ItalianFormats.findDates("1.234.567", ref).isEmpty())
    }

    @Test fun severalDatesInReadingOrderWithoutOverlap() {
        val hits = ItalianFormats.findDates("1ª rata 5 dic 2026, 2ª rata 05/01/2027 e 3ª rata 2027-02-05", ref)
        assertEquals(listOf(LocalDate.of(2026, 12, 5), LocalDate.of(2027, 1, 5), LocalDate.of(2027, 2, 5)), hits.map { it.date })
    }

    @Test fun nonBreakingSpacesBecomePlainSpaces() {
        assertEquals("€ 33,33 il 5 dic 2026", ItalianFormats.normalizeSpaces("€ 33,33 il 5 dic 2026"))
    }
}
