package xyz.grtlabs.pilastri.sync.eb

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.data.ImportedBalance
import xyz.grtlabs.pilastri.data.ImportedMovement
import xyz.grtlabs.pilastri.sync.auto.SyncRange
import xyz.grtlabs.pilastri.sync.isybank.IsybankParser

/** An accounted-for-later movement (pending, on hold, scheduled). Kept apart: never in an [ImportBatch], never spending. */
class EbPendingMovement(val date: LocalDate, val signedCents: Long, val currency: String, val description: String, val status: String?) {
    override fun toString() = "EbPendingMovement(***)"
}

/** A balance the bank reported, with the date it refers to. [dateKnown] false: the API gave no date and the read date was used. */
class EbBalanceSnapshot(val type: String?, val cents: Long, val currency: String, val date: LocalDate, val dateKnown: Boolean) {
    override fun toString() = "EbBalanceSnapshot($type)"
}

/** Counts only; safe to log. */
data class EbStats(val booked: Int, val pending: Int, val ignored: Int, val pages: Int)

sealed interface EbReadResult {
    /**
     * A completed read. [batch] holds booked movements only and claims coverage for the whole requested range.
     * [emptyConfirmed] is true when the bank positively answered an empty list for the finished range.
     * [pendingComplete] is false when the pending list could not be read (it is optional and never affects coverage).
     */
    class Ok(
        val batch: ImportBatch,
        val pending: List<EbPendingMovement>,
        val pendingComplete: Boolean,
        val balances: List<EbBalanceSnapshot>,
        val emptyConfirmed: Boolean,
        val stats: EbStats,
    ) : EbReadResult

    /** No coverage, no movements: the read is not trusted. [message] is Italian, user-facing and carries no data. */
    data class Failed(val message: String) : EbReadResult
}

/**
 * Maps one account's Enable Banking answers to the app's [ImportBatch].
 *
 * Rules:
 * - Coverage ([ImportBatch.coverageFrom]/[ImportBatch.coverageTo]) is the requested range, and only when the booked read is
 *   [EbTransactionsResult.complete] (every page read, no error). Otherwise the result is [EbReadResult.Failed].
 * - A row that cannot be read (no amount, sign, date or currency) makes the whole read a failure: coverage would claim a row that
 *   is missing. A read with zero rows is fine only because the API answered an explicit empty list for the finished range.
 * - Pending, held and scheduled rows go to [EbReadResult.Ok.pending]; cancelled and rejected rows are dropped.
 * - The balance keeps its date; only a booked balance (CLBD, ITBD) is preferred over an available one (CLAV, ITAV).
 * - Row ids are hashes of the account key, date, amount and text plus the occurrence index of an identical row, as the isybank
 *   parser does: no bank id is relied on (see BANK-INTEGRATIONS.md), and a re-read gives the same ids.
 */
object EbMapper {
    private val ROME: ZoneId = ZoneId.of("Europe/Rome")
    private val BOOKED_BALANCES = listOf("CLBD", "ITBD")
    private val AVAILABLE_BALANCES = listOf("CLAV", "ITAV")
    private val PENDING_STATUSES = setOf("PDNG", "HOLD", "SCHD", "OTHR")
    private val DROPPED_STATUSES = setOf("CNCL", "RJCT")

