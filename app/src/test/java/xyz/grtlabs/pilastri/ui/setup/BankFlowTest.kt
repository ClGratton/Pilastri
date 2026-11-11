package xyz.grtlabs.pilastri.ui.setup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.sync.eb.EbAspsp

/** The bank-only run (Impostazioni › Banche: "Aggiungi banca" and "Rinnova"). Synthetic bank names only. */
class BankFlowTest {
    private fun step(s: SetupStep) = SetupEntry.Step(s)
    private fun bank(name: String) = EbAspsp(name, "IT", 15_552_000, listOf("personal"), emptyList(), emptyList(), false)

    private class Access(var session: Boolean) : BankAccess {
        override val signUpUrl = "https://example.invalid/sign-in/"
        override val redirectUrl = "https://localhost/pilastri/eb"
        override suspend fun registered() = true
        override suspend fun hasSession(bank: String) = session && bank == "Banca Alfa"
        override suspend fun publicKeyPem() = "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----"
        override suspend fun verifyRegistration(applicationId: String) = false
        override suspend fun banks(): List<EbAspsp> = emptyList()
        override suspend fun startLink(bank: EbAspsp): String = "https://bank.example.invalid/auth"
        override fun isRedirect(url: String) = url.startsWith(redirectUrl)
        override suspend fun completeLink(url: String, bank: EbAspsp): Boolean = true
    }

    @Test fun addingStartsAtThePickerUnderAnUnseenRoot() {
        val m = SetupRules.bankFlow(null)
        assertEquals(listOf(step(SetupStep.BENVENUTO), step(SetupStep.SCEGLI_BANCA)), m.entries)
        assertEquals(listOf(SetupStep.SCEGLI_BANCA, SetupStep.CONSENSI), SetupRules.bankSteps(m))
        assertFalse(SetupRules.bankRunEnded(m))
    }

    @Test fun renewingStartsAtTheConsentOfThatBank() {
        val m = SetupRules.bankFlow("Banca Alfa")
        assertEquals(listOf(step(SetupStep.BENVENUTO), step(SetupStep.CONSENSI)), m.entries)
        assertEquals(AccessState.REGISTERED, m.access)
        assertEquals(ListState.READY, m.list)
        assertEquals(listOf("Banca Alfa"), m.chosenBanks)
        assertEquals(BankLink.IN_ATTESA, m.links["Banca Alfa"])
        assertEquals("Banca Alfa", SetupRules.currentBank(m))
        assertEquals(listOf(SetupStep.CONSENSI), SetupRules.bankSteps(m))
        assertFalse(SetupRules.bankRunEnded(m))
    }

    @Test fun everyPathOutOfTheConsentsEndsTheRun() {
        // "Dopo" on the picker.
        val skipped = SetupRules.skipBanks(SetupRules.toggleBank(SetupRules.bankFlow(null), "Banca Alfa"))
        assertTrue(SetupRules.bankRunEnded(skipped))
        // The consents all done (linked, failed-and-continued or skipped) push Profilo.
        val chosen = SetupRules.banksDone(SetupRules.toggleBank(SetupRules.bankFlow(null), "Banca Alfa"))
        assertEquals(step(SetupStep.CONSENSI), chosen.top)
        assertFalse(SetupRules.bankRunEnded(chosen))
        val linked = SetupRules.push(SetupRules.consentLinked(chosen, "Banca Alfa"), SetupStep.PROFILO)
        assertTrue(SetupRules.bankRunEnded(linked))
        // Renewing: the consent is the only page, and its end is the run's end too.
        val renewed = SetupRules.push(SetupRules.consentLinked(SetupRules.bankFlow("Banca Alfa"), "Banca Alfa"), SetupStep.PROFILO)
        assertTrue(SetupRules.bankRunEnded(renewed))
    }

    @Test fun mergePendingClearsLinkedBanksKeepsUntouchedOnesAndAddsUnfinishedOnes() {
        val old = setOf("Banca Alfa", "Banca Beta")
        // This run linked Alfa and left Gamma unfinished; Beta was not touched.
        val m = SetupModel(
            chosenBanks = listOf("Banca Alfa", "Banca Gamma"),
            links = mapOf("Banca Alfa" to BankLink.COLLEGATA, "Banca Gamma" to BankLink.DA_APPROVARE),
        )
        assertEquals(setOf("Banca Beta", "Banca Gamma"), SetupRules.mergePending(old, m))
        // A run that linked nothing and chose nothing changes nothing.
        assertEquals(old, SetupRules.mergePending(old, SetupRules.bankFlow(null)))
    }

    @Test fun aSecondBankIsAddedAndOnlyTheSameBankNeedsRenew() = runBlocking {
        val access = Access(session = true)
        // Several banks can be linked: another bank starts its consent even though Alfa holds a session.
        assertTrue(BankSteps.begin(bank("Banca Beta"), access) is BeginOutcome.Page)
        // The same bank is blocked unless the run renews it.
        assertEquals(BeginOutcome.Blocked, BankSteps.begin(bank("Banca Alfa"), access))
        assertTrue(BankSteps.begin(bank("Banca Alfa"), access, replace = true) is BeginOutcome.Page)
        // The flag does not hide a missing bank entry.
        assertEquals(BeginOutcome.Failed, BankSteps.begin(null, access, replace = true))
    }

    @Test fun noSessionStartsEitherWay() = runBlocking {
        val access = Access(session = false)
        assertTrue(BankSteps.begin(bank("Banca Alfa"), access) is BeginOutcome.Page)
        assertTrue(BankSteps.begin(bank("Banca Alfa"), access, replace = true) is BeginOutcome.Page)
    }
}
