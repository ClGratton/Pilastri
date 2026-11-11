package xyz.grtlabs.pilastri.sync.paypal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.zip.ZipInputStream
import xyz.grtlabs.pilastri.data.Evidence
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.data.ImportedBalance
import xyz.grtlabs.pilastri.data.ImportedMovement
import xyz.grtlabs.pilastri.data.Installment
import xyz.grtlabs.pilastri.data.InstallmentPaymentSeen
import xyz.grtlabs.pilastri.data.InstallmentPlan
import xyz.grtlabs.pilastri.data.InstallmentSource
import xyz.grtlabs.pilastri.data.InstallmentState
import xyz.grtlabs.pilastri.data.PlanReview

/**
 * Reads the PayPal "Activity download" report (CSV or TAB, optionally a ZIP of CSV parts).
 *
 * UNVERIFIED AGAINST A REAL ITALIAN FILE (2026-10-31). PayPal's Activity Download report page lists the English header row
 * (Date, Time, TimeZone, Name, Type, Status, Currency, Gross, Fee, Net, ..., Transaction ID, ..., Reference Txn ID, ...,
 * Balance, ..., Transaction Event Code, ..., Balance Impact, ..., Number of Installments, Installment Amount, ...) and says the
 * date and money formats are localized to the account's country. The Italian header spellings below are not documented where
 * I could read them, so columns are found by normalized alias (English and Italian), never by position, and the T-code
 * reference is used when the file carries it. Date, gross amount and transaction code are the only required columns.
 *
 * Everything here is pure: no network, no credentials, no Android classes. E-mail addresses, postal addresses and phone
 * numbers in the file are never read into the result.
 */
object PaypalParser {
    const val MAX_FILE_BYTES = 10 * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 40 * 1024 * 1024
    const val MAX_ROWS = 200_000
    const val MAX_COLUMNS = 150
    const val MAX_CELL_CHARS = 1_000
    const val MAX_ZIP_ENTRIES = 24
    private const val HEADER_SCAN_ROWS = 20
    private const val PI3_LENDER = "PayPal Paga in 3 rate"
    private const val OTHER_LENDER = "PayPal pagamento a rate"
    private const val PI3_PARTS = 3

    fun parse(bytes: ByteArray, fileName: String, fetchedAt: Instant): ImportBatch {
        if (bytes.isEmpty()) throw IllegalArgumentException("Il file è vuoto.")
        if (bytes.size > MAX_FILE_BYTES) throw IllegalArgumentException("Il file supera i 10 MB: non sembra il report Attività di PayPal.")
        val text = when {
            startsWith(bytes, 0x25, 0x50, 0x44, 0x46) -> throw IllegalArgumentException(
                "Il PDF non può essere letto. In PayPal crea il report Attività nel formato CSV e scegli quel file.")
            startsWith(bytes, 0x50, 0x4B, 0x03, 0x04) -> unzip(bytes)
            startsWith(bytes, 0xD0, 0xCF, 0x11, 0xE0) -> throw IllegalArgumentException(
                "Il formato Excel non è supportato. In PayPal crea il report Attività nel formato CSV.")
            startsWith(bytes, 0x4D, 0x5A) || startsWith(bytes, 0x7F, 0x45, 0x4C, 0x46) || startsWith(bytes, 0x23, 0x21) -> throw IllegalArgumentException(
                "Questo file non è un report di PayPal.")
            else -> decode(bytes)
        }
        val head = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').take(200)
        if (head.startsWith("<")) {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            throw IllegalArgumentException("Il file è una pagina web o un documento XML${if (ext.isNotEmpty()) " con estensione .$ext" else ""}, non un CSV. Crea il report Attività di PayPal nel formato CSV.")
        }
        if (head.startsWith("!Type") || head.startsWith("!Account") || head.startsWith("!TRNS")) throw IllegalArgumentException(
            "Il formato QIF/IIF non è supportato. In PayPal crea il report Attività nel formato CSV.")
        return build(readRows(text), fetchedAt)
    }

    // ---- input ---------------------------------------------------------------------------------------------

    private fun startsWith(bytes: ByteArray, vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }

