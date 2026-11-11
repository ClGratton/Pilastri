package xyz.grtlabs.pilastri.ai

import java.util.Collections
import java.util.EnumSet

/**
 * What a change does to the archive. The kind decides the approval tier in [AiPolicy.requiresTap].
 *
 * Tier 1 (applied directly, with a change card and a per-change undo): [ADD_PLAN_ITEM], [ADD_ACCOUNT],
 * [SET_IMPORT_CATEGORY], [IGNORE_IMPORT], [REOPEN_IMPORT], [IGNORE_INSTALLMENT_PLAN], [SET_INSTALLMENT_CATEGORY].
 *
 * Tier 2 (prepared as a pending change with a diff, applied only after the user taps "Applica"): [ADD_EVENT]
 * (contributions, withdrawals, income, fees), [ADD_VALUATION] (pillar and account), [CLASSIFY_IMPORT]
 * (events, trades and transfers created from an import), [CONFIRM_INSTALLMENT_PLAN] (a plan found by a connector becomes a
 * counted commitment: "applying an import"), [MARK_INSTALLMENT_PAID] (a dated payment fact with evidence). Transfers, trades
 * and indices have no assistant tool yet;
 * any kind added later is Tier 2 until it is listed as direct.
 *
 * Tier 3 (rule pack, Council 4 c): [RULE_LAW_CHANGE], [RULE_STEP_CHANGE] and [RULE_PERSONAL_CHANGE] (a regime attestation is
 * a PERSONAL_DECLARED value, so it is one of these). They are never Tier 1 or 2: each waits as its own row in the Agente panel,
 * with the diff on the person's numbers, and is confirmed one by one; [AiPolicy.tier3Kinds]. [RULE_REFRESH] (identical values
 * from a newer primary source or document: only dates change) and [SET_SCENARIO_VALUE] (Futuro only) are Tier 1.
 *
 * Never offered: performance attestations, archive restore, API keys and sessions, privacy toggles.
 * No tool exists for them; see [AiTools].
 */
enum class AiChangeKind {
    ADD_PLAN_ITEM,
    ADD_ACCOUNT,
    SET_IMPORT_CATEGORY,
    IGNORE_IMPORT,
    REOPEN_IMPORT,
    ADD_EVENT,
    ADD_VALUATION,
    CLASSIFY_IMPORT,
    CONFIRM_INSTALLMENT_PLAN,
    IGNORE_INSTALLMENT_PLAN,
    SET_INSTALLMENT_CATEGORY,
    MARK_INSTALLMENT_PAID,
    RULE_LAW_CHANGE,
    RULE_STEP_CHANGE,
    RULE_PERSONAL_CHANGE,
    RULE_REFRESH,
    SET_SCENARIO_VALUE,
}

object AiPolicy {
    /** Kinds that are Tier 1 (direct) when nothing else forces a tap. Read-only: a cast cannot add a kind to it. */
    val directKinds: Set<AiChangeKind> = Collections.unmodifiableSet(
        EnumSet.of(
            AiChangeKind.ADD_PLAN_ITEM,
            AiChangeKind.ADD_ACCOUNT,
            AiChangeKind.SET_IMPORT_CATEGORY,
            AiChangeKind.IGNORE_IMPORT,
            AiChangeKind.REOPEN_IMPORT,
            AiChangeKind.IGNORE_INSTALLMENT_PLAN,
            AiChangeKind.SET_INSTALLMENT_CATEGORY,
            AiChangeKind.RULE_REFRESH,
            AiChangeKind.SET_SCENARIO_VALUE,
        ),
    )

    /** Rule-pack changes confirmed row by row (Tier 3). Disjoint from [directKinds] by construction (checked at load). */
    val tier3Kinds: Set<AiChangeKind> = Collections.unmodifiableSet(
        EnumSet.of(AiChangeKind.RULE_LAW_CHANGE, AiChangeKind.RULE_STEP_CHANGE, AiChangeKind.RULE_PERSONAL_CHANGE),
    )

