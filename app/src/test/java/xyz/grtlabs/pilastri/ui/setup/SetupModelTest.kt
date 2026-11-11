package xyz.grtlabs.pilastri.ui.setup

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.ArchiveCodec
import xyz.grtlabs.pilastri.data.Gestione
import xyz.grtlabs.pilastri.data.IncomePosition
import xyz.grtlabs.pilastri.data.Origin
import xyz.grtlabs.pilastri.data.PositionKind
import xyz.grtlabs.pilastri.data.Sourced
import xyz.grtlabs.pilastri.data.coded
import xyz.grtlabs.pilastri.sync.Connectors
import xyz.grtlabs.pilastri.sync.eb.EbAspsp

/** Synthetic values only. The "secrets" below are invented markers used to prove nothing secret is persisted. */
class SetupModelTest {
    private val today = LocalDate.of(2026, 11, 1)
    private fun id(k: PositionKind) = "pos-${k.name.lowercase()}"
    private fun step(s: SetupStep) = SetupEntry.Step(s)
    private fun bank(name: String) = EbAspsp(name, "IT", 15_552_000, listOf("personal"), emptyList(), emptyList(), false)

    @Test fun duplicateBankNamesAreListedOnce() = runBlocking {
        assertEquals(listOf("Banca Alfa", "Banca Beta"), BankSteps.loadBanks(FakeAccess(list = listOf(bank("Banca Beta"), bank("Banca Alfa"), bank("Banca Alfa"))))?.map { it.name })
    }

    private class FakeAccess(
        var isRegistered: Boolean = true,
        var session: Boolean = false,
        var list: List<EbAspsp>? = null,
        var startFails: Boolean = false,
        var linkOk: Boolean = true,
    ) : BankAccess {
        override val signUpUrl = "https://example.invalid/sign-in/"
        override val redirectUrl = "https://localhost/pilastri/eb"
        val authUrl = "https://bank.example.invalid/auth?state=SECRET-STATE-77&client=SECRET-APP-ID-42"
        val redirect = "https://localhost/pilastri/eb?code=SECRET-CODE-99&state=SECRET-STATE-77"
        override suspend fun registered() = isRegistered
        override suspend fun hasSession(bank: String) = session
        override suspend fun publicKeyPem() = "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----"
        override suspend fun verifyRegistration(applicationId: String) = false
        override suspend fun banks(): List<EbAspsp> = list ?: throw IllegalStateException("rete")
        override suspend fun startLink(bank: EbAspsp): String = if (startFails) throw IllegalStateException("401") else authUrl
        override fun isRedirect(url: String) = url.startsWith(redirectUrl)
        override suspend fun completeLink(url: String, bank: EbAspsp): Boolean = linkOk.also { if (it) session = true }
    }

    /* Step order and Back */

    @Test fun stepsRunInTheCouncilOrderWithoutBanks() {
        var m = SetupModel()
        assertEquals("The total is one number from the start", 6, m.steps.size)
        m = SetupRules.push(m, SetupStep.POSIZIONE); assertEquals(SetupStep.POSIZIONE, m.step)
        m = SetupRules.positionsDone(m); assertEquals(SetupStep.SCEGLI_BANCA, m.step)
        assertEquals("The mail offer sits over Scegli banca, once", SetupEntry.MailAccess, m.top); m = SetupRules.mailDone(m)
        m = SetupRules.banksDone(m); assertEquals("No bank chosen: Continua is Dopo", SetupStep.PROFILO, m.step)
        m = SetupRules.push(m, SetupStep.ALTRI_CONTI)
        assertEquals(listOf(SetupStep.BENVENUTO, SetupStep.POSIZIONE, SetupStep.SCEGLI_BANCA, SetupStep.PROFILO, SetupStep.ALTRI_CONTI),
            m.entries.map { (it as SetupEntry.Step).step })
        assertEquals("Skipping the banks keeps Consensi counted", 6, m.steps.size)
        assertEquals("D7: the last page is board page 9 of 9", 9 to 9, SetupRules.progress(m))
    }

