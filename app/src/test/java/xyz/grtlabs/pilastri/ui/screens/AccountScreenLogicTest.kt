package xyz.grtlabs.pilastri.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import xyz.grtlabs.pilastri.data.Account
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.domain.AccountValuation
import xyz.grtlabs.pilastri.domain.Confidence
import xyz.grtlabs.pilastri.domain.Money
import xyz.grtlabs.pilastri.sync.auto.AttentionReason
import xyz.grtlabs.pilastri.sync.auto.ProviderSyncState
import xyz.grtlabs.pilastri.sync.auto.UserAction
import xyz.grtlabs.pilastri.ui.Fmt

/** The account page logic (lazy-UX 3): editing the figure in place and the state line of the sync row. Synthetic data only. */
class AccountScreenLogicTest {
    private val today = LocalDate.now()
    private val acc = Account("a1", "Conto prova", 1, "fixture")
    private fun value(date: LocalDate, cents: Long, c: Confidence) = AccountValuation("a1", date, Money(cents), "fixture", c)
    private fun archive(vararg v: AccountValuation) = Archive(accounts = listOf(acc), accountValuations = v.toList())

    @Test fun aValueOfTodayTypedByThePersonIsReplaced() {
        val edited = archive(value(today, 10_000, Confidence.USER_REPORTED)).withEditedAccountValue("a1", today, 25_000, "Inserito a mano · Conto prova")
        assertEquals(listOf(25_000L), edited.accountValuations.map { it.amount.cents })
        assertEquals(Confidence.USER_REPORTED, edited.accountValuations.single().confidence)
    }

    @Test fun aValueOfTodayFromTheProviderIsNotOverwritten() {
        val a = archive(value(today, 10_000, Confidence.DOCUMENT))
        val e = assertThrows(IllegalArgumentException::class.java) { a.withEditedAccountValue("a1", today, 25_000, "Inserito a mano") }
        assertEquals("Il valore di oggi arriva dal fornitore", e.message)
        assertEquals(10_000L, a.accountValuations.single().amount.cents)
    }

    @Test fun anotherDayAddsADatedPointAndKeepsTheHistory() {
        val old = today.minusDays(40)
        val edited = archive(value(old, 10_000, Confidence.USER_REPORTED)).withEditedAccountValue("a1", today, 12_000, "Inserito a mano")
        assertEquals(setOf(old to 10_000L, today to 12_000L), edited.accountValuations.map { it.date to it.amount.cents }.toSet())
    }

    @Test fun theEditRulesOfAReportedValueStillApply() {
        assertThrows(IllegalArgumentException::class.java) { archive().withEditedAccountValue("a1", today, -1, "Inserito a mano") }
        assertThrows(IllegalArgumentException::class.java) { archive().withEditedAccountValue("a1", today, 100, " ") }
        assertThrows(IllegalArgumentException::class.java) { archive().withEditedAccountValue("a1", today.plusDays(1), 100, "Inserito a mano") }
    }

    @Test fun theStateLineIsFreshnessOrTheThingThePersonMustDo() {
        val covered = LocalDate.of(2026, 10, 31)
        val fresh = ProviderSyncState.Fresh(covered, Instant.now())
        assertEquals("dati fino al ${Fmt.day(covered)}" to false, accountSyncLine(fresh, null, today))
        assertEquals("dati fino al ${Fmt.day(covered)}" to false, accountSyncLine(ProviderSyncState.Syncing(fresh), null, today))
        assertEquals("aggiornato oggi" to false, accountSyncLine(ProviderSyncState.Fresh(null, Instant.now()), null, today))
        assertEquals("dati fino al ${Fmt.day(covered)}" to false, accountSyncLine(ProviderSyncState.Idle, covered, today))
        assertEquals(null to false, accountSyncLine(ProviderSyncState.Idle, null, today))
        assertEquals(null to false, accountSyncLine(ProviderSyncState.Syncing(null), null, today))
        val needs = ProviderSyncState.NeedsUser(UserAction.SIGN_IN_AGAIN, AttentionReason.SIGN_IN, "Accedi di nuovo a X", fresh)
        assertEquals("Accedi di nuovo a X" to true, accountSyncLine(needs, covered, today))
        assertEquals("Errore" to true, accountSyncLine(ProviderSyncState.Failed("Errore", fresh), covered, today))
    }

    @Test fun theRowOffersOneActionAndRefreshOnlyForALinkedAutomaticAccount() {
        val idle = ProviderSyncState.Idle
        val page = ProviderSyncState.NeedsUser(UserAction.SIGN_IN_AGAIN, AttentionReason.SIGN_IN, "x", null)
        val push = ProviderSyncState.NeedsUser(UserAction.APPROVE_2FA, AttentionReason.SCA_PUSH, "x", null)
        assertEquals(AccountSyncAction.SIGN_IN, accountSyncAction(page, linked = true, automatic = true))
        assertEquals(AccountSyncAction.CONNECT, accountSyncAction(idle, linked = false, automatic = true))
        assertEquals(AccountSyncAction.REFRESH, accountSyncAction(idle, linked = true, automatic = true))
        assertEquals(AccountSyncAction.REFRESH, accountSyncAction(push, linked = true, automatic = true))
        assertEquals(AccountSyncAction.NONE, accountSyncAction(idle, linked = true, automatic = false))
        assertTrue(accountOffersRefresh(automatic = true, linked = true))
        assertFalse(accountOffersRefresh(automatic = true, linked = false))
        assertFalse(accountOffersRefresh(automatic = false, linked = true))
    }
}
