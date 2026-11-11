package xyz.grtlabs.pilastri.sync.eb

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the REAL page scripts of [EbPanelScripts] under node against the fake page of tools/eb-script-harness.mjs. Fails if node is missing. */
class EbScriptHarnessTest {
    private fun harness(): File {
        var d: File? = File("").absoluteFile
        while (d != null) { File(d, "tools/eb-script-harness.mjs").takeIf { it.isFile }?.let { return it }; d = d.parentFile }
        error("tools/eb-script-harness.mjs not found")
    }

    @Test fun everyScenarioHoldsOnTheRealScripts() {
        val dir = java.nio.file.Files.createTempDirectory("eb-scripts").toFile()
        try {
            mapOf("REGISTER" to EbPanelScripts.REGISTER, "ASPSPS" to EbPanelScripts.ASPSPS, "STATUS" to EbPanelScripts.STATUS, "LINK" to EbPanelScripts.LINK)
                .forEach { (n, t) -> File(dir, "$n.js").writeText(t) }
            val p = try { ProcessBuilder("node", harness().path, dir.path).redirectErrorStream(true).start() }
            catch (e: java.io.IOException) { throw AssertionError("node is required for the page-script harness: ${e.message}") }
            val out = p.inputStream.bufferedReader().readText()
            assertTrue("harness timed out", p.waitFor(60, TimeUnit.SECONDS))
            assertTrue("harness failed:\n" + out.lines().filter { !it.startsWith("ok ") }.joinToString("\n"), p.exitValue() == 0)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aScriptExceptionIsNotWordedAsTheProvidersRefusal() {
        val f = xyz.grtlabs.pilastri.ui.setup.EbFlowRules.failureOf(EbPanelResult.Failed(EbPanelFailure.INTERNAL))
        org.junit.Assert.assertEquals("Errore di Pilastri nella registrazione", f.message)
        org.junit.Assert.assertEquals("Riprova", f.kind.primary)
        org.junit.Assert.assertEquals("Apri la pagina", f.kind.secondary)
    }
}
