package xyz.grtlabs.pilastri

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import android.content.Intent
import xyz.grtlabs.pilastri.ui.AppRoot
import xyz.grtlabs.pilastri.ui.CaptureHook
import xyz.grtlabs.pilastri.ui.ArchiveSyncHost
import xyz.grtlabs.pilastri.ui.AppearanceStore
import xyz.grtlabs.pilastri.ui.DarkPal
import xyz.grtlabs.pilastri.ui.LightPal
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.PilastriTheme
import xyz.grtlabs.pilastri.ui.appDark
import xyz.grtlabs.pilastri.ui.observeReduceMotion

/**
 * The redone app (concepts 9 and 10, chosen 2026-10-31). The appearance comes from Impostazioni → Aspetto (default
 * Chiaro), not blindly from the system: the window background and the bar icons are set from that choice before the
 * first frame, and again whenever it changes, so nothing flashes the other colour. System bars stay transparent.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // CR-84: the splash stays until the start destination is decided; the first visible frame is the settled screen.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { xyz.grtlabs.pilastri.ui.StartGate.keepSplash() }
        applyWindow(AppearanceStore.isDark(this))
        window.isNavigationBarContrastEnforced = false
        setContent {
            val dark = appDark()
            LaunchedEffect(dark) { applyWindow(dark) }
            PilastriTheme(dark) {
                CompositionLocalProvider(LocalReduceMotion provides observeReduceMotion()) {
                    // Consistency check: every type tag (ui/Tags.kt) becomes the node's resource-id in `uiautomator dump`.
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        if (CaptureHook.ebInitial.value == null) AppRoot()   // capture seam: the app is not composed under the EB pages (its nodes would overlap them in the dump)
                        // capture seam (debug tooling): the Enable Banking setup pages rendered from a synthetic state, nothing runs
                        CaptureHook.ebInitial.value?.let { m ->
                            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                                xyz.grtlabs.pilastri.ui.setup.EbCaptureSeam(this@MainActivity, m)
                            }
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) { CaptureHook.handle(this, intent); deliverReturn(intent) }
    }

    /** The bank's return (App Link, or the site page's intent: hand-back) reaches the activity on a cold start and in onNewIntent (singleTask). */
    private fun deliverReturn(i: Intent?) {
        if (i?.action != Intent.ACTION_VIEW) return
        xyz.grtlabs.pilastri.ui.setup.BankReturn.deliver(this, i.dataString, if (i.`package` != null) "intent" else "applink")
    }

    /** Capture navigation (debug tooling, sample data only): see [CaptureHook]. */
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); CaptureHook.handle(this, intent); deliverReturn(intent) }

    /**
     * Every return to the foreground syncs each linked provider (at most every 15 minutes each; AGENTS.md: sync is automatic
     * and invisible). On a cold start the archive is not loaded yet: AppRoot runs the first sync once it is.
     */
    override fun onStart() {
        super.onStart()
        xyz.grtlabs.pilastri.sync.eb.EbLog.line(xyz.grtlabs.pilastri.sync.eb.EbLog.EB, "app foreground sinceBankOpenS=${xyz.grtlabs.pilastri.ui.setup.BankBrowser.secondsSinceOpen()}")
        ArchiveSyncHost.foreground(this)
    }

    override fun onStop() {
        super.onStop()
        xyz.grtlabs.pilastri.sync.eb.EbLog.line(xyz.grtlabs.pilastri.sync.eb.EbLog.EB, "app background sinceBankOpenS=${xyz.grtlabs.pilastri.ui.setup.BankBrowser.secondsSinceOpen()}")
    }

    private fun applyWindow(dark: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        enableEdgeToEdge(style, style)
        window.setBackgroundDrawable(ColorDrawable((if (dark) DarkPal else LightPal).bg.toArgb()))
        // Android 13+: the next cold start's splash uses this theme instead of the system's night mode, when the
        // matching window themes exist (Theme.Pilastri.Light / Theme.Pilastri.Dark); otherwise nothing changes.
        if (Build.VERSION.SDK_INT >= 33) {
            val id = resources.getIdentifier(if (dark) "Theme.Pilastri.Dark" else "Theme.Pilastri.Light", "style", packageName)
            if (id != 0) runCatching { splashScreen.setSplashScreenTheme(id) }
        }
    }
}
