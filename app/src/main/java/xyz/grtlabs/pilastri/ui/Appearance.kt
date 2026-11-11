package xyz.grtlabs.pilastri.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

/** The user's appearance choice (Impostazioni → Aspetto). Light is the default: it is what the user expects to see. */
enum class Appearance(val label: String) { AUTO("Automatico"), LIGHT("Chiaro"), DARK("Scuro") }

/**
 * One owner for the appearance: stored in SharedPreferences ("aspetto"), observable by Compose, resolved in one place.
 * Every activity that uses [PilastriTheme] follows it; MainActivity also paints its window and bar icons from it.
 */
object AppearanceStore {
    private const val FILE = "ui"
    private const val KEY = "aspetto"
    private var loaded = false
    private var current by mutableStateOf(Appearance.LIGHT)

    fun get(ctx: Context): Appearance {
        if (!loaded) {
            current = runCatching { Appearance.valueOf(ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) ?: "LIGHT") }.getOrDefault(Appearance.LIGHT)
            loaded = true
        }
        return current
    }

    fun set(ctx: Context, a: Appearance) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, a.name).apply()
        loaded = true
        current = a
    }

    /** The resolved choice outside composition (window background before the first frame). */
    fun isDark(ctx: Context): Boolean = when (get(ctx)) {
        Appearance.LIGHT -> false
        Appearance.DARK -> true
        Appearance.AUTO -> (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}

/** The resolved choice inside composition; recomposes when the user changes it. */
@Composable
fun appDark(): Boolean {
    val ctx = LocalContext.current
    val system = isSystemInDarkTheme()
    return when (AppearanceStore.get(ctx)) { Appearance.AUTO -> system; Appearance.LIGHT -> false; Appearance.DARK -> true }
}

/** The palette, crossfading for a short moment when the appearance changes (a colour effect, not travel). */
@Composable
fun animatedPal(dark: Boolean): Pal {
    val target = if (dark) DarkPal else LightPal
    val progress = remember { Animatable(1f) }
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        if (target != to) {
            from = lerpPal(from, to, progress.value)
            to = target
            progress.snapTo(0f)
            progress.animateTo(1f, tween(if (LocalReduceMotionValue.value) 120 else 260))
        }
    }
    return if (progress.value >= 1f) to else lerpPal(from, to, progress.value)
}

/** Reduced motion still crossfades, only shorter; read outside composition by [animatedPal]. */
internal object LocalReduceMotionValue { var value = false }

fun lerpPal(a: Pal, b: Pal, t: Float): Pal = Pal(
        bg = lerp(a.bg, b.bg, t),
        card = lerp(a.card, b.card, t),
        cardLine = lerp(a.cardLine, b.cardLine, t),
        inset = lerp(a.inset, b.inset, t),
        btnLine = lerp(a.btnLine, b.btnLine, t),
        ink = lerp(a.ink, b.ink, t),
        ink2 = lerp(a.ink2, b.ink2, t),
        ink3 = lerp(a.ink3, b.ink3, t),
        brand = lerp(a.brand, b.brand, t),
        accent = lerp(a.accent, b.accent, t),
        onAccent = lerp(a.onAccent, b.onAccent, t),
        accentLabel = lerp(a.accentLabel, b.accentLabel, t),
        accentSoft = lerp(a.accentSoft, b.accentSoft, t),
        accentTile = lerp(a.accentTile, b.accentTile, t),
        accentLine = lerp(a.accentLine, b.accentLine, t),
        accentTrack = lerp(a.accentTrack, b.accentTrack, t),
        accentFill = lerp(a.accentFill, b.accentFill, t),
        warnDot = lerp(a.warnDot, b.warnDot, t),
        warnOnAccent = lerp(a.warnOnAccent, b.warnOnAccent, t),
        composer = lerp(a.composer, b.composer, t),
        composerSub = lerp(a.composerSub, b.composerSub, t),
        composerDisc = lerp(a.composerDisc, b.composerDisc, t),
        composerDiscInk = lerp(a.composerDiscInk, b.composerDiscInk, t),
        chip = lerp(a.chip, b.chip, t),
        onChip = lerp(a.onChip, b.onChip, t),
        primary = lerp(a.primary, b.primary, t),
        onPrimary = lerp(a.onPrimary, b.onPrimary, t),
        tonal = lerp(a.tonal, b.tonal, t),
        onTonal = lerp(a.onTonal, b.onTonal, t),
        track = lerp(a.track, b.track, t),
        fill = lerp(a.fill, b.fill, t),
        over = lerp(a.over, b.over, t),
        overInk = lerp(a.overInk, b.overInk, t),
        good = lerp(a.good, b.good, t),
        goodInk = lerp(a.goodInk, b.goodInk, t),
        goodBg = lerp(a.goodBg, b.goodBg, t),
        warnBg = lerp(a.warnBg, b.warnBg, t),
        warnInk = lerp(a.warnInk, b.warnInk, t),
        lilacBg = lerp(a.lilacBg, b.lilacBg, t),
        lilacInk = lerp(a.lilacInk, b.lilacInk, t),
        tabPill = lerp(a.tabPill, b.tabPill, t),
        tabOn = lerp(a.tabOn, b.tabOn, t),
        tabOff = lerp(a.tabOff, b.tabOff, t),
        ribbonIn = lerp(a.ribbonIn, b.ribbonIn, t),
        ribbonOut = lerp(a.ribbonOut, b.ribbonOut, t),
        ribbonOver = lerp(a.ribbonOver, b.ribbonOver, t),
        ribbonRest = lerp(a.ribbonRest, b.ribbonRest, t),
        nodeDark = lerp(a.nodeDark, b.nodeDark, t),
        nodeMid = lerp(a.nodeMid, b.nodeMid, t),
        selected = lerp(a.selected, b.selected, t),
        selectedLine = lerp(a.selectedLine, b.selectedLine, t),
        pressTint = lerp(a.pressTint, b.pressTint, t),
        accentTileLabel = lerp(a.accentTileLabel, b.accentTileLabel, t),
)
