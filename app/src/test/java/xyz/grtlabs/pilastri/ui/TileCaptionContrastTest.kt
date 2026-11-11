package xyz.grtlabs.pilastri.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** F5: captions on the hero month tile (accent under accentTile) keep WCAG AA (4.5:1) in both palettes; the user's tile strength is untouched. */
class TileCaptionContrastTest {
    private fun ratio(a: Color, b: Color): Float { val x = a.luminance(); val y = b.luminance(); return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f) }
    private fun check(p: Pal, name: String) {
        val tile = p.accentTile.compositeOver(p.accent)
        val r = ratio(p.accentTileLabel, tile)
        assertTrue("$name: accentTileLabel on the tile is $r (>= 4.5)", r >= 4.5f)
        assertTrue("$name: still a lilac tint, not white", p.accentTileLabel != Color.White && p.accentTileLabel.blue > p.accentTileLabel.green)
    }
    @Test fun lightPalette() = check(LightPal, "light")
    @Test fun darkPalette() = check(DarkPal, "dark")
}
