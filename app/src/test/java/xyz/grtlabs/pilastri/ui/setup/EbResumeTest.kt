package xyz.grtlabs.pilastri.ui.setup

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.sync.eb.EbPanel

/* The setup resumes after a kill or an update (synthetic data only). */
class EbResumeTest {
    private val bank = "Banca Esempio"
    private fun dir() = java.nio.file.Files.createTempDirectory("progress").toFile()
    private fun o(vararg kv: Pair<String, Any?>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }

    private val approve = EbFlowModel(
        stack = listOf(EbStep(EbScreen.BANKS), EbStep(EbScreen.APPROVE, bank)), checks = setOf(EbCheck.SIGNED_IN, EbCheck.KEY, EbCheck.REGISTERED),
        chosen = listOf(bank), queue = listOf(bank), asked = listOf(bank), psu = mapOf(bank to "personal"), linked = mapOf("Altra" to 2, "Terza" to null),
        working = true, failure = EbFailure(EbFailKind.UNREACHABLE), sentAt = 5L, retryAt = 9L,
    )

    @Test fun storeAndCodecRoundTrip() {
        val d = dir()
        val store = SetupProgressStore(d)
        val setup = SetupModel(entries = listOf(SetupEntry.Step(SetupStep.BENVENUTO), SetupEntry.Step(SetupStep.POSIZIONE)), chosenBanks = listOf(bank))
        store.saveSetup(setup)
        store.saveEb(EbFlowCodec.encode(approve))
        val again = SetupProgressStore(d)   // a new process
        assertEquals(setup.entries, again.loadSetup()!!.entries)
        assertEquals(listOf(bank), again.loadSetup()!!.chosenBanks)
        val m = EbFlowCodec.decode(again.loadEb()!!)!!
        assertEquals(approve.stack, m.stack); assertEquals(approve.checks, m.checks); assertEquals(approve.chosen, m.chosen)
        assertEquals(approve.queue, m.queue); assertEquals(approve.psu, m.psu); assertEquals(approve.linked, m.linked)
        assertFalse(m.working); assertNull(m.failure); assertNull(m.sentAt); assertNull(m.retryAt)
        again.saveEb(null); assertNull(again.loadEb()); assertNotNull(again.loadSetup())
        again.clear(); assertNull(again.loadSetup())
    }

    @Test fun unreadableStoredValuesAreDropped() {
        val d = dir()
        File(d, "setup-progress.json").writeText("{not json")
        assertNull(SetupProgressStore(d).loadSetup()); assertNull(SetupProgressStore(d).loadEb())
        assertNull(EbFlowCodec.decode("garbage")); assertNull(EbFlowCodec.decode("""{"v":1,"stack":[{"s":"NOPE","b":null,"r":0}]}"""))
        assertNull(EbFlowCodec.decode(EbFlowCodec.encode(approve.copy(queue = emptyList()))))   // EB-5 without a bank: no half-restored page
    }

    @Test fun linkAndRegistrationFallBackToEmailAndApproveNeverKeepsAPage() = runBlocking {
        val link = EbFlowModel(stack = listOf(EbStep(EbScreen.EMAIL), EbStep(EbScreen.LINK)), sentAt = 7L)
        assertEquals(EbScreen.EMAIL, EbFlowCodec.decode(EbFlowCodec.encode(link))!!.screen)
        assertEquals(EbScreen.EMAIL, EbFlowCodec.decode(EbFlowCodec.encode(link.copy(stack = link.stack + EbStep(EbScreen.REGISTER))))!!.screen)
        val m = EbFlowCodec.decode(EbFlowCodec.encode(approve))!!
        assertTrue(EbFlowCodec.needsStatusCheck(m))
        // the provider's answer decides
        var t = 0L
        val page = FakeEbPage(); val access = FakeEbAccess().apply { stored = "00000000-0000-4000-8000-000000000001" }
        page.states("in")
        page.on("STATUS", o("active" to true, "linked" to 3))   // active, accounts linked, no session for the bank: already linked
        val onward = EbFlowCodec.resumeApprove(EbPanel(page, { t }, { t += it }), access, m.current)(EbFlowRules.working(m))
        assertEquals(EbScreen.CONFIRM, onward.screen)
        page.on("STATUS", o("active" to true, "linked" to 0))   // no account linked: not linked yet
        val stay = EbFlowCodec.resumeApprove(EbPanel(page, { t }, { t += it }), access, m.current)(EbFlowRules.working(m))
        assertEquals(EbScreen.APPROVE, stay.screen); assertFalse(stay.working); assertNull(stay.failure)
        assertTrue(page.loads.none { it.contains("banca") })   // no bank page from stored state
        assertTrue(page.shows.isEmpty())
    }

    @Test fun storedTextHoldsNoForbiddenField() {
        val text = EbFlowCodec.encode(approve).lowercase() + SetupProgressStore(dir()).also { it.saveEb(EbFlowCodec.encode(approve)) }.loadEb()!!.lowercase()
        for (bad in listOf("@", "http", "token", "session", "email", "oobcode", "appid", "pem", "password")) assertFalse(bad, bad in text)
        val keys = JSONObject(EbFlowCodec.encode(approve)).keys().asSequence().toSet()
        assertEquals(setOf("v", "stack", "checks", "chosen", "queue", "asked", "psu", "linked", "round", "open"), keys)
    }
}
