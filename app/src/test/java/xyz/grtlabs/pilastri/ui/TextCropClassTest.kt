package xyz.grtlabs.pilastri.ui

import java.io.File
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-08 class: glyphs cropped by a clip that is smaller than the text's own ink box. The 2026-11-09 capture showed the first glyph
 * of the pillar date lines cut: the whole PillarColumn was `pressable(RoundedCornerShape(16.dp))` and its last text line starts at
 * x = 0, inside the bottom-start corner arc. Machine rules, each failure lists `file:line`:
 *  1. a `pressable` with an all-corner rounded shape >= 12 dp on a Column that holds text (text meets the corner arcs);
 *  2. a `Type` style whose line height is below the font's ink box (1.1 em): a clipping parent crops the first line's top;
 *  3. `LineHeightStyle` trimming (it removes the room the ascent needs) anywhere in ui/.
 * Plus the geometry that proves rule 1 (an arc really cuts a glyph box at the corner).
 */
class TextCropClassTest {
    private val root = File("src/main/java/xyz/grtlabs/pilastri").takeIf { it.isDirectory } ?: File("app/src/main/java/xyz/grtlabs/pilastri")
    private fun kt() = File(root, "ui").walkTopDown().filter { it.extension == "kt" }.toList()
    private fun at(f: File, i: Int) = f.relativeTo(root).path.replace(File.separatorChar, '/') + ":" + (i + 1)

    /** True when the point (x, y) of a box of height [h] lies outside its bottom-start corner arc of radius [r] (all in dp). */
    private fun outsideBottomStartArc(x: Float, y: Float, h: Float, r: Float): Boolean {
        if (x >= r || y <= h - r) return false
        val dx = r - x
        val dy = y - (h - r)
        return sqrt(dx * dx + dy * dy) > r
    }

    @Test fun geometry_roundBottomCornerCutsTextAtTheEdge() {
        // The date line's first glyph: left edge x = 0..6 dp, a few dp above the bottom of a 16 dp-radius column.
        assertTrue(outsideBottomStartArc(1f, 99f, 100f, 16f))
        assertTrue(outsideBottomStartArc(2f, 96f, 100f, 16f))
        assertEquals(false, outsideBottomStartArc(16f, 99f, 100f, 16f))
        assertEquals(false, outsideBottomStartArc(1f, 84f, 100f, 16f))
    }

    @Test fun rule1_noRoundedPressableClipOnATextColumn() {
        val pressRx = Regex("""pressable\(RoundedCornerShape\((\d+)\.dp\)""")
        val columnEndRx = Regex("""\bColumn\(\s*$""")
        val columnPressRx = Regex("""\bColumn\(.*pressable""")
        val bad = ArrayList<String>()
        for (f in kt()) {
            val ls = f.readLines()
            ls.forEachIndexed { i, l ->
                val m = pressRx.find(l.substringBefore("//")) ?: return@forEachIndexed
                if (m.groupValues[1].toInt() < 12) return@forEachIndexed
                val onColumn = columnEndRx.containsMatchIn(l) || columnPressRx.containsMatchIn(l) ||
                    (i > 0 && columnEndRx.containsMatchIn(ls[i - 1].substringBefore("//")))
                if (onColumn) bad += at(f, i)
            }
        }
        assertTrue("D-08: a rounded pressable clip on a Column cuts text at the corners; round only where the surface is (${bad.size}): $bad", bad.isEmpty())
    }

    @Test fun rule2_typeLineHeightCoversTheInkBox() {
        val rx = Regex("""= st\([^,]+,\s*([\d.]+)\.sp,\s*([\d.]+)\.sp""")
        val kit = File(root, "ui/Kit.kt").readLines()
        val bad = kit.mapIndexedNotNull { i, l ->
            val m = rx.find(l) ?: return@mapIndexedNotNull null
            val size = m.groupValues[1].toFloat()
            val line = m.groupValues[2].toFloat()
            if (line < size * 1.1f) "Kit.kt:" + (i + 1) + " " + size + "sp/" + line + "sp" else null
        }
        assertTrue("D-08: Type line height below 1.1 em crops the first line's top under a clipping parent (${bad.size}): $bad", bad.isEmpty())
    }

    @Test fun rule3_noLineHeightTrim() {
        val rx = Regex("""LineHeightStyle|Trim\.(Both|FirstLineTop|LastLineBottom)""")
        val bad = kt().flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (rx.containsMatchIn(l.substringBefore("//"))) at(f, i) else null } }
        assertTrue("D-08: LineHeightStyle trim removes the room a glyph's ascent needs (${bad.size}): $bad", bad.isEmpty())
    }
}
