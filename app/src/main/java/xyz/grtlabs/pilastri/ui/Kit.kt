package xyz.grtlabs.pilastri.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/*
 * The visual kit of the redone interface (concepts 9 and 10, chosen 2026-10-31): white cards on pale lilac, a deep-purple
 * accent card, pill buttons, one primary number per card. Dark mode keeps the same structure: near-black aubergine
 * surfaces, cards one step lighter, the accent card stays a saturated deep purple so it still leads the page.
 */

/** Every colour a screen uses, light and dark. Meaning is always repeated in text or an icon, never colour alone. */
@Immutable
data class Pal(
    val bg: Color, val card: Color, val cardLine: Color, val inset: Color, val btnLine: Color,
    val ink: Color, val ink2: Color, val ink3: Color, val brand: Color,
    val accent: Color, val onAccent: Color, val accentLabel: Color, val accentSoft: Color, val accentTile: Color,
    val accentLine: Color, val accentTrack: Color, val accentFill: Color, val warnDot: Color, val warnOnAccent: Color,
    val composer: Color, val composerSub: Color, val composerDisc: Color, val composerDiscInk: Color,
    val chip: Color, val onChip: Color, val primary: Color, val onPrimary: Color, val tonal: Color, val onTonal: Color,
    val track: Color, val fill: Color, val over: Color, val overInk: Color,
    val good: Color, val goodInk: Color, val goodBg: Color, val warnBg: Color, val warnInk: Color, val lilacBg: Color, val lilacInk: Color,
    val tabPill: Color, val tabOn: Color, val tabOff: Color,
    val ribbonIn: Color, val ribbonOut: Color, val ribbonOver: Color, val ribbonRest: Color, val nodeDark: Color, val nodeMid: Color,
    val selected: Color, val selectedLine: Color, val pressTint: Color,
    /** Captions inside the hero month tile (accent under [accentTile]): >= 4.5:1 on that blend, still a lilac tint (F5). */
    val accentTileLabel: Color,
)

val LightPal = Pal(
    bg = Color(0xFFF7F3FA), card = Color.White, cardLine = Color(0xFFE8DFF1), inset = Color(0xFFF7F3FA), btnLine = Color(0xFFDCCFEA),
    ink = Color(0xFF1D1B20), ink2 = Color(0xFF49454F), ink3 = Color(0xFF3A3542), brand = Color(0xFF4D3D75),
    accent = Color(0xFF4D3D75), onAccent = Color.White, accentLabel = Color(0xFFCFBDFE), accentSoft = Color(0xFFE9DDFF), accentTile = Color(0x1FFFFFFF),
    accentLine = Color(0xFFCFBDFE), accentTrack = Color(0x3DFFFFFF), accentFill = Color(0xFFCFBDFE), warnDot = Color(0xFFFFB84D), warnOnAccent = Color(0xFFFFD9A0),
    composer = Color(0xFF3B2A6B), composerSub = Color(0xFFD9CCF4), composerDisc = Color(0xFFE2D4FA), composerDiscInk = Color(0xFF22124A),
    chip = Color(0xFFE9DDFF), onChip = Color(0xFF22124A), primary = Color(0xFF65558F), onPrimary = Color.White, tonal = Color(0xFFE9DDFF), onTonal = Color(0xFF22124A),
    track = Color(0xFFE9E1F1), fill = Color(0xFF65558F), over = Color(0xFFB3261E), overInk = Color(0xFF8C1D18),
    good = Color(0xFF2D6A4F), goodInk = Color(0xFF1F5A40), goodBg = Color(0xFFDFF3E8), warnBg = Color(0xFFFFF1D6), warnInk = Color(0xFF7A4B00),
    lilacBg = Color(0xFFE9DDFF), lilacInk = Color(0xFF4D3D75),
    tabPill = Color(0xFFE2D4FA), tabOn = Color(0xFF22124A), tabOff = Color(0xFF49454F),
    ribbonIn = Color(0xFFCFBDFE), ribbonOut = Color(0xFFDCD2EC), ribbonOver = Color(0xFFF4B9B5), ribbonRest = Color(0xFFCDEBDA),
    nodeDark = Color(0xFF4D3D75), nodeMid = Color(0xFF65558F),
    selected = Color(0xFFE2D4FA), selectedLine = Color(0xFF4D3D75), pressTint = Color(0xFF4D3D75), accentTileLabel = Color(0xFFE4D8FF),
)