    fun map(
        provider: ImportProvider,
        bankName: String,
        account: EbLinkedAccount,
        range: SyncRange,
        booked: EbTransactionsResult,
        pending: EbTransactionsResult?,
        balances: List<EbBalance>?,
        fetchedAt: Instant,
    ): EbReadResult {
        if (!booked.complete) return EbReadResult.Failed("Lettura dei movimenti incompleta: nessun periodo confermato.")
        if (booked.unreadable > 0) return EbReadResult.Failed("${booked.unreadable} movimenti non leggibili: lettura non confermata.")

        val warnings = ArrayList<String>()
        val movements = ArrayList<ImportedMovement>()
        val pendingOut = ArrayList<EbPendingMovement>()
        val occurrences = HashMap<String, Int>()
        var unreadable = 0
        var ignored = 0
        var nonEur = 0
        for (t in booked.transactions) {
            val status = t.status?.uppercase(Locale.ROOT)
            when {
                status in DROPPED_STATUSES -> ignored++
                status in PENDING_STATUSES -> parsePending(t)?.let { pendingOut += it } ?: run { ignored++ }
                else -> {
                    val row = parse(t)
                    if (row == null) { unreadable++; continue }
                    if (row.currency != "EUR") nonEur++
                    val key = "eb|${account.key}|${row.date}|${row.cents}|${row.currency}|${normalize(row.description)}"
                    val n = occurrences.getOrDefault(key, 0)
                    occurrences[key] = n + 1
                    movements += ImportedMovement(
                        externalId = sha256Hex("$key|$n"), date = row.date, signedCents = row.cents, currency = row.currency,
                        description = row.description, kind = row.kind, rawType = row.rawType,
                    )
                }
            }
        }
        if (unreadable > 0) return EbReadResult.Failed("$unreadable movimenti non leggibili: lettura non confermata.")

        var pendingComplete = true
        if (pending == null) {
            pendingComplete = false
            warnings += "Movimenti in sospeso non letti."
        } else {
            if (!pending.complete || pending.unreadable > 0) {
                pendingComplete = false
                warnings += "Movimenti in sospeso non letti per intero."
            }
            pending.transactions.forEach { t ->
                val status = t.status?.uppercase(Locale.ROOT)
                // A bank that ignores the status filter answers booked rows here too: they are in the booked read already.
                if (status == "BOOK" || status in DROPPED_STATUSES) return@forEach
                parsePending(t)?.let { pendingOut += it } ?: run { pendingComplete = false; warnings += "Un movimento in sospeso non è leggibile." }
            }
        }
        if (pendingOut.isNotEmpty()) warnings += "${pendingOut.size} movimenti in sospeso non importati: non contano come spesa."
        if (nonEur > 0) warnings += "$nonEur movimenti in valuta diversa da EUR: non verranno aggiunti."

        val snapshots = balances.orEmpty().mapNotNull { snapshot(it, fetchedAt) }
        val chosen = chooseBalance(snapshots)
        if (balances == null) warnings += "Saldo non letto."
        else if (chosen == null) warnings += "Nessun saldo in EUR dalla banca."
        else {
            if (chosen.type in AVAILABLE_BALANCES) warnings += "Il saldo contabile non è indicato: usato il saldo disponibile."
            if (!chosen.dateKnown) warnings += "Data del saldo non indicata: usata la data della lettura."
        }
        val emptyConfirmed = booked.transactions.isEmpty()
        if (emptyConfirmed) warnings += "La banca ha confermato nessun movimento nel periodo."

        val sorted = movements.sortedByDescending { it.date }
        val batch = ImportBatch(
            provider = provider,
            accountLabel = "$bankName · ${account.label}".take(100),
            fetchedAt = fetchedAt,
            coverageFrom = range.from,
            coverageTo = range.to,
            movements = sorted,
            balance = chosen?.let { ImportedBalance(it.date, it.cents) },
            warnings = warnings.distinct(),
        )
        return EbReadResult.Ok(
            batch = batch,
            pending = pendingOut.sortedByDescending { it.date },
            pendingComplete = pendingComplete,
            balances = snapshots,
            emptyConfirmed = emptyConfirmed,
            stats = EbStats(sorted.size, pendingOut.size, ignored, booked.pages + (pending?.pages ?: 0)),
        )
    }

    private class Row(val date: LocalDate, val cents: Long, val currency: String, val description: String, val kind: ExternalKind, val rawType: String?)

    private fun parse(t: EbTransaction): Row? {
        val sign = when (t.indicator?.uppercase(Locale.ROOT)) { "DBIT" -> -1L; "CRDT" -> 1L; else -> return null }
        val magnitude = EbApi.cents(t.amountText, signed = false) ?: return null
        val currency = t.currency?.uppercase(Locale.ROOT)?.takeIf { Regex("[A-Z]{3}").matches(it) } ?: return null
        val date = t.bookingDate ?: t.valueDate ?: t.transactionDate ?: return null
        val cents = sign * magnitude
        val description = describe(t, cents)
        return Row(date, cents, currency, description, IsybankParser.classifyKind("${t.bankCodeText.orEmpty()} $description", cents), (t.bankCodeText ?: t.bankCode)?.take(100))
    }

    private fun parsePending(t: EbTransaction): EbPendingMovement? {
        val sign = when (t.indicator?.uppercase(Locale.ROOT)) { "DBIT" -> -1L; "CRDT" -> 1L; else -> return null }
        val magnitude = EbApi.cents(t.amountText, signed = false) ?: return null
        val currency = t.currency?.uppercase(Locale.ROOT)?.takeIf { Regex("[A-Z]{3}").matches(it) } ?: return null
        val date = t.bookingDate ?: t.valueDate ?: t.transactionDate ?: return null
        return EbPendingMovement(date, sign * magnitude, currency, describe(t, sign * magnitude), t.status)
    }

    /** The other party first (the payee of an outflow, the payer of an inflow), then the remittance text or the note. */
    private fun describe(t: EbTransaction, cents: Long): String {
        val party = (if (cents < 0) t.creditorName ?: t.debtorName else t.debtorName ?: t.creditorName)
        val text = t.remittance.joinToString(" ").trim().ifEmpty { t.note.orEmpty() }
        return listOfNotNull(party?.takeIf { it.isNotBlank() }, text.takeIf { it.isNotBlank() })
            .joinToString(" · ").ifBlank { t.bankCodeText ?: "Movimento" }.replace(Regex("\\s+"), " ").take(200)
    }

    private fun snapshot(b: EbBalance, fetchedAt: Instant): EbBalanceSnapshot? {
        val cents = EbApi.cents(b.amountText, signed = true) ?: return null
        val currency = b.currency?.uppercase(Locale.ROOT)?.takeIf { it == "EUR" } ?: return null
        val known = b.referenceDate ?: b.lastChange?.atZone(ROME)?.toLocalDate()
        return EbBalanceSnapshot(b.type?.uppercase(Locale.ROOT), cents, currency, known ?: fetchedAt.atZone(ROME).toLocalDate(), known != null)
    }

    private fun chooseBalance(all: List<EbBalanceSnapshot>): EbBalanceSnapshot? {
        for (type in BOOKED_BALANCES + AVAILABLE_BALANCES) all.firstOrNull { it.type == type }?.let { return it }
        return null
    }

    private fun normalize(s: String) = s.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
