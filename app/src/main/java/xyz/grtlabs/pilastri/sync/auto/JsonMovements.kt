package xyz.grtlabs.pilastri.sync.auto

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.data.ImportedMovement

/**
 * How a provider's JSON movement list maps to [ImportedMovement]. Paths are dotted keys from the item (or from the response root for
 * [items]; empty = the root is the array). Recorded from the real response shape by the primary agent; nothing here is guessed.
 */
data class JsonMapping(
    val items: String,
    val id: String,
    val date: String,
    val amount: String,
    val accountLabel: String,
    val currency: String? = null,
    val description: String? = null,
    val type: String? = null,
    /** Rows whose [status] (normalized) is in [skipStatuses] are pending and not imported. */
    val status: String? = null,
    val skipStatuses: Set<String> = emptySet(),
    val amountInCents: Boolean = false,
    /** Provider type text (normalized) to kind; anything else is OTHER, never guessed from the sign. */
    val kinds: Map<String, ExternalKind> = emptyMap(),
    val defaultCurrency: String = "EUR",
    /** True only when the endpoint is known to return the whole requested period in one answer (no paging). */
    val returnsWholeRange: Boolean = false,
)

object JsonMovements {
    private val ROME: ZoneId = ZoneId.of("Europe/Rome")
    private const val MAX_ITEMS = 50_000
    private val DAY_FIRST = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

    fun read(bytes: ByteArray, m: JsonMapping, provider: ImportProvider, window: SyncRange, fetchedAt: Instant): ImportBatch {
        val root = try {
            JSONTokener(String(bytes, StandardCharsets.UTF_8)).nextValue()
        } catch (_: Exception) {
            throw IllegalArgumentException("La risposta non è JSON leggibile.")
        }
        val items = (if (m.items.isEmpty()) root else walk(root, m.items)) as? JSONArray
            ?: throw IllegalArgumentException("La risposta non contiene l'elenco dei movimenti: formato da aggiornare.")
        if (items.length() > MAX_ITEMS) throw IllegalArgumentException("Troppi movimenti in una risposta.")
        val out = ArrayList<ImportedMovement>()
        var invalid = 0
        var pending = 0
        for (i in 0 until items.length()) {
            val item = items.opt(i) as? JSONObject ?: run { invalid++; null } ?: continue
            if (m.status != null && norm(text(walk(item, m.status))) in m.skipStatuses.map(::norm)) { pending++; continue }
            val id = text(walk(item, m.id))?.takeIf { it.length in 1..120 }
            val date = date(walk(item, m.date))
            val cents = cents(walk(item, m.amount), m.amountInCents)
            if (id == null || date == null || cents == null) { invalid++; continue }
            val typeText = m.type?.let { text(walk(item, it)) }
            out += ImportedMovement(
                externalId = id,
                date = date,
                signedCents = cents,
                currency = m.currency?.let { text(walk(item, it)) }?.uppercase(Locale.ROOT)?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: m.defaultCurrency,
                description = (m.description?.let { text(walk(item, it)) } ?: typeText ?: "Movimento").take(200),
                kind = typeText?.let { m.kinds[norm(it)] } ?: ExternalKind.OTHER,
                rawType = typeText?.take(80),
            )
        }
        // A read that finds no rows, or rows that do not parse, is a failure: it never claims coverage ("dati fino al ...").
        if (invalid > 0) throw IllegalArgumentException("$invalid righe non leggibili: formato da aggiornare.")
        if (out.isEmpty() && pending == 0) throw IllegalArgumentException("Nessun movimento letto: lettura non riuscita.")
        val warnings = buildList {
            if (invalid > 0) add("$invalid righe non leggibili non importate.")
            if (pending > 0) add("$pending movimenti in sospeso non importati.")
            if (!m.returnsWholeRange) add("Periodo coperto calcolato dalle date lette.")
        }
        return ImportBatch(
            provider = provider,
            accountLabel = m.accountLabel,
            fetchedAt = fetchedAt,
            coverageFrom = if (m.returnsWholeRange) window.from else out.minOfOrNull { it.date },
            coverageTo = if (m.returnsWholeRange) window.to else out.maxOfOrNull { it.date },
            movements = out,
            warnings = warnings,
        )
    }

