package xyz.grtlabs.pilastri.sync.auto

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import java.lang.ref.WeakReference
import xyz.grtlabs.pilastri.MainActivity

/**
 * Keeps the background sync's WebView attached to the app's own main window, out of sight: behind the opaque app content, in a
 * one-pixel clip, with no touches, focus, autofill or accessibility. Attached, Chromium treats the page as a visible tab (layout,
 * timers and rendering run as in a browser, unlike a never-attached view), and in debug builds DevTools lists it.
 *
 * Only [MainActivity] hosts it, never a provider page (those hold their own visible sessions). The host follows the activity: on
 * a recreation (fold, unfold) the WebView leaves the destroyed window and joins the new one; with no host it keeps working,
 * unattached. Main thread only.
 */
internal object HiddenWebHost : Application.ActivityLifecycleCallbacks {
    private var installed = false
    private var host: WeakReference<Activity>? = null
    private val webs = LinkedHashSet<WebView>()

    /** Registers the activity callbacks once; [context] that is the started main activity becomes the host now. */
    fun install(context: Context) {
        if (!installed) {
            (context.applicationContext as? Application)?.let { it.registerActivityLifecycleCallbacks(this); installed = true }
        }
        (context as? Activity)?.let(::offer)
    }

    /** True when [web] is now in a window (false: no main activity at the moment; it joins the next one that starts). */
    fun attach(web: WebView): Boolean {
        web.isFocusable = false
        web.isFocusableInTouchMode = false
        web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        webs += web
        val a = host?.get() ?: return false
        return place(web, a)
    }

    fun detach(web: WebView) {
        webs -= web
        val frame = web.parent as? Frame ?: return
        (frame.parent as? ViewGroup)?.removeView(frame)
        frame.removeView(web)
    }

    fun isAttached(web: WebView): Boolean = web.isAttachedToWindow

    private fun eligible(a: Activity) = a is MainActivity && !a.isFinishing && !a.isDestroyed

    private fun offer(a: Activity) {
        if (!eligible(a) || host?.get() === a) return
        host = WeakReference(a)
        webs.forEach { place(it, a) }
    }

    private fun place(web: WebView, a: Activity): Boolean {
        val decor = a.window?.decorView as? ViewGroup ?: return false
        val frame = web.parent as? Frame ?: Frame(a.applicationContext).also { f ->
            (web.parent as? ViewGroup)?.removeView(web)
            // The size the page was laid out at (a phone, see WebLoginDriver.offscreenWebView); the frame clips it to one pixel.
            f.addView(web, FrameLayout.LayoutParams(web.width.coerceAtLeast(1), web.height.coerceAtLeast(1)))
        }
        if (frame.parent === decor) return true
        (frame.parent as? ViewGroup)?.removeView(frame)
        // Index 0: drawn first, under the window's content root (the app's opaque Compose screen).
        decor.addView(frame, 0, FrameLayout.LayoutParams(1, 1))
        return true
    }

    override fun onActivityStarted(activity: Activity) = offer(activity)

    override fun onActivityDestroyed(activity: Activity) {
        if (host?.get() !== activity) return
        host = null
        webs.forEach { w -> (w.parent as? Frame)?.let { f -> (f.parent as? ViewGroup)?.removeView(f) } }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    /** The one-pixel clip: takes no touch, focus, autofill or accessibility for itself or the page inside. */
    private class Frame(context: Context) : FrameLayout(context) {
        init {
            clipChildren = true
            isFocusable = false
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true
        override fun onTouchEvent(event: MotionEvent): Boolean = false
    }
}