    @Test fun chosenBanksAddTheConsentStepBetweenBanksAndProfile() {
        var m = SetupRules.push(SetupRules.push(SetupModel(), SetupStep.POSIZIONE), SetupStep.SCEGLI_BANCA)
        m = SetupRules.toggleBank(SetupRules.toggleBank(m, "Banca Alfa"), "Banca Beta")
        m = SetupRules.banksDone(m)
        assertEquals(SetupStep.CONSENSI, m.step)
        assertEquals(listOf(SetupStep.BENVENUTO, SetupStep.POSIZIONE, SetupStep.SCEGLI_BANCA, SetupStep.CONSENSI, SetupStep.PROFILO, SetupStep.ALTRI_CONTI), m.steps)
        assertEquals("Consensi is board page 6 of 9", 6 to 9, SetupRules.progress(m))
        assertEquals("Banca Alfa", SetupRules.currentBank(m))
        assertEquals(1 to 2, SetupRules.bankIndex(m, "Banca Alfa"))
    }

    @Test fun backIsTheExactPopAndStopsAtBenvenuto() {
        val root = SetupModel()
        assertEquals(root, SetupRules.back(root))
        val a = SetupRules.push(root, SetupStep.POSIZIONE)
        val b = SetupRules.positionsDone(SetupRules.togglePosition(a, PositionKind.STUDENTE))
        assertEquals(SetupEntry.MailAccess, b.top)
        val c = SetupRules.back(SetupRules.back(b)) // the mail page, then Scegli banca
        assertEquals(a.entries, c.entries)
        assertEquals("Choices survive Back", listOf(PositionKind.STUDENTE), c.positions)
        assertEquals(root.entries, SetupRules.back(c).entries)
    }

    @Test fun leavingABankPageKeepsTheAuthorizationAndLeavingSignUpUnverifiedFails() {
        var m = SetupRules.banksDone(SetupRules.toggleBank(SetupRules.push(SetupModel(), SetupStep.SCEGLI_BANCA), "Banca Alfa"))
        m = BankSteps.applyBegin(m, "Banca Alfa", BeginOutcome.Page("https://bank.example.invalid/a"))
        // the bank's address opens in the person's browser (BankBrowser): no page entry, the bank waits as "in corso" until its return
        assertEquals(step(SetupStep.CONSENSI), m.top)
        assertEquals(null, m.failedBank)
        assertEquals(BankLink.IN_CORSO, m.links["Banca Alfa"])

        val signUp = SetupRules.push(SetupRules.push(SetupModel(), SetupStep.SCEGLI_BANCA).copy(access = AccessState.NOT_REGISTERED), SetupEntry.SignUp)
        assertEquals(AccessState.FAILED, SetupRules.back(signUp).access)
        assertEquals(step(SetupStep.SCEGLI_BANCA), SetupRules.closePage(signUp).top)
    }

    /* Positions */

    @Test fun positionsAreWrittenAsDeclaredWithEveryOtherFieldNd() {
        var m = SetupRules.togglePosition(SetupModel(), PositionKind.DIPENDENTE_PUBBLICO)
        m = SetupRules.togglePosition(m, PositionKind.AUTONOMO_FORFETTARIO)
        m = SetupRules.setGestione(m, PositionKind.AUTONOMO_FORFETTARIO, Gestione.ARTIGIANI)
        val written = SetupRules.positionsToWrite(m, today, ::id)
        assertEquals(listOf(PositionKind.DIPENDENTE_PUBBLICO, PositionKind.AUTONOMO_FORFETTARIO), written.map { it.kind.known })
        written.forEach { p ->
            assertEquals(Origin.DICHIARATO, p.label.origin)
            assertEquals(today, p.label.asOf)
            listOf(p.validFrom, p.validTo, p.severanceRegime, p.severanceDestination, p.contractTerm, p.hireDate, p.ateco, p.regimeStart, p.incomeSource)
                .forEach { assertEquals(Origin.ND, it.origin); assertNull(it.value) }
        }
        assertEquals(Origin.ND, written[0].gestione.origin)
        assertEquals(Origin.DICHIARATO, written[1].gestione.origin)
        assertEquals(Gestione.ARTIGIANI, written[1].gestione.value?.known)
    }

    @Test fun nonSoAndUnansweredGestioneStayNd() {
        var m = SetupRules.togglePosition(SetupModel(), PositionKind.COLLABORATORE)
        assertEquals(Origin.ND, SetupRules.positionsToWrite(m, today, ::id).single().gestione.origin)
        m = SetupRules.setGestione(m, PositionKind.COLLABORATORE, Gestione.ND)
        assertEquals(Origin.ND, SetupRules.positionsToWrite(m, today, ::id).single().gestione.origin)
        m = SetupRules.setGestione(m, PositionKind.COLLABORATORE, Gestione.GESTIONE_SEPARATA)
        m = SetupRules.setGestione(m, PositionKind.COLLABORATORE, Gestione.GESTIONE_SEPARATA)
        assertNull("Tapping the chosen gestione again clears it", m.gestioni[PositionKind.COLLABORATORE])
    }

