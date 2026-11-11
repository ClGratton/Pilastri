package xyz.grtlabs.pilastri.sync.gmail

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Tolerant readers for the way Italian receipts write amounts and dates: "€ 33,33", "33,33 €", "EUR 1.234,56", "1,234.56",
 * "5 nov 2026", "5 novembre", "05/11/2026", "05.11.26", "2026-11-05", "oggi", "tra 2 giorni". Pure functions, no I/O.
 */
internal object ItalianFormats {
    data class AmountHit(val range: IntRange, val cents: Long)
    data class DateHit(val range: IntRange, val date: LocalDate)

    /** Largest amount accepted from a mail, in cents (10 million euro): beyond this the text is not a payment amount. */
    const val MAX_CENTS = 1_000_000_000L

    /**
     * "33,33" -> 3333, "1.234,56" -> 123456, "1,234.56" -> 123456, "1.234" -> 123400 (thousands), "33.33" -> 3333, "7" -> 700.
     * Null for anything that is not a plain positive amount.
     */
    fun parseCents(raw: String): Long? {
        val s = raw.trim().replace(" ", "").replace(" ", "").replace(" ", "")
        if (s.isEmpty() || !s.all { it.isDigit() || it == '.' || it == ',' } || !s.first().isDigit() || !s.last().isDigit()) return null
        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        val decimalAt = when {
            lastDot >= 0 && lastComma >= 0 -> maxOf(lastDot, lastComma)
            lastComma >= 0 -> if (s.count { it == ',' } == 1 && s.length - lastComma - 1 in 1..2) lastComma else -1
            lastDot >= 0 -> if (s.count { it == '.' } == 1 && s.length - lastDot - 1 in 1..2) lastDot else -1
            else -> -1
        }
        val whole = (if (decimalAt >= 0) s.substring(0, decimalAt) else s).filter { it.isDigit() }
        val fraction = if (decimalAt >= 0) s.substring(decimalAt + 1) else ""
        if (whole.isEmpty() || whole.length > 9 || fraction.any { !it.isDigit() }) return null
        val cents = whole.toLong() * 100 + fraction.padEnd(2, '0').toLong()
        return cents.takeIf { it in 1..MAX_CENTS }
    }

    private const val NUM = """\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"""
    private const val EURO = """(?:€|EUR(?![A-Za-z])|euro\b)"""

    // A euro sign followed by another number belongs to that number ("Rata 2 € 33,33" is 33,33, not 2).
    private val amountRe = Regex("""(?<![\d.,])(?:$EURO\s*($NUM)(?!\d)|($NUM)(?!\d)\s*$EURO(?!\s*\d))""", RegexOption.IGNORE_CASE)

    /** Euro amounts in reading order. Blank the dates first ([blankDates]) so that "2026 € 33,33" is not read as 2026 euro. */
    fun findAmounts(text: String): List<AmountHit> = amountRe.findAll(text).mapNotNull { m ->
        val token = m.groups[1]?.value ?: m.groups[2]?.value ?: return@mapNotNull null
        parseCents(token)?.let { AmountHit(m.range, it) }
    }.toList()

    /** [text] with every date replaced by spaces of the same length, so positions stay valid. */
    fun blankDates(text: String, ref: LocalDate): String {
        val chars = text.toCharArray()
        findDates(text, ref, relative = false).forEach { for (i in it.range) chars[i] = ' ' }
        return String(chars)
    }

    private val monthNumbers: Map<String, Int> = mapOf(
        "gennaio" to 1, "gen" to 1, "january" to 1, "jan" to 1,
        "febbraio" to 2, "feb" to 2, "february" to 2,
        "marzo" to 3, "mar" to 3, "march" to 3,
        "aprile" to 4, "apr" to 4, "april" to 4,
        "maggio" to 5, "mag" to 5, "may" to 5,
        "giugno" to 6, "giu" to 6, "june" to 6, "jun" to 6,
        "luglio" to 7, "lug" to 7, "july" to 7, "jul" to 7,
        "agosto" to 8, "ago" to 8, "august" to 8, "aug" to 8,
        "settembre" to 9, "sett" to 9, "set" to 9, "september" to 9, "sep" to 9,
        "ottobre" to 10, "ott" to 10, "october" to 10, "oct" to 10,
        "novembre" to 11, "nov" to 11, "november" to 11,
        "dicembre" to 12, "dic" to 12, "december" to 12, "dec" to 12,
    )
    private val monthAlt = monthNumbers.keys.sortedByDescending { it.length }.joinToString("|")

