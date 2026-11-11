package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import xyz.grtlabs.pilastri.ui.charts.rememberBarGrow
import xyz.grtlabs.pilastri.ui.charts.rememberChartEnter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.domain.rules.F24Kind
import xyz.grtlabs.pilastri.domain.rules.RuleStatus
import xyz.grtlabs.pilastri.domain.rules.RuleValue
import xyz.grtlabs.pilastri.domain.rules.Rules
import xyz.grtlabs.pilastri.ui.*

/** The shipped rule pack for the screens, parsed once per process. Null when the pack cannot be read (then every figure is n/d). */
private object ScreenRules {
    @Volatile var cached: Rules? = null
    /** Parses the pack (323 KB) off the main thread, once; screens read [cached] and show n/d until it is there. */
    fun load(assets: android.content.res.AssetManager): Rules? = cached ?: synchronized(this) {
        cached ?: runCatching { Rules.fromAssets(assets) }.getOrNull()?.also { cached = it }
    }
}

/** Called at app start: warms the rule pack on a background thread so no composition parses JSON. */
fun preloadRules(assets: android.content.res.AssetManager) { Thread { ScreenRules.load(assets) }.apply { isDaemon = true; name = "rules-preload" }.start() }

@Composable
fun rememberRules(): Rules? {
    val assets = LocalContext.current.assets
    val state = androidx.compose.runtime.produceState(ScreenRules.cached) {
        if (value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ScreenRules.load(assets) }
    }
    return state.value
}

fun statusLabel(v: RuleValue<*>): String = when (v.status) {
    RuleStatus.EXACT -> "calcolato"
    RuleStatus.STIMA -> "stima"
    RuleStatus.ND -> "n/d"
}

private fun RuleValue<Long>.eur(): String = value?.let { Fmt.eur(it) } ?: "n/d"

/** Shared numbers of the year's deduction, from the rule pack and the archive's fund contributions. */
private class DeductionView(val year: Int, val d: xyz.grtlabs.pilastri.domain.rules.DeductionCeiling?, val bar: DeductionBar)

@Composable
private fun rememberDeduction(app: App, a: Archive): DeductionView {
    val rules = rememberRules()
    val year = app.today.year
    return remember(a, rules, year) {
        val contributions = a.fundContributions(year)
        val d = rules?.deductionCeiling(year, contributions)
        DeductionView(year, d, deductionBar(contributions, year, d?.ceiling?.value, fundNameMap(a)))
    }
}

/** Fund id (or "account:<id>" for an account without a described fund) to the name shown. */
private fun fundNameMap(a: Archive): Map<String, String> =
    a.accounts.filter { it.type == AccountType.PENSION_FUND || it.fundId != null }.associate { "account:${it.id}" to it.name } +
        a.funds.associate { it.id to (it.name.value ?: "Fondo") }


private class BarSeg(val cents: Long, val color: Color)

/**
 * The Futuro "Previdenza" card (board 4a-07): regime, the year's deduction (nominal), what is counted against the ceiling and
 * the room left, a bar with the ceiling tick. The whole card opens Previdenza. Unknown amounts read n/d, never 0.
 */
@Composable
fun PrevidenzaCard(app: App, a: Archive) {
    val pal = LocalPal.current
    val v = rememberDeduction(app, a)
    val regime = remember(a) { portfolioRegime(a.positions) }
    val bar = v.bar
    val counted = bar.countedCents
    val spoken = "Previdenza, regime $regime. Deduzione ${v.year}: ${counted?.let { Fmt.eur(it) } ?: "n/d"} conteggiati, spazio ${bar.roomCents?.let { Fmt.eur(it) } ?: "n/d"}, tetto ${bar.ceilingCents?.let { Fmt.eur(it) } ?: "n/d"}. Apre la previdenza."
    TitledCard(
        "Previdenza",
        Modifier.pressable(CardShape, pal.pressTint, onClick = { app.hx?.tick(); app.go(Route.Pension) }).semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(8.dp),
        trailing = {
            Text("Regime $regime", style = Type.small, color = pal.ink2)
            Icon(Ic.chevronRight, null, Modifier.size(16.dp), tint = pal.brand)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Deduzione ${v.year}", style = Type.smallStrong, color = pal.ink, modifier = Modifier.weight(1f))
            Tag("nominale")
        }
        Text("${counted?.let { Fmt.eur(it) } ?: "n/d"} conteggiati · spazio ${bar.roomCents?.let { Fmt.eur(it) } ?: "n/d"}", style = Type.small, color = pal.ink2)
        DeductionBarView(listOf(BarSeg(counted ?: 0L, pal.fill)).filter { it.cents > 0 }, bar, "tetto", showSpace = false, description = "")
    }
}

