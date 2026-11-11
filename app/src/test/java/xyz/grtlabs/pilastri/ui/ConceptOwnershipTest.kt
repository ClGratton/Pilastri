package xyz.grtlabs.pilastri.ui

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-76 one implementation per concept, CR-77 every gesture with a commit fraction uses the shared haptic helper, CR-75 (source side) subtitles are joined with NBSP.
 * User, 2026-11-09 ~17:25: "evita che stessi concetti abbiano codice diverso"; ~17:10: the threshold haptic "vale per tutta app".
 * Config: tools/concept-owners.json (owners, signatures, known second implementations with reasons). Source scan of app/src/main, like SourceRulesTest.
 * Must-flag fixtures in src/test/resources/concepts (bad, bad-gesture) and their good twins: a rule that stops flagging them fails here.
 * Report: app/build/layout-consistency/concepts.md and gestures.md.
 */
class ConceptOwnershipTest {
    private val root = File("src/main/java/xyz/grtlabs/pilastri").takeIf { it.isDirectory } ?: File("app/src/main/java/xyz/grtlabs/pilastri")
    private val resources = File("src/test/resources/concepts").takeIf { it.isDirectory } ?: File("app/src/test/resources/concepts")
    private val config: JSONObject = run {
        val f = listOf(File("tools/concept-owners.json"), File("../tools/concept-owners.json"), File("../../tools/concept-owners.json")).first { it.exists() }
        JSONObject(f.readText())
    }

