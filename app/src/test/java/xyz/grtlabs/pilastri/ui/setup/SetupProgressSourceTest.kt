package xyz.grtlabs.pilastri.ui.setup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G-16 (work in progress is visible) and G-15 (a control hidden for the keyboard returns with the keyboard), source side.
 * Class 1: a setup page that waits on work never passes the literal `false` as StepScaffold's `working`, except the pages that are
 * static or carry their own per-row progress (listed, with the reason). Class 2: no visibility gate on the animated keyboard inset
 * outside the shared helper `keyboardShownOrComing`.
 */
class SetupProgressSourceTest {
    private val root = File("src/main/java/xyz/grtlabs/pilastri").takeIf { it.isDirectory } ?: File("app/src/main/java/xyz/grtlabs/pilastri")
    private fun kt() = root.walkTopDown().filter { it.extension == "kt" }.toList()

    /** Titles of pages whose `working` is a literal false by design: static pages, and "Posta" whose rows carry their own progress. */
    private val staticPages = setOf("Benvenuto", "Profilo proposto", "Posta")

    private val literalFalse = Regex("""StepScaffold\([^\n]*"([^"]+)", (?:null|[^,]*to[^,]*), false,""")

    @Test fun stepScaffoldNeverHidesWork() {
        val bad = mutableListOf<String>()
        kt().filter { it.path.replace('\\', '/').contains("/ui/setup/") }.forEach { f ->
            literalFalse.findAll(f.readText()).forEach { m ->
                val t = m.groupValues[1]
                if (t !in staticPages) bad += "${f.name}: $t"
            }
        }
        assertTrue("StepScaffold with working = false on a page that waits on work: $bad", bad.isEmpty())
    }

    @Test fun pastePageTakesTheRealWorkingState() {
        val text = File(root, "ui/setup/EbLinkHelp.kt").readText()
        assertTrue(text.contains("\"Incolla il link\", null, working,"))
    }

    @Test fun noKeyboardVisibilityGateOutsideTheHelper() {
        val offenders = kt().filter { !it.name.startsWith("ImeSignal") }.filter { f ->
            f.readLines().any { l -> l.substringBefore("//").contains("WindowInsets.ime.getBottom") }
        }.map { it.name }
        assertEquals("keyboard gate must use keyboardShownOrComing()", emptyList<String>(), offenders)
        assertTrue(File(root, "ui/ImeSignal.kt").readText().contains("imeAnimationTarget"))
    }
}