/**
 * 4b-05 and 4b-06: the year's deduction as one segmented bar (a segment per fund and party, a dashed segment for the room, the
 * ceiling tick from the rule pack), the printed scale, the room ("calcolato"; "oltre il tetto" when over) and the first-job
 * extra (n/d while the pack has no sourced rule). Bars grow on the fill spring.
 */
@Composable
fun DeductionDetailCard(app: App, a: Archive) {
    val pal = LocalPal.current
    val v = rememberDeduction(app, a)
    val rules = rememberRules()
    val extra = remember(rules, v.year) { rules?.firstJobExtraDeduction(v.year) }
    val bar = v.bar
    val d = v.d
    val segs = remember(bar, pal) {
        val funds = bar.segments.map { it.fundId }.distinct()
        bar.segments.map { s ->
            val base = if (s.party == DeductionParty.TUO) pal.fill else pal.ribbonIn
            BarSeg(s.cents, base.copy(alpha = 1f - 0.2f * (funds.indexOf(s.fundId) % 3)))
        }
    }
    val description = "Deduzione ${v.year}: " + (bar.segments.joinToString(", ") { "${it.fundName} ${if (it.party == DeductionParty.TUO) "tuo" else "datore"} ${Fmt.eur(it.cents)}" }.ifEmpty { "nessun versamento noto" }) +
        ". Spazio ${bar.roomCents?.let { Fmt.eur(it) } ?: "n/d"}, tetto ${bar.ceilingCents?.let { Fmt.eur(it) } ?: "n/d"}" + (bar.excessCents?.let { ", oltre il tetto di ${Fmt.eur(it)}" } ?: "")
    TitledCard("Deduzione ${v.year}", verticalArrangement = Arrangement.spacedBy(8.dp), trailing = {
        Tag("TFR escluso")
        InfoButton("Deduzione ${v.year}", listOfNotNull(
            "Tetto ${d?.ceiling?.eur() ?: "n/d"} (${d?.ceiling?.let(::statusLabel) ?: "n/d"}) per persona e anno, fondi e datore insieme.",
            d?.ceiling?.sourceIds?.takeIf { it.isNotEmpty() }?.let { "Fonti: ${it.joinToString(", ")}" },
            d?.counted?.reason, d?.duplicatesDropped?.takeIf { it > 0 }?.let { "$it registrazioni doppie non contate." },
            bar.unknownParts.takeIf { it > 0 }?.let { "$it mesi con una parte non nota: il totale resta n/d." },
        ).joinToString("\n\n"))
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DeductionBarView(segs, bar, "tetto", showSpace = true, description = description)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("tuo" to pal.fill, "datore" to pal.ribbonIn).forEach { (name, color) ->
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(color))
                    Text(name, style = Type.tiny, color = pal.ink)
                }
                Spacer(Modifier.weight(1f))
                Text("per persona e anno", style = Type.tiny, color = pal.ink2)
            }
            FactLine("Spazio di deduzione", bar.roomCents?.let { Fmt.eur(it) } ?: "n/d",
                note = when { bar.roomCents == null -> null; (bar.excessCents ?: 0) > 0 -> "oltre il tetto"; else -> d?.deductible?.let(::statusLabel) })
            FactLine("Quota extra prima occupazione", extra?.eur() ?: "n/d", stacked = true)
        }
    }
}

/**
 * A bar over a printed scale: [segs] stack from the left, a dashed segment shows the room up to the ceiling, the ceiling tick is
 * labelled, the part over the ceiling is tinted. Unknown total = empty outline. Grows on [Motion.fill]; reduced motion fades in.
 */