    internal fun walk(root: Any?, path: String): Any? {
        var x = root
        for (key in path.split('.')) {
            x = when (x) {
                is JSONObject -> x.opt(key)
                is JSONArray -> key.toIntOrNull()?.let { x.opt(it) }
                else -> return null
            }
        }
        return x.takeUnless { it == JSONObject.NULL }
    }

    private fun text(v: Any?): String? = when (v) {
        null -> null
        is String -> v.trim().takeIf { it.isNotEmpty() }
        is Number, is Boolean -> v.toString()
        else -> null
    }

    private fun norm(s: String?) = LoginMatcher.norm(s)

    internal fun date(v: Any?): LocalDate? = when (v) {
        is Number -> runCatching { Instant.ofEpochMilli(v.toLong()).atZone(ROME).toLocalDate() }.getOrNull()
        is String -> v.trim().let { s ->
            runCatching { LocalDate.parse(s) }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(s).atZoneSameInstant(ROME).toLocalDate() }.getOrNull()
                ?: runCatching { LocalDate.parse(s.take(10)) }.getOrNull()
                ?: runCatching { LocalDate.parse(s, DAY_FIRST) }.getOrNull()
        }
        else -> null
    }

    /** Signed cents; null for anything ambiguous or with sub-cent digits (never rounded silently). */
    internal fun cents(v: Any?, inCents: Boolean): Long? {
        val decimal: BigDecimal = when (v) {
            is Int, is Long -> BigDecimal.valueOf((v as Number).toLong())
            is Number -> runCatching { BigDecimal(v.toString()) }.getOrNull()
            is String -> AmountText.parse(v)
            else -> null
        } ?: return null
        val value = if (inCents) decimal else decimal.movePointRight(2)
        return runCatching { value.setScale(0, RoundingMode.UNNECESSARY).longValueExact() }.getOrNull()
    }
}

/** Amount text as Italian and English pages write it: "−1.234,56 €", "-12.34", "+5,00", "EUR 3,10". */
object AmountText {
    private val SPACES = Regex("[\\s\\u00A0\\u202F]+")

    fun parse(raw: String): BigDecimal? {
        var s = raw.replace(SPACES, "").replace('−', '-').replace('–', '-')
        s = s.replace(Regex("(?i)eur|usd|gbp|chf|€|\\$|£"), "")
        var negative = false
        if (s.startsWith("-")) { negative = true; s = s.substring(1) } else if (s.startsWith("+")) s = s.substring(1)
        if (s.endsWith("-")) { negative = true; s = s.dropLast(1) }
        if (s.isEmpty() || !s.matches(Regex("[0-9.,]+"))) return null
        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        val normalized = when {
            lastComma >= 0 && lastDot >= 0 -> if (lastComma > lastDot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            lastComma >= 0 -> if (s.count { it == ',' } == 1) s.replace(',', '.') else return null
            // "1.234" alone is ambiguous between a thousands separator and decimals: accepted only with 1-2 decimals.
            lastDot >= 0 -> if (s.count { it == '.' } == 1 && s.length - lastDot - 1 in 1..2) s else if (s.matches(Regex("\\d{1,3}(\\.\\d{3})+"))) s.replace(".", "") else return null
            else -> s
        }
        val value = runCatching { BigDecimal(normalized) }.getOrNull() ?: return null
        return if (negative) value.negate() else value
    }

    /** ISO code for a symbol or code in the text, or null. */
    fun currency(raw: String): String? = when {
        raw.contains('€') || raw.contains("EUR", ignoreCase = true) -> "EUR"
        raw.contains('£') || raw.contains("GBP", ignoreCase = true) -> "GBP"
        raw.contains("CHF", ignoreCase = true) -> "CHF"
        raw.contains("USD", ignoreCase = true) || raw.contains('$') -> "USD"
        else -> null
    }
}
