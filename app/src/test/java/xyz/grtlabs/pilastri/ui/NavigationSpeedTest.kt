package xyz.grtlabs.pilastri.ui

import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.debug.SampleArchive
import xyz.grtlabs.pilastri.ui.screens.flowMonths

/**
 * Wave 13 (user 2026-11-09: switching to Pilastri waited 1 s before animating). SKILL §2 "Navigation speed": a destination
 * starts its transition within one frame budget, so no heavy work runs on its first composition.
 *  1. Per-destination read-model budget on a 20x archive (warm JIT, best of several runs).
 *  2. Source rule: in a @Composable body, an archive read model, or a JSON / asset / SharedPreferences read, runs on the main
 *     thread at first composition unless it sits inside remember/produceState (read models) and is never a JSON/asset parse.
 */
class NavigationSpeedTest {
    private val budgetMs = 8.0
    private fun scaled(base: Archive, k: Int): Archive {
        val rows = base.externalMovements
        val more = (1..k).flatMap { n -> rows.map { it.copy(id = it.id + "#$n", date = it.date.minusDays((n % 300).toLong())) } }
        return base.copy(externalMovements = rows + more)
    }
    private fun best(f: () -> Unit): Double {
        repeat(5) { f() }
        return (1..7).minOf { val s = System.nanoTime(); f(); (System.nanoTime() - s) / 1e6 }
    }

    @Test fun destinationReadModelsStayWithinBudget() {
        val today = LocalDate.now()
        val a = scaled(SampleArchive.build(today), 20)
        val cover = a.liquidityCoverage()
        val destinations = mapOf<String, () -> Unit>(
            "Pilastri" to {
                a.attivitaSum(); a.realCard(); a.freshness(today); a.newestValueDate(); a.attivitaLine(today); a.incomeArrival(today); a.briefing(today)
                a.dataMonths(today); a.flowMonths(today, cover); a.monthValueChange(YearMonth.from(today)); a.monthVersamenti(YearMonth.from(today))
            },
            "Spese" to { a.spendLedger() },
        )
        val over = destinations.mapValues { best(it.value) }.filter { it.value > budgetMs }
        destinations.keys.forEach { println("NAVSPEED $it ${"%.2f".format(best(destinations.getValue(it)))} ms") }
        assertTrue("first-composition read model over $budgetMs ms on a 20x archive: $over", over.isEmpty())
    }

    @Test fun composableBodiesDoNoHeavyWork() {
        val main = File("src/main/java/xyz/grtlabs/pilastri")
        val readModels = main.resolve("data").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> Regex("""fun Archive\.(\w+)\(""").findAll(f.readText()).map { it.groupValues[1] } }
            .filter { it !in setOf("copy", "equals", "hashCode", "toString", "categoryName", "budgetFor") && !it.startsWith("with") && !it.startsWith("without") }.toSet()
        val heavy = Regex("""assets\.open|\.readText\(|JSONObject\(|JSONArray\(|Rules\.fromAssets|Rules\.fromJson|getSharedPreferences|Json\.decode""")
        val model = Regex("""\.(${readModels.joinToString("|")})\(""")
        val deferred = Regex("""remember\w*\(|produceState|derivedStateOf|LaunchedEffect|DisposableEffect|onClick|clickable|scope\.launch|\.commit\b|commit\s*\{|app\.go""")
        val allow = File("src/test/resources/nav-speed-allow.txt").takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") }.orEmpty().toSet()
        val bad = ArrayList<String>()
        main.resolve("ui").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val lines = f.readLines()
            var i = 0
            while (i < lines.size) {
                if (lines[i].trim() == "@Composable" || lines[i].trim().startsWith("@Composable ")) {
                    var j = i; var depth = 0; var started = false; var guard = 0 // guard: remember-style block being tracked
                    while (j < lines.size) {
                        val l = lines[j]; val code = l.substringBefore("//")
                        if (started && j > i) {
                            val key = "${f.name}:${l.trim()}"
                            if (key !in allow) {
                                if (heavy.containsMatchIn(code)) bad += "$key  [heavy read in composable]"
                                else if (guard == 0 && model.containsMatchIn(code) && !deferred.containsMatchIn(code) && !code.contains("model.") ) bad += "$key  [un-remembered archive read model]"
                            }
                        }
                        if (deferred.containsMatchIn(code) && guard == 0 && code.count { it == '{' } > code.count { it == '}' }) guard = 1 .also { _ -> }
                        // brace accounting for the function and for a remember-like block that opens on one line
                        for (c in code) { if (c == '{') { depth++; started = true; if (guard > 0) guard++ } else if (c == '}') { depth--; if (guard > 0) guard-- } }
                        if (guard == 1) guard = 0
                        if (started && depth <= 0) break
                        j++
                    }
                    i = j
                }
                i++
            }
        }
        assertTrue("heavy work inside @Composable bodies (fix, or justify in src/test/resources/nav-speed-allow.txt):\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
