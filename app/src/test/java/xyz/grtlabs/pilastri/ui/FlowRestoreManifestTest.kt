package xyz.grtlabs.pilastri.ui

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K28 / CR-95 / DESIGN G-21, source side: leaving the app never resets a flow (user, 2026-11-10: "Avevo fatto apro banca, stava lavorando,
 * e quando ho aperto si è resettato. E chissà in quanti posti sta roba non va.").
 * Same rules as design-method/scripts/addons/state-restore.mjs over app/src/main, config tools/state-restore.json. Fails on: a rule line that is
 * neither allowed nor inside a flow owner range; an allow entry without reason or anchor; a flow file with no flow entry; a named test that does not
 * exist under tests_root; a flow with neither a test nor a known_lost item for a trigger. known_lost items never fail (they are the debt list).
 * Line-based: it sees neither state assigned through helpers nor a restore that is declared and never wired; the executed restore tests are the proof.
 */
class FlowRestoreManifestTest {
    private val triggers = listOf("background", "recreate", "process", "link")
    private val configFile = listOf(File("tools/state-restore.json"), File("../tools/state-restore.json"), File("../../tools/state-restore.json")).first { it.exists() }
    private val base: File = configFile.absoluteFile.parentFile!!.parentFile!!
    private val cfg = JSONObject(configFile.readText())

    private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
    private fun objects(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }
    private fun norm(p: String) = p.replace('\\', '/')
    private fun sameFile(rel: String, file: String) = rel == file || rel.endsWith("/$file")

    private class Src(val rel: String, val lines: List<String>)
    private val sources: List<Src> = strings(cfg.optJSONArray("roots")).flatMap { r ->
        val root = File(base, r)
        root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.map { Src(norm(it.relativeTo(root).path), it.readText().split(Regex("\r?\n"))) }.toList()
    }
    private val testFiles: List<Pair<String, String>> = File(base, cfg.optString("tests_root", "tests")).walkTopDown()
        .filter { it.isFile && it.extension in setOf("kt", "java") }.map { norm(it.path) to it.readText() }.toList()

    private fun stripComment(l: String) = l.replace(Regex("//.*$"), "")
    private fun isComment(l: String): Boolean { val t = l.trimStart(); return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ") }

    private class Hit(val rule: String, val rel: String, val line: Int, val text: String)
    private fun scan(): List<Hit> {
        val out = mutableListOf<Hit>()
        for (s in sources) for (r in objects(cfg.optJSONArray("rules"))) {
            val re = Regex(r.getString("regex"), if (r.optString("flags").contains('i')) setOf(RegexOption.IGNORE_CASE) else emptySet())
            val near = r.optString("near").takeIf { it.isNotEmpty() }?.let { Regex(it) }
            val until = r.optString("until").takeIf { it.isNotEmpty() }?.let { Regex(it) }
            val window = r.optInt("window", 12)
            s.lines.forEachIndexed { i, raw ->
                if (isComment(raw) || !re.containsMatchIn(stripComment(raw))) return@forEachIndexed
                if (near != null) {
                    var hit = false
                    var j = i
                    while (j < minOf(s.lines.size, i + 1 + window)) {
                        if (j > i && until != null && until.containsMatchIn(s.lines[j])) break
                        if (!isComment(s.lines[j]) && near.containsMatchIn(stripComment(s.lines[j]))) { hit = true; break }
                        j++
                    }
                    if (!hit) return@forEachIndexed
                }
                out += Hit(r.getString("id"), s.rel, i + 1, raw.trim())
            }
        }
        return out
    }

    private class Owner(val file: String, val from: Int, val to: Int)
    private fun owners(flow: JSONObject): List<Owner> {
        val raw = flow.opt("owner")
        val list = when (raw) { is JSONArray -> strings(raw); is String -> listOf(raw); else -> emptyList() }
        return list.map { o ->
            val m = Regex("^(.*?)(?::(\\d+)-(\\d+))?$").find(norm(o))!!
            Owner(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].toIntOrNull() ?: Int.MAX_VALUE)
        }
    }

    private fun testExists(spec: String): Boolean {
        val cls = spec.substringBefore('#'); val m = spec.substringAfter('#', "")
        return testFiles.any { (rel, text) ->
            (rel.substringAfterLast('/').removeSuffix(".kt").removeSuffix(".java") == cls || Regex("\\b(class|object)\\s+$cls\\b").containsMatchIn(text)) &&
                (m.isEmpty() || Regex("(fun|void)\\s+`?$m\\b").containsMatchIn(text))
        }
    }

    @Test fun everyFlowIsListedAndEveryTriggerHasATestOrAKnownLoss() {
        val flows = objects(cfg.optJSONArray("flows")); val allow = objects(cfg.optJSONArray("allow"))
        val problems = mutableListOf<String>()
        for (a in allow) {
            if (a.optString("reason").isBlank()) problems += "allow without reason: ${a.optString("rule")} ${a.optString("file")}"
            if (a.optString("symbol").isBlank() && a.optString("line_text").isBlank()) problems += "allow without anchor: ${a.optString("rule")} ${a.optString("file")}"
        }
        for (h in scan()) {
            val covered = flows.any { f -> owners(f).any { sameFile(h.rel, it.file) && h.line in it.from..it.to } }
            val allowed = allow.any { a ->
                a.optString("reason").isNotBlank() && a.optString("rule") == h.rule && sameFile(h.rel, norm(a.optString("file"))) &&
                    ((a.optString("line_text").isNotEmpty() && h.text.contains(a.optString("line_text"))) || (a.optString("symbol").isNotEmpty() && h.text.contains(a.optString("symbol"))))
            }
            if (!covered && !allowed) problems += "${h.rule} ${h.rel}:${h.line} ${h.text.take(110)} (not in a flow, not allowed)"
        }
        val pats = strings(cfg.optJSONArray("flow_file_patterns")).map { Regex(it) }
        for (s in sources) if (pats.any { it.containsMatchIn(s.rel) } && flows.none { f -> owners(f).any { sameFile(s.rel, it.file) } }) problems += "flow file with no flow entry: ${s.rel}"
        for (f in flows) {
            val tests = f.optJSONObject("tests") ?: JSONObject()
            val lost = objects(f.optJSONArray("known_lost"))
            for (t in triggers) {
                val spec = tests.optString(t)
                if (spec.isNotEmpty()) { if (!testExists(spec)) problems += "test missing: ${f.getString("flow")}/$t -> $spec" }
                else if (lost.none { it.optString("trigger") == t }) problems += "trigger uncovered: ${f.getString("flow")}/$t (no test, no known_lost)"
            }
            lost.filter { it.optString("trigger") !in triggers }.forEach { problems += "known_lost with an unknown trigger: ${f.getString("flow")}/${it.optString("trigger")}" }
        }
        assertTrue("state-restore manifest (K28):\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