    private val isoRe = Regex("""(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)""")
    private val numericRe = Regex("""(?<![\d/.\-])(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{4}|\d{2})(?!\d)""")
    private val dayMonthRe = Regex("""(?<![\d/.\-])(\d{1,2})(?:º|°|\^|st|nd|rd|th)?\s+($monthAlt)\.?(?:\s*,?\s*(\d{4}))?(?!\w)""", RegexOption.IGNORE_CASE)
    private val monthDayRe = Regex("""(?<!\w)($monthAlt)\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})(?!\d)""", RegexOption.IGNORE_CASE)
    private val todayRe = Regex("""(?<!\w)(oggi|today)(?!\w)""", RegexOption.IGNORE_CASE)
    private val tomorrowRe = Regex("""(?<!\w)(domani|tomorrow)(?!\w)""", RegexOption.IGNORE_CASE)
    private val inDaysRe = Regex("""(?<!\w)(?:tra|fra|in)\s+(\d{1,3})\s+(?:giorni|giorno|days|day)(?!\w)""", RegexOption.IGNORE_CASE)

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        if (year in 2000..2100) runCatching { LocalDate.of(year, month, day) }.getOrNull() else null

    /** A day-month date without a year: the occurrence nearest to [ref], looking ahead rather than back. */
    private fun withoutYear(day: Int, month: Int, ref: LocalDate): LocalDate? {
        val candidate = safeDate(ref.year, month, day) ?: return null
        val days = ChronoUnit.DAYS.between(ref, candidate)
        return when {
            days < -60 -> safeDate(ref.year + 1, month, day)
            days > 300 -> safeDate(ref.year - 1, month, day)
            else -> candidate
        }
    }

    /**
     * Dates in reading order. Numeric dates are day/month/year (Italian). A date without year is read relative to [ref], the date
     * of the mail. With [relative], "oggi", "domani" and "tra N giorni" count as dates too.
     */
    fun findDates(text: String, ref: LocalDate, relative: Boolean = true): List<DateHit> {
        val hits = ArrayList<DateHit>()
        isoRe.findAll(text).forEach { m ->
            safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { hits += DateHit(m.range, it) }
        }
        numericRe.findAll(text).forEach { m ->
            val yearText = m.groupValues[3]
            val year = if (yearText.length == 2) 2000 + yearText.toInt() else yearText.toInt()
            safeDate(year, m.groupValues[2].toInt(), m.groupValues[1].toInt())?.let { hits += DateHit(m.range, it) }
        }
        dayMonthRe.findAll(text).forEach { m ->
            val month = monthNumbers[m.groupValues[2].lowercase()] ?: return@forEach
            val day = m.groupValues[1].toInt()
            val year = m.groupValues[3]
            val date = if (year.isEmpty()) withoutYear(day, month, ref) else safeDate(year.toInt(), month, day)
            date?.let { hits += DateHit(m.range, it) }
        }
        monthDayRe.findAll(text).forEach { m ->
            val month = monthNumbers[m.groupValues[1].lowercase()] ?: return@forEach
            safeDate(m.groupValues[3].toInt(), month, m.groupValues[2].toInt())?.let { hits += DateHit(m.range, it) }
        }
        if (relative) {
            todayRe.findAll(text).forEach { hits += DateHit(it.range, ref) }
            tomorrowRe.findAll(text).forEach { hits += DateHit(it.range, ref.plusDays(1)) }
            inDaysRe.findAll(text).forEach { m -> hits += DateHit(m.range, ref.plusDays(m.groupValues[1].toLong())) }
        }
        // Overlaps (a numeric reading inside a longer match) keep the earlier, longer hit.
        val sorted = hits.sortedWith(compareBy({ it.range.first }, { -(it.range.last - it.range.first) }))
        val out = ArrayList<DateHit>()
        for (h in sorted) if (out.isEmpty() || h.range.first > out.last().range.last) out += h
        return out
    }

    /** Spaces and invisible characters that HTML mail uses for layout become a plain space. */
    fun normalizeSpaces(text: String): String = text
        .replace(' ', ' ').replace(' ', ' ').replace(' ', ' ').replace('​', ' ').replace('‌', ' ')
        .replace('‍', ' ').replace('⁠', ' ').replace('﻿', ' ').replace('­', ' ')
}