    @Test fun onlyPartitaIvaAndCollaborazioneRevealAGestioneRow() {
        val withRow = PositionKind.entries.filter { PositionCatalog.gestioni(it).isNotEmpty() }.toSet()
        assertEquals(setOf(PositionKind.AUTONOMO_FORFETTARIO, PositionKind.AUTONOMO_ORDINARIO, PositionKind.COLLABORATORE), withRow)
        withRow.forEach { assertEquals(Gestione.ND, PositionCatalog.gestioni(it).last()) }
    }

    @Test fun nessunRedditoExcludesTheOthersAndDeselectingClearsGestione() {
        var m = SetupRules.togglePosition(SetupModel(), PositionKind.AUTONOMO_ORDINARIO)
        m = SetupRules.setGestione(m, PositionKind.AUTONOMO_ORDINARIO, Gestione.CASSA)
        m = SetupRules.togglePosition(m, PositionKind.NESSUN_REDDITO)
        assertEquals(listOf(PositionKind.NESSUN_REDDITO), m.positions)
        assertTrue(m.gestioni.isEmpty())
        m = SetupRules.togglePosition(m, PositionKind.STUDENTE)
        assertEquals(listOf(PositionKind.STUDENTE), m.positions)
    }

    @Test fun skipLeavesEverythingNdAndWritesNothing() {
        var m = SetupRules.togglePosition(SetupRules.push(SetupModel(), SetupStep.POSIZIONE), PositionKind.PENSIONATO)
        m = SetupRules.skipPositions(m)
        assertTrue(m.positions.isEmpty())
        assertTrue(m.positionsSkipped)
        assertTrue(SetupRules.positionsToWrite(m, today, ::id).isEmpty())
        val rows = SetupRules.profileRows(m)
        assertEquals(ProfileRow("Posizione", "n/d", "saltato", RowTone.WARN), rows.first())
        assertTrue("No row invents a value", rows.all { it.value == "n/d" })
        assertEquals("Continua with nothing chosen is a skip", true, SetupRules.positionsDone(SetupRules.push(SetupModel(), SetupStep.POSIZIONE)).positionsSkipped)
    }

    @Test fun applyingNeverOverwritesAnExistingPosition() {
        val existing = IncomePosition("old", coded(PositionKind.STUDENTE), label = Sourced.declared("Studente"))
        val a = Archive(positions = listOf(existing))
        val m = SetupRules.togglePosition(SetupRules.togglePosition(SetupModel(), PositionKind.STUDENTE), PositionKind.COLLABORATORE)
        val next = SetupRules.applyToArchive(a, SetupRules.positionsToWrite(m, today, ::id))
        assertEquals(listOf("old", "pos-collaboratore"), next.positions.map { it.id })
        assertEquals(a, SetupRules.applyToArchive(a, emptyList()))
    }

    /* Altri conti */

    @Test fun altriContiOffersTheRegistryMatchForTheChosenPositions() {
        val publicEmployee = SetupRules.togglePosition(SetupModel(), PositionKind.DIPENDENTE_PUBBLICO)
        assertEquals(listOf("TRADE_REPUBLIC", "PAYPAL", "ASSISTANT"), SetupRules.offered(publicEmployee).map { it.id })
        assertTrue("NoiPA is not implemented, so not offered", SetupRules.offered(publicEmployee).none { it == Connectors.NOIPA })
        assertEquals(SetupRules.offered(publicEmployee), SetupRules.offered(SetupModel()))
        assertEquals(setOf("PAYPAL"), SetupRules.toggleLater(SetupModel(), "PAYPAL").later)
    }

    /* Enable Banking failures */

