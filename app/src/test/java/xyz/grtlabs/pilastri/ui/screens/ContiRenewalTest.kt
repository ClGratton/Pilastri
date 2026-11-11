package xyz.grtlabs.pilastri.ui.screens

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Rinnova" on Impostazioni › Banche and the Trade Republic PIN line. Synthetic dates. */
class ContiRenewalTest {
    private val today = LocalDate.of(2026, 11, 1)

    @Test fun exactlySevenDaysLeftOffersRenewal() = assertTrue(consentNeedsRenewal(today.plusDays(7), today))

    @Test fun eightDaysLeftDoesNot() = assertFalse(consentNeedsRenewal(today.plusDays(8), today))

    @Test fun anExpiredConsentOffersRenewal() {
        assertTrue(consentNeedsRenewal(today.minusDays(1), today))
        assertTrue(consentNeedsRenewal(today, today))
    }

    @Test fun anUnknownEndOffersNothing() = assertFalse(consentNeedsRenewal(null, today))

    @Test fun theTrPinLineShowsWhetherTheSwitchIsOnOrOff() {
        assertEquals("Senza PIN niente lettura automatica", trPinLine(declined = true))
        assertEquals(trPinLine(declined = true), trPinLine(declined = false))
        assertEquals("Ricorda il PIN", TrPinLabel)
    }
}
