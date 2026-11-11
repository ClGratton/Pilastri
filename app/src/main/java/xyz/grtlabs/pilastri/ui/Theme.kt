// Structure, type scale and shape tokens adapted from the user's own Cattedra app (theme/Theme.kt);
// Roboto Flex is under the SIL OFL, see assets/licenses/OFL-RobotoFlex.txt.
package xyz.grtlabs.pilastri.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.grtlabs.pilastri.R

/** Meaningful colors Material 3 lacks. Always paired with a sign, word or icon: color is never the only cue. */
@Immutable
class Semantic(
    val gain: Color, val gainContainer: Color, val onGainContainer: Color,
    val loss: Color, val lossContainer: Color, val onLossContainer: Color,
    val warnContainer: Color, val onWarnContainer: Color,
)

/**
 * Action color is a pastel, never the dark saturated `primary`:
 *  - `fill` filled buttons, selected day, big bars; `on` text on it;
 *  - `mid` small things that must stay visible (dots, lines, sparklines, outlines);
 *  - `soft`/`onSoft` the container and its text.
 */
@Immutable
class ActionColors(val fill: Color, val on: Color, val mid: Color, val soft: Color, val onSoft: Color)

val LocalAction = staticCompositionLocalOf { ActionColors(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified) }
val LocalSemantic = staticCompositionLocalOf {
    Semantic(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified)
}

private val StudioLight = lightColorScheme(
    primary = Color(0xFF65558F), onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF), onPrimaryContainer = Color(0xFF22124A), inversePrimary = Color(0xFFCFBDFE),
    secondary = Color(0xFF625B71), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1E192B),
    tertiary = Color(0xFF2D6A4F), tertiaryContainer = Color(0xFFC7F0DA), onTertiaryContainer = Color(0xFF0B3A26),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF7F3FA), onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFF7F3FA), onSurface = Color(0xFF1D1B20), onSurfaceVariant = Color(0xFF49454F),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFBF8FD),
    surfaceContainer = Color(0xFFF3EDF8), surfaceContainerHigh = Color(0xFFFFFFFF), surfaceContainerHighest = Color(0xFFE7E0EE),
    outline = Color(0xFF7A757F), outlineVariant = Color(0xFFDCCFEA),
)
private val StudioDark = darkColorScheme(
    primary = Color(0xFFCFBDFE), onPrimary = Color(0xFF36275D),
    primaryContainer = Color(0xFF4D3D75), onPrimaryContainer = Color(0xFFE9DDFF), inversePrimary = Color(0xFF65558F),
    secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFF8DD6AF), tertiaryContainer = Color(0xFF1F4D38), onTertiaryContainer = Color(0xFFC7F0DA),
    error = Color(0xFFF2B8B5), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF141119), onBackground = Color(0xFFECE6F0),
    surface = Color(0xFF141119), onSurface = Color(0xFFECE6F0), onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainerLowest = Color(0xFF0F0D13), surfaceContainerLow = Color(0xFF1A1621),
    surfaceContainer = Color(0xFF1E1A25), surfaceContainerHigh = Color(0xFF241F2C), surfaceContainerHighest = Color(0xFF332C3D),
    outline = Color(0xFF948F99), outlineVariant = Color(0xFF3A3247),
)

private val SemLight = Semantic(Color(0xFF2D6A4F), Color(0xFFC7F0DA), Color(0xFF0B3A26), Color(0xFFB3261E), Color(0xFFF9DEDC), Color(0xFF410E0B), Color(0xFFFFE2A8), Color(0xFF3D2A00))
private val SemDark = Semantic(Color(0xFF8DD6AF), Color(0xFF1F4D38), Color(0xFFC7F0DA), Color(0xFFF2B8B5), Color(0xFF8C1D18), Color(0xFFF9DEDC), Color(0xFF5A3F00), Color(0xFFFFE2A8))

private fun actionColors(s: ColorScheme, dark: Boolean) =
    if (dark) ActionColors(s.primary, s.onPrimary, s.primary, s.primaryContainer, s.onPrimaryContainer)
    else ActionColors(s.inversePrimary, s.onPrimaryContainer, lerp(s.inversePrimary, s.primary, 0.55f), s.primaryContainer, s.onPrimaryContainer)

/** Roboto Flex is variable: weight, width and optical size are chosen per style. */
@OptIn(ExperimentalTextApi::class)
fun flex(weight: Int, width: Float = 100f, opsz: Float = 14f): FontFamily = FontFamily(
    Font(R.font.roboto_flex, weight = FontWeight(weight), variationSettings = FontVariation.Settings(
        FontVariation.weight(weight), FontVariation.width(width), FontVariation.Setting("opsz", opsz))),
)

private val Body = flex(400)
private val Medium = flex(500)
private val Semi = flex(620)

/** Big wide figures with tabular digits: the app's typographic signature, as in Cattedra. */
val HeroNumber = TextStyle(fontFamily = flex(760, width = 135f, opsz = 96f), fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1.2).sp, fontFeatureSettings = "tnum")
val TileNumber = TextStyle(fontFamily = flex(700, width = 125f, opsz = 36f), fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp, fontFeatureSettings = "tnum")
val FigureNumber = TextStyle(fontFamily = flex(640, width = 115f, opsz = 20f), fontSize = 16.sp, lineHeight = 22.sp, fontFeatureSettings = "tnum")

private val AppTypography = Typography(
    displaySmall = HeroNumber,
    headlineMedium = TextStyle(fontFamily = Semi, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Semi, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Semi, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Semi, fontSize = 17.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Semi, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Body, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum"),
    bodySmall = TextStyle(fontFamily = Body, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Semi, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(44.dp),
)

@Composable
fun PilastriTheme(dark: Boolean = appDark(), content: @Composable () -> Unit) {
    // The appearance chosen in Impostazioni → Aspetto (default Chiaro), resolved once; surfaces crossfade when it changes.
    val pal = animatedPal(dark)
    val base = if (dark) StudioDark else StudioLight
    val scheme = base.copy(background = pal.bg, surface = pal.bg, surfaceContainerLowest = pal.card, surfaceContainerHigh = pal.card, onBackground = pal.ink, onSurface = pal.ink, onSurfaceVariant = pal.ink2)
    CompositionLocalProvider(LocalSemantic provides (if (dark) SemDark else SemLight), LocalAction provides actionColors(base, dark)) {
        CompositionLocalProvider(LocalPal provides pal, LocalDark provides dark) {
            MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
        }
    }
}