val DarkPal = Pal(
    bg = Color(0xFF141119), card = Color(0xFF1E1A25), cardLine = Color(0xFF2D2737), inset = Color(0xFF18141E), btnLine = Color(0xFF3A3247),
    ink = Color(0xFFECE6F0), ink2 = Color(0xFFCAC4D0), ink3 = Color(0xFFB9B1C4), brand = Color(0xFFCFBDFE),
    accent = Color(0xFF3B2D66), onAccent = Color(0xFFF6F1FF), accentLabel = Color(0xFFCFBDFE), accentSoft = Color(0xFFDCD0F5), accentTile = Color(0x17FFFFFF),
    accentLine = Color(0xFFB9A5F0), accentTrack = Color(0x2EFFFFFF), accentFill = Color(0xFFCFBDFE), warnDot = Color(0xFFFFB84D), warnOnAccent = Color(0xFFFFD9A0),
    composer = Color(0xFF2B2147), composerSub = Color(0xFFC9BCEA), composerDisc = Color(0xFFCFBDFE), composerDiscInk = Color(0xFF22124A),
    chip = Color(0xFF352D47), onChip = Color(0xFFE9DDFF), primary = Color(0xFFCFBDFE), onPrimary = Color(0xFF22124A), tonal = Color(0xFF352D47), onTonal = Color(0xFFE9DDFF),
    track = Color(0xFF2E2838), fill = Color(0xFFB6A3EE), over = Color(0xFFF2B8B5), overInk = Color(0xFFFFB4AB),
    good = Color(0xFF8DD6AF), goodInk = Color(0xFF9ED9B8), goodBg = Color(0xFF1E3A2D), warnBg = Color(0xFF3B2E14), warnInk = Color(0xFFFFD08A),
    lilacBg = Color(0xFF352D47), lilacInk = Color(0xFFCFBDFE),
    tabPill = Color(0xFF3A3150), tabOn = Color(0xFFF0E8FF), tabOff = Color(0xFFCAC4D0),
    ribbonIn = Color(0xFF4E4175), ribbonOut = Color(0xFF3A3348), ribbonOver = Color(0xFF5E2D2C), ribbonRest = Color(0xFF1F4434),
    nodeDark = Color(0xFFB6A3EE), nodeMid = Color(0xFF9F8BD6),
    selected = Color(0xFF3A3150), selectedLine = Color(0xFFCFBDFE), pressTint = Color(0xFFCFBDFE), accentTileLabel = Color(0xFFCFBDFE),
)

val LocalPal = staticCompositionLocalOf { LightPal }
val LocalDark = staticCompositionLocalOf { false }

/* Type: Roboto Flex at the boards' sizes and weights; amounts use tabular figures and a slightly wide cut. */
private val W400 = flex(400)
private val W520 = flex(520)
private val W650 = flex(650)
private val W720 = flex(720)
private val W750 = flex(750)
private val W780 = flex(780)
private val NumBig = flex(800, width = 112f, opsz = 40f)
private val NumMid = flex(780, width = 106f, opsz = 24f)
private val NumSmall = flex(720, width = 102f, opsz = 16f)

private fun st(f: FontFamily, size: TextUnit, line: TextUnit, spacing: TextUnit = 0.sp, num: Boolean = false) =
    TextStyle(fontFamily = f, fontSize = size, lineHeight = line, letterSpacing = spacing, fontFeatureSettings = if (num) "tnum" else null)

