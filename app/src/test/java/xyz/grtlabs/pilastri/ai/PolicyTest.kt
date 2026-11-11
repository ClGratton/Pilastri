package xyz.grtlabs.pilastri.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {
    @Test fun italianIbanIsMaskedWithAndWithoutSpaces() {
        assertEquals("IBAN [IBAN] grazie", AiPolicy.redact("IBAN IT60 X054 2811 1010 0000 0123 456 grazie"))
        assertEquals("[IBAN]", AiPolicy.redact("IT60X0542811101000000123456"))
        assertEquals("a [IBAN].", AiPolicy.redact("a it60x0542811101000000123456."))
    }

    @Test fun genericIbanIsMaskedOnlyWithAValidChecksum() {
        assertEquals("[IBAN]", AiPolicy.redact("DE89 3704 0044 0532 0130 00"))
        assertEquals("[IBAN] GRAZIE", AiPolicy.redact("DE89 3704 0044 0532 0130 00 GRAZIE"))
        assertEquals("[IBAN]", AiPolicy.redact("GB82WEST12345698765432"))
        assertEquals("Codice AB12 CDEF GHIJ", AiPolicy.redact("Codice AB12 CDEF GHIJ"))
    }

    @Test fun groupedCardNumbersAndFiscalCodesAreMasked() {
        assertEquals("carta [CARTA] scade", AiPolicy.redact("carta 4111 1111 1111 1111 scade"))
        assertEquals("[CARTA]", AiPolicy.redact("4111-1111-1111-1111"))
        assertEquals("[CARTA]", AiPolicy.redact("3782 822463 10005"))
        assertEquals("cf [CF]", AiPolicy.redact("cf RSSMRA85T10A562S"))
    }

    @Test fun longDigitRunsAreMaskedButAmountsAndDatesSurvive() {
        assertEquals("tel [NUMERO]", AiPolicy.redact("tel 3331234567"))
        assertEquals("1234567.89 € il 2026-03-02", AiPolicy.redact("1234567.89 € il 2026-03-02"))
        assertEquals("dal 2026-03-02 2026-03-05", AiPolicy.redact("dal 2026-03-02 2026-03-05"))
        assertEquals("12345678", AiPolicy.redact("12345678"))
    }

    @Test fun structuredModeKeepsAppProducedIdsIntact() {
        val uuid = "3f2b8c9e-1234-4abc-9def-123456789012"
        assertEquals(uuid, AiPolicy.redact(uuid, longDigitRuns = false))
        assertEquals("[IBAN]", AiPolicy.redact(AiFixtures.IBAN_TEXT, longDigitRuns = false))
        assertEquals("[NUMERO]", AiPolicy.redact("123456789012"))
    }

    @Test fun cardNumbersWithAnySeparatorAreFullyMasked() {
        val nbsp = ' '
        val nnbsp = ' '
        for (card in listOf(
            "4111.1111.1111.1111",
            "4111${nbsp}1111${nbsp}1111${nbsp}1111",
            "4111${nnbsp}1111${nnbsp}1111${nnbsp}1111",
            "4111_1111_1111_1111",
            "4111/1111/1111/1111",
            "4111 1111.1111-1111", // mixed separators: the Luhn pass
            "4111 - 1111 - 1111 - 1111".replace(" - ", "-"),
            "3782.822463.10005", // Amex 4-6-5 with dots
            "3782${nbsp}8224${nbsp}6310${nbsp}005", // 15 digits, Luhn-valid, unusual grouping
            "4222222222222", // 13 digits, no separator
        )) {
            assertEquals(card, "carta [CARTA] scade", AiPolicy.redact("carta $card scade"))
        }
        // A card next to a date in free text: only the card goes.
        assertEquals("pagato il 2026-03-02 con [CARTA].", AiPolicy.redact("pagato il 2026-03-02 con 4111.1111.1111.1111."))
        assertEquals("il 2026-03-02 [CARTA]", AiPolicy.redact("il 2026-03-02 4111 1111.1111-1111"))
    }

    @Test fun ibansWithAnySeparatorAreFullyMasked() {
        val nbsp = ' '
        for (iban in listOf(
            "IT60.X054.2811.1010.0000.0123.456",
            "IT60${nbsp}X054${nbsp}2811${nbsp}1010${nbsp}0000${nbsp}0123${nbsp}456",
            "IT60-X054-2811-1010-0000-0123-456",
            "IT60 X054 2811 1010 0000 0123 456",
            "IT60_X054_2811_1010_0000_0123_456",
            "IT60/X054/2811/1010/0000/0123/456",
        )) {
            assertEquals(iban, "iban [IBAN] grazie", AiPolicy.redact("iban $iban grazie"))
        }
        // Other countries need a valid checksum, with any separator.
        assertEquals("[IBAN]", AiPolicy.redact("DE89.3704.0044.0532.0130.00"))
        assertEquals("[IBAN]", AiPolicy.redact("DE89${nbsp}3704${nbsp}0044${nbsp}0532${nbsp}0130${nbsp}00"))
        assertEquals("[IBAN] fine", AiPolicy.redact("GB82-WEST-1234-5698-7654-32 fine"))
        // Without a valid checksum a foreign code is not an IBAN (its digit groups may still be masked as a card).
        assertFalse(AiPolicy.redact("DE88.3704.0044.0532.0130.00").contains("[IBAN]"))
    }

    @Test fun amountsDatesAndIdsSurviveTheWiderSeparators() {
        for (plain in listOf(
            "1.234,56 €", "12.345.678,90 €", "1 234,56 €", "1.234.567.890.123,45 €",
            "dal 2026-03-02 2026-03-05 2026-03-07 2026-03-09", "il 02/03/2026 e il 05.03.2026",
            "rata 2/3", "tel. 333.123.4567", "ore 10:00", "versione 1.2.3.4",
        )) {
            assertEquals(plain, plain, AiPolicy.redact(plain))
        }
        val uuid = "3f2b8c9e-1234-4abc-9def-123456789012"
        assertEquals(uuid, AiPolicy.redact(uuid, longDigitRuns = false))
        // Structured output keeps a 16-digit app id that happens to pass Luhn; free text does not.
        assertEquals("4111111111111111", AiPolicy.redact("4111111111111111", longDigitRuns = false))
        assertEquals("[CARTA]", AiPolicy.redact("4111111111111111"))
        // Groups glued to a letter belong to an id, not to a card; a Luhn-invalid mixed grouping is not a card either.
        assertEquals("ordine 4111.1111.1111.1112x", AiPolicy.redact("ordine 4111.1111.1111.1112x"))
        assertEquals("ordine 4111 1111.1111-1112", AiPolicy.redact("ordine 4111 1111.1111-1112"))
    }

    @Test fun defaultTiersFollowTheCouncilPolicy() {
        val direct = setOf(
            AiChangeKind.ADD_PLAN_ITEM, AiChangeKind.ADD_ACCOUNT, AiChangeKind.SET_IMPORT_CATEGORY, AiChangeKind.IGNORE_IMPORT, AiChangeKind.REOPEN_IMPORT,
            // Council 1: "ignore/reopen of review items" and labels are Tier 1
            AiChangeKind.IGNORE_INSTALLMENT_PLAN, AiChangeKind.SET_INSTALLMENT_CATEGORY,
            // Council 4 c: identical values that only refresh dates, and SCENARIO values (Futuro only), are Tier 1
            AiChangeKind.RULE_REFRESH, AiChangeKind.SET_SCENARIO_VALUE,
        )
        // Council 4 c: LAW, step and personal changes are Tier 3 (row by row), never direct
        assertEquals(setOf(AiChangeKind.RULE_LAW_CHANGE, AiChangeKind.RULE_STEP_CHANGE, AiChangeKind.RULE_PERSONAL_CHANGE), AiPolicy.tier3Kinds)
        AiPolicy.tier3Kinds.forEach { assertTrue("$it", AiPolicy.requiresTap(it) && it !in AiPolicy.directKinds) }
        AiChangeKind.entries.forEach { kind ->
            assertEquals("$kind", kind !in direct, AiPolicy.requiresTap(kind))
        }
        assertTrue(AiPolicy.requiresTap(AiChangeKind.ADD_EVENT))
        assertTrue(AiPolicy.requiresTap(AiChangeKind.ADD_VALUATION))
        assertTrue(AiPolicy.requiresTap(AiChangeKind.CLASSIFY_IMPORT))
        assertFalse(AiPolicy.requiresTap(AiChangeKind.ADD_PLAN_ITEM))
        // confirming a found plan is "applying an import"; a paid part is a dated payment fact
        assertTrue(AiPolicy.requiresTap(AiChangeKind.CONFIRM_INSTALLMENT_PLAN))
        assertTrue(AiPolicy.requiresTap(AiChangeKind.MARK_INSTALLMENT_PAID))
    }
}