@Composable
private fun DeductionBarView(segs: List<BarSeg>, bar: DeductionBar, tickLabel: String, showSpace: Boolean, description: String, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val enter = rememberChartEnter()
    val grow = rememberBarGrow(1)[0]
    val scale = bar.scaleCents.toFloat()
    val ceiling = bar.ceilingCents
    val tickAt = ceiling?.let { (it / scale).coerceIn(0f, 1f) }
    Column(Modifier.then(modifier).fillMaxWidth().graphicsLayer { alpha = enter.alpha.value }.semantics(mergeDescendants = true) { if (description.isNotEmpty()) contentDescription = description }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (ceiling != null && tickAt != null) Box(Modifier.fillMaxWidth(tickAt.coerceAtLeast(0.2f)).heightIn(min = 14.dp)) {
            Text("$tickLabel ${Fmt.eur(ceiling)}", style = Type.tiny, color = pal.ink2, softWrap = false, maxLines = 1, modifier = Modifier.align(Alignment.CenterEnd).wrapContentWidth(Alignment.End, unbounded = true))
        }
        Canvas(Modifier.fillMaxWidth().height(18.dp).chartTag().semantics { contentDescription = description.ifEmpty { "Deduzione conteggiata rispetto al tetto" } }) {
            val h = 10.dp.toPx(); val top = (size.height - h) / 2f; val r = CornerRadius(h / 2f)
            drawRoundRect(pal.track, Offset(0f, top), Size(size.width, h), r)
            val counted = bar.countedCents
            if (counted == null) drawRoundRect(pal.btnLine, Offset(0f, top), Size(size.width, h), r, style = Stroke(1.dp.toPx()))
            else {
                val gap = 1.5.dp.toPx()
                var x = 0f
                val g = grow.value
                segs.forEach { s ->
                    val w = size.width * (s.cents / scale) * g
                    if (w > 0.5f) drawRoundRect(s.color, Offset(x, top), Size((w - gap).coerceAtLeast(1f), h), CornerRadius(2.dp.toPx()))
                    x += w
                }
                val overFrom = tickAt?.let { size.width * it }
                if (overFrom != null && bar.excessCents != null && x > overFrom) drawRect(pal.over.copy(alpha = 0.55f), Offset(overFrom, top), Size(x - overFrom, h))
                if (showSpace && ceiling != null && counted < ceiling) {
                    val from = x.coerceAtMost(size.width); val to = size.width * (ceiling / scale) * g
                    if (to - from > 4.dp.toPx()) drawRoundRect(pal.ink3, Offset(from + 1.dp.toPx(), top + 1.dp.toPx()), Size(to - from - 2.dp.toPx(), h - 2.dp.toPx()), CornerRadius(2.dp.toPx()),
                        style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
                }
            }
            if (tickAt != null) drawLine(pal.ink, Offset(size.width * tickAt, 0f), Offset(size.width * tickAt, size.height), 2.dp.toPx())
        }
        if (showSpace) {
            Row(Modifier.fillMaxWidth()) {
                Text("0", style = Type.tiny, color = pal.ink2)
                Text(Fmt.eur(bar.scaleCents / 2), style = Type.tiny, color = pal.ink2, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Text(Fmt.eur(bar.scaleCents), style = Type.tiny, color = pal.ink2)
            }
            bar.roomCents?.let { Text("spazio ${Fmt.eur(it)}", style = Type.tiny, color = pal.ink2) }
        }
    }
}

/**
 * Deduction card (Council 4 d): any fund plus the employer, deduplicated per (fund, month); the ceiling per person-year from the
 * pack; TFR excluded; first-job extra n/d. A bar with a tick at the ceiling and the status label of each figure. Without
 * employer data the room is n/d. Not an attention place.
 */
@Composable
fun DeductionCard(app: App, a: Archive) {
    val pal = LocalPal.current
    val rules = rememberRules()
    val year = app.today.year
    val d = remember(a, rules) { rules?.deductionCeiling(year, a.fundContributions(year)) }
    val extra = remember(rules) { rules?.firstJobExtraDeduction(year) }
    val ceiling = d?.ceiling?.value
    val counted = d?.counted?.value
    val room = if (ceiling != null && counted != null) (ceiling - counted).coerceAtLeast(0) else null
    TitledCard("Deduzione $year", verticalArrangement = Arrangement.spacedBy(6.dp), trailing = {
        Tag("TFR escluso")
        InfoButton("Deduzione $year", listOfNotNull(
            "Tetto ${d?.ceiling?.eur() ?: "n/d"} (${d?.ceiling?.let(::statusLabel) ?: "n/d"}) per persona e anno, fondi e datore insieme.",
            d?.ceiling?.sourceIds?.takeIf { it.isNotEmpty() }?.let { "Fonti: ${it.joinToString(", ")}" },
            d?.counted?.reason, d?.duplicatesDropped?.takeIf { it > 0 }?.let { "$it registrazioni doppie non contate." },
        ).joinToString("\n\n"))
    }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(counted?.let { Fmt.eur(it) } ?: "n/d", style = Type.secondaryFigure, color = pal.ink)
            Spacer(Modifier.width(6.dp))
            Text("conteggiati · ${d?.counted?.let(::statusLabel) ?: "n/d"}", style = Type.small, color = pal.ink2, modifier = Modifier.weight(1f))
            Text("tetto ${d?.ceiling?.eur() ?: "n/d"}", style = Type.small, color = pal.ink2)
        }
        CeilingBar(counted, ceiling)
        FactLine("Spazio di deduzione", room?.let { Fmt.eur(it) } ?: "n/d",
            note = when { room == null -> null; (d?.excess?.value ?: 0) > 0 -> "oltre il tetto"; else -> d?.deductible?.let(::statusLabel) })
        FactLine("Quota extra prima occupazione", extra?.eur() ?: "n/d", stacked = true)
    }
}

/** Counted against the ceiling: the scale is the larger of the two, the tick marks the ceiling. Unknown counted = empty outline. */
@Composable
private fun CeilingBar(counted: Long?, ceiling: Long?, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val scale = maxOf(ceiling ?: 0, counted ?: 0).coerceAtLeast(1)
    val shape = RoundedCornerShape(5.dp)
    BoxWithConstraints(modifier.fillMaxWidth().height(18.dp).semantics {
        contentDescription = "Conteggiati ${counted?.let { Fmt.eur(it) } ?: "n/d"} su un tetto di ${ceiling?.let { Fmt.eur(it) } ?: "n/d"}"
    }) {
        val w = maxWidth
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(10.dp).clip(shape).background(pal.track)
            .then(if (counted == null) Modifier.border(1.dp, pal.btnLine, shape) else Modifier)) {
            if (counted != null) Box(Modifier.fillMaxHeight().width(w * (counted.toFloat() / scale).coerceIn(0f, 1f)).background(if (ceiling != null && counted > ceiling) pal.over else pal.fill))
        }
        if (ceiling != null) Box(Modifier.offset(x = (w * (ceiling.toFloat() / scale)) - 1.dp).width(2.dp).fillMaxHeight().background(pal.ink))
    }
}

