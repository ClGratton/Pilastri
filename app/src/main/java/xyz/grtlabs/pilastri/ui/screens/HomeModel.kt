package xyz.grtlabs.pilastri.ui.screens

import java.time.LocalDate
import java.time.YearMonth
import xyz.grtlabs.pilastri.data.*

/**
 * The archive read models of the top destinations (Pilastri, Mese's span, Spese), computed once per archive version off the
 * main thread (see AppRoot) so that switching to a destination does no data work on its first composition (SKILL §2 "Speed").
 * [source] and [today] identify the version: [App.home] falls back to computing it inline only when no model matches.
 */
internal class HomeModel(val source: Archive, val today: LocalDate) {
    val sum = source.attivitaSum()
    val real = source.realCard()
    val fresh = source.freshness(today)
    val asOf = source.newestValueDate()
    val line = source.attivitaLine(today)
    val arrival = source.incomeArrival(today)
    val lapsed = source.briefing(today).filter { it.kind == BriefingKind.SESSION_EXPIRED }
        .mapNotNull { b -> ImportProvider.entries.firstOrNull { it.label == b.subject } }.firstOrNull()
    val data: Set<YearMonth> = source.dataMonths(today)
    val cover = source.liquidityCoverage()
    val timeline: List<YearMonth> = monthTimeline(data, today).ifEmpty { listOf(YearMonth.from(today)) }
    val flow: Set<YearMonth> = source.flowMonths(today, cover)
    val ledger = source.spendLedger()
    /** The four pillar columns of the accent card (value, target, date), so the first composition only draws them. */
    val columns: List<ColumnData> = (1..4).map { p -> ColumnData(p, source.pillarValue(p).centsOrNull(), source.targetFor(p, today), source.pillarValueDate(p, today)) }

    private class Box<T>(val v: T)
    private val changes = java.util.concurrent.ConcurrentHashMap<YearMonth, Box<MonthValueChange?>>()
    private val versamenti = java.util.concurrent.ConcurrentHashMap<YearMonth, Box<MonthVersamenti>>()
    /** The month figures of the card's strip, memoised per month; the newest months are filled here, off the main thread. */
    fun change(m: YearMonth): MonthValueChange? = changes.getOrPut(m) { Box(source.monthValueChange(m)) }.v
    fun versamenti(m: YearMonth): MonthVersamenti = versamenti.getOrPut(m) { Box(source.monthVersamenti(m)) }.v
    init {
        // The strip composes the settled month and its neighbours; the default month is among the newest of the timeline.
        (timeline.takeLast(3) + YearMonth.from(today)).distinct().forEach { m -> runCatching { change(m); versamenti(m) } }
    }
}