    @Test fun anEnableBankingFailureOffersRetryOrContinueAndNeverClaimsAConnection() = runBlocking {
        val access = FakeAccess(list = listOf(bank("Banca Alfa"), bank("Banca Beta")), startFails = true)
        var m = SetupRules.banksDone(SetupRules.toggleBank(SetupRules.push(SetupModel(), SetupStep.SCEGLI_BANCA), "Banca Alfa"))
        m = BankSteps.applyBegin(m, "Banca Alfa", BankSteps.begin(bank("Banca Alfa"), access))
        assertEquals("Banca Alfa", m.failedBank)
        assertEquals(BankLink.NON_RIUSCITA, m.links["Banca Alfa"])
        assertFalse(SetupRules.consentsDone(m))
        // Riprova: the same bank waits again; a second failure offers the same choice.
        m = SetupRules.retryConsent(m)
        assertEquals(BankLink.IN_ATTESA, m.links["Banca Alfa"]); assertNull(m.failedBank)
        m = BankSteps.applyBegin(m, "Banca Alfa", BankSteps.begin(bank("Banca Alfa"), access))
        assertEquals("Banca Alfa", m.failedBank)
        // Continua senza: setup goes on, the bank stays n/d and waits in Conti.
        m = SetupRules.continueWithout(m)
        assertTrue(SetupRules.consentsDone(m))
        assertEquals(listOf("Banca Alfa"), SetupRules.pendingBanks(m))
        assertTrue(m.links.values.none { it == BankLink.COLLEGATA })
        assertTrue(SetupRules.profileRows(m).none { it.tag == "collegata" })
        assertEquals(ProfileRow("Banca", "Banca Alfa", "non riuscito", RowTone.WARN), SetupRules.profileRows(m).first { it.label == "Banca" })
    }

    @Test fun listRegistrationAndRedirectFailuresAreHonest() = runBlocking {
        val access = FakeAccess(isRegistered = false)
        assertEquals(AccessState.NOT_REGISTERED, BankSteps.registered(access))
        assertFalse(access.verifyRegistration("00000000-0000-0000-0000-000000000000"))
        assertNull("A list that cannot be read is a failure", BankSteps.loadBanks(access))
        assertEquals(ListState.FAILED, BankSteps.applyList(SetupModel(), null).list)
        access.list = emptyList()
        assertNull("An empty list is a failure, not 'no banks'", BankSteps.loadBanks(access))

        access.list = listOf(bank("Banca Alfa")); access.linkOk = false
        var m = SetupRules.banksDone(SetupRules.toggleBank(SetupRules.push(SetupModel(), SetupStep.SCEGLI_BANCA), "Banca Alfa"))
        m = BankSteps.applyBegin(m, "Banca Alfa", BankSteps.begin(bank("Banca Alfa"), access))
        assertEquals(BankLink.IN_CORSO, m.links["Banca Alfa"])
        m = BankSteps.applyComplete(m, "Banca Alfa", BankSteps.complete(bank("Banca Alfa"), access.redirect, access))
        assertEquals(BankLink.NON_RIUSCITA, m.links["Banca Alfa"])
        assertEquals("Banca Alfa", m.failedBank)
        // A missing bank entry fails too, without calling the bank.
        assertEquals(BeginOutcome.Failed, BankSteps.begin(null, access))
    }

    @Test fun aSecondBankWaitsWhileTheClientHoldsOneSession() = runBlocking {
        val access = FakeAccess(list = listOf(bank("Banca Alfa"), bank("Banca Beta")))
        var m = SetupRules.push(SetupModel(), SetupStep.SCEGLI_BANCA)
        m = SetupRules.banksDone(SetupRules.toggleBank(SetupRules.toggleBank(m, "Banca Alfa"), "Banca Beta"))
        m = BankSteps.applyBegin(m, "Banca Alfa", BankSteps.begin(bank("Banca Alfa"), access))
        m = SetupRules.closePage(m)
        m = BankSteps.applyComplete(m, "Banca Alfa", BankSteps.complete(bank("Banca Alfa"), access.redirect, access))
        assertEquals(BankLink.COLLEGATA, m.links["Banca Alfa"])
        assertEquals("Banca Beta", SetupRules.currentBank(m))
        m = BankSteps.applyBegin(m, "Banca Beta", BankSteps.begin(bank("Banca Beta"), access))
        assertEquals(BankLink.DA_APPROVARE, m.links["Banca Beta"])
        assertTrue(SetupRules.consentsDone(m))
        assertEquals(listOf("Banca Beta"), SetupRules.pendingBanks(m))
    }

