package xyz.grtlabs.pilastri.data

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/*
 * Council 4 a: the person's working positions (archive v14). Nothing here names an employer, fund, contract or regime:
 * those are the person's data, read from documents or declared in setup. Missing is ND, never a default.
 */

/**
 * One working position. Combinations are several positions at once (a teacher with a side partita IVA is two).
 * Every field except [id] and [kind] is [Sourced]: documented, declared, calculated or ND.
 */
data class IncomePosition(
    val id: String,
    val kind: Coded<PositionKind>,
    val validFrom: Sourced<LocalDate> = Sourced.nd(),
    val validTo: Sourced<LocalDate> = Sourced.nd(),
    val label: Sourced<String> = Sourced.nd(),
    val gestione: Sourced<Coded<Gestione>> = Sourced.nd(),
    val severanceRegime: Sourced<Coded<SeveranceRegime>> = Sourced.nd(),
    val severanceDestination: Sourced<Coded<SeveranceDestination>> = Sourced.nd(),
    /** The contract term as the document or the person states it (the shared contract has no closed enum for it yet). */
    val contractTerm: Sourced<String> = Sourced.nd(),
    val hireDate: Sourced<LocalDate> = Sourced.nd(),
    /** ATECO code of a self-employed position, as stated. */
    val ateco: Sourced<String> = Sourced.nd(),
    /** Start of the position's tax regime (for example forfettario), as stated. */
    val regimeStart: Sourced<LocalDate> = Sourced.nd(),
    /** Who pays this income, as stated (employer, client, pension body). */
    val incomeSource: Sourced<String> = Sourced.nd(),
    /** Social-security contributions paid per year (DESIGN 9-05), one entry per year; missing years are simply absent, never 0. */
    val paidContributions: List<PaidContribution> = emptyList(),
) {
    init {
        require(id.isNotBlank() && id.length <= 120) { "Posizione non valida" }
        require(paidContributions.size <= 60 && paidContributions.map { it.year }.toSet().size == paidContributions.size) { "Contributi versati non validi" }
        listOf(label, contractTerm, ateco, incomeSource).forEach { s ->
            require(s.value == null || (s.value.isNotBlank() && s.value.length <= 200)) { "Posizione non valida" }
        }
        val from = validFrom.value
        val to = validTo.value
        require(from == null || to == null || !to.isBefore(from)) { "La posizione termina prima di iniziare" }
    }
}

/** Contributions paid in one [year], in cents. The amount is always known and never negative: an unknown year is not stored. */
data class PaidContribution(val year: Int, val amount: Sourced<Long>) {
    init {
        require(year in 2000..2100) { "Anno non valido" }
        val cents = amount.value
        require(amount.isKnown && cents != null && cents >= 0) { "Importo non valido" }
    }
}

/** How the figure got here, in the words the screen shows: the person's entry, the document field, or a calculation. */
fun PaidContribution.originLabel(): String = when (amount.origin) {
    Origin.DICHIARATO -> "inserito da te"
    Origin.DOCUMENTO -> amount.fieldLabel ?: "documento"
    Origin.CALCOLATO -> "calcolato"
    Origin.ND -> "n/d"
}

/** The most recent year with a paid-contributions figure, or null when none is known. */
fun IncomePosition.latestPaid(): PaidContribution? = paidContributions.maxByOrNull { it.year }

/** JSON form of [Sourced]: {value, origin, docId, fieldLabel, asOf}; every value is a string so decimals keep their scale. */
internal object SourcedJson {
    private fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else getString(k)

    fun <T : Any> encode(s: Sourced<T>, write: (T) -> String): JSONObject = JSONObject()
        .put("value", s.value?.let(write) ?: JSONObject.NULL)
        .put("origin", s.origin.name)
        .put("docId", s.docId ?: JSONObject.NULL)
        .put("fieldLabel", s.fieldLabel ?: JSONObject.NULL)
        .put("asOf", s.asOf?.toString() ?: JSONObject.NULL)

