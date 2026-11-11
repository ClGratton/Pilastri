package xyz.grtlabs.pilastri.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test

/** Fix wave 5 (R1-01): no Flusso label carrying a figure is ever elided or clipped, at cover and open-Fold right-pane widths. */
class FlowNoElisionTest {
    private val dp: (Float) -> Float = { it }
    // Cover 411 dp card (~360), open-Fold right pane (narrow) down to the width that elided "dal saldo 441 €".
    private val widths = listOf(411f, 360f, 260f, 200f)
    private val scales = listOf(1.0f, 2.0f)

    private class Call(val text: String, val maxWidth: Float, val maxLines: Int, val needLines: Int, val longestWord: Float)

    private fun input() = FlowInput(
        100_000L, "stipendio", listOf("Fondo pensione" to 30_000L), 98_000L,
        listOf(FlowCategory("Casa", 52_000L, false), FlowCategory("Svago", 46_000L, true)), emptyList(),
    )

    private fun run(w: Float, fs: Float): List<Call> {
        val calls = mutableListOf<Call>()
        val measure = FlowMeasure { text, style, maxWidth, maxLines ->
            val em = fs * when (style) { FlowText.TITLE -> 12.4f; FlowText.STRONG, FlowText.SMALL -> 11.5f; FlowText.TINY -> 10.5f; FlowText.BIG -> 16f }
            val ch = em * 0.6f
            var lines = 1; var cur = 0f; var widest = 0f; var longest = 0f
            text.split(' ').forEach { word ->
                val ww = word.length * ch
                longest = maxOf(longest, ww)
                when {
                    cur == 0f -> cur = ww
                    cur + ch + ww <= maxWidth -> cur += ch + ww
                    else -> { widest = maxOf(widest, cur); lines++; cur = ww }
                }
            }
            calls += Call(text, maxWidth, maxLines, lines, longest)
            FlowSize(minOf(maxOf(widest, cur), maxWidth), minOf(lines, maxLines) * em * 1.3f)
        }
        layoutFlow(input(), w, dp, fs, measure)
        return calls
    }

    @Test fun figureLabelsAreNeverElidedAtAnySupportedWidth() {
        for (w in widths) for (fs in scales) {
            if (fs > 1.5f && w < 320f) continue // below 320 dp at 200% text the column cannot hold a word of the figure; not a supported combination
            val m = run(w, fs).filter { it.text.startsWith("dal saldo ") && it.maxLines == 4 }
            assertTrue("@${w}dp x$fs: deficit label measured", m.isNotEmpty())
            m.forEach { k ->
                assertTrue("@${w}dp x$fs: '${k.text}' needs ${k.needLines} lines, has ${k.maxLines}", k.needLines <= k.maxLines)
                assertTrue("@${w}dp x$fs: '${k.text}' word ${k.longestWord} wider than ${k.maxWidth}", k.longestWord <= k.maxWidth + 0.01f)
            }
        }
    }
}