    private class Sig(val re: Regex, val near: Regex?)
    private fun sigs(a: JSONArray) = (0 until a.length()).map { i ->
        val o = a.get(i)
        if (o is JSONObject) Sig(Regex(o.getString("re")), o.optString("near").takeIf { it.isNotEmpty() }?.let { Regex(it) }) else Sig(Regex(o as String), null)
    }
    private fun code(l: String) = l.substringBefore("//")
    private fun active(l: String): Boolean { val t = l.trimStart(); return !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ")) }

    /** Lines (1-based) of [text] that carry a signature (a `near` signature also needs its second regex within 15 lines). */
    private fun hits(text: String, sigs: List<Sig>): List<Pair<Int, String>> {
        val lines = text.lines(); val out = ArrayList<Pair<Int, String>>()
        lines.forEachIndexed { i, l ->
            if (!active(l)) return@forEachIndexed
            val c = code(l)
            for (s in sigs) if (s.re.containsMatchIn(c)) {
                if (s.near != null && (maxOf(0, i - 15)..minOf(lines.size - 1, i + 15)).none { j -> j != i && active(lines[j]) && s.near.containsMatchIn(code(lines[j])) }) continue
                out += (i + 1) to l.trim().take(110); break
            }
        }
        return out
    }

    private class Src(val rel: String, val text: String)
    private fun main(): List<Src> = root.walkTopDown().filter { it.extension == "kt" }.map { Src(it.relativeTo(root).path.replace('\\', '/'), it.readText()) }.toList()
    private fun fixtures(dir: String): List<Src> = File(resources, dir).walkTopDown().filter { it.isFile && it.name.endsWith(".kt.txt") }
        .map { Src(it.relativeTo(File(resources, dir)).path.replace('\\', '/').removeSuffix(".txt"), it.readText()) }.toList()
    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    /** Offenders of "one implementation per concept" among [files]; [useKnown] applies the config's known second implementations. */
    private fun conceptOffenders(files: List<Src>, useKnown: Boolean): List<String> {
        val out = ArrayList<String>(); val cs = config.getJSONArray("concepts")
        for (k in 0 until cs.length()) {
            val c = cs.getJSONObject(k); val name = c.getString("name")
            val owners = strings(c.getJSONArray("owners")).toSet()
            val known = c.optJSONArray("known")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("file") }.toSet() }.orEmpty()
            val s = sigs(c.getJSONArray("signatures"))
            for (f in files) {
                if (f.rel in owners || (useKnown && f.rel in known)) continue
                for ((n, l) in hits(f.text, s)) out += "[$name] ${f.rel}:$n  $l   (owner: ${owners.joinToString()})"
            }
        }
        return out
    }

    /** Top-level declarations (column-0 fun/class/object) of a file as (declaration line, first line number, text). */
    private fun segments(text: String): List<Triple<String, Int, String>> {
        val lines = text.lines()
        val decl = Regex("^(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:private|internal|public|inline|override|abstract|open)\\s+)*(?:fun|class|object)\\b")
        val starts = lines.indices.filter { decl.containsMatchIn(lines[it]) }
        if (starts.isEmpty()) return listOf(Triple("(file)", 1, text))
        return starts.mapIndexed { i, s -> Triple(lines[s].trim().take(70), s + 1, lines.subList(s, if (i + 1 < starts.size) starts[i + 1] else lines.size).joinToString("\n")) }
    }

    private fun gestureOffenders(files: List<Src>, useExceptions: Boolean): List<String> {
        val g = config.getJSONObject("gestures"); val s = sigs(g.getJSONArray("signatures")); val helper = Regex(g.getString("helper"))
        val ex = g.getJSONArray("exceptions").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val out = ArrayList<String>()
        for (f in files) for ((name, line, body) in segments(f.text)) {
            val h = hits(body, s); if (h.isEmpty()) continue
            if (body.lines().any { active(it) && helper.containsMatchIn(code(it)) }) continue
            if (useExceptions && ex.any { it.getString("file") == f.rel && name.contains(it.getString("segment")) }) continue
            out += "${f.rel}:${line + h.first().first - 1}  in `$name`  ${h.first().second}"
        }
        return out
    }

    private fun report(name: String, rows: List<String>) {
        val d = File("build").takeIf { it.isDirectory }?.let { File(it, "layout-consistency") } ?: File("app/build/layout-consistency"); d.mkdirs()
        File(d, "$name.md").writeText("# $name\n\n" + (if (rows.isEmpty()) "no offenders\n" else rows.joinToString("\n") { "- $it" } + "\n"))
    }

    @Test fun cr76_fixtureBadIsFlagged() {
        val bad = conceptOffenders(fixtures("bad"), useKnown = false)
        assertTrue("CR-76 must-flag: pre-wave9 Home.kt (rememberHeroStrip, a second PeriodState factory, heroFade position) is not flagged: $bad", bad.any { it.contains("[period strip]") && it.contains("Home.kt") })
        assertTrue("CR-76 must-flag: a second PeriodState factory in another file is not flagged: $bad", bad.any { it.contains("Other.kt") && it.contains("PeriodState") })
    }

    @Test fun cr76_fixtureGoodTwinIsClean() {
        val bad = conceptOffenders(fixtures("good"), useKnown = false)
        assertTrue("CR-76 good twin (a screen that only passes parameters to the shared strip) must be clean: $bad", bad.isEmpty())
    }

    @Test fun cr76_everyKnownExceptionCarriesADecision() {
        val cs = config.getJSONArray("concepts"); val bad = ArrayList<String>()
        for (k in 0 until cs.length()) { val c = cs.getJSONObject(k); val a = c.optJSONArray("known") ?: continue
            for (i in 0 until a.length()) { val e = a.getJSONObject(i); if (e.optString("decision").isBlank() || e.optString("reason").isBlank()) bad += "${c.getString("name")}: ${e.optString("file")}" } }
        assertTrue("CR-76: every known second implementation needs a reason and a decision (the user's, or the pending-owner note): $bad", bad.isEmpty())
    }

    @Test fun cr76_everyConceptHasOneImplementation() {
        val files = main(); val bad = conceptOffenders(files, useKnown = true)
        report("concepts", bad)
        // a stale config would pass vacuously: every concept's owner file(s) must still carry at least one of its signatures
        val cs = config.getJSONArray("concepts"); val stale = ArrayList<String>()
        for (k in 0 until cs.length()) {
            val c = cs.getJSONObject(k); val s = sigs(c.getJSONArray("signatures"))
            if (strings(c.getJSONArray("owners")).none { o -> files.firstOrNull { it.rel == o }?.let { hits(it.text, s).isNotEmpty() } == true }) stale += c.getString("name")
        }
        assertTrue("CR-76: the owner file(s) of $stale carry none of the concept's signatures (config stale)", stale.isEmpty())
        assertTrue("CR-76: a concept is implemented outside its owner (${bad.size}); use the owner's component or move the code there, or record a known exception with its reason in tools/concept-owners.json:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    @Test fun cr77_fixtureBadGestureIsFlagged() {
        val bad = gestureOffenders(fixtures("bad-gesture"), useExceptions = false)
        assertTrue("CR-77 must-flag: a drag with a commit fraction and no shared haptic helper is not flagged: $bad", bad.any { it.contains("SwipeToDismissBad") })
    }

    @Test fun cr77_fixtureGoodGestureTwinIsClean() {
        val bad = gestureOffenders(fixtures("good-gesture"), useExceptions = false)
        assertTrue("CR-77 good twin (same drag, calls rememberCommitHaptics) must be clean: $bad", bad.isEmpty())
    }

    @Test fun cr77_everyGestureUsesTheSharedCommitHaptics() {
        val bad = gestureOffenders(main(), useExceptions = true)
        report("gestures", bad)
        assertTrue("CR-77: gestures without rememberCommitHaptics/CommitHaptics (${bad.size}); wire the shared helper (ui/CommitHaptics.kt) or record an exception with its reason in tools/concept-owners.json:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    /** CR-75 source side (wave 10): ui/BoundText.kt `bindSeparators` is the single owner of the NBSP before " · " and the `ui.Text` wrappers use it; no source draws a label through material3.Text directly. */
    @Test fun cr75_pageHeaderSubtitlesAreJoinedWithNbsp() {
        val owner = File(root, "ui/BoundText.kt").readText()
        assertTrue("CR-75: BoundText.kt must define bindSeparators( replacing the separator with NBSP + dot", owner.contains("fun bindSeparators(") && owner.contains("replace(\" · \", \" · \")"))
        assertTrue("CR-75: the String Text wrapper must call bindSeparators", owner.contains("bindSeparators(text)"))
        val star = Regex("^[ 	]*import androidx[.]compose[.]material3[.][*][ 	]*$")
        val own = "import xyz.grtlabs.pilastri.ui.Text"
        val bypass = main().filter { it.rel != "ui/BoundText.kt" }.filter { s ->
            val ls = s.text.lines()
            val samePkg = ls.firstOrNull { it.startsWith("package ") }?.trim() == "package xyz.grtlabs.pilastri.ui"
            ls.any { it.trim() == "import androidx.compose.material3.Text" || code(it).contains("androidx.compose.material3.Text(") } ||
                (!samePkg && ls.any { star.containsMatchIn(it) } && ls.none { it.trim() == own })
        }.map { it.rel }
        assertTrue("CR-75: these files draw text through material3.Text and bypass the NBSP binding (import xyz.grtlabs.pilastri.ui.Text): $bypass", bypass.isEmpty())
    }

    @Test fun connectAccountsFlowHasOneComponentAndOneEntryPoint() {
        // User, 2026-11-09: connecting accounts after setup is the SAME step component as in onboarding, not a copy.
        val bad = conceptOffenders(fixtures("bad"), useKnown = false)
        assertTrue("must-flag: a second connect-accounts flow in a screen is not flagged: $bad", bad.any { it.contains("[connect accounts flow]") && it.contains("ConnectCopy.kt") })
        // Wave 16: one EB setup flow; a second panel driver (store or link_accounts outside EbPanel) is flagged.
        assertTrue("must-flag: a second Enable Banking setup is not flagged: $bad", bad.any { it.contains("[enable banking setup (native panel)]") && it.contains("EbSignUpCopy.kt") })
        val files = main()
        assertTrue("The entry point 'Collega un conto' must exist in Impostazioni > Conti", files.any { it.rel == "ui/screens/Settings.kt" && it.text.contains("\"Collega un conto\"") && it.text.contains("flow.open(null)") })
        val openers = files.filter { it.rel.startsWith("ui/screens/") }.sumOf { f -> Regex("flow[.]open[(]null[)]").findAll(f.text).count() }
        assertTrue("Exactly one screen entry opens the shared bank run (flow.open(null)), found $openers", openers == 1)
    }
}