    /** ZIP of CSV/TAB parts (PayPal splits reports above 50,000 records). Entries are only read in memory, never written anywhere. */
    private fun unzip(bytes: ByteArray): String {
        val parts = ArrayList<String>()
        var total = 0L
        var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ZIP_ENTRIES) throw IllegalArgumentException("L'archivio contiene troppi file: non sembra il report Attività di PayPal.")
                val name = entry.name.lowercase(Locale.ROOT)
                if (entry.isDirectory || name.contains("..") || name.startsWith("/") || name.startsWith("__macosx") ||
                    !(name.endsWith(".csv") || name.endsWith(".tab") || name.endsWith(".txt"))) continue
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_EXPANDED_BYTES) throw IllegalArgumentException("L'archivio è troppo grande una volta aperto: non sembra il report Attività di PayPal.")
                    out.write(buffer, 0, n)
                }
                parts += decode(out.toByteArray())
            }
        }
        if (parts.isEmpty()) throw IllegalArgumentException("L'archivio non contiene file CSV. Crea il report Attività di PayPal nel formato CSV.")
        return parts.joinToString("\n")
    }

    internal fun decode(bytes: ByteArray): String = when {
        startsWith(bytes, 0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        startsWith(bytes, 0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        startsWith(bytes, 0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    /** RFC 4180 style reader: quoted fields may hold the delimiter, quotes ("") and line breaks. Cells are trimmed and capped. */
    internal fun readRows(text: String): List<List<String>> {
        val delimiter = detectDelimiter(text)
        val rows = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false

        fun endField() {
            if (fields.size < MAX_COLUMNS) fields += field.toString().trim().take(MAX_CELL_CHARS)
            field.setLength(0)
        }
        fun endRow() {
            endField()
            if (fields.any { it.isNotEmpty() }) {
                if (rows.size >= MAX_ROWS) throw IllegalArgumentException("Il file contiene troppe righe: non sembra il report Attività di PayPal.")
                rows += fields
            }
            fields = ArrayList()
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted -> when {
                    ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    ch == '"' -> quoted = false
                    else -> field.append(ch)
                }
                ch == '"' && field.isBlank() -> { quoted = true; field.setLength(0) }
                ch == delimiter -> endField()
                ch == '\r' -> { if (i + 1 < text.length && text[i + 1] == '\n') i++; endRow() }
                ch == '\n' -> endRow()
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) endRow()
        return rows
    }

    /** The delimiter that occurs most often (outside quotes) in the first non-empty line: PayPal uses `,` and TAB, Italian Excel re-saves use `;`. */
    private fun detectDelimiter(text: String): Char {
        val counts = mutableMapOf(',' to 0, ';' to 0, '\t' to 0)
        var quoted = false
        var seen = false
        for (ch in text) {
            if (ch == '"') { quoted = !quoted; seen = true }
            else if (quoted) seen = true
            else if (ch == '\n') { if (seen) break }
            else if (ch != '\r') { seen = true; if (ch in counts) counts[ch] = counts.getValue(ch) + 1 }
        }
        val best = listOf(',', ';', '\t').maxByOrNull { counts.getValue(it) } ?: ','
        return if (counts.getValue(best) == 0) ',' else best
    }

    // ---- header --------------------------------------------------------------------------------------------

    private enum class Field { DATE, TIME, NAME, TYPE, STATUS, CURRENCY, GROSS, FEE, NET, TXID, REF, BALANCE, IMPACT, ITEM, CODE, INSTALLMENTS, INSTALLMENT_AMOUNT }

    /** Normalized header text to (field, rank): when two columns claim one field the higher rank wins. Older Italian files call the type column "Descrizione". */
    private val ALIASES: Map<String, Pair<Field, Int>> = buildMap {
        fun add(field: Field, rank: Int, vararg names: String) = names.forEach { put(it, field to rank) }
        add(Field.DATE, 1, "date", "data")
        add(Field.TIME, 1, "time", "ora", "orario")
        add(Field.NAME, 1, "name", "nome")
        add(Field.TYPE, 2, "type", "tipo", "tipo di transazione", "tipo transazione")
        add(Field.TYPE, 1, "descrizione", "description")
        add(Field.STATUS, 1, "status", "stato")
        add(Field.CURRENCY, 1, "currency", "valuta", "divisa")
        add(Field.GROSS, 2, "gross", "lordo")
        add(Field.GROSS, 1, "amount", "importo")
        add(Field.FEE, 1, "fee", "tariffa", "commissione", "commissioni")
        add(Field.NET, 1, "net", "netto")
        add(Field.TXID, 1, "transaction id", "codice transazione", "id transazione", "codice della transazione", "numero transazione", "txn id")
        add(Field.REF, 1, "reference txn id", "codice transazione di riferimento", "id transazione di riferimento", "codice di riferimento",
            "codice transazione riferimento", "id transazione riferimento", "codice riferimento transazione", "riferimento")
        add(Field.BALANCE, 1, "balance", "saldo")
        add(Field.IMPACT, 1, "balance impact", "impatto sul saldo", "effetto sul saldo", "impatto saldo")
        add(Field.ITEM, 1, "item title", "titolo oggetto", "titolo articolo", "nome articolo", "descrizione articolo")
        add(Field.CODE, 1, "transaction event code", "codice evento transazione", "codice evento")
        add(Field.INSTALLMENTS, 1, "number of installments", "numero di rate", "numero rate")
        add(Field.INSTALLMENT_AMOUNT, 1, "installment amount", "importo rata", "importo della rata")
    }

    private class Columns(val index: Map<Field, Int>) {
        fun cell(row: List<String>, field: Field): String = index[field]?.let { row.getOrNull(it) }.orEmpty()
        fun has(field: Field) = field in index
    }

    private fun detectColumns(row: List<String>): Columns? {
        val best = HashMap<Field, Pair<Int, Int>>() // field -> (rank, column)
        row.forEachIndexed { column, text ->
            val (field, rank) = ALIASES[normalize(text)] ?: return@forEachIndexed
            val current = best[field]
            if (current == null || rank > current.first) best[field] = rank to column
        }
        if (Field.DATE !in best || Field.GROSS !in best) return null
        return Columns(best.mapValues { it.value.second })
    }

    // ---- rows ----------------------------------------------------------------------------------------------

    private enum class Impact { CREDIT, DEBIT, MEMO, UNKNOWN }

    private class Row(
        val order: Int,
        val txid: String,
        val date: LocalDate,
        val time: LocalTime?,
        val currency: String,
        val gross: Long,
        val fee: Long,
        val balance: Long?,
        val completed: Boolean,
        val impact: Impact,
        val typeText: String,
        val code: Int?,
        val name: String,
        val item: String,
        val reference: String?,
        /** Whole row, lowercase, accents removed, date-like text removed: for keyword search only. */
        val lower: String,
        val installmentCount: Int?,
        val cells: List<String>,
    )

    private class Counters {
        var notCompleted = 0
        var memo = 0
        var holds = 0
        var conversions = 0
        var reversalsOut = 0
        var refundsIn = 0
        var unrecognized = 0
        val unrecognizedTypes = LinkedHashSet<String>()
        var duplicates = 0
        var unreadable = 0
        var zero = 0
        var inconsistent = 0
        var nonEur = 0
        var installmentCovered = 0
        var installmentUnlinked = 0
        var orphanSpending = 0
        var foreignConverted = 0
        var foreignUnconverted = 0
        var foreignFundingExcluded = 0
    }

    private val COMPLETED = setOf("completed", "completato", "completata")
    private val TXID_SHAPE = Regex("[A-Za-z0-9._-]{3,64}")
    private val T_CODE_PREFIX = Regex("^\\s*T(\\d{4})\\b[\\s:.-]*")
    private val T_CODE_CELL = Regex("T?(\\d{4})")
    private val DATE_LIKE = Regex("\\d{1,4}[/.\\-]\\d{1,2}[/.\\-]\\d{1,4}")

    private fun build(allRows: List<List<String>>, fetchedAt: Instant): ImportBatch {
        val headerIndex = (0 until minOf(allRows.size, HEADER_SCAN_ROWS)).firstOrNull { detectColumns(allRows[it]) != null }
            ?: throw IllegalArgumentException(
                "Il file non sembra il report Attività di PayPal: non trovo le colonne Data e Lordo (Date e Gross nella versione inglese). Crea il report Attività nel formato CSV.")
        val columns = detectColumns(allRows[headerIndex])!!
        if (!columns.has(Field.TXID)) throw IllegalArgumentException(
            "Il report PayPal non contiene la colonna del codice transazione (Codice transazione / Transaction ID): senza non posso evitare i duplicati. Crea di nuovo il report con tutte le colonne.")

        val dates = allRows.drop(headerIndex + 1).map { columns.cell(it, Field.DATE) }
        val dateOrder = DateOrder.detect(dates)
        val counters = Counters()
        val parsed = ArrayList<Row>()
        val seenIds = HashSet<String>()
        var order = 0
        for (r in headerIndex + 1 until allRows.size) {
            val cells = allRows[r]
            // A repeated header row (parts of a ZIP) is not data.
            if (ALIASES[normalize(columns.cell(cells, Field.DATE))]?.first == Field.DATE) continue
            val txid = columns.cell(cells, Field.TXID)
            val date = parseDate(columns.cell(cells, Field.DATE), dateOrder.order)
            val gross = parseCents(columns.cell(cells, Field.GROSS))
            if (date == null || gross == null || !TXID_SHAPE.matches(txid)) { counters.unreadable++; continue }
            if (!seenIds.add(txid)) { counters.duplicates++; continue }

            val feeCell = parseCents(columns.cell(cells, Field.FEE))?.let { -Math.abs(it) }
            val net = parseCents(columns.cell(cells, Field.NET))
            val fee = feeCell ?: (net?.let { it - gross }?.takeIf { it < 0 } ?: 0L)
            if (net != null && feeCell != null && net != gross + feeCell) counters.inconsistent++
            val typeCell = columns.cell(cells, Field.TYPE)
            val prefix = T_CODE_PREFIX.find(typeCell)
            val code = T_CODE_CELL.find(columns.cell(cells, Field.CODE))?.groupValues?.get(1)?.toIntOrNull() ?: prefix?.groupValues?.get(1)?.toIntOrNull()
            parsed += Row(
                order = order++, txid = txid, date = date, time = parseTime(columns.cell(cells, Field.TIME)),
                currency = currencyOf(columns.cell(cells, Field.CURRENCY)), gross = gross, fee = fee,
                balance = parseCents(columns.cell(cells, Field.BALANCE)),
                completed = !columns.has(Field.STATUS) || normalize(columns.cell(cells, Field.STATUS)) in COMPLETED,
                impact = impactOf(columns.cell(cells, Field.IMPACT)),
                typeText = clean(if (prefix != null) typeCell.removeRange(prefix.range) else typeCell),
                code = code, name = clean(columns.cell(cells, Field.NAME)), item = clean(columns.cell(cells, Field.ITEM)),
                reference = columns.cell(cells, Field.REF).takeIf { TXID_SHAPE.matches(it) },
                lower = fold(cells.joinToString(" ").replace(DATE_LIKE, " ")),
                installmentCount = columns.cell(cells, Field.INSTALLMENTS).toIntOrNull()?.takeIf { it in 2..24 },
                cells = cells,
            )
        }

        val plans = extractPlans(parsed, fetchedAt)
        val foreign = extractForeign(parsed, plans.override)
        counters.foreignConverted = foreign.converted
        counters.foreignUnconverted = foreign.unconverted
        counters.foreignFundingExcluded = foreign.fundingExcluded
        val overrides = plans.override + foreign.override

        val movements = ArrayList<ImportedMovement>()
        for (row in parsed) {
            if (!row.completed) { counters.notCompleted++; continue }
            val override = overrides[row.txid]
            if (override is Override.Skip) { counters.installmentCovered++; continue }
            if (override is Override.Unlinked) { counters.installmentUnlinked++; continue }
            if (override is Override.ConversionLeg || override is Override.ForeignFunding) continue // counted in the foreign-currency summary
            if (row.impact == Impact.MEMO) { counters.memo++; continue }
            if (row.gross == 0L) { counters.zero++; continue }
            val verdict = when (override) {
                is Override.AsPayment, is Override.AsOrphanPayment, is Override.AsEur -> Verdict.Move(ExternalKind.CARD_PAYMENT)
                is Override.AsFunding -> Verdict.Move(ExternalKind.DEPOSIT)
                else -> classify(row.typeText, row.code, row.gross)
            }
            when (verdict) {
                is Verdict.Skip -> when (verdict.reason) {
                    SkipReason.HOLD -> counters.holds++
                    SkipReason.CONVERSION -> counters.conversions++
                    SkipReason.REVERSAL_OUT -> counters.reversalsOut++
                    SkipReason.UNRECOGNIZED -> { counters.unrecognized++; if (counters.unrecognizedTypes.size < 5) counters.unrecognizedTypes += row.typeText.ifBlank { "(senza tipo)" } }
                }
                is Verdict.Move -> {
                    if (verdict.refund) counters.refundsIn++
                    if (override is Override.AsOrphanPayment) counters.orphanSpending++
                    // A foreign-currency purchase whose conversion is in the file is imported at the euro amount PayPal took.
                    val euro = override as? Override.AsEur
                    if (euro == null && row.currency != "EUR") counters.nonEur++
                    val description = when (override) {
                        is Override.AsPayment -> override.text
                        is Override.AsOrphanPayment -> override.text
                        is Override.AsEur -> override.text
                        is Override.AsFunding -> override.text
                        else -> describe(verdict.kind, row)
                    }
                    movements += ImportedMovement(
                        externalId = row.txid, date = row.date, signedCents = euro?.cents ?: row.gross, currency = if (euro != null) "EUR" else row.currency,
                        description = description, kind = verdict.kind, rawType = row.typeText.ifBlank { null }?.take(100),
                    )
                    if (row.fee != 0L) movements += ImportedMovement(
                        externalId = "${row.txid}:fee", date = row.date, signedCents = row.fee, currency = row.currency,
                        description = "Commissione PayPal · $description".take(200), kind = ExternalKind.FEE, rawType = "Tariffa",
                    )
                }
            }
        }

        val warnings = ArrayList<String>()
        val balance = latestBalance(parsed, warnings)
        buildWarnings(counters, dateOrder, plans, movements.isEmpty(), columns).let { warnings.addAll(0, it) }
        val dated = parsed.map { it.date }
        return ImportBatch(
            provider = ImportProvider.PAYPAL,
            accountLabel = "PayPal",
            fetchedAt = fetchedAt,
            // No claim of coverage from a read that found no rows or could not read some (never "dati fino al ...").
            coverageFrom = if (movements.isEmpty() || counters.unreadable > 0) null else dated.minOrNull(),
            coverageTo = if (movements.isEmpty() || counters.unreadable > 0) null else dated.maxOrNull(),
            movements = movements.sortedWith(compareBy({ it.date }, { it.externalId })),
            balance = balance,
            warnings = warnings,
            installmentPlans = plans.plans,
            installmentPayments = plans.payments,
        )
    }

    private fun buildWarnings(c: Counters, order: DateOrder, plans: PlanExtraction, empty: Boolean, columns: Columns): List<String> {
        val w = ArrayList<String>()
        fun n(count: Int, one: String, many: String) = if (count == 1) "1 $one" else "$count $many"
        if (empty) w += "Nessun movimento completato trovato nel file."
        if (c.notCompleted > 0) w += "${n(c.notCompleted, "riga non completata esclusa", "righe non completate escluse")} (in sospeso, annullate, rifiutate o stornate)."
        if (c.memo > 0) w += "${n(c.memo, "riga senza effetto sul saldo esclusa", "righe senza effetto sul saldo escluse")} (promemoria)."
        if (c.holds > 0) w += "${n(c.holds, "blocco o autorizzazione escluso", "blocchi e autorizzazioni esclusi")}: non sono spese."
        if (c.conversions > 0) w += "${n(c.conversions, "conversione di valuta esclusa", "conversioni di valuta escluse")}: se hai pagato in valuta estera, registra a mano l'importo in euro."
        if (c.refundsIn > 0) w += "${n(c.refundsIn, "rimborso o storno in entrata importato", "rimborsi o storni in entrata importati")} come altro: non sono entrate né contributi."
        if (c.reversalsOut > 0) w += "${n(c.reversalsOut, "storno o rettifica in uscita escluso", "storni o rettifiche in uscita esclusi")}: non sono spese."
        if (c.unrecognized > 0) w += "${n(c.unrecognized, "uscita di tipo non riconosciuto esclusa", "uscite di tipo non riconosciuto escluse")} (${c.unrecognizedTypes.joinToString(", ")}): non importate come spesa."
        if (c.duplicates > 0) w += "${n(c.duplicates, "riga duplicata ignorata", "righe duplicate ignorate")} (stesso codice transazione)."
        if (c.unreadable > 0) w += "${n(c.unreadable, "riga con data, importo o codice non leggibile ignorata", "righe con data, importo o codice non leggibile ignorate")}."
        if (c.zero > 0) w += "${n(c.zero, "riga a importo zero ignorata", "righe a importo zero ignorate")}."
        if (c.inconsistent > 0) w += "${n(c.inconsistent, "riga con netto diverso da lordo più tariffa", "righe con netto diverso da lordo più tariffa")}: usati lordo e tariffa."
        if (c.nonEur > 0) w += "${n(c.nonEur, "movimento in valuta diversa da EUR", "movimenti in valuta diversa da EUR")}: non verranno aggiunti."
        if (c.foreignConverted > 0) w += "${n(c.foreignConverted, "acquisto in valuta estera importato in euro", "acquisti in valuta estera importati in euro")} con l'importo della conversione di PayPal."
        if (c.foreignUnconverted > 0) w += "${n(c.foreignUnconverted, "acquisto in valuta estera", "acquisti in valuta estera")} senza la conversione di valuta nel file: non importato come spesa. " +
            (if (c.foreignFundingExcluded > 0) "${n(c.foreignFundingExcluded, "ricarica in euro corrispondente esclusa", "ricariche in euro corrispondenti escluse")}, così l'addebito in banca resta una spesa e non si abbina; registra a mano l'importo in euro se serve."
            else "Non ho trovato con certezza la ricarica in euro corrispondente: l'addebito PAYPAL in banca potrebbe risultare un giro di fondi senza spesa; registra a mano l'importo in euro.")
        if (order.ambiguous) w += "Le date del file sono ambigue (giorno e mese entrambi 12 o meno): lette come giorno/mese."
        if (!columns.has(Field.STATUS)) w += "Manca la colonna Stato: tutte le righe sono state considerate completate."
        if (plans.plans.isNotEmpty()) w += "${n(plans.plans.size, "piano a rate trovato", "piani a rate trovati")}: controlla importi e scadenze prima di confermare. Gli addebiti PAYPAL del conto bancario non vanno contati due volte."
        if (c.installmentCovered > 0) w += "${n(c.installmentCovered, "riga a rate non contata come spesa", "righe a rate non contate come spesa")}: l'importo è già nell'acquisto o nelle rate."
        if (c.installmentUnlinked > 0) w += "${n(c.installmentUnlinked, "riga a rate non collegata a un acquisto del file non importata", "righe a rate non collegate a un acquisto del file non importate")}: scarica un periodo che includa l'acquisto."
        if (c.orphanSpending > 0) w += "${n(c.orphanSpending, "rata in uscita non collegata a un acquisto del file importata come spesa", "rate in uscita non collegate a un acquisto del file importate come spesa")}: " +
            "l'acquisto non è nel file e nessun importo di quel piano entra in PayPal nel file, quindi la rata è la spesa; scarica un periodo che includa l'acquisto per verificare che non sia già contato."
        if (plans.unnumbered > 0) w += "${n(plans.unnumbered, "rata di cui non riconosco il numero", "rate di cui non riconosco il numero")}: non segnata come pagata."
        return w
    }

    // ---- kind ----------------------------------------------------------------------------------------------

    private enum class SkipReason { HOLD, CONVERSION, REVERSAL_OUT, UNRECOGNIZED }

    private sealed interface Verdict {
        class Move(val kind: ExternalKind, val refund: Boolean = false) : Verdict
        class Skip(val reason: SkipReason) : Verdict
    }

    private enum class Group { PAYMENT, FEE, CONVERSION, FUNDING, WITHDRAWAL, BONUS, REFUND, HOLD, UNKNOWN }

    /** PayPal T-code families (developer.paypal.com T-code reference, read 2026-10-31). */
    private fun groupOfCode(code: Int): Group = when (code) {
        in 0..19 -> Group.PAYMENT
        in 100..199 -> Group.FEE
        in 200..299 -> Group.CONVERSION
        in 300..399 -> Group.FUNDING
        in 400..499 -> Group.WITHDRAWAL
        in 500..599 -> Group.PAYMENT
        in 600..699 -> Group.REFUND
        in 700..799 -> Group.FUNDING
        in 800..899 -> Group.BONUS
        1105, 1110, 1111 -> Group.HOLD
        in 1100..1299 -> Group.REFUND
        in 1300..1399, in 1500..1599 -> Group.HOLD
        else -> Group.UNKNOWN
    }

    private val HOLD_TEXT = Regex("\\b(blocco|blocchi|hold|autorizzazione|autorizzazioni|authorization|authorisation|void|svincolo|revoca)\\b|^(ordine|order)\\b")
    private val CONVERSION_TEXT = Regex("\\b(conversione|conversion|cambio valuta)\\b")
    private val REFUND_TEXT = Regex("\\b(rimborso|rimborsi|rimborsato|refund|storno|reversal|chargeback|contestazione|restituzione|rettifica|adjustment|correzione)\\b")
    private val FUNDING_TEXT = Regex("\\b(deposito|deposit|funding|ricarica|aggiunta fondi|top up)\\b|trasferimento (bancario|da conto|da banca)|bank transfer")
    private val WITHDRAWAL_TEXT = Regex("\\b(prelievo|prelievi|withdrawal)\\b|trasferimento (su|verso) (il )?(conto )?banc|transfer to (your )?bank")
    private val FEE_TEXT = Regex("\\b(commissione|commissioni|tariffa|tariffe|fee|fees|spese|canone)\\b")
    private val BONUS_TEXT = Regex("\\b(bonus|cashback|cash back|premio)\\b")
    private val PAYMENT_TEXT = Regex("\\b(pagamento|pagamenti|payment|checkout|acquisto|purchase|donazione|donation|abbonamento|subscription|preautorizzato|pre approved|preapproved|invio denaro|send money|rimessa)\\b")

    private fun groupOf(typeText: String, code: Int?): Group {
        val text = normalize(typeText)
        return code?.let(::groupOfCode)?.takeIf { it != Group.UNKNOWN } ?: when {
            HOLD_TEXT.containsMatchIn(text) -> Group.HOLD
            CONVERSION_TEXT.containsMatchIn(text) -> Group.CONVERSION
            REFUND_TEXT.containsMatchIn(text) -> Group.REFUND
            FUNDING_TEXT.containsMatchIn(text) -> Group.FUNDING
            WITHDRAWAL_TEXT.containsMatchIn(text) -> Group.WITHDRAWAL
            FEE_TEXT.containsMatchIn(text) -> Group.FEE
            BONUS_TEXT.containsMatchIn(text) -> Group.BONUS
            PAYMENT_TEXT.containsMatchIn(text) -> Group.PAYMENT
            else -> Group.UNKNOWN
        }
    }

    private fun classify(typeText: String, code: Int?, gross: Long): Verdict {
        val group = groupOf(typeText, code)
        val negative = gross < 0
        return when (group) {
            Group.HOLD -> Verdict.Skip(SkipReason.HOLD)
            Group.CONVERSION -> Verdict.Skip(SkipReason.CONVERSION)
            Group.PAYMENT -> Verdict.Move(if (negative) ExternalKind.CARD_PAYMENT else ExternalKind.OTHER)
            Group.FEE -> Verdict.Move(if (negative) ExternalKind.FEE else ExternalKind.OTHER)
            Group.FUNDING -> if (negative) Verdict.Skip(SkipReason.REVERSAL_OUT) else Verdict.Move(ExternalKind.DEPOSIT)
            Group.WITHDRAWAL -> Verdict.Move(if (negative) ExternalKind.WITHDRAWAL else ExternalKind.OTHER)
            Group.BONUS -> if (negative) Verdict.Skip(SkipReason.REVERSAL_OUT) else Verdict.Move(ExternalKind.BONUS)
            Group.REFUND -> if (negative) Verdict.Skip(SkipReason.REVERSAL_OUT) else Verdict.Move(ExternalKind.OTHER, refund = true)
            Group.UNKNOWN -> if (negative) Verdict.Skip(SkipReason.UNRECOGNIZED) else Verdict.Move(ExternalKind.OTHER)
        }
    }

    private fun describe(kind: ExternalKind, row: Row): String {
        val text = when (kind) {
            ExternalKind.CARD_PAYMENT -> row.name.ifBlank { row.item }.ifBlank { row.typeText }
            ExternalKind.DEPOSIT, ExternalKind.WITHDRAWAL, ExternalKind.FEE -> row.typeText.ifBlank { row.name }
            else -> listOf(row.typeText, row.name).filter { it.isNotBlank() }.joinToString(" · ")
        }
        return text.ifBlank { "Movimento PayPal" }.take(200)
    }

    // ---- installments --------------------------------------------------------------------------------------

    private sealed interface Override {
        /** The amount is already counted elsewhere (the purchase or its repayments): not a movement. */
        data object Skip : Override
        /** An installment row that names a purchase which is not in the file: not importable as spending. */
        data object Unlinked : Override
        /** A repayment that is the spending itself, because the purchase is not a cash movement of the account. */
        class AsPayment(val text: String) : Override
        /** Money entering PayPal to pay a part: funding that pairs with the bank debit. */
        class AsFunding(val text: String) : Override
        /** An outgoing part of a purchase that is not in the file, with no part of that plan entering PayPal in the file: the part is the spending. */
        class AsOrphanPayment(val text: String) : Override
        /** A foreign-currency purchase whose conversion is in the file: the spending, at the euro amount of the conversion's EUR leg. */
        class AsEur(val cents: Long, val text: String) : Override
        /** A leg of the currency conversion that [AsEur] used: the euro amount is already on the purchase. */
        data object ConversionLeg : Override
        /** The euro funding row of a foreign-currency purchase that could not be converted: excluded so the bank debit does not pair with it. */
        data object ForeignFunding : Override
    }

    private class PlanExtraction(
        val plans: List<InstallmentPlan>,
        val payments: List<InstallmentPaymentSeen>,
        val override: Map<String, Override>,
        val unnumbered: Int,
    )

    private val PI3_TEXT = Regex("\\b(paga in 3|pay in 3|paga in tre|pay in three)\\b")
    private val PART_NUMBER = Regex("\\b(?:rata|installment)\\s*(?:n\\s*)?([1-9])\\b|\\b([1-9])\\s*(?:/|di|of)\\s*3\\b|\\b(prima|seconda|terza|first|second|third)\\s+(?:rata|installment)\\b")
    private val ORDINALS = mapOf("prima" to 1, "seconda" to 2, "terza" to 3, "first" to 1, "second" to 2, "third" to 3)

    private fun isInstallmentRow(row: Row) = row.completed && (PI3_TEXT.containsMatchIn(row.lower) || row.installmentCount != null)

    private fun explicitPartNumber(row: Row, count: Int): Int? {
        val m = PART_NUMBER.find(row.lower) ?: return null
        val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { ORDINALS[m.groupValues[3]]?.toString().orEmpty() }
        return raw.toIntOrNull()?.takeIf { it in 1..count }
    }

    /**
     * Pay in 3 as PayPal's Italian pages describe it (help "Cos'è Paga in 3 rate?" and the Termini e Condizioni of 2026-09-09):
     * the purchase shows at its full amount, a second item is the one-third payment of the purchase day, and the two other
     * parts are collected one and two months after the day the merchant is paid. What the CSV rows of the parts look like (sign,
     * Type, whether they carry the purchase's code) is NOT documented, so a row is tied to a purchase by its code (Reference
     * Txn ID, or any cell equal to the code of another row) or, failing that, by being a third of an outflow on its day.
     *
     * The spending rule follows the first part: if it leaves the account it is a payment, so the purchase (financed, not cash)
     * is not counted and each negative part is the spending; if it enters the account it is funding that pairs with the bank's
     * PAYPAL debit, so the purchase is the spending and negative repayments are not counted again. Either way a purchase is
     * counted once. Parts are numbered from the text ("rata 2 di 3") or by months since the first part; due dates exist only when
     * a first part anchors them, and amounts of unpaid parts are the unpaid remainder split evenly (an odd cent goes to the last).
     */
    private fun extractPlans(rows: List<Row>, fetchedAt: Instant): PlanExtraction {
        val candidates = rows.filter(::isInstallmentRow)
        if (candidates.isEmpty()) return PlanExtraction(emptyList(), emptyList(), emptyMap(), 0)
        val allById = rows.associateBy { it.txid }
        val purchases = LinkedHashMap<String, Row>()
        val partsOf = HashMap<String, MutableList<Row>>()
        val orphans = ArrayList<Row>()
        val unlinked = ArrayList<Row>()

        for (row in candidates) {
            val target = row.reference?.takeIf { it != row.txid }?.let { allById[it] }
                ?: row.cells.asSequence().filter { it != row.txid && it.length >= 8 }.mapNotNull { allById[it] }.firstOrNull()
            when {
                target != null && target.completed && target.txid != row.txid -> {
                    purchases.putIfAbsent(target.txid, target)
                    partsOf.getOrPut(target.txid) { ArrayList() } += row
                }
                row.reference != null && row.reference != row.txid -> orphans += row
                else -> unlinked += row
            }
        }
        // An unlinked outflow is a purchase, unless it is a third of another outflow on its own day (then it is that purchase's first part).
        val outflows = unlinked.filter { it.gross < 0 }
        for (row in outflows) {
            val owner = outflows.firstOrNull { it !== row && isThirdOf(row, it) }
            if (owner != null) { purchases.putIfAbsent(owner.txid, owner); partsOf.getOrPut(owner.txid) { ArrayList() } += row }
            else purchases.putIfAbsent(row.txid, row)
        }
        for (row in unlinked) if (row.gross > 0) {
            val owner = outflows.firstOrNull { isThirdOf(row, it) }
            if (owner != null) { purchases.putIfAbsent(owner.txid, owner); partsOf.getOrPut(owner.txid) { ArrayList() } += row }
        }

        val override = HashMap<String, Override>()
        val plans = ArrayList<InstallmentPlan>()
        val payments = ArrayList<InstallmentPaymentSeen>()
        var unnumbered = 0
        val at = fetchedAt.toString()

        for ((id, purchase) in purchases) {
            val parts = partsOf[id].orEmpty().filter { it.txid != purchase.txid }.sortedWith(compareBy({ it.date }, { it.time }, { it.order }))
            val count = purchase.installmentCount ?: PI3_PARTS
            val firstPart = parts.firstOrNull()
            val purchaseImportable = purchase.impact != Impact.MEMO && purchase.gross != 0L
            val spendOnParts = !purchaseImportable || (firstPart != null && firstPart.gross < 0)
            val merchant = purchase.name.ifBlank { purchase.item }.takeIf { it.isNotBlank() }?.take(120)
            val label = merchant ?: "acquisto"
            val anchor = firstPart?.date?.takeIf { ChronoUnit.DAYS.between(purchase.date, it) in 0..10 }
            val total = if (purchase.currency == "EUR") Math.abs(purchase.gross).takeIf { it > 0 } else null
            if (spendOnParts) override[purchase.txid] = Override.Skip

            // Only the leg that carries the payment is numbered: the funding leg (money in) in the first layout, the outgoing leg in the second.
            val numbered = LinkedHashMap<Row, Int>()
            val taken = HashSet<Int>()
            val usable = parts.filter { it.currency == "EUR" && it.gross != 0L }
            for (part in usable.filter { (it.gross < 0) == spendOnParts }.ifEmpty { usable }) {
                val seq = explicitPartNumber(part, count)
                    ?: anchor?.let { 1 + Math.round(ChronoUnit.DAYS.between(it, part.date) / 30.4375).toInt() }
                if (seq != null && seq in 1..count && taken.add(seq)) numbered[part] = seq else unnumbered++
            }
            for (part in parts) {
                val seq = numbered[part]
                val text = (if (seq != null) "Rata $seq/$count · $label" else "Rata a rate · $label").take(200)
                when {
                    part.currency != "EUR" -> {}
                    part.gross < 0 -> override[part.txid] = if (spendOnParts) Override.AsPayment(text) else Override.Skip
                    else -> override[part.txid] = Override.AsFunding(text)
                }
            }

            val unseen = (1..count).filter { it !in taken }
            val paidTotal = numbered.keys.sumOf { Math.abs(it.gross) }
            val remaining = total?.minus(paidTotal)?.takeIf { it > 0 }
            val share: Map<Int, Long?> = if (remaining != null && unseen.isNotEmpty()) {
                val base = remaining / unseen.size
                val extra = (remaining % unseen.size).toInt()
                unseen.withIndex().associate { (i, seq) -> seq to (base + if (i >= unseen.size - extra) 1 else 0).takeIf { it > 0 } }
            } else unseen.associateWith { null }
            val rounded = remaining != null && unseen.isNotEmpty() && remaining % unseen.size != 0L

            val installments = (1..count).map { seq ->
                val paid = numbered.entries.firstOrNull { it.value == seq }?.key
                if (paid != null) Installment(seq, paid.date, Math.abs(paid.gross), InstallmentState.PAID, paidOn = paid.date)
                else Installment(seq, anchor?.plusMonths((seq - 1).toLong()), share[seq], InstallmentState.DUE)
            }

            val evidence = ArrayList<Evidence>()
            evidence += Evidence("paypal:${purchase.txid}", purchase.date, summary("Acquisto a rate · $label", total))
            numbered.forEach { (part, seq) -> evidence += Evidence("paypal:${part.txid}", part.date, summary("Rata $seq di $count pagata", Math.abs(part.gross))) }
            if (rounded) evidence += Evidence("paypal:${purchase.txid}-calc", purchase.date, "Importi delle rate non pagate calcolati dal totale: possibile differenza di 1 centesimo.")
            val pi3 = count == PI3_PARTS
            plans += InstallmentPlan(
                id = "${if (pi3) "paypal-pi3" else "paypal-rate"}:${purchase.txid}", lender = if (pi3) PI3_LENDER else OTHER_LENDER, merchant = merchant,
                totalCents = total, currency = "EUR", count = count, installments = installments, purchaseDate = purchase.date,
                source = InstallmentSource.PAYPAL, evidence = evidence.distinctBy { it.ref }.take(200), review = PlanReview.PENDING,
                extractedByAgent = false, updatedAt = at,
            )
            numbered.forEach { (part, seq) ->
                payments += InstallmentPaymentSeen("${if (pi3) "paypal-pi3" else "paypal-rate"}:${purchase.txid}", seq, part.date, Math.abs(part.gross),
                    Evidence("paypal:${part.txid}", part.date, summary("Rata $seq di $count pagata", Math.abs(part.gross))))
            }
        }

        // Parts whose purchase is not in this file: only an explicit number can place them, and the reference names the plan.
        // A plan whose parts enter PayPal in the file (funding that pairs with the bank debit) had its purchase counted as spending when it
        // was made, so its outgoing rows are repayments and stay out. An outgoing part with no such funding is the first-part-out layout:
        // there the repayments are the spending, and dropping them would make the later parts vanish from a monthly export.
        val fundedPlans = rows.filter { it.completed && it.currency == "EUR" && it.gross > 0 && it.reference != null && (it in orphans || groupOf(it.typeText, it.code) == Group.FUNDING) }
            .mapNotNull { it.reference }.toSet()
        for (row in orphans.sortedBy { it.order }) {
            val seq = explicitPartNumber(row, PI3_PARTS)
            val ref = row.reference
            val spending = row.gross < 0 && row.currency == "EUR" && ref !in fundedPlans
            val text = ((if (seq != null) "Rata $seq/$PI3_PARTS · " else "Rata a rate · ") + row.name.ifBlank { row.item }.ifBlank { "acquisto" }).take(200)
            if (ref != null && seq != null && row.currency == "EUR" && row.gross != 0L) {
                payments += InstallmentPaymentSeen("paypal-pi3:$ref", seq, row.date, Math.abs(row.gross),
                    Evidence("paypal:${row.txid}", row.date, summary("Rata $seq di $PI3_PARTS pagata", Math.abs(row.gross))))
                override[row.txid] = when {
                    row.gross > 0 -> Override.AsFunding("Rata $seq/$PI3_PARTS · Paga in 3 rate")
                    spending -> Override.AsOrphanPayment(text)
                    else -> Override.Unlinked
                }
            } else {
                if (ref != null && seq == null && row.gross != 0L) unnumbered++
                if (row.gross < 0) override[row.txid] = if (spending) Override.AsOrphanPayment(text) else Override.Unlinked
            }
        }
        return PlanExtraction(plans, payments.distinctBy { it.planId to it.evidence.ref }, override, unnumbered)
    }

    private class ForeignExtraction(val override: Map<String, Override>, val converted: Int, val unconverted: Int, val fundingExcluded: Int)

    private fun linked(a: Row, b: Row): Boolean =
        a.reference == b.txid || b.reference == a.txid ||
            a.cells.any { it.length >= 8 && it != a.txid && it == b.txid } || b.cells.any { it.length >= 8 && it != b.txid && it == a.txid }

    /**
     * Purchases in a foreign currency. PayPal funds them in euro (a deposit that pairs with the bank's PAYPAL debit), converts, and pays in the
     * foreign currency, which the import counts out: the purchase would vanish while its funding still pairs, so the bank debit shows as a
     * transfer. When the export holds the conversion pair that names the purchase (an EUR leg out and a leg in the purchase currency), the
     * purchase is imported in euro at the EUR leg's amount, with its own merchant description. Without the pair the matching euro funding
     * row (the one that names the purchase, else the only funding of that day) is excluded so nothing pairs, and the file is warned.
     */
    private fun extractForeign(rows: List<Row>, taken: Map<String, Override>): ForeignExtraction {
        val purchases = rows.filter {
            it.completed && it.currency != "EUR" && it.gross < 0 && it.impact != Impact.MEMO && it.txid !in taken && groupOf(it.typeText, it.code) == Group.PAYMENT
        }
        if (purchases.isEmpty()) return ForeignExtraction(emptyMap(), 0, 0, 0)
        val override = HashMap<String, Override>()
        var converted = 0
        var unconverted = 0
        var excluded = 0
        val usable = rows.filter { it.completed && it.impact != Impact.MEMO && it.txid !in taken }
        for (p in purchases) {
            val legs = usable.filter { groupOf(it.typeText, it.code) == Group.CONVERSION && it.txid !in override && linked(it, p) }
            val euroLegs = legs.filter { it.currency == "EUR" && it.gross < 0 }
            val foreignLegs = legs.filter { it.currency == p.currency && it.gross > 0 }
            if (euroLegs.size == 1 && foreignLegs.isNotEmpty()) {
                override[p.txid] = Override.AsEur(euroLegs[0].gross, describe(ExternalKind.CARD_PAYMENT, p))
                (euroLegs + foreignLegs).forEach { override[it.txid] = Override.ConversionLeg }
                converted++
                continue
            }
            unconverted++
            val fundings = usable.filter { it.currency == "EUR" && it.gross > 0 && it.txid !in override && groupOf(it.typeText, it.code) == Group.FUNDING }
            val named = fundings.filter { linked(it, p) }
            val alone = if (purchases.count { it.date == p.date } == 1) fundings.filter { it.date == p.date && it.reference == null } else emptyList()
            val match = if (named.isNotEmpty()) named.singleOrNull() else alone.singleOrNull()
            if (match != null) { override[match.txid] = Override.ForeignFunding; excluded++ }
        }
        return ForeignExtraction(override, converted, unconverted, excluded)
    }

    private fun isThirdOf(part: Row, purchase: Row) =
        part.currency == "EUR" && purchase.currency == "EUR" && part.gross != 0L && purchase.gross < 0 &&
            ChronoUnit.DAYS.between(purchase.date, part.date) in 0..1 && Math.abs(3 * Math.abs(part.gross) - Math.abs(purchase.gross)) <= 3

    private fun summary(what: String, cents: Long?): String = (if (cents != null) "$what · ${euros(cents)}" else what).take(200)

    private fun euros(cents: Long): String = "${cents / 100},${"%02d".format(cents % 100)} €"

    // ---- balance -------------------------------------------------------------------------------------------

    /** The running balance of the latest completed EUR row, only when that row is unambiguous. */
    private fun latestBalance(rows: List<Row>, warnings: MutableList<String>): ImportedBalance? {
        val withBalance = rows.filter { it.completed && it.currency == "EUR" && it.balance != null }
        val latest = withBalance.maxWithOrNull(compareBy<Row>({ it.date }, { it.time ?: LocalTime.MIN })) ?: return null
        val sameMoment = withBalance.filter { it.date == latest.date && (it.time ?: LocalTime.MIN) == (latest.time ?: LocalTime.MIN) }
        if (sameMoment.map { it.balance }.distinct().size > 1) {
            warnings += "Il saldo del file non è determinabile: più righe nello stesso istante riportano saldi diversi."
            return null
        }
        return latest.balance?.takeIf { it >= 0 }?.let { ImportedBalance(latest.date, it) }
    }

    // ---- cells ---------------------------------------------------------------------------------------------

    private class DateOrder(val order: Order, val ambiguous: Boolean) {
        enum class Order { DMY, MDY }

        companion object {
            /** Day first unless a value proves month first. Both proofs in one file is a contradiction. */
            fun detect(texts: List<String>): DateOrder {
                var dayFirst = false
                var monthFirst = false
                var any = false
                for (text in texts) {
                    val m = DATE_PARTS.find(text) ?: continue
                    if (m.groupValues[1].length == 4) continue
                    any = true
                    if (m.groupValues[1].toInt() > 12) dayFirst = true
                    if (m.groupValues[2].toInt() > 12) monthFirst = true
                }
                return when {
                    dayFirst && monthFirst -> throw IllegalArgumentException("Le date del file hanno formati contrastanti: non riesco a leggerle.")
                    monthFirst -> DateOrder(Order.MDY, false)
                    dayFirst -> DateOrder(Order.DMY, false)
                    else -> DateOrder(Order.DMY, any)
                }
            }
        }
    }

    private val DATE_PARTS = Regex("^\\s*(\\d{1,4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,4})\\s*$")

    private fun parseDate(text: String, order: DateOrder.Order): LocalDate? {
        val m = DATE_PARTS.find(text) ?: return null
        val a = m.groupValues[1]
        val b = m.groupValues[2].toInt()
        val c = m.groupValues[3]
        return try {
            when {
                a.length == 4 -> LocalDate.of(a.toInt(), b, c.toInt())
                c.length == 4 -> if (order == DateOrder.Order.DMY) LocalDate.of(c.toInt(), b, a.toInt()) else LocalDate.of(c.toInt(), a.toInt(), b)
                else -> null
            }
        } catch (e: DateTimeException) { null }
    }

    private fun parseTime(text: String): LocalTime? = try { if (text.isBlank()) null else LocalTime.parse(text.trim()) } catch (e: DateTimeException) { null }

    private fun impactOf(text: String): Impact = when (normalize(text)) {
        "memo", "promemoria" -> Impact.MEMO
        "credit", "credito", "accredito" -> Impact.CREDIT
        "debit", "debito", "addebito" -> Impact.DEBIT
        else -> Impact.UNKNOWN
    }

    private fun currencyOf(text: String): String {
        val t = text.trim().uppercase(Locale.ROOT)
        return when {
            t.isEmpty() || t == "EURO" || t == "€" -> "EUR"
            t.length == 3 && t.all { it in 'A'..'Z' } -> t
            else -> "XXX"
        }
    }

    /**
     * PayPal amounts carry two decimals. Accepts "1.234,56", "1,234.56", "-12,50", "12.50", "−3,00" and a bare integer.
     * "1,234" and "1.234" (one separator followed by three digits) are ambiguous between thousands and decimals and are
     * refused instead of guessed.
     */
    internal fun parseCents(raw: String): Long? {
        var t = raw.trim().replace(" ", "").replace("\u00A0", "").replace("\u202F", "").replace("€", "").replace('−', '-').replace("'", "")
        if (t.isEmpty()) return null
        var negative = false
        if (t.startsWith("(") && t.endsWith(")")) { negative = true; t = t.substring(1, t.length - 1) }
        if (t.endsWith("-")) { negative = true; t = t.dropLast(1) }
        if (t.startsWith("-")) { negative = true; t = t.drop(1) } else if (t.startsWith("+")) t = t.drop(1)
        if (t.isEmpty() || t.any { it !in '0'..'9' && it != '.' && it != ',' } || t.none { it in '0'..'9' }) return null
        val separators = t.filter { it == '.' || it == ',' }
        val digits: String
        if (separators.isEmpty()) digits = t + "00"
        else {
            val decimal = separators.last()
            val group = if (decimal == '.') ',' else '.'
            val last = t.lastIndexOf(decimal)
            val after = t.substring(last + 1)
            val before = t.substring(0, last)
            val thousandsOnly = separators.length >= 2 && separators.all { it == separators.first() }
            when {
                after.length in 1..2 -> {
                    if (before.isEmpty() || before.contains(decimal)) return null
                    if (before.contains(group) && !Regex("\\d{1,3}(${Regex.escape(group.toString())}\\d{3})+").matches(before)) return null
                    digits = before.replace(group.toString(), "") + after.padEnd(2, '0')
                }
                after.length == 3 && thousandsOnly -> {
                    if (!Regex("\\d{1,3}(${Regex.escape(decimal.toString())}\\d{3})+").matches(t)) return null
                    digits = t.replace(decimal.toString(), "") + "00"
                }
                else -> return null
            }
        }
        if (digits.length > 15) return null
        val cents = digits.toLong()
        return if (negative) -cents else cents
    }

    // ---- text ----------------------------------------------------------------------------------------------

    private val MARKS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val SPACES = Regex("\\s+")
    private val UNSAFE = Regex("[\\p{Cc}\\p{Cf}\\u2028\\u2029]")

    /** Lowercase with accents removed, punctuation kept. */
    private fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT).replace(SPACES, " ").trim()

    /** Lowercase, accents removed, everything but letters and digits turned into single spaces. */
    internal fun normalize(s: String): String = fold(s).replace(NON_ALNUM, " ").trim()

    /**
     * Text that is shown and may be exported later: control and bidirectional-override characters are removed and leading
     * `= + - @` dropped, so a cell can never be read as a spreadsheet formula.
     */
    internal fun clean(s: String): String =
        s.replace(UNSAFE, " ").replace(SPACES, " ").trim().trimStart('=', '+', '-', '@', ' ').take(200)
}
