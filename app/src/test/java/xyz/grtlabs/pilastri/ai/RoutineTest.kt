package xyz.grtlabs.pilastri.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The Claude routine route (Cattedra ADR 0016) on fakes: no network, no Drive. Synthetic ids and tokens only. */
class RoutineTest {
    private val token = "sk-ant-oat01-SYNTHETIC0123456789abcdef"
    private val config = RoutineConfig("trig_SYNTH12345", token)

    @Test fun configIsReadFromWhatTheUserPastes() {
        assertEquals("trig_SYNTH12345", RoutineConfig.routineIdFrom("https://api.anthropic.com/v1/claude_code/routines/trig_SYNTH12345/fire"))
        assertEquals("trig_SYNTH12345", RoutineConfig.routineIdFrom("  trig_SYNTH12345 "))
        assertNull(RoutineConfig.routineIdFrom("https://claude.ai/code"))
        assertEquals(token, RoutineConfig.cleanToken("  $token\n"))
        assertNull(RoutineConfig.cleanToken("sk-ant-api03-notaroutinetoken000000"))
        assertNull(RoutineConfig.cleanToken("sk-ant-oat01-has space inside 00000000"))
        assertNull(RoutineConfig.from("nothing", token))
    }

    private class FakeHttp(val status: Int, val body: String = "{}", val fail: Boolean = false) : AiHttp {
        var url = ""
        var headers = emptyMap<String, String>()
        var sent = ""
        var calls = 0
        override suspend fun post(url: String, headers: Map<String, String>, body: String): AiHttpReply {
            calls++
            this.url = url; this.headers = headers; sent = body
            if (fail) throw IOException("offline")
            return AiHttpReply(status, this.body)
        }
    }

    @Test fun fireCallsTheDocumentedEndpointWithTheTokenOnlyThere() {
        val http = FakeHttp(200, """{"claude_code_session_url":"https://claude.ai/code/session_x"}""")
        val r = AiFixtures.blocking { RoutineFire(config, http).fire("ciao") }
        assertEquals(RoutineFireResult.Started("https://claude.ai/code/session_x"), r)
        assertEquals("https://api.anthropic.com/v1/claude_code/routines/trig_SYNTH12345/fire", http.url)
        assertEquals("Bearer $token", http.headers["Authorization"])
        assertEquals("2023-06-01", http.headers["anthropic-version"])
        assertEquals("ciao", JSONObject(http.sent).getString("text"))
    }

    @Test fun fireOutcomesAreMappedWithoutEchoingTheBody() {
        assertEquals(RoutineFireResult.Unauthorized, RoutineFire.parse(401, ""))
        assertEquals(RoutineFireResult.Unauthorized, RoutineFire.parse(404, ""))
        assertEquals(RoutineFireResult.Busy, RoutineFire.parse(429, ""))
        val paused = RoutineFire.parse(400, """{"error":{"message":"routine is paused $token"}}""") as RoutineFireResult.Refused
        assertTrue(paused.why.contains("paused"))
        assertTrue(!paused.why.contains(token))
        assertTrue((RoutineFire.parse(503, "<html>") as RoutineFireResult.Refused).why.contains("503"))
        assertEquals(RoutineFireResult.Offline, AiFixtures.blocking { RoutineFire(config, FakeHttp(0, fail = true)).fire("x") })
    }

    @Test fun requestsAndRepliesFollowTheWire() {
        val req = JSONObject(RoutineWire.request("20261031-101010-abcdef", "Pilastri-scambio", "domanda")!!)
        assertEquals("Pilastri-scambio", req.getString("cartella"))
        assertNull(RoutineWire.request("id", "f", "x".repeat(RoutineWire.MAX_TEXT)))
        assertEquals("risposta-abc.txt", RoutineWire.replyName("abc"))
        assertEquals(RoutineWire.Reply.Answer("Testo\nseconda riga"), RoutineWire.reply("abc", "pilastri-risposta abc\r\nTesto\r\nseconda riga\r\n"))
        assertEquals(RoutineWire.Reply.Answer("solo testo"), RoutineWire.reply("abc", "solo testo"))
        assertTrue(RoutineWire.reply("abc", "pilastri-risposta xyz\nTesto") is RoutineWire.Reply.Failed)
        assertEquals(RoutineWire.Reply.Failed("Drive non raggiungibile"), RoutineWire.reply("abc", "pilastri-errore abc\nDrive non raggiungibile"))
        assertTrue(RoutineWire.reply("abc", "   ") is RoutineWire.Reply.Failed)
        assertTrue(RoutineWire.newId().matches(Regex("\\d{8}-\\d{6}-[0-9a-f]{6}")))
    }

    private class FakeFolder(private val appearsAfter: Int, private val content: String?) : ExchangeFolder {
        override val name = "Pilastri-scambio"
        var reads = 0
        val deleted = ArrayList<String>()
        override suspend fun read(fileName: String): String? {
            reads++
            if (reads == 1) throw IOException("listing lento")
            return if (reads > appearsAfter) content?.replace("{NAME}", fileName) else null
        }
        override suspend fun delete(fileName: String) { deleted += fileName }
    }

    @Test fun askFiresOnceThenReadsAndDeletesTheReply() {
        var fires = 0
        var sent = ""
        val folder = FakeFolder(3, "pilastri-risposta id-1\nRisposta della routine.")
        val ask = RoutineAsk({ text -> fires++; sent = text; RoutineFireResult.Started("") }, folder, newId = { "id-1" }, pause = { })
        val outcome = AiFixtures.blocking { ask.ask("domanda") }
        assertEquals(RoutineOutcome.Answer("Risposta della routine."), outcome)
        assertEquals(1, fires)
        assertEquals("domanda", JSONObject(sent).getString("messaggio"))
        assertEquals(listOf("risposta-id-1.txt"), folder.deleted)
    }

    @Test fun askReportsRefusalsWithoutRetrying() {
        var fires = 0
        val folder = FakeFolder(0, "x")
        fun ask(result: RoutineFireResult) = AiFixtures.blocking { RoutineAsk({ fires++; result }, folder, newId = { "id" }, pause = { }).ask("q") }
        assertEquals(RoutineOutcome.NotAuthorized, ask(RoutineFireResult.Unauthorized))
        assertTrue((ask(RoutineFireResult.Busy) as RoutineOutcome.Failed).why.contains("troppe"))
        assertTrue((ask(RoutineFireResult.Offline) as RoutineOutcome.Failed).why.contains("rete"))
        assertEquals(3, fires)
        assertEquals(0, folder.reads)
    }

    @Test fun askGivesUpAfterTheWaitAndNeverFiresAgain() {
        var fires = 0
        var waited = 0L
        val folder = FakeFolder(Int.MAX_VALUE, null)
        val outcome = AiFixtures.blocking { RoutineAsk({ fires++; RoutineFireResult.Started("") }, folder, newId = { "id" }, pause = { waited += it }).ask("q", replyMs = 120_000) }
        assertTrue(outcome is RoutineOutcome.Failed)
        assertEquals(1, fires)
        assertTrue(waited >= 120_000)
    }

    @Test fun theRoutinePromptTreatsThirdPartyTextAsData() {
        assertTrue(RoutinePrompt.TEXT.contains("testo_esterno"))
        assertTrue(RoutinePrompt.TEXT.contains("pilastri-risposta <id>"))
        assertTrue(RoutinePrompt.TEXT.contains("disableConversionToGoogleType"))
    }
}
