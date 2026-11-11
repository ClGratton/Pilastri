package xyz.grtlabs.pilastri.data

import xyz.grtlabs.pilastri.domain.rules.F24Due
import xyz.grtlabs.pilastri.domain.rules.F24Kind
import xyz.grtlabs.pilastri.domain.rules.Rules
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/*
 * Pure data layer of the irregular-income blocks (board 9): what a partita IVA or a collaboratore receives, who paid it,
 * the F24 dates that follow from it and the gate of "Quanto accantonare". Nothing here is tied to one person: the position
 * kinds come from the archive, the F24 dates only from the rule pack, and a missing figure is null or ND, never zero.
 */

/** Position kinds whose income arrives irregularly and is shown as monthly bars with the payers behind them. */
val IrregularKinds: Set<PositionKind> = setOf(PositionKind.AUTONOMO_FORFETTARIO, PositionKind.AUTONOMO_ORDINARIO, PositionKind.COLLABORATORE)

/** Position kinds that file an F24 for saldo and acconti (same gate as the former F24Commitments). */
val F24Kinds: Set<PositionKind> = setOf(PositionKind.AUTONOMO_FORFETTARIO, PositionKind.AUTONOMO_ORDINARIO)

fun Archive.irregularPositions(): List<IncomePosition> = positions.filter { it.kind.known in IrregularKinds }

private val creditRoles = setOf(null, MovementRole.SALARY, MovementRole.INCOME)

/** Every credit that can be irregular income, any month, oldest first. The transfer pairs are computed once. */
private fun Archive.allIrregularCredits(): List<ExternalMovement> {
    val liquidity = liquidityIds()
    val paired = transferPairs().keys
    val transferIds = transfers.map { it.id }.toSet()
    return externalMovements.filter { m ->
        m.accountId in liquidity && m.signedCents > 0 && m.status != ExternalStatus.IGNORED &&
            (m.kind == ExternalKind.DEPOSIT || m.kind == ExternalKind.OTHER) && !m.isRefund() &&
            m.id !in paired && m.linkedRecordId !in transferIds && m.role in creditRoles
    }.sortedBy { it.date }
}

/**
 * Credits of [month] on liquidity accounts that look like income: a deposit (or an unclassified credit) that is not ignored,
 * not a refund, not one side of an own-account transfer and not already filed as something other than salary or income.
 */
fun Archive.irregularCredits(month: YearMonth): List<ExternalMovement> =
    allIrregularCredits().filter { YearMonth.from(it.date) == month }

/**
 * One month of the income bars. [cents] is the month's credits when [state] is DATA and 0 otherwise (the state, not the number,
 * says "nothing recorded" or "not known").
 */
data class IncomeBar(val month: YearMonth, val cents: Long, val state: BarState)

/**
 * Twelve months ending at [endMonth], oldest first. DATA when credits were recorded; NONE when the liquidity data covers
 * part of the month and there were none; ND when the month is outside the coverage (or the coverage is unknown).
 * The current month counts up to [today].
 */
fun Archive.incomeBars(endMonth: YearMonth, today: LocalDate): List<IncomeBar> {
    val byMonth = allIrregularCredits().groupBy { YearMonth.from(it.date) }
    val coverage = liquidityCoverage()
    return (11 downTo 0).map { back ->
        val month = endMonth.minusMonths(back.toLong())
        val sum = byMonth[month].orEmpty().sumOf { it.signedCents }
        when {
            sum > 0 -> IncomeBar(month, sum, BarState.DATA)
            coverage.daysIn(month, today) != null -> IncomeBar(month, 0L, BarState.NONE)
            else -> IncomeBar(month, 0L, BarState.ND)
        }
    }
}

/** One payer's share of a month. [payer] null = the description names nobody (the screen shows "mittente n/d"). */
data class PayerTotal(val payer: String?, val cents: Long, val count: Int, val last: LocalDate)

/** The month's credits grouped by who paid them, largest first. Unreadable senders form one group with a null payer. */
fun Archive.compensi(month: YearMonth): List<PayerTotal> =
    irregularCredits(month).groupBy { m -> payerOf(m.description)?.let { p -> merchantKey(p).ifBlank { p.lowercase() } } }
        .values.map { group ->
            val latest = group.maxByOrNull { it.date }!!
            PayerTotal(payerOf(latest.description), group.sumOf { it.signedCents }, group.size, latest.date)
        }
        .sortedWith(compareByDescending<PayerTotal> { it.cents }.thenBy { it.payer == null }.thenBy { it.payer.orEmpty() })

private val payerStart = Regex("(?i)\\bordinante\\s*:|\\bmittente\\s*:|(?:^|\\s)da\\s")
private val payerEnd = Regex("(?i)\\s-\\s|;|\\||\\b(?:causale|cro|rif(?:erimento)?|trn)\\b")

/**
 * The sender named in a bank description: the text after "da", "ordinante:" or "mittente:", cut before " - ", ";", "|"
 * or a causale/CRO/riferimento/TRN marker. Null when nothing readable follows.
 */