object Type {
    /** Page heading (Pilastri, Spese, Futuro, Agente, Impostazioni, pushed pages): the top level of the hierarchy, 28 sp, 1.27x the period title. 28 + 2 dp top + one subtitle line = the 48 dp header. */
    val pageHeading = st(W780, 28.sp, 32.sp, (-0.4).sp)
    /** Period title (Mese month, Spese range) and dialog titles: second level, 22 sp. */
    val screenTitle = st(W780, 22.sp, 26.sp, (-0.3).sp)
    val title = st(W780, 22.sp, 26.sp, (-0.2).sp)
    val pageTitle = st(W720, 20.sp, 24.sp, (-0.2).sp)
    val sub = st(W400, 11.5.sp, 14.sp)
    val caps = st(W720, 11.sp, 14.sp, 1.sp)
    val section = st(W720, 12.sp, 16.sp)
    val cardTitle = st(W750, 14.sp, 18.sp)
    val rowTitle = st(W650, 13.sp, 17.sp)
    val body = st(W400, 13.sp, 18.sp)
    val bodyStrong = st(W650, 13.sp, 18.sp)
    val small = st(W400, 11.5.sp, 15.sp)
    val smallStrong = st(W650, 11.5.sp, 15.sp)
    val tiny = st(W520, 10.5.sp, 13.sp)
    val tinyStrong = st(W720, 10.5.sp, 13.sp)
    val button = st(W720, 13.sp, 16.sp)
    val hero = st(NumBig, 30.sp, 36.sp, (-0.8).sp, num = true) // CR-57: board hero is 30 (no board draws 36)
    val heroSmall = st(NumBig, 32.sp, 36.sp, (-0.8).sp, num = true)
    val figure = st(NumBig, 26.sp, 32.sp, (-0.3).sp, num = true)
    val primaryFigure = figure
    val secondaryFigure = st(NumBig, 17.sp, 23.sp, (-0.2).sp, num = true)
    val labelOnAccent = st(W650, 11.sp, 14.sp)
    val captionOnAccent = st(W400, 10.5.sp, 13.sp)
    val attentionTitle = st(W750, 14.sp, 18.sp)
    val attentionLine = st(W400, 11.5.sp, 15.sp)
    val chip = st(W650, 13.sp, 16.sp)
    val statusChip = st(W720, 11.5.sp, 14.sp)
    val figureSmall = st(NumMid, 20.sp, 24.sp, (-0.3).sp, num = true)
    val tileValue = st(NumMid, 15.sp, 19.sp, num = true)
    val amount = st(NumSmall, 13.sp, 17.sp, num = true)
    val amountSmall = st(NumSmall, 11.5.sp, 15.sp, num = true)

    // Setup flow roles (boards 8a/8b/8c draw 30/15/16/14; listed in the DESIGN.md Tokens table, D1).
    val setupTitle = st(W780, 30.sp, 36.sp, (-0.4).sp)
    val setupLead = st(W400, 15.sp, 22.sp)
    val setupRow = st(flex(700), 16.sp, 20.sp)
    val setupName = st(W650, 15.sp, 20.sp)
    val setupNameStrong = st(flex(700), 15.sp, 20.sp)
    val setupChip = st(flex(500), 15.sp, 19.sp)
    val setupChipOn = st(flex(700), 15.sp, 19.sp)
    val setupChipSmall = st(flex(500), 14.sp, 18.sp)
    val setupChipSmallOn = st(flex(700), 14.sp, 18.sp)
    val setupValue = st(W650, 14.sp, 18.sp, num = true)
    val setupCounter = st(flex(600), 13.sp, 16.sp, num = true)
    val setupOverline = st(W650, 13.sp, 18.sp, num = true)
}