    fun <T : Any> decode(o: JSONObject, read: (String) -> T): Sourced<T> {
        val raw = o.str("value")
        require(raw == null || raw.length <= 500) { "Valore troppo lungo" }
        return Sourced(raw?.let(read), Origin.valueOf(o.getString("origin")), o.str("docId"), o.str("fieldLabel"), o.str("asOf")?.let(LocalDate::parse))
    }

    fun text(s: Sourced<String>) = encode(s) { it }
    fun date(s: Sourced<LocalDate>) = encode(s) { it.toString() }
    fun decimal(s: Sourced<BigDecimal>) = encode(s) { it.toPlainString() }
    fun <E : Enum<E>> code(s: Sourced<Coded<E>>) = encode(s) { it.raw }

    fun text(o: JSONObject, k: String): Sourced<String> = decode(o.getJSONObject(k)) { it }
    fun date(o: JSONObject, k: String): Sourced<LocalDate> = decode(o.getJSONObject(k), LocalDate::parse)
    fun decimal(o: JSONObject, k: String): Sourced<BigDecimal> = decode(o.getJSONObject(k)) {
        require(it.matches(Regex("-?(?:0|[1-9][0-9]{0,15})(?:\\.[0-9]{1,12})?"))) { "Decimale non valido" }
        BigDecimal(it)
    }
    inline fun <reified E : Enum<E>> code(o: JSONObject, k: String): Sourced<Coded<E>> = decode(o.getJSONObject(k)) { codedOf<E>(it) }
}

internal object PositionCodec {
    fun encode(list: List<IncomePosition>): JSONArray = JSONArray().apply {
        list.forEach { p -> put(positionJson(p)) }
    }

    private fun positionJson(p: IncomePosition): JSONObject {
        val o = JSONObject()
            .put("id", p.id).put("kind", p.kind.raw)
            .put("validFrom", SourcedJson.date(p.validFrom)).put("validTo", SourcedJson.date(p.validTo))
            .put("label", SourcedJson.text(p.label)).put("gestione", SourcedJson.code(p.gestione))
            .put("severanceRegime", SourcedJson.code(p.severanceRegime)).put("severanceDestination", SourcedJson.code(p.severanceDestination))
            .put("contractTerm", SourcedJson.text(p.contractTerm)).put("hireDate", SourcedJson.date(p.hireDate))
            .put("ateco", SourcedJson.text(p.ateco)).put("regimeStart", SourcedJson.date(p.regimeStart))
            .put("incomeSource", SourcedJson.text(p.incomeSource))
        // Written only when there is something to write, so an archive without paid contributions stays byte-identical.
        if (p.paidContributions.isNotEmpty()) o.put("paidContributions", JSONArray().apply {
            p.paidContributions.forEach { c -> put(JSONObject().put("year", c.year).put("amount", SourcedJson.encode(c.amount) { it.toString() })) }
        })
        return o
    }

    private fun decodePaid(a: JSONArray): List<PaidContribution> {
        require(a.length() <= 60) { "Troppi contributi versati" }
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { c ->
            PaidContribution(c.getInt("year"), SourcedJson.decode(c.getJSONObject("amount")) { it.toLong() })
        } }
    }

    fun decode(a: JSONArray): List<IncomePosition> {
        require(a.length() <= 1000) { "Troppe posizioni" }
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            IncomePosition(o.getString("id"), codedOf(o.getString("kind")),
                SourcedJson.date(o, "validFrom"), SourcedJson.date(o, "validTo"), SourcedJson.text(o, "label"),
                SourcedJson.code(o, "gestione"), SourcedJson.code(o, "severanceRegime"), SourcedJson.code(o, "severanceDestination"),
                SourcedJson.text(o, "contractTerm"), SourcedJson.date(o, "hireDate"), SourcedJson.text(o, "ateco"),
                SourcedJson.date(o, "regimeStart"), SourcedJson.text(o, "incomeSource"),
                if (o.has("paidContributions")) decodePaid(o.getJSONArray("paidContributions")) else emptyList())
        } }
    }
}