fun payerOf(description: String): String? {
    val start = payerStart.find(description) ?: return null
    val rest = description.substring(start.range.last + 1)
    val end = payerEnd.find(rest)?.range?.first ?: rest.length
    return rest.substring(0, end).trim().trim(',', '.', ':').trim().takeIf { it.isNotBlank() }
}

/** What the bank movements say about one F24 date. NON_RILEVATO: past and no matching payment found (not proof of non-payment). */
enum class F24State { PAGATO, DA_PAGARE, NON_RILEVATO }

/**
 * One F24 line. [due] null = the pack gives no date (state null). [amountCents] is null until the previous-year return is
 * known. [daysLeft] counts to [due] while it is not past.
 */
data class F24Line(val label: String, val due: LocalDate?, val amountCents: Long?, val state: F24State?, val daysLeft: Long?)

private val f24Text = Regex("(?i)\\bf24\\b|agenzia\\s+(?:delle\\s+)?entrate")
private const val F24_DAYS_BEFORE = 15L
private const val F24_DAYS_AFTER = 40L

/**
 * PAGATO when an outflow (not ignored) dated from 15 days before to 40 days after [due] names F24 or the Agenzia delle
 * Entrate; the kind TAX alone is not enough. Otherwise DA_PAGARE while [due] has not passed, else NON_RILEVATO.
 */
fun f24Status(due: LocalDate, today: LocalDate, outflows: List<ExternalMovement>): F24State {
    val from = due.minusDays(F24_DAYS_BEFORE)
    val to = due.plusDays(F24_DAYS_AFTER)
    val paid = outflows.any { m ->
        m.signedCents < 0 && m.status != ExternalStatus.IGNORED && !m.date.isBefore(from) && !m.date.isAfter(to) && f24Text.containsMatchIn(m.description)
    }
    return when {
        paid -> F24State.PAGATO
        !due.isBefore(today) -> F24State.DA_PAGARE
        else -> F24State.NON_RILEVATO
    }
}

/**
 * The F24 lines of the current year (saldo and 1° acconto share one date and one line), plus next year's once every date of
 * this year has passed. Dates come from the pack only. The caller gates by [F24Kinds].
 */
fun Archive.f24Lines(rules: Rules, today: LocalDate): List<F24Line> {
    val liquidity = liquidityIds()
    val outflows = externalMovements.filter { it.accountId in liquidity && it.signedCents < 0 && it.status != ExternalStatus.IGNORED }
    fun line(label: String, due: LocalDate?) = F24Line(
        label, due, null,
        state = due?.let { f24Status(it, today, outflows) },
        daysLeft = due?.takeIf { !it.isBefore(today) }?.let { ChronoUnit.DAYS.between(today, it) },
    )
    fun linesOf(year: Int): List<F24Line> {
        val dues = runCatching { rules.f24Schedule(year, null) }.getOrDefault(emptyList())
        fun date(kind: F24Kind): LocalDate? = dues.firstOrNull { it.kind == kind }?.due?.value
        fun has(kind: F24Kind, list: List<F24Due> = dues) = list.any { it.kind == kind }
        if (dues.isEmpty()) return emptyList()
        val saldo = date(F24Kind.SALDO); val first = date(F24Kind.ACCONTO_1)
        return buildList {
            if (has(F24Kind.SALDO) && has(F24Kind.ACCONTO_1) && saldo == first) add(line("F24 saldo e 1° acconto", saldo))
            else {
                if (has(F24Kind.SALDO)) add(line("F24 saldo", saldo))
                if (has(F24Kind.ACCONTO_1)) add(line("F24 1° acconto", first))
            }
            if (has(F24Kind.ACCONTO_2)) add(line("F24 2° acconto", date(F24Kind.ACCONTO_2)))
        }
    }
    val thisYear = linesOf(today.year)
    val allPast = thisYear.isNotEmpty() && thisYear.all { it.due != null && it.due.isBefore(today) }
    return if (allPast) thisYear + linesOf(today.year + 1) else thisYear
}

/** Pack rows the forfettario "Quanto accantonare" estimate stands on, as law rows valid at the date. */
private val accantonareRows = listOf("forf.coeff.table_valid_2026", "forf.reddito.formula")

/**
 * Status of every row the estimate needs: the two forfettario rows, then the gestione contribution row. The pack has no such
 * row yet, so that entry is null and [accantonareReady] stays false.
 */
fun Rules.accantonareStatuses(date: LocalDate): List<PackStatus?> =
    accantonareRows.map { pack.at(it, date)?.status } + listOf<PackStatus?>(null)

/** True only when there is something to check and every row is VERIFIED; a missing row (null) keeps the estimate hidden. */
fun accantonareReady(statuses: List<PackStatus?>): Boolean = statuses.isNotEmpty() && statuses.all { it == PackStatus.VERIFIED }