    @Test fun dopoOnBanksLeavesThemDaApprovareWithoutConsents() {
        val m = SetupRules.skipBanks(SetupRules.toggleBank(SetupRules.push(SetupModel(), SetupStep.SCEGLI_BANCA), "Banca Alfa"))
        assertEquals(SetupStep.PROFILO, m.step)
        assertTrue("Consensi stays counted (U-01)", SetupStep.CONSENSI in m.steps)
        assertEquals(6, m.steps.size)
        assertEquals(BankLink.DA_APPROVARE, m.links["Banca Alfa"])
    }

    @Test fun aStaleBeginIsIgnoredAfterTheUserMovedOn() {
        var m = SetupRules.banksDone(SetupRules.toggleBank(SetupRules.push(SetupModel(), SetupStep.SCEGLI_BANCA), "Banca Alfa"))
        m = SetupRules.back(m)
        assertEquals(m, BankSteps.applyBegin(m, "Banca Alfa", BeginOutcome.Page("https://bank.example.invalid/a")))
    }

    /* Nothing secret is persisted */

    @Test fun nothingSecretReachesTheArchiveOrTheSavedState() = runBlocking {
        val access = FakeAccess(list = listOf(bank("Banca Alfa")))
        var m = SetupRules.togglePosition(SetupModel(), PositionKind.DIPENDENTE_PRIVATO)
        m = SetupRules.banksDone(SetupRules.toggleBank(SetupRules.push(SetupRules.positionsDone(SetupRules.push(m, SetupStep.POSIZIONE)), SetupStep.SCEGLI_BANCA), "Banca Alfa"))
        m = BankSteps.applyBegin(m, "Banca Alfa", BankSteps.begin(bank("Banca Alfa"), access))
        val withPage = SetupCodec.encode(m)
        m = SetupRules.closePage(m)
        m = BankSteps.applyComplete(m, "Banca Alfa", BankSteps.complete(bank("Banca Alfa"), access.redirect, access))
        val archive = SetupRules.applyToArchive(Archive(), SetupRules.positionsToWrite(m, today, ::id))
        val stored = String(ArchiveCodec.encode(archive), Charsets.UTF_8)
        listOf(withPage, SetupCodec.encode(m), stored).forEach { text ->
            listOf("SECRET", "state=", "code=", "auth?", "PUBLIC KEY", access.redirectUrl).forEach { secret ->
                assertFalse("'$secret' persisted", text.contains(secret))
            }
        }
        assertFalse("Bank consent never enters the archive", stored.contains("Banca Alfa"))
    }

    /* Saved state */

    @Test fun savedStateRoundTripsAndDropsProviderPages() {
        var m = SetupRules.togglePosition(SetupModel(), PositionKind.AUTONOMO_FORFETTARIO)
        m = SetupRules.setGestione(m, PositionKind.AUTONOMO_FORFETTARIO, Gestione.GESTIONE_SEPARATA)
        m = SetupRules.positionsDone(SetupRules.push(m, SetupStep.POSIZIONE))
        m = SetupRules.banksDone(SetupRules.toggleBank(m.copy(access = AccessState.REGISTERED), "Banca Alfa"))
        m = BankSteps.applyBegin(m, "Banca Alfa", BeginOutcome.Page("https://bank.example.invalid/a"))
        val back = SetupCodec.decode(SetupCodec.encode(m))
        assertEquals(step(SetupStep.CONSENSI), back.top)
        assertEquals(m.positions, back.positions)
        assertEquals(m.gestioni, back.gestioni)
        assertEquals(listOf("Banca Alfa"), back.chosenBanks)
        assertEquals("A consent in progress is a named failure with its retry; nothing starts by itself", BankLink.NON_RIUSCITA, back.links["Banca Alfa"])
        assertEquals("Banca Alfa", back.failedBank)
        assertEquals("Access is checked again", AccessState.UNKNOWN, back.access)
        assertEquals(SetupModel(), SetupCodec.decode("not json"))
    }

    /* Registration probe */

    @Test fun theApplicationIdIsTakenOnlyFromTheControlPanelAndOnlyWhenUnambiguous() {
        val id = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
        assertEquals(id, EbRegistrationProbe.fromUrl("https://enablebanking.com/cp/applications/$id"))
        assertNull(EbRegistrationProbe.fromUrl("https://evil.example.com/cp/applications/$id"))
        assertNull(EbRegistrationProbe.fromUrl("https://enablebanking.com/docs/$id"))
        assertTrue(EbRegistrationProbe.isControlPanel("https://enablebanking.com/cp/applications"))
        assertFalse(EbRegistrationProbe.isControlPanel("https://enablebanking.com/sign-in/"))
        assertEquals(id, EbRegistrationProbe.fromPageText("Application ID\n${id.uppercase()}\nKey: $id"))
        assertNull(EbRegistrationProbe.fromPageText("$id and 11111111-2222-3333-4444-555555555555"))
        assertNull(EbRegistrationProbe.fromPageText(null))
    }

