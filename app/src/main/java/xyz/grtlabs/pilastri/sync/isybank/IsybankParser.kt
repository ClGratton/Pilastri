package xyz.grtlabs.pilastri.sync.isybank

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.data.ImportedBalance
import xyz.grtlabs.pilastri.data.ImportedMovement

/**
 * Reads the Excel/CSV movements export of isybank (retail app: Conti > Visualizza tutte > Scarica PDF/Excel; internet
 * banking: Conti > Excel).
 *
 * The exact column spellings of isybank's own file are UNVERIFIED: no public isybank parser or sample exists. The layout
 * is therefore detected, not assumed. A header row is the first row within [HEADER_SCAN_ROWS] that has a date column
 * (Data / Data contabile / Data operazione ...) and either an Importo column or both Accrediti and Addebiti; columns are
 * mapped by normalized header text. This covers the two Intesa Sanpaolo group layouts known from the public
 * ofxstatement-intesasp plugin: "Lista Operazione" (Data, Operazione, Dettagli, Conto o carta, Contabilizzazione,
 * Categoria, Valuta, Importo with signed amounts) and "Lista Movimenti" (Data contabile, Data valuta, Descrizione,
 * Accrediti, Addebiti, Descrizione estesa, Mezzo).
 *
 * Everything here is pure: no network, no credentials, no Android classes.
 */
object IsybankParser {
    const val HEADER_SCAN_ROWS = 80
    const val MAX_FILE_BYTES = 10 * 1024 * 1024

    fun parse(bytes: ByteArray, fileName: String, fetchedAt: Instant): ImportBatch {
        val sheets = readSheets(bytes, fileName)
        for (sheet in sheets.sortedBy { sheetPriority(it.name) }) {
            val index = (0 until minOf(sheet.rows.size, HEADER_SCAN_ROWS)).firstOrNull { detectColumns(sheet.rows[it]) != null } ?: continue
            return parseSheet(sheet, index, detectColumns(sheet.rows[index])!!, fetchedAt)
        }
        throw IllegalArgumentException(
            "Il file non sembra un'esportazione dei movimenti isybank: non trovo la riga di intestazione con Data e Importo (oppure Accrediti e Addebiti).",
        )
    }

    // ---- input ---------------------------------------------------------------------------------------------

