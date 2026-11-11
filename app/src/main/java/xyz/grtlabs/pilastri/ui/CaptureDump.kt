package xyz.grtlabs.pilastri.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import java.io.FileOutputStream

/**
 * The in-app half of `tools/capture-all.mjs` v2: writes the screen as XML in uiautomator's format (hierarchy/node with text, resource-id,
 * class, content-desc, checkable..selected, bounds in screen px) and a half-size PNG, with no adb round trip per screen.
 *
 * The Compose part is NOT a re-implementation of the accessibility mapping: every node's attributes come from the AccessibilityNodeInfo that
 * Compose's own accessibility provider builds (the same object uiautomator reads), only the tree walk follows the merged semantics tree. The
 * View wrappers (FrameLayout, content, ComposeView...) and every other window (popups, dialogs) are walked as views. Nodes not visible to the
 * user are skipped, as uiautomator does. Debug tooling: callers must have checked the sample-data guard ([CaptureHook]).
 */
@OptIn(InternalComposeUiApi::class)
object CaptureDump {
    private val worker by lazy { HandlerThread("capture-io").apply { start() }.let { Handler(it.looper) } }

    /** Every attached window root, the activity's first (popups and dialogs are separate windows). */
    private fun roots(activity: Activity): List<View> {
        val decor = activity.window.decorView
        val others = runCatching {
            val g = Class.forName("android.view.WindowManagerGlobal")
            val f = g.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") (f.get(g.getMethod("getInstance").invoke(null)) as List<View>)
        }.getOrDefault(emptyList()).filter { it !== decor && it.isAttachedToWindow && it.visibility == View.VISIBLE }
        return listOf(decor) + others
    }

