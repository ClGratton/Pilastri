package xyz.grtlabs.pilastri.ui.setup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.sync.DurableLog
import xyz.grtlabs.pilastri.sync.DurableLogFile

/** Failure wording per role, and the durable log. (The in-app bank page hand-off tests went with the page: BankConsentPage is removed, see BankReturnTest.) */
class BankHandoffTest {
    @Test fun aPageThatDidNotLoadIsNotAnAccessRefusal() {
        val m = EbFlowRules.fail(EbFlowModel(), EbFailure(EbFailKind.PAGE_UNREACHABLE, "Banca Alfa"))
        assertEquals("Banca Alfa non risponde", m.failure!!.message)
        assertEquals("Enable Banking non risponde", EbFailure(EbFailKind.UNREACHABLE).message)   // one formatter for the role
        assertEquals("Banca Alfa: accesso ai movimenti non autorizzato", EbFailure(EbFailKind.SCA_DENIED, "Banca Alfa").message)
    }

    @Test fun theDurableLogAppendsAcrossRecreationAndKeepsItsBound() {
        val dir = File.createTempFile("dlog", "").let { it.delete(); it.mkdirs(); it }
        var t = 1_700_000_000_000L
        DurableLogFile(dir, 400, "9.9") { t++ }.apply { write("Pilastri.EB", "one"); close() }
        DurableLogFile(dir, 400, "9.9") { t++ }.apply { write("Pilastri.EB", "two"); close() }     // a re-created logger appends
        val cur = File(dir, "pilastri-log-0.txt").readLines()
        assertEquals(2, cur.size); assertTrue(cur[0].endsWith("Pilastri.EB one")); assertTrue(cur[0].contains(" v9.9 "))
        val l = DurableLogFile(dir, 400, "9.9") { t++ }
        repeat(40) { l.write("Pilastri.EB", "line $it") }
        l.close()
        assertTrue(File(dir, "pilastri-log-0.txt").length() <= 400); assertTrue(File(dir, "pilastri-log-1.txt").length() <= 400)
        assertEquals(setOf("pilastri-log-0.txt", "pilastri-log-1.txt"), dir.list()!!.toSet())
        assertTrue(File(dir, "pilastri-log-0.txt").readText().contains("line 39"))
        dir.deleteRecursively()
    }

    @Test fun theProcessStartMarkerIsWrittenAsTheFirstLineOfARestart() {
        val dir = File.createTempFile("dlog", "").let { it.delete(); it.mkdirs(); it }
        DurableLogFile(dir, 4000, "9.9") { 1L }.apply { write("Pilastri.EB", "old"); close() }
        DurableLogFile(dir, 4000, "9.9") { 2L }.apply { write("Pilastri.App", DurableLog.startText(42, 36, false)); close() }
        val l = File(dir, "pilastri-log-0.txt").readLines()
        assertEquals(2, l.size); assertTrue(l[1].endsWith("Pilastri.App process_start pid=42 sdk=36 first_file=false"))
        dir.deleteRecursively()
    }
}