    init { check(directKinds.none { it in tier3Kinds }) { "A Tier 3 kind can never be direct" } }

    fun isTier3(kind: AiChangeKind): Boolean = kind in tier3Kinds

    /**
     * The single place that decides whether a kind waits for the user's tap. A pure function with no mutable state, so
     * no code path can weaken it. Callers may only ADD taps: [AiWriteRouter] also forces one when third-party text was
     * read in the turn or the conversation (taint rule), when a turn holds more than one write, or when its `extraTap`
     * says so; nothing can remove one.
     */
    fun requiresTap(kind: AiChangeKind): Boolean = kind !in directKinds

    /**
     * Separators people, banks and PDFs put between the groups of an IBAN or a card number: whitespace (also the no-break
     * U+00A0, the narrow no-break U+202F and every other Unicode space), ".", "-", "_" and "/". Kept in step with [isSep].
     */
    private const val SEP = """[\s\p{Z}.\-_/]"""

    /** Italian IBANs by shape (IT + 25 characters), with or without separators; no checksum, so a mistyped one is masked too. */
    private val itIban = Regex("""(?<![A-Za-z0-9])IT$SEP?\d{2}(?:$SEP?[A-Z0-9]){23}(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
    private val genericIbanStart = Regex("""(?<![A-Za-z0-9])[A-Z]{2}\d{2}""")
    private val fiscalCode = Regex("""\b[A-Z]{6}\d{2}[A-EHLMPR-T]\d{2}[A-Z]\d{3}[A-Z]\b""", RegexOption.IGNORE_CASE)

    /** 4-4-4-x and 4-6-5 groupings with the same separator throughout: never an amount or a date, masked without a checksum. */
    private val groupedCard = Regex(
        """(?<![A-Za-z0-9])\d{4}($SEP)\d{4}\1\d{4}\1\d{1,7}(?![A-Za-z0-9])|(?<![A-Za-z0-9])\d{4}($SEP)\d{6}\2\d{5}(?![A-Za-z0-9])""",
    )
    private val longDigits = Regex("""\d{9,}""")

    /**
     * Masks what must not leave the device when redaction is on: IBANs (Italian by shape, any other country with a valid
     * mod-97 checksum), Italian fiscal codes, card numbers (grouped as above, or any 13-19 digits passing the Luhn check,
     * with any of the [SEP] separators between groups) and any run of nine or more digits. Amounts ("1.234,56 €") and ISO
     * dates are unaffected. [longDigitRuns] = false keeps plain digit runs, for structured tool output whose ids and
     * numbers are produced by the app itself.
     */
    fun redact(text: String, longDigitRuns: Boolean = true): String {
        var out = itIban.replace(text, "[IBAN]")
        out = maskGenericIbans(out)
        out = fiscalCode.replace(out, "[CF]")
        out = groupedCard.replace(out, "[CARTA]")
        out = maskLuhnCards(out, plainRuns = longDigitRuns)
        if (longDigitRuns) out = longDigits.replace(out, "[NUMERO]")
        return out
    }

    private fun isSep(c: Char) = c.isWhitespace() || c == '.' || c == '-' || c == '_' || c == '/'
    private fun isDigit(c: Char) = c in '0'..'9'
    private fun isAlnum(c: Char) = c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z'
    private fun IntRange.size() = last - first + 1

    /**
     * Card numbers in any grouping: digit groups joined by single [isSep] separators are scanned for the longest run of
     * consecutive groups holding 13-19 digits that passes the Luhn check. Several groups must look like a card (first
     * group 4-6 digits, middle groups 3-6, last 1-7), so dates (2026-03-02) and amounts (1.234.567,89) never qualify.
     * A single group of 13-19 digits counts only when [plainRuns] is on (structured output keeps the app's own ids).
     */
    private fun maskLuhnCards(text: String, plainRuns: Boolean): String {
        val out = StringBuilder()
        var cursor = 0
        var i = 0
        while (i < text.length) {
            // A chain starts at a digit that does not continue a word or an id (a group glued to a letter is skipped).
            if (!isDigit(text[i]) || (i > 0 && isAlnum(text[i - 1]))) { i++; continue }
            val groups = ArrayList<IntRange>()
            var j = i
            while (true) {
                val start = j
                while (j < text.length && isDigit(text[j])) j++
                groups += start until j
                if (j + 1 < text.length && isSep(text[j]) && isDigit(text[j + 1])) { j++; continue }
                break
            }
            // A last group that runs into a letter is part of a word or an id, not of a card.
            if (j < text.length && isAlnum(text[j])) groups.removeAt(groups.lastIndex)
            var a = 0
            while (a < groups.size) {
                var digits = 0
                var best = -1
                var b = a
                while (b < groups.size) {
                    digits += groups[b].size()
                    if (digits > 19) break
                    if (digits >= 13 && cardShape(groups, a, b, plainRuns) && luhn(text, groups, a, b)) best = b
                    b++
                }
                if (best >= 0) {
                    out.append(text, cursor, groups[a].first).append("[CARTA]")
                    cursor = groups[best].last + 1
                    a = best + 1
                } else a++
            }
            i = maxOf(j, i + 1)
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    private fun cardShape(groups: List<IntRange>, a: Int, b: Int, plainRuns: Boolean): Boolean {
        if (a == b) return plainRuns
        if (groups[a].size() !in 4..6) return false
        for (k in a + 1 until b) if (groups[k].size() !in 3..6) return false
        return groups[b].size() in 1..7
    }

    private fun luhn(text: String, groups: List<IntRange>, a: Int, b: Int): Boolean {
        var sum = 0
        var double = false
        for (g in b downTo a) {
            for (k in groups[g].last downTo groups[g].first) {
                var d = text[k] - '0'
                if (double) { d *= 2; if (d > 9) d -= 9 }
                sum += d
                double = !double
            }
        }
        return sum % 10 == 0
    }

    private fun maskGenericIbans(text: String): String {
        val sb = StringBuilder()
        var cursor = 0
        var search = 0
        while (true) {
            val start = genericIbanStart.find(text, search) ?: break
            val begin = start.range.first
            // Collect up to 34 alphanumerics, allowing a single separator (space, NBSP, ".", "-", "_", "/") between them.
            val positions = ArrayList<Int>()
            var i = begin
            while (i < text.length && positions.size < 34) {
                val c = text[i]
                if (c in 'A'..'Z' || c in '0'..'9') { positions += i; i++ }
                else if (isSep(c) && i + 1 < text.length && (text[i + 1] in 'A'..'Z' || text[i + 1] in '0'..'9')) i++
                else break
            }
            var matchedEnd = -1
            var length = positions.size
            while (length >= 15) {
                val end = positions[length - 1] + 1
                val boundaryOk = end >= text.length || !(text[end] in 'A'..'Z' || text[end] in '0'..'9')
                if (boundaryOk) {
                    val compact = StringBuilder().also { sb2 -> positions.take(length).forEach { sb2.append(text[it]) } }.toString()
                    if (ibanChecksumValid(compact)) { matchedEnd = end; break }
                }
                length--
            }
            if (matchedEnd >= 0) {
                sb.append(text, cursor, begin).append("[IBAN]")
                cursor = matchedEnd
                search = matchedEnd
            } else {
                search = begin + 1
            }
        }
        sb.append(text, cursor, text.length)
        return sb.toString()
    }

    private fun ibanChecksumValid(iban: String): Boolean {
        if (iban.length !in 15..34) return false
        val rearranged = iban.substring(4) + iban.substring(0, 4)
        var remainder = 0
        for (ch in rearranged) {
            val value = if (ch in '0'..'9') ch - '0' else if (ch in 'A'..'Z') ch - 'A' + 10 else return false
            remainder = if (value < 10) (remainder * 10 + value) % 97 else (remainder * 100 + value) % 97
        }
        return remainder == 1
    }
}