    /** [attrs]: extra attributes on the root (the capture marks a tapped node there); `insets` = the system bars of the activity window in screen px. */
    /** Closes popups and dialogs of the app (separate windows): BACK key on each of their roots. */
    fun dismissPopups(activity: Activity) {
        roots(activity).drop(1).forEach { v ->
            runCatching { v.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK)); v.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK)) }
        }
    }

    fun xml(activity: Activity, attrs: String = ""): String {
        val sb = StringBuilder("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\"").append(insets(activity)).append(attrs).append(">")
        roots(activity).forEachIndexed { i, r -> view(r, sb, i) }
        return sb.append("</hierarchy>").toString()
    }

    /** ` insets="[left,top][right,bottom]" density="d"`: status-bar (top, with the cutout) and navigation-bar bands of the window in px (safe-area add-on). */
    fun insets(activity: Activity): String {
        val w = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView) ?: return ""

        val s = w.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()); val n = w.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
        return " insets=\"[${maxOf(s.left, n.left)},${s.top}][${maxOf(s.right, n.right)},${n.bottom}]\" density=\"${activity.resources.displayMetrics.density}\""
    }

    private fun esc(s: CharSequence?): String {
        if (s == null) return ""
        val b = StringBuilder(s.length + 8)
        for (c in s) when {
            c == '&' -> b.append("&amp;"); c == '<' -> b.append("&lt;"); c == '>' -> b.append("&gt;"); c == '"' -> b.append("&quot;")
            c == '\n' -> b.append("&#10;"); c == '\r' -> b.append("&#13;"); c == '\t' -> b.append("&#9;")
            c.code < 32 -> {}
            else -> b.append(c)
        }
        return b.toString()
    }

    private fun open(sb: StringBuilder, index: Int, text: CharSequence?, rid: String, cls: String, pkg: String, desc: CharSequence?, checkable: Boolean, checked: Boolean,
                     clickable: Boolean, enabled: Boolean, focusable: Boolean, focused: Boolean, scrollable: Boolean, longClickable: Boolean, password: Boolean,
                     selected: Boolean, b: Rect, extra: String = "", tag: String = "node") {
        sb.append('<').append(tag).append(" index=\"").append(index).append("\" text=\"").append(esc(text)).append("\" resource-id=\"").append(esc(rid)).append("\" class=\"").append(esc(cls))
            .append("\" package=\"").append(esc(pkg)).append("\" content-desc=\"").append(esc(desc)).append("\" checkable=\"").append(checkable).append("\" checked=\"").append(checked)
            .append("\" clickable=\"").append(clickable).append("\" enabled=\"").append(enabled).append("\" focusable=\"").append(focusable).append("\" focused=\"").append(focused)
            .append("\" scrollable=\"").append(scrollable).append("\" long-clickable=\"").append(longClickable).append("\" password=\"").append(password)
            .append("\" selected=\"").append(selected).append("\" bounds=\"[").append(b.left).append(',').append(b.top).append("][").append(b.right).append(',').append(b.bottom).append("]\"").append(extra).append(if (tag == "node") ">" else "/>")
    }

    private fun rectAttr(name: String, l: Int, t: Int, r: Int, b: Int) = " $name=\"[$l,$t][$r,$b]\""

    /** Visual-integrity attributes: `layout-bounds` = the node's own unclipped box on screen (visible `bounds` is already clipped by ancestors), `scroll-end` on a scroller at its end. */
    private fun extras(v: View, sn: SemanticsNode, rootLoc: IntArray): String {
        val p = sn.positionInRoot; val x = rootLoc[0] + p.x.toInt(); val y = rootLoc[1] + p.y.toInt()
        val sb = StringBuilder(rectAttr("layout-bounds", x, y, x + sn.size.width, y + sn.size.height))
        sn.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { sb.append(" scroll-end=\"").append(it.value() >= it.maxValue() - 0.5f).append('"') }
        return sb.toString()
    }

    private fun idName(v: View): String =
        if (v.id == View.NO_ID) "" else runCatching { v.resources.getResourceName(v.id) }.getOrDefault("")

    private fun view(v: View, sb: StringBuilder, index: Int) {
        if (v.visibility != View.VISIBLE) return
        if (v.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") { compose(v, sb); return }   // the host is the semantics root below
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        open(sb, index, (v as? android.widget.TextView)?.text, idName(v), v.javaClass.name, v.context.packageName, v.contentDescription, false, false, v.isClickable, v.isEnabled,
            v.isFocusable, v.isFocused, false, v.isLongClickable, false, v.isSelected, Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height))
        if (v is ViewGroup) { var k = 0; for (i in 0 until v.childCount) { val c = v.getChildAt(i); if (c.visibility == View.VISIBLE) view(c, sb, k++) } }
        sb.append("</node>")
    }

    /** The children Compose's own accessibility provider lists (replaced children plus the fake role/description nodes of merged clickables), which uiautomator walks. */
    private val replacedChildren by lazy { runCatching { SemanticsNode::class.java.getMethod("getReplacedChildren\$ui") }.getOrNull() }
    @Suppress("UNCHECKED_CAST")
    private fun kids(sn: SemanticsNode): List<SemanticsNode> =
        runCatching { replacedChildren?.invoke(sn) as? List<SemanticsNode> }.getOrNull() ?: sn.children

    private fun compose(v: View, sb: StringBuilder) {
        val owner = (v as RootForTest).semanticsOwner
        val provider: AccessibilityNodeProvider = v.accessibilityNodeProvider ?: return
        val rootId = owner.unmergedRootSemanticsNode.id
        val rootLoc = IntArray(2); v.getLocationOnScreen(rootLoc)
        var hidden = 0
        fun walk(sn: SemanticsNode, index: Int) {
            val info: AccessibilityNodeInfo = provider.createAccessibilityNodeInfo(if (sn.id == rootId) AccessibilityNodeProvider.HOST_VIEW_ID else sn.id) ?: return
            val b = Rect(); info.getBoundsInScreen(b)
            if (!info.isVisibleToUser) {
                // not drawn to the user (clipped away, off the window): kept out of the uiautomator tree, listed as a flat `hidden-node` (never matched by `<node`) so the checker can tell a hidden label from an absent one
                if (hidden < 400 && (!info.text.isNullOrBlank() || !info.contentDescription.isNullOrBlank())) {
                    hidden++
                    open(sb, index, info.text, info.viewIdResourceName ?: "", info.className?.toString() ?: "android.view.View", v.context.packageName, info.contentDescription,
                        false, false, false, true, false, false, false, false, false, false, b, extras(v, sn, rootLoc), "hidden-node")
                }
                for (c in kids(sn)) walk(c, 0)
                return
            }
            open(sb, index, info.text, info.viewIdResourceName ?: "", info.className?.toString() ?: "android.view.View", v.context.packageName, info.contentDescription,
                info.isCheckable, info.isChecked, info.isClickable, info.isEnabled, info.isFocusable, info.isFocused, info.isScrollable, info.isLongClickable, info.isPassword, info.isSelected, b, extras(v, sn, rootLoc))
            var k = 0; for (c in kids(sn)) walk(c, k++)
            sb.append("</node>")
        }
        // the UNMERGED tree, as Compose's accessibility provider exposes it to uiautomator (children of merging nodes are listed too); nodes the provider does not
        // expose (cleared semantics, covered) come back null and are skipped with their subtree
        walk(owner.unmergedRootSemanticsNode, 0)
    }

    /** Runs [r] on the capture I/O thread after every PNG queued so far is written (the DONE line goes here, so one pull gets every file). */
    fun afterIo(r: () -> Unit) { worker.post(r) }

    /** Half-size PNG of the activity window (PixelCopy); [done] runs on the main thread as soon as the pixels are copied (the PNG is encoded
     *  on the I/O thread meanwhile), or right away when the copy fails. */
    fun shot(activity: Activity, file: File, done: () -> Unit) {
        val d = activity.window.decorView
        if (d.width < 2 || d.height < 2) { done(); return }
        val bmp = Bitmap.createBitmap(d.width / 2, d.height / 2, Bitmap.Config.ARGB_8888)
        val main = Handler(Looper.getMainLooper())
        runCatching {
            PixelCopy.request(activity.window, bmp, { r ->
                main.post(done)
                if (r == PixelCopy.SUCCESS) runCatching { file.parentFile?.mkdirs(); FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                bmp.recycle()
            }, worker)
        }.onFailure { done() }
    }
}