/* Formatting: whole euros for figures at a glance, cents where a single movement or part is listed. */
object Fmt {
    private val it = Locale.ITALY
    private val sym = DecimalFormatSymbols(it)
    private fun whole() = DecimalFormat("#,##0", sym)
    private fun cents() = DecimalFormat("#,##0.00", sym)
    private const val MINUS = "−"
    /** Figure and currency symbol never split across lines: U+00A0 NO-BREAK SPACE before the euro sign. */
    const val EUR_SUFFIX = "\u00A0€"
    fun eur(cents: Long): String {
        val euros = BigDecimal.valueOf(cents, 2).setScale(0, RoundingMode.HALF_UP).abs()
        return (if (cents < 0 && euros.signum() != 0) MINUS else "") + whole().format(euros) + EUR_SUFFIX
    }
    fun eurCents(cents: Long): String = (if (cents < 0) MINUS else "") + cents().format(BigDecimal.valueOf(kotlin.math.abs(cents), 2)) + EUR_SUFFIX
    fun signed(cents: Long): String = if (cents > 0) "+" + eur(cents) else eur(cents)
    fun signedCents(cents: Long): String = if (cents > 0) "+" + eurCents(cents) else eurCents(cents)
    fun pct(fraction: Double): String = "${(fraction * 100).let { kotlin.math.round(it).toInt() }}%"
    fun day(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("d MMM", it))
    fun dayYear(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("d MMM yyyy", it))
    fun weekday(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEE d MMM", it))
    fun month(m: YearMonth): String = m.month.getDisplayName(JTextStyle.FULL_STANDALONE, it).lowercase(it)
    fun monthTitle(m: YearMonth): String = month(m).replaceFirstChar { c -> c.titlecase(it) }
    fun monthShort(m: YearMonth): String = m.month.getDisplayName(JTextStyle.SHORT_STANDALONE, it).lowercase(it).trimEnd('.')
    fun monthYear(m: YearMonth): String = monthTitle(m) + " " + m.year
    fun relative(d: LocalDate, today: LocalDate = LocalDate.now()): String = when (val n = java.time.temporal.ChronoUnit.DAYS.between(d, today)) {
        0L -> "oggi"; 1L -> "ieri"; -1L -> "domani"
        else -> if (n in 2..6) "$n giorni fa" else if (n in -6..-2) "tra ${-n} giorni" else day(d)
    }
}

/* Icons: the boards' 24-unit stroke paths, drawn as vectors so they scale and tint. */
private fun strokeIcon(name: String, vararg paths: String, width: Float = 2f): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    paths.forEach { d ->
        addPath(PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
}.build()

object Ic {
    val settings by lazy { strokeIcon("settings", "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z", "M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2") }
    val chevronRight by lazy { strokeIcon("right", "M9 5l7 7-7 7", width = 2.4f) }
    val back by lazy { strokeIcon("back", "M15 5l-7 7 7 7", width = 2.2f) }
    val down by lazy { strokeIcon("down", "M6 9l6 6 6-6", width = 2.4f) }
    val sparkle by lazy { strokeIcon("sparkle", "M12 3l1.8 4.7L18.5 9.5l-4.7 1.8L12 16l-1.8-4.7L5.5 9.5l4.7-1.8z") }
    val mic by lazy { strokeIcon("mic", "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5 11a7 7 0 0 0 14 0M12 18v3") }
    val send by lazy { strokeIcon("send", "M12 19V6M6 11l6-6 6 6", width = 2.2f) }
    val help by lazy { strokeIcon("help", "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0z", "M9.5 9.5a2.5 2.5 0 1 1 3.6 2.25c-.65.32-1.1.9-1.1 1.6v.4M12 16.9v.1") }
    val info by lazy { strokeIcon("info", "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0z", "M12 11v5M12 8v.01") }
    val pillars by lazy { strokeIcon("pillars", "M5 20V9M10 20V9M14 20V9M19 20V9M3 20h18M4 6h16l-8-3z") }
    val spese by lazy { strokeIcon("spese", "M4 6h16M4 12h10M4 18h6") }
    val futuro by lazy { strokeIcon("futuro", "M3 17l6-6 4 4 8-8M15 7h6v6") }
    val salary by lazy { strokeIcon("salary", "M12 4v10m0 0l-4-4m4 4l4-4M5 19h14") }
    val search by lazy { strokeIcon("search", "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4") }
    val clock by lazy { strokeIcon("clock", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 2") }
    val recurring by lazy { strokeIcon("recurring", "M4 12a8 8 0 0 1 14-5.3M20 12a8 8 0 0 1-14 5.3M18 3v4h-4M6 21v-4h4") }
    val warning by lazy { strokeIcon("warning", "M12 8v5M12 16.5v.5M10.3 3.9L2.4 18a2 2 0 0 0 1.7 3h15.8a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z") }
    val calendar by lazy { strokeIcon("calendar", "M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM3 10h18M8 3v4M16 3v4") }
    val link by lazy { strokeIcon("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1") }
    val check by lazy { strokeIcon("check", "M5 12.5l4.5 4.5L19 7.5", width = 2.6f) }
    val plus by lazy { strokeIcon("plus", "M12 5v14M5 12h14") }
    val upload by lazy { strokeIcon("upload", "M12 16V4m0 0l-4 4m4-4l4 4M5 20h14") }
    val refresh by lazy { strokeIcon("refresh", "M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5") }
    val close by lazy { strokeIcon("close", "M6 6l12 12M18 6L6 18") }
    val card by lazy { strokeIcon("card", "M4 6h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1zM3 10h18M7 15h3") }
    val mail by lazy { strokeIcon("mail", "M4 6h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1zM3.5 7l8.5 6 8.5-6") }
    val shield by lazy { strokeIcon("shield", "M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z") }
    val box by lazy { strokeIcon("box", "M4 8l8-4 8 4v8l-8 4-8-4zM4 8l8 4 8-4M12 12v8") }
    val target by lazy { strokeIcon("target", "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0zM16 12a4 4 0 1 1-8 0a4 4 0 1 1 8 0z") }
    val tag by lazy { strokeIcon("tag", "M3 12V4h8l9 9-8 8zM7.5 7.5v.01") }
    val pie by lazy { strokeIcon("pie", "M12 3v9h9a9 9 0 1 1-9-9zM15 3.5A9 9 0 0 1 20.5 9H15z") }
    /** Freshness glyphs (board 1a): complete and partial. */
    val checkCircle by lazy { strokeIcon("checkCircle", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM8 12.5l2.7 2.7L16 9.5", width = 2.2f) }
    val alertCircle by lazy { strokeIcon("alertCircle", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7.5V13M12 16v.01", width = 2.2f) }
}

/* Surfaces */
val CardShape = RoundedCornerShape(24.dp)
val BigCardShape = RoundedCornerShape(28.dp)
val AccentShape = RoundedCornerShape(32.dp)

fun Modifier.card(pal: Pal, shape: androidx.compose.ui.graphics.Shape = CardShape, color: Color = pal.card): Modifier =
    this.testTag(UiTag.CARD).clip(shape).background(color).border(1.dp, pal.cardLine, shape)

@Composable
fun Card(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = CardShape, color: Color? = null, padding: Dp = 14.dp, content: @Composable () -> Unit) {
    val pal = LocalPal.current
    Box(modifier.fillMaxWidth().periodPageSurfaceBounds().card(pal, shape, color ?: pal.card).padding(padding)) { Box(Modifier.fillMaxWidth().cardBodyTag()) { content() } }
}

/**
 * The ONE 2 dp sync / refresh bar (indeterminate, or determinate when [progress] is given: the pull). Its place is exactly 2 dp high: Material's indicator
 * enlarges its semantics bounds (about 22 dp tall, which overlapped the text above), so the semantics live on this exact box and the indicator's are cleared.
 */
@Composable
fun SyncBar(modifier: Modifier = Modifier, color: Color? = null, track: Color? = null, progress: (() -> Float)? = null) {
    val pal = LocalPal.current
    val c = color ?: pal.fill
    val t = track ?: pal.track
    Box(modifier.fillMaxWidth().height(2.dp).semantics { contentDescription = "Aggiornamento in corso" }) {
        val bar = Modifier.fillMaxWidth().height(2.dp).clearAndSetSemantics { }
        if (progress == null) androidx.compose.material3.LinearProgressIndicator(bar, color = c, trackColor = t, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp)
        else androidx.compose.material3.LinearProgressIndicator(progress, bar, color = c, trackColor = t, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
    }
}

/** The one chip height (checker "chip height"). */
val ChipHeight = 20.dp

/** The small "calcolato" / "datato" / "da confermare" label used across the boards. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, bg: Color? = null, fg: Color? = null, selected: Boolean = false, tag: String? = null) {
    val pal = LocalPal.current
    Box(modifier.testTag(tag ?: if (selected) UiTag.SELECTED else UiTag.CHIP).clip(RoundedCornerShape(10.dp)).background(bg ?: pal.chip).heightIn(min = ChipHeight).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        // Never clipped: no maxLines; a starved slot wraps the label instead of cutting it (minimum width = the label's own width).
        Text(text, style = Type.tinyStrong, color = fg ?: pal.onChip)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, tag: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val pal = LocalPal.current
    Row(modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) { // L-1: sentence case, on the card edge
        Text(text.replaceFirstChar { it.titlecase(Locale.ITALY) }, style = Type.section, color = pal.brand, modifier = Modifier.weight(1f).testTag(UiTag.SECTION_LABEL).semantics { heading() })
        if (tag != null) Tag(tag)
        trailing?.invoke()
    }
}

enum class Btn { PRIMARY, TONAL, OUTLINE, ON_ACCENT, GHOST }

@Composable
fun PillButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: Btn = Btn.TONAL, icon: ImageVector? = null,
    enabled: Boolean = true, height: Dp = 44.dp, description: String? = null, trailingIcon: ImageVector? = null,
) {
    val pal = LocalPal.current
    val hx = rememberHx()
    val (bg, fg) = when (kind) {
        Btn.PRIMARY -> pal.primary to pal.onPrimary
        Btn.TONAL -> pal.tonal to pal.onTonal
        Btn.OUTLINE -> pal.card to pal.brand
        Btn.ON_ACCENT -> Color.White.copy(alpha = 0.16f) to pal.onAccent
        Btn.GHOST -> Color.Transparent to pal.brand
    }
    val shape = RoundedCornerShape(height / 2)
    val touch = maxOf(height, 48.dp)
    val touchShape = RoundedCornerShape(touch / 2)
    Box(
        modifier.semantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center, propagateMinConstraints = true,
    ) {
        Row(
            // CR-23: the pressable node (semantics, press) is >= 48 dp tall; the visible pill keeps its [height] via the vertical inset below.
            Modifier.defaultMinSize(minWidth = touch, minHeight = touch)
                .pressable(touchShape, pal.pressTint, onClick = onClick, enabled = enabled, haptic = { hx.tap() })
                .padding(vertical = (touch - height) / 2)
                .pillTag()
                .clip(shape).background(if (enabled) bg else bg.copy(alpha = 0.45f))
                .then(if (kind == Btn.OUTLINE) Modifier.border(1.dp, pal.btnLine, shape) else Modifier)
                // A text button (GHOST) has no visible box: its icon/label start on the content line, so no invisible horizontal padding (N-02).
                .padding(horizontal = if (kind == Btn.GHOST) 0.dp else if (icon != null) 14.dp else 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = if (enabled) fg else fg.copy(alpha = 0.5f)); Spacer(Modifier.width(8.dp)) }
            Text(text, style = Type.button, color = if (enabled) fg else fg.copy(alpha = 0.5f), textAlign = TextAlign.Center)
            if (trailingIcon != null) { Spacer(Modifier.width(6.dp)); Icon(trailingIcon, null, Modifier.size(16.dp), tint = if (enabled) fg else fg.copy(alpha = 0.5f)) }
        }
    }
}

/** A round icon-only control, 48dp hit target. */
@Composable
fun RoundIcon(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp, bg: Color? = null, tint: Color? = null, line: Boolean = false, iconSize: Dp = 20.dp) {
    val pal = LocalPal.current
    // CR-23: pressable() widens the hit area to 48 dp around the (possibly smaller) visible disc.
    Box(modifier.pressable(CircleShape, pal.pressTint, onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).background(bg ?: Color.Transparent, CircleShape).then(if (line) Modifier.border(1.dp, pal.btnLine, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(iconSize), tint = tint ?: pal.brand) }
    }
}

/** A filled disc with a stroke icon, decorative (the row says what it means). */
@Composable
fun IconDisc(icon: ImageVector, bg: Color, ink: Color, size: Dp = 36.dp) {
    Box(Modifier.iconDiscTag().size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = ink)
    }
}

/**
 * A meter bar. The fill follows the value with the spatial spring when the value changes, and a cold start is drawn
 * settled (Council 2: fills animate only on change). [fraction] null draws an outlined, unfilled track.
 */
@Composable
fun Meter(fraction: Float?, modifier: Modifier = Modifier, track: Color, fill: Color, height: Dp = 8.dp, outline: Color? = null) {
    val target = (fraction ?: 0f).coerceIn(0f, 1f)
    val anim = remember { Animatable(target) }
    LaunchedEffect(target) { if (anim.targetValue != target) anim.animateTo(target, Motion.spatial()) }
    Box(modifier.fillMaxWidth().height(height).drawBehind {
        val r = CornerRadius(size.height / 2, size.height / 2)
        if (fraction == null && outline != null) {
            val sw = 1.5.dp.toPx()
            drawRoundRect(outline, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), r, style = Stroke(sw))
        } else {
            drawRoundRect(track, cornerRadius = r)
            val w = size.width * anim.value
            if (w > 0.5f) drawRoundRect(fill, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r)
        }
    })
}

/** ⓘ: longer definitions live behind it (text rule of round 2). */
@Composable
fun InfoButton(title: String, body: String, modifier: Modifier = Modifier, tint: Color? = null) {
    var open by rememberSaveable { mutableStateOf(false) }
    // The Box is the popover's anchor: the card grows out of this "?".
    Box(modifier) {
        RoundIcon(Ic.info, "$title: spiegazione", { open = true }, tint = tint, iconSize = 20.dp)
        if (open) InfoDialog(title, body) { open = false }
    }
}

/**
 * An explanation in a small popover that grows from where it was opened (lazy-UX #10): from the "?" when composed beside it,
 * centred when composed at the app's root (a notice). Dismissed by a tap outside, a drag or Back; no "Chiudi" button.
 */
@Composable
fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    val pal = LocalPal.current
    Popover(onDismiss, title) {
        Text(title, style = Type.cardTitle, color = pal.ink, modifier = Modifier.semantics { heading() })
        Box(Modifier.padding(top = 6.dp).heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text(body, style = Type.body, color = pal.ink2)
        }
    }
}

/** Header for a pushed page: the shared [PageHeader] (G-12) with a 48dp back button in its leading slot. */
@Composable
fun BackHeader(title: String, subtitle: String?, onBack: (() -> Unit)?, modifier: Modifier = Modifier, backLabel: String = "Indietro", titleModifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val pal = LocalPal.current
    // On the open Fold the island carries Back: the page does not repeat it.
    val back: (@Composable () -> Unit)? = if (onBack != null && !LocalBackInFrame.current) ({ RoundIcon(Ic.back, backLabel, onBack, bg = pal.card, line = true) }) else null
    PageHeader(title, subtitle, modifier, titleModifier, leading = back, trailing = trailing)
}

/** A row inside a card: title, one caption, an optional trailing value and a chevron when it opens something. */
@Composable
fun ListRow(
    title: String, caption: String?, onClick: (() -> Unit)?, modifier: Modifier = Modifier, value: String? = null, valueColor: Color? = null,
    leading: (@Composable () -> Unit)? = null, tag: String? = null, chevron: Boolean = onClick != null, selected: Boolean = false,
) {
    val pal = LocalPal.current
    val shape = RoundedCornerShape(20.dp)
    val fill by animateFill(pal.selected, selected, "row")
    Row(
        // The 52 dp floor is a TOUCH target: only a tappable row has it. A read-only row is its content plus RowVerticalInset, so the last row of a card sits CardInset from the edge.
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.heightIn(min = 52.dp) else Modifier).then(if (selected) Modifier.selectedTag() else Modifier)
            .then(if (onClick != null) Modifier.pressable(shape, pal.pressTint, onClick = onClick) else Modifier.clip(shape))
            .background(fill).padding(horizontal = RowInset, vertical = RowVerticalInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Type.rowTitle, color = pal.ink, modifier = Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (tag != null) { Spacer(Modifier.width(6.dp)); Tag(tag) }
            }
            if (caption != null) Text(caption, style = Type.small, color = pal.ink2)
        }
        if (value != null) { Spacer(Modifier.width(8.dp)); Text(value, style = Type.amount, color = valueColor ?: pal.ink, textAlign = TextAlign.End) }
        if (chevron) { Spacer(Modifier.width(4.dp)); Icon(Ic.chevronRight, null, Modifier.size(16.dp), tint = pal.brand) }
    }
}

/** An empty or unavailable state that says why, never a zero. */
@Composable
fun EmptyNote(title: String, body: String? = null, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val pal = LocalPal.current
    CardColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CardHeader(title)
        if (body != null) Text(body, style = Type.small, color = pal.ink2)
        if (action != null && onAction != null) PillButton(action, onAction, Modifier.padding(top = 4.dp), kind = Btn.TONAL)
    }
}

/** Two-column label/value line used in details. */
@Composable
fun FactLine(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null, note: String? = null, stacked: Boolean = false) {
    val pal = LocalPal.current
    // No min height: a 32 dp box around a 15 dp line leaves 8+ dp of slack under the LAST line of a card (device: 19.4 dp to the card edge, token 14). The gap above carries the rhythm instead.
    Row(modifier.fillMaxWidth().padding(top = if (stacked) 12.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.body, color = pal.ink2)
            if (note != null) Text(note, style = Type.tiny, color = pal.ink2)
        }
        Spacer(Modifier.width(12.dp))
        Text(value, style = Type.amount, color = valueColor ?: pal.ink, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 220.dp))
    }
}

/** A thin vertical rule for grouped rows. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color? = null) {
    val pal = LocalPal.current
    Box(modifier.fillMaxWidth().height(1.dp).background(color ?: pal.cardLine))
}

/** Hides decorative children from accessibility and gives the group one spoken label. */
fun Modifier.spoken(text: String): Modifier = this.clearAndSetSemantics { contentDescription = text }

/** Semantics for a whole-row button that is not a Text button. */
fun Modifier.buttonRole(): Modifier = this.semantics { role = Role.Button }

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun FillHeight(modifier: Modifier = Modifier) = Spacer(modifier.fillMaxHeight())

/**
 * A single-line figure that never cuts: at large text sizes it steps its own size down (to [minScale]) until it fits
 * the available width. Amounts cannot wrap, so this is how they reflow.
 */
@Composable
fun FitText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, minScale: Float = 0.55f, textAlign: TextAlign? = null) {
    // No SubcomposeLayout here (it would break rows measured by intrinsics): the text reports overflow and steps down.
    var scale by remember(text, style) { mutableStateOf(1f) }
    var fits by remember(text, style) { mutableStateOf(false) }
    Text(
        text, modifier.drawWithContent { if (fits) drawContent() }, color = color, maxLines = 1, softWrap = false, textAlign = textAlign,
        style = style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale),
        onTextLayout = { r -> if (r.didOverflowWidth && scale > minScale) scale = (scale - 0.05f).coerceAtLeast(minScale) else fits = true },
    )
}