    private fun readSheets(bytes: ByteArray, fileName: String): List<Sheet> {
        if (bytes.isEmpty()) throw IllegalArgumentException("Il file è vuoto.")
        if (bytes.size > MAX_FILE_BYTES) throw IllegalArgumentException("Il file supera i 10 MB: non è un'esportazione dei movimenti isybank.")
        return when {
            Xlsx.isZip(bytes) -> Xlsx.read(bytes).sheets
            Xlsx.isLegacyXls(bytes) -> throw IllegalArgumentException(
                "Il formato Excel 97-2003 (.xls) non è supportato. Esporta di nuovo i movimenti in formato .xlsx oppure CSV.",
            )
            bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF" -> throw IllegalArgumentException(
                "Il PDF non può essere letto. In isybank scarica l'Excel dei movimenti (Conti › Excel) oppure un CSV.",
            )
            else -> {
                val head = Csv.decode(bytes.copyOf(minOf(bytes.size, 512))).trimStart('﻿', ' ', '\t', '\r', '\n')
                if (head.startsWith("<")) {
                    val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    throw IllegalArgumentException(
                        "Il file è una pagina web o un documento XML${if (ext.isNotEmpty()) " con estensione .$ext" else ""}, non un Excel. Esporta i movimenti in formato .xlsx oppure CSV.",
                    )
                }
                listOf(Csv.read(bytes))
            }
        }
    }

    private fun sheetPriority(name: String): Int = normalizeHeader(name).let { if ("lista operaz" in it || "lista movim" in it || "movimenti" in it) 0 else 1 }

    // ---- header detection ----------------------------------------------------------------------------------

    private class Columns(
        val date: Int,
        val valueDate: Int?,
        val amount: Int?,
        val credit: Int?,
        val debit: Int?,
        val operation: Int?,
        val descriptions: List<Int>,
        val medium: Int?,
        val account: Int?,
        val status: Int?,
        val category: Int?,
        val currency: Int?,
    )

    private val DATE_RANK = mapOf(
        "data contabile" to 3, "data" to 2,
        "data operazione" to 1, "data movimento" to 1, "data registrazione" to 1,
    )
    private val CREDIT = setOf("accrediti", "accredito", "entrate", "avere")
    private val DEBIT = setOf("addebiti", "addebito", "uscite", "dare")
    private val OPERATION = setOf("operazione", "tipo operazione", "tipologia", "tipo")
    private val DETAIL = setOf("dettagli", "dettaglio", "causale")

    private fun detectColumns(row: List<Cell?>): Columns? {
        var date: Int? = null
        var dateRank = 0
        var valueDate: Int? = null
        var amount: Int? = null
        var credit: Int? = null
        var debit: Int? = null
        var operation: Int? = null
        val descriptions = ArrayList<Int>()
        var medium: Int? = null
        var account: Int? = null
        var status: Int? = null
        var category: Int? = null
        var currency: Int? = null
        row.forEachIndexed { index, cell ->
            if (cell !is Cell.Text) return@forEachIndexed
            val h = normalizeHeader(cell.value)
            if (h.isEmpty()) return@forEachIndexed
            val rank = DATE_RANK[h]
            if (rank != null && rank > dateRank) { date = index; dateRank = rank }
            when {
                h == "data valuta" -> if (valueDate == null) valueDate = index
                h == "importo" || h.startsWith("importo ") -> if (amount == null) amount = index
                h in CREDIT -> if (credit == null) credit = index
                h in DEBIT -> if (debit == null) debit = index
                h in OPERATION -> if (operation == null) operation = index
                h.startsWith("descrizione") || h in DETAIL -> descriptions += index
                h == "mezzo" -> if (medium == null) medium = index
                h == "conto" || h == "carta" || h.startsWith("conto ") || h.startsWith("carta ") -> if (account == null) account = index
                "contabilizz" in h || h == "stato" -> if (status == null) status = index
                h == "categoria" -> if (category == null) category = index
                h == "valuta" || h == "divisa" -> if (currency == null) currency = index
            }
        }
        val dateColumn = date ?: return null
        if (amount == null && (credit == null || debit == null)) return null
        return Columns(dateColumn, valueDate, amount, credit, debit, operation, descriptions, medium, account, status, category, currency)
    }

    // ---- rows ----------------------------------------------------------------------------------------------

    private class Entry(
        val date: LocalDate,
        val cents: Long,
        val description: String,
        val kind: ExternalKind,
        val rawType: String?,
        val category: String?,
        val currency: String,
        val account: String,
    )

    private val PENDING = Regex("\\bnon contabilizz|\\bin corso\\b|\\bautorizzat|\\bpending\\b")

    private fun parseSheet(sheet: Sheet, headerIndex: Int, firstColumns: Columns, fetchedAt: Instant): ImportBatch {
        val rows = sheet.rows
        val entries = ArrayList<Entry>()
        var columns = firstColumns
        var pending = 0
        var unreadable = 0
        var blanksInside = 0
        var blankRun = 0
        for (r in headerIndex + 1 until rows.size) {
            val row = rows[r]
            val repeatedHeader = detectColumns(row)
            if (repeatedHeader != null) { columns = repeatedHeader; blankRun = 0; continue }
            if (row.all { it.asText().isEmpty() }) { blankRun++; continue }
            if (entries.isNotEmpty()) blanksInside += blankRun
            blankRun = 0

            val c = columns
            val dateCell = row.at(c.date)
            val status = normalizeHeader(row.at(c.status).asText())
            val datePending = dateCell is Cell.Text && PENDING.containsMatchIn(normalizeHeader(dateCell.value))
            if (PENDING.containsMatchIn(status) || datePending) { pending++; continue }

            val date = dateCell.asDate(allowSerial = true) ?: row.at(c.valueDate).asDate(allowSerial = true)
            val cents = amountCents(row, c)
            if (date == null || cents == null) {
                if (date != null || hasAmountText(row, c)) unreadable++
                continue
            }

            val operation = row.at(c.operation).asText().collapse()
            val details = c.descriptions.map { row.at(it).asText().collapse() }.filter { it.isNotEmpty() }
            val medium = row.at(c.medium).asText().collapse()
            val description = joinDistinct(details).ifEmpty { operation }.ifEmpty { medium }.ifEmpty { "Movimento isybank" }
            entries += Entry(
                date = date,
                cents = cents,
                description = description,
                kind = classifyKind("$operation ${details.joinToString(" ")} $medium", cents),
                rawType = operation.ifEmpty { medium }.takeIf { it.isNotEmpty() }?.take(100),
                category = row.at(c.category).asText().collapse().takeIf { it.isNotEmpty() }?.take(60),
                currency = currencyOf(row.at(c.currency).asText()),
                account = row.at(c.account).asText().collapse(),
            )
        }

        // externalId is a hash of date, cents, description and the occurrence index of that identical triple in the
        // file, so a re-import of an overlapping export gives the same ids and two identical purchases stay distinct.
        // The description is built by the rule above; changing that rule would change ids, so keep it stable.
        val seen = HashMap<String, Int>()
        val movements = entries.map { e ->
            val key = "${e.date}|${e.cents}|${e.description.lowercase(Locale.ROOT).collapse()}"
            val n = seen[key] ?: 0
            seen[key] = n + 1
            ImportedMovement(
                externalId = sha256Hex("$key|$n"), date = e.date, signedCents = e.cents, currency = e.currency,
                description = e.description, kind = e.kind, rawType = e.rawType, category = e.category,
            )
        }

        val preamble = rows.subList(0, headerIndex)
        val periodFrom = periodDate(preamble, "data inizio periodo")
        val periodTo = periodDate(preamble, "data fine periodo")
        val balance = findBalance(preamble)
        val account = entries.map { it.account }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val nonEur = entries.count { it.currency != "EUR" }

        val warnings = ArrayList<String>()
        if (movements.isEmpty()) warnings += "Nessun movimento contabilizzato trovato nel file."
        if (pending > 0) warnings += "${count(pending, "movimento non contabilizzato", "movimenti non contabilizzati")} (in corso) non importati: potrai importarli quando risulteranno registrati."
        if (unreadable > 0) warnings += "${count(unreadable, "riga con data o importo non leggibile ignorata", "righe con data o importo non leggibile ignorate")}."
        if (blanksInside > 0) warnings += "${count(blanksInside, "riga vuota ignorata", "righe vuote ignorate")} nella tabella."
        if (nonEur > 0) warnings += "${count(nonEur, "movimento in valuta diversa da EUR", "movimenti in valuta diversa da EUR")}: non verranno aggiunti."
        if (balance?.second == true) warnings += "Il saldo contabile non è nel file: usato il saldo disponibile indicato."

        return ImportBatch(
            provider = ImportProvider.ISYBANK,
            accountLabel = if (account != null) "isybank · $account".take(100) else "isybank",
            fetchedAt = fetchedAt,
            // No claim of coverage from a read that found no rows or could not read some (never "dati fino al ...").
            coverageFrom = if (movements.isEmpty() || unreadable > 0) null else periodFrom ?: entries.minOfOrNull { it.date },
            coverageTo = if (movements.isEmpty() || unreadable > 0) null else periodTo ?: entries.maxOfOrNull { it.date },
            movements = movements,
            balance = balance?.first,
            warnings = warnings,
        )
    }

    private fun amountCents(row: List<Cell?>, c: Columns): Long? {
        if (c.amount != null) return row.at(c.amount).asCents()
        val credit = row.at(c.credit)
        val debit = row.at(c.debit)
        val creditBlank = credit.isPlaceholder()
        val debitBlank = debit.isPlaceholder()
        if (creditBlank && debitBlank) return null
        // Accrediti and Addebiti are magnitudes (debits may be unsigned); the sign comes from the column.
        val credited = if (creditBlank) 0L else credit.asCents()?.let { Math.abs(it) } ?: return null
        val debited = if (debitBlank) 0L else debit.asCents()?.let { Math.abs(it) } ?: return null
        return credited - debited
    }

    private fun hasAmountText(row: List<Cell?>, c: Columns) = listOfNotNull(c.amount, c.credit, c.debit).any { !row.at(it).isPlaceholder() }

    // ---- kind ----------------------------------------------------------------------------------------------

    private val TAX = Regex("\\b(imposta|imposte|bollo|ritenut\\w*)\\b")
    private val FEE = Regex("\\b(commission\\w*|canone|canoni|spese)\\b")
    private val INTEREST = Regex("\\binteress\\w*")
    private val WITHDRAWAL = Regex("\\b(prelievo|prelievi|prelevamento)\\b")
    private val CARD = Regex("\\b(carta|carte|pos|pagamento|pagamenti|pagobancomat|bancomat|visa|mastercard|apple pay|google pay)\\b")
    private val REFUND = Regex("\\b(storno|rimborso|rimborsi|annullamento)\\b")

    /**
     * A hint for the review step, never an accounting decision. Fees and taxes are checked first so that "Commissioni su
     * pagamento" or "Imposta di bollo carta" are not read as card payments. Positive refunds, fee or tax credits are
     * OTHER, so they are never suggested as a personal contribution.
     */
    internal fun classifyKind(text: String, cents: Long): ExternalKind {
        val t = normalizeHeader(text)
        val negative = cents < 0
        return when {
            cents == 0L -> ExternalKind.OTHER
            TAX.containsMatchIn(t) -> if (negative) ExternalKind.TAX else ExternalKind.OTHER
            FEE.containsMatchIn(t) -> if (negative) ExternalKind.FEE else ExternalKind.OTHER
            INTEREST.containsMatchIn(t) -> if (negative) ExternalKind.FEE else ExternalKind.INTEREST
            negative && WITHDRAWAL.containsMatchIn(t) -> ExternalKind.WITHDRAWAL
            negative && CARD.containsMatchIn(t) -> ExternalKind.CARD_PAYMENT
            !negative && REFUND.containsMatchIn(t) -> ExternalKind.OTHER
            !negative -> ExternalKind.DEPOSIT
            else -> ExternalKind.WITHDRAWAL
        }
    }

    // ---- preamble ------------------------------------------------------------------------------------------

    private fun periodDate(preamble: List<List<Cell?>>, label: String): LocalDate? {
        for (row in preamble) for ((i, cell) in row.withIndex()) {
            if (cell !is Cell.Text || !normalizeHeader(cell.value).startsWith(label)) continue
            findDateInText(cell.value)?.let { return it }
            for (j in i + 1 until row.size) row[j].asDate(allowSerial = false)?.let { return it }
        }
        return null
    }

    /** A balance only when it is clearly labeled "Saldo contabile" (preferred) or "Saldo disponibile" and has a date. */
    private fun findBalance(preamble: List<List<Cell?>>): Pair<ImportedBalance, Boolean>? {
        for ((label, fallback) in listOf("saldo contabile" to false, "saldo disponibile" to true)) {
            for (row in preamble) for ((i, cell) in row.withIndex()) {
                if (cell !is Cell.Text || !normalizeHeader(cell.value).startsWith(label)) continue
                val valueIndex = (i + 1 until row.size).firstOrNull { row[it].asCents() != null } ?: continue
                val cents = row[valueIndex].asCents() ?: continue
                val date = findDateInText(cell.value)
                    ?: row.indices.filter { it != valueIndex && it != i }.firstNotNullOfOrNull { row[it].asDate(allowSerial = false) }
                    ?: continue
                return ImportedBalance(date, cents) to fallback
            }
        }
        return null
    }

    // ---- cells ---------------------------------------------------------------------------------------------

    private fun List<Cell?>.at(index: Int?): Cell? = if (index == null) null else getOrNull(index)

    private fun Cell?.asText(): String = when (this) {
        null -> ""
        is Cell.Text -> value.trim()
        is Cell.Num -> value.stripTrailingZeros().toPlainString()
        is Cell.Date -> value.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        is Cell.Bool -> value.toString()
    }

    private fun Cell?.isPlaceholder(): Boolean = asText().let { it.isEmpty() || it == "-" || it == "–" || it == "—" }

    private fun Cell?.asDate(allowSerial: Boolean): LocalDate? = when (this) {
        is Cell.Date -> value
        is Cell.Text -> parseDateText(value)
        is Cell.Num -> if (allowSerial && value.compareTo(BigDecimal(20_000)) >= 0 && value.compareTo(BigDecimal(100_000)) <= 0) Xlsx.serialToDate(value) else null
        else -> null
    }

    private fun Cell?.asCents(): Long? = when (this) {
        is Cell.Num -> value.toCents()
        is Cell.Text -> parseItalianDecimal(value)?.toCents()
        else -> null
    }

    private val ANCHORED_DATE = Regex("^\\s*(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4}|\\d{2})(?!\\d)")
    private val ISO_DATE = Regex("^\\s*(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)")
    private val EMBEDDED_DATE = Regex("(?<!\\d)(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4})(?!\\d)")

    internal fun parseDateText(text: String): LocalDate? {
        ISO_DATE.find(text)?.let { m -> return safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        val m = ANCHORED_DATE.find(text) ?: return null
        val year = m.groupValues[3].toInt().let { if (m.groupValues[3].length == 2) 2000 + it else it }
        return safeDate(year, m.groupValues[2].toInt(), m.groupValues[1].toInt())
    }

    private fun findDateInText(text: String): LocalDate? =
        EMBEDDED_DATE.find(text)?.let { safeDate(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? = try { LocalDate.of(year, month, day) } catch (e: java.time.DateTimeException) { null }

    private val THOUSANDS_DOTS = Regex("[1-9]\\d{0,2}(\\.\\d{3})+")

    /** Italian and plain numbers: "1.234,56", "-12,5", "(12,50)", "12,50-", "€ 3,00", "1234.56". Null if not a number. */
    internal fun parseItalianDecimal(raw: String): BigDecimal? {
        var t = raw.trim().replace(" ", "").replace(" ", "").replace("€", "").replace("EUR", "", ignoreCase = true).replace('−', '-')
        if (t.isEmpty()) return null
        var negative = false
        if (t.startsWith("(") && t.endsWith(")")) { negative = true; t = t.substring(1, t.length - 1) }
        if (t.endsWith("-")) { negative = true; t = t.dropLast(1) }
        if (t.startsWith("-")) { negative = true; t = t.drop(1) } else if (t.startsWith("+")) t = t.drop(1)
        if (t.isEmpty() || !t.all { it in '0'..'9' || it == '.' || it == ',' } || t.none { it in '0'..'9' }) return null
        val commas = t.count { it == ',' }
        val dots = t.count { it == '.' }
        val plain = when {
            commas > 0 && dots > 0 -> if (t.lastIndexOf(',') > t.lastIndexOf('.')) t.replace(".", "").replace(',', '.') else t.replace(",", "")
            commas > 1 -> t.replace(",", "")
            commas == 1 -> t.replace(',', '.')
            dots >= 1 && THOUSANDS_DOTS.matches(t) -> t.replace(".", "")
            dots > 1 -> return null
            else -> t
        }
        val number = try { BigDecimal(plain) } catch (e: NumberFormatException) { return null }
        return if (negative) number.negate() else number
    }

    private fun BigDecimal.toCents(): Long? {
        if (precision() - scale() > 15) return null
        return try { setScale(2, RoundingMode.HALF_UP).unscaledValue().longValueExact() } catch (e: ArithmeticException) { null }
    }

    private fun currencyOf(text: String): String {
        val t = text.trim().lowercase(Locale.ROOT)
        return when {
            t.isEmpty() || t == "euro" || t == "eur" || t == "€" -> "EUR"
            t.length == 3 && t.all { it in 'a'..'z' } -> t.uppercase(Locale.ROOT)
            else -> "XXX"
        }
    }

    // ---- text ----------------------------------------------------------------------------------------------

    private val MARKS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val SPACES = Regex("\\s+")

    /** Lowercase, accents removed, everything but letters and digits turned into single spaces. */
    internal fun normalizeHeader(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT).replace(NON_ALNUM, " ").trim()

    private fun String.collapse() = replace(SPACES, " ").trim()

    /** Joins the distinct pieces; a piece already contained in another (case-insensitive) is not repeated. */
    private fun joinDistinct(pieces: List<String>): String {
        val kept = ArrayList<String>()
        for (piece in pieces) {
            if (kept.any { it.contains(piece, ignoreCase = true) }) continue
            kept.removeAll { piece.contains(it, ignoreCase = true) }
            kept += piece
        }
        return kept.joinToString(" · ")
    }

    private fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