    /* Fix wave 2026-11-08: U-01, U-02, U-03 */

    @Test fun totalNeverChangesWhateverTheBankChoice() {
        val skipped = SetupRules.skipBanks(SetupRules.push(SetupModel(), SetupStep.SCEGLI_BANCA))
        val chosen = SetupRules.banksDone(SetupRules.toggleBank(SetupRules.push(SetupModel(), SetupStep.SCEGLI_BANCA), "Banca Alfa"))
        assertEquals(SetupModel().steps.size, skipped.steps.size)
        assertEquals(SetupModel().steps.size, chosen.steps.size)
    }

    @Test fun continuaOnPosizioneNeedsAChoice() {
        val m = SetupRules.push(SetupModel(), SetupStep.POSIZIONE)
        assertFalse(SetupRules.canContinuePositions(m))
        assertTrue(SetupRules.canContinuePositions(SetupRules.togglePosition(m, PositionKind.STUDENTE)))
    }

    @Test fun bankPrimaryNamesTheCountNeverTheGateway() {
        assertEquals("Continua", SetupRules.bankPrimaryLabel(0))
        assertEquals("Continua (2)", SetupRules.bankPrimaryLabel(2))
    }

    @Test fun chosenCommonBanksMapToTheGatewayNamesAndUnmatchedAreDropped() {
        val api = listOf("Intesa Sanpaolo S.p.A.", "UniCredit", "Fineco").map { bank(it) }
        assertEquals(listOf("Intesa Sanpaolo S.p.A.", "UniCredit"), SetupRules.matchChosen(listOf("Intesa Sanpaolo", "unicredit", "N26"), api))
        val m = BankSteps.applyList(SetupModel(chosenBanks = listOf("Fineco", "N26"), links = mapOf("N26" to BankLink.IN_ATTESA)), api)
        assertEquals(listOf("Fineco"), m.chosenBanks)
        assertTrue(m.links.isEmpty())
    }

    @Test fun theBoardCounterIsNineAndNeverRenumbers() {
        var m = SetupModel()
        assertEquals(1 to 9, SetupRules.progress(m))
        m = SetupRules.push(m, SetupStep.POSIZIONE); assertEquals(2 to 9, SetupRules.progress(m))
        assertEquals("a document read: board page 3", 3 to 9, SetupRules.progress(m.copy(docs = listOf(SetupDoc("cedolino", "sha")))))
        m = SetupRules.push(m, SetupStep.SCEGLI_BANCA); assertEquals(4 to 9, SetupRules.progress(m))
        assertEquals("sign-up page: 5", 5 to 9, SetupRules.progress(SetupRules.push(m, SetupEntry.SignUp)))
        // skipping the banks leaves Profilo and Altri conti at 8 and 9
        val skipped = SetupRules.push(SetupRules.skipBanks(SetupRules.toggleBank(m, "Banca Alfa")), SetupStep.PROFILO)
        assertEquals(8 to 9, SetupRules.progress(skipped))
        assertEquals(9 to 9, SetupRules.progress(SetupRules.push(skipped, SetupStep.ALTRI_CONTI)))
        val consent = SetupRules.banksDone(SetupRules.toggleBank(m, "Banca Alfa"))
        assertEquals(6 to 9, SetupRules.progress(consent))
        assertEquals("a failed consent: 7", 7 to 9, SetupRules.progress(SetupRules.consentFailed(consent, "Banca Alfa")))
    }

    @Test fun theBankListComesFromTheDatedAsset() {
        val json = java.io.File("src/main/assets/" + BankCatalog.ASSET).readText()
        val catalog = BankCatalog.parse(json)
        assertTrue(catalog.version.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
        assertTrue(catalog.source.isNotBlank())
        assertEquals(9, catalog.banks.size)
        assertEquals(catalog.banks.map { it.name }, SetupRules.fallbackBanks(catalog).map { it.name })
        assertTrue(catalog.banks.all { it.id.isNotBlank() && it.initials.isNotBlank() })
        assertEquals("iy", catalog.marks()["isybank"])
    }
}
