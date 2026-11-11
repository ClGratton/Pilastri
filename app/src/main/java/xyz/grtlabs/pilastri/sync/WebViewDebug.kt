package xyz.grtlabs.pilastri.sync

import android.content.Context
import android.webkit.WebView
import xyz.grtlabs.pilastri.BuildConfig

/**
 * The one place a WebView is created. Debugging (the DevTools socket tools/webview-html-log.mjs reads) follows
 * BuildConfig.WEBVIEW_DEVTOOLS: on in debug and fast builds, off in release. WebViewDebugSourceTest fails if any other file
 * constructs a WebView or switches debugging on by itself.
 */
object WebViewDebug {
    fun enable() { if (BuildConfig.WEBVIEW_DEVTOOLS) WebView.setWebContentsDebuggingEnabled(true) }

    fun create(context: Context): WebView { enable(); return WebView(context) }

    /**
     * A WebView whose page sees a LIGHT colour scheme whatever the app's theme (`prefers-color-scheme: light`): a bank's page styled for
     * dark on its own light box was unreadable (user, 2026-11-10 23:02). Algorithmic darkening is off by the same context (no night mode).
     */
    fun createLight(context: Context): WebView {
        enable()
        val cfg = android.content.res.Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or android.content.res.Configuration.UI_MODE_NIGHT_NO
        }
        return WebView(context.createConfigurationContext(cfg))
    }
}
