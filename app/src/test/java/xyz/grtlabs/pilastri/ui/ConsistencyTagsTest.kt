package xyz.grtlabs.pilastri.ui

import java.io.File
import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * Source-level guard for tools/consistency-check.mjs: the checker only sees elements that carry a type tag (ui/Tags.kt).
 */
class ConsistencyTagsTest {
    private val root = File("src/main/java/xyz/grtlabs/pilastri").takeIf { it.isDirectory } ?: File("app/src/main/java/xyz/grtlabs/pilastri")
    private fun sources() = root.walkTopDown().filter { it.extension == "kt" }.toList()

    private fun stripComments(t: String): String {
        val block = Regex("/[*](?s:.*?)[*]/")
        val line = Regex("//[^\\r\\n]*")
        return t.replace(block, " ").replace(line, " ")
    }

    @Test fun everyTypeTagIsUsedByAComponent() {
        val tags = File(root, "ui/Tags.kt").readText()
        val names = Regex("const val ([A-Z_]+) = ").findAll(tags).map { it.groupValues[1] }.toList()
        // Comments are stripped (a KDoc that mentions a tag is not an application). A tag counts as applied by `UiTag.X` or by its Tags.kt modifier helper
        // (`Modifier.paneLeftTag()` for PANE_LEFT): AppRoot/Island apply the pane and dock tags through those helpers, not through the constant.
        val used = sources().filter { it.name != "Tags.kt" }.joinToString(" ") { stripComments(it.readText()) }
        fun helper(n: String) = n.lowercase().split('_').mapIndexed { i, w -> if (i == 0) w else w.replaceFirstChar { it.uppercase() } }.joinToString("") + "Tag()"
        val unused = names.filter { !(used.contains("UiTag.$it") || used.contains(helper(it))) }
        assertTrue("UiTag constants never applied to a component: $unused", unused.isEmpty())
    }

    
    @Test fun everyCardTitleGoesThroughTheTaggedComponent() {
        // Component files are excluded: CardKit.kt holds the tagged CardHeader itself; Kit.kt's CalloutBar, EmptyNote and InfoDialog
        // are not titled cards (a callout, an empty-state note and a dialog heading), so their Type.cardTitle text is a style choice, not a card title.
        val offenders = sources().filter { it.name != "CardKit.kt" && it.name != "Kit.kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (line.contains("Type.cardTitle") && !line.contains("cardTitleTag()")) "${f.name}:${i + 1}" else null
            }
        }
        assertTrue("Type.cardTitle used without the card-title tag (${offenders.size}): $offenders", offenders.isEmpty())
    }
}
