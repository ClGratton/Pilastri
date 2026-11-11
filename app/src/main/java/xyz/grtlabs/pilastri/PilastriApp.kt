package xyz.grtlabs.pilastri

import android.app.Application
import xyz.grtlabs.pilastri.ui.installAppMotion

/** Installs the app-wide animation scale (reduced motion runs on real time, see [xyz.grtlabs.pilastri.ui.AppMotionScale]) before any window exists. */
class PilastriApp : Application() {
    override fun onCreate() {
        super.onCreate()
        xyz.grtlabs.pilastri.sync.DurableLog.init(this)
        installAppMotion(this)
        xyz.grtlabs.pilastri.ui.screens.preloadRules(assets)
    }
}