/**
 * F24 saldo and acconti as forward commitments beside Rate (Council 4 d), only for a position kind that files them. Dates come
 * from the pack; amounts are a "stima" only from a previous-year return, otherwise n/d. Not an attention place.
 */
@Composable
fun F24Commitments(app: App, a: Archive) {
    val pal = LocalPal.current
    val files = remember(a) { a.positions.any { it.kind.known == PositionKind.AUTONOMO_FORFETTARIO || it.kind.known == PositionKind.AUTONOMO_ORDINARIO } }
    if (!files) return
    val rules = rememberRules() ?: return
    val today = app.today
    val dues = remember(rules, today) {
        (today.year..today.year + 1).flatMap { y -> runCatching { rules.f24Schedule(y, null) }.getOrDefault(emptyList()) }
            .filter { it.due.value == null || !it.due.value!!.isBefore(today) }.take(3)
    }
    if (dues.isEmpty()) return
    SectionHeader("F24")
    RowsCard {
        dues.forEach { f ->
            val name = when (f.kind) { F24Kind.SALDO -> "Saldo ${f.taxYear}"; F24Kind.ACCONTO_1 -> "1° acconto ${f.taxYear}"; F24Kind.ACCONTO_2 -> "2° acconto ${f.taxYear}" }
            ListRow(name, f.due.value?.let { Fmt.dayYear(it) } ?: "data n/d", null, value = f.amount.eur(), tag = if (f.amount.value != null) statusLabel(f.amount) else null)
        }
    }
}
