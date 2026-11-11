package xyz.grtlabs.pilastri.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chart labels are derived outside the draw phase (ChartCanvas.collectLabels): no label recording and no snapshot-state write
 * inside a draw lambda or a DrawScope function of the ui/charts folder and the chart files of ui/screens (SpendCharts.kt, Flow.kt).
 * Scans the bodies of `Canvas(...) { }`, `drawBehind { }`, `drawWithContent { }`, `drawWithCache { }`, `ChartCanvas(...) { }` and
 * `fun DrawScope.xxx(...)`; ChartCanvas.kt itself is the only file that may name ChartLabels.
 */
class ChartDrawRulesTest {
    private val root = File("src/main/java/xyz/grtlabs/pilastri").takeIf { it.isDirectory } ?: File("app/src/main/java/xyz/grtlabs/pilastri")
    private val ui = File(root, "ui")
    private val files get() = File(ui, "charts").walkTopDown().filter { it.extension == "kt" }.toList() + File(ui, "screens/SpendCharts.kt") + File(ui, "screens/Flow.kt")

    private val callStarts = Regex("""(?<!fun )\b(Canvas|ChartCanvas|drawBehind|drawWithContent|drawWithCache)\b\s*([({])""")
    private val drawFuns = Regex("""fun\s+DrawScope\.\w+""")
    private val forbidden = listOf(
        Regex("""\bChartLabels\b"""), Regex("""\bChartLabelSink\b"""), Regex("""\bmutableStateOf\b"""),
        Regex("""\b\w+\.value\s*=[^=]"""), Regex("""\.snapTo\("""), Regex("""\bpublished\b"""),
        Regex("""\b\w+\.add\(\s*DrawnLabel"""),
    )
    private val nl = '\n'

    private fun balanced(s: String, open: Int): String {
        val o = s[open]; val c = if (o == '{') '}' else ')'
        var d = 0
        for (i in open until s.length) { if (s[i] == o) d++ else if (s[i] == c) { d--; if (d == 0) return s.substring(open, i + 1) } }
        return s.substring(open)
    }

    private fun lineOf(src: String, index: Int) = src.substring(0, index).count { it == nl } + 1

    /** (description, body) of every draw lambda and DrawScope function in [src]. */
    private fun drawBodies(src: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (m in callStarts.findAll(src)) {
            var p = m.range.last
            if (src[p] == '(') { p += balanced(src, p).length; while (p < src.length && src[p].isWhitespace()) p++ }
            if (p < src.length && src[p] == '{') out.add("${m.groupValues[1]} lambda @${lineOf(src, m.range.first)}" to balanced(src, p))
        }
        for (m in drawFuns.findAll(src)) {
            val paren = src.indexOf('(', m.range.last)
            var p = paren + balanced(src, paren).length
            while (p < src.length && src[p] != '{' && src[p] != '=') p++
            val text = if (p < src.length && src[p] == '{') balanced(src, p)
            else src.substring(p, src.indexOf("$nl$nl", p).let { if (it < 0) src.length else it })
            out.add("${m.value} @${lineOf(src, m.range.first)}" to text)
        }
        return out
    }

    @Test fun noLabelRecordingOrStateWriteInDrawLambdas() {
        val bad = ArrayList<String>()
        for (f in files) {
            val src = f.readLines().joinToString("$nl") { it.substringBefore("//") }
            val isCanvasFile = f.name == "ChartCanvas.kt"
            for ((what, b) in drawBodies(src)) for (r in forbidden) r.find(b)?.let { hit ->
                // ChartCanvas.kt's drawChartText reads `ChartLabels.collecting` by design (a plain list set only by the label pass).
                if (!(isCanvasFile && what.startsWith("fun DrawScope.drawChartText"))) bad.add("${f.name}: `${hit.value.trim()}` in $what")
            }
            if (!isCanvasFile && Regex("""\bChartLabels\b|\bChartLabelSink\b""").containsMatchIn(src)) bad.add("${f.name}: names ChartLabels/ChartLabelSink (only ChartCanvas.kt may)")
        }
        assertTrue("label recording or state write inside a draw lambda (${bad.size}): $bad", bad.isEmpty())
    }

    /** The scanner itself: it flags a recording and a state write inside a multi-line Canvas call's lambda and a DrawScope function. */
    @Test fun scannerFlagsTheForbiddenShapes() {
        val src = "Canvas($nl  Modifier.pointerInput(1) { ok() },$nl) { sink.add(DrawnLabel(a)); x.value = 1f }${nl}fun DrawScope.foo(a: Int) = ChartLabels.record(1)"
        val bodies = drawBodies(src)
        assertTrue("found ${bodies.map { it.first }}", bodies.size == 2)
        assertTrue(forbidden.count { it.containsMatchIn(bodies[0].second) } >= 2 && forbidden.any { it.containsMatchIn(bodies[1].second) })
    }
}
