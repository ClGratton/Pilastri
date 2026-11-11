package xyz.grtlabs.pilastri.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.parseEuroInput

/** The pure rules of the inline amount edit (lazy-UX 12). Synthetic values only. */
class InlineAmountTest {
    @Test fun theSameValueTypedAnotherWayIsUnchanged() {
        assertEquals(InlineEdit.Unchanged, resolveInlineAmount("1500", 150_000, false))
        assertEquals(InlineEdit.Unchanged, resolveInlineAmount("1.500,00", 150_000, false))
        assertEquals(InlineEdit.Unchanged, resolveInlineAmount(" 1500 € ", 150_000, false))
    }

    @Test fun aNewValueIsSavedInCents() {
        assertEquals(InlineEdit.Save(150_050), resolveInlineAmount("1.500,50", 150_000, false))
        assertEquals(InlineEdit.Save(99), resolveInlineAmount("0,99", null, false))
        assertEquals(InlineEdit.Save(0), resolveInlineAmount("0", 100, false))
    }

    @Test fun anInvalidAmountCarriesTheReasonOfTheItalianRules() {
        val ambiguous = resolveInlineAmount("1.5", 100, false)
        assertTrue(ambiguous is InlineEdit.Invalid && ambiguous.message.contains("ambiguo"))
        assertTrue(resolveInlineAmount("abc", null, false) is InlineEdit.Invalid)
        assertTrue(resolveInlineAmount("1,234", null, false) is InlineEdit.Invalid)
    }

    @Test fun blankIsRefusedUnlessTheFieldMayBeEmptied() {
        assertEquals(InlineEdit.Invalid("Inserisci un importo."), resolveInlineAmount("  ", 100, false))
        assertEquals(InlineEdit.Unchanged, resolveInlineAmount("", null, true))
        assertEquals(InlineEdit.Clear, resolveInlineAmount("", 100, true))
    }

    @Test fun theEditTextReadsBackToTheSameCents() {
        assertEquals("", inlineEditText(null))
        assertEquals("1500", inlineEditText(150_000))
        assertEquals("1500,50", inlineEditText(150_050))
        assertEquals("0,05", inlineEditText(5))
        assertEquals("0", inlineEditText(0))
        for (c in listOf(0L, 1L, 5L, 99L, 100L, 150_050L, 123_456_789L)) assertEquals(c, parseEuroInput(inlineEditText(c)))
    }

    @Test fun largeTextEditsInTheSheet() {
        assertFalse(useSheetFallback(1.0f))
        assertFalse(useSheetFallback(1.49f))
        assertTrue(useSheetFallback(1.5f))
        assertTrue(useSheetFallback(2.0f))
    }
}
