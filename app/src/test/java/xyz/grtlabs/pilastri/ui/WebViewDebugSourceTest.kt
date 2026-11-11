package xyz.grtlabs.pilastri.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every in-app WebView is made by sync/WebViewDebug.create, the one owner of WebView debugging (user, 2026-11-09: no HTML capture was possible). */
class WebViewDebugSourceTest {
    private val root = File("src/main/java/xyz/grtlabs/pilastri").takeIf { it.isDirectory } ?: File("app/src/main/java/xyz/grtlabs/pilastri")
    private val files = root.walkTopDown().filter { it.extension == "kt" && it.name != "WebViewDebug.kt" }.toList()
    private fun active(l: String) = l.trimStart().let { !(it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.startsWith("import ")) }

    @Test fun noWebViewIsBuiltOrDebuggingSwitchedOutsideTheHelper() {
        val ctor = Regex("(?<![A-Za-z.])WebView[(]")
        val bad = files.flatMap { f -> f.readLines().withIndex().filter { (_, l) -> active(l) && (ctor.containsMatchIn(l.substringBefore("//")) || l.contains("setWebContentsDebuggingEnabled")) }
            .map { (i, l) -> "${f.name}:${i + 1}  ${l.trim().take(100)}" } }
        assertTrue("Use WebViewDebug.create(context) for every WebView: $bad", bad.isEmpty())
        assertTrue("The helper must exist", File(root, "sync/WebViewDebug.kt").readText().contains("BuildConfig.WEBVIEW_DEVTOOLS"))
    }
}
