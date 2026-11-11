package xyz.grtlabs.pilastri.data

import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate

/** Provenance-aware, non-ledger facts that can be shown for review before any integration. */
enum class PrefillFactStatus { REPORTED, MISSING, UNKNOWN }

data class PrefillFact<T>(
    val status: PrefillFactStatus,
    val value: T?,
    /** Identifies the supplied JSON field, including when that field is absent. */
    val source: String
) {
    init {
        require(source.isNotBlank())
        require((status == PrefillFactStatus.REPORTED) == (value != null))
    }
}

/**
 * Parses only confirmed date and contribution-election fields. Values are reported facts,
 * not ledger events, balances, or evidence that a contribution was actually paid.
 */
data class PrefillFacts(
    val firstContractStart: PrefillFact<LocalDate>,
    val firstContractEnd: PrefillFact<LocalDate>,
    val allianzJoinDate: PrefillFact<LocalDate>,
    val allianzEquityAllocation: PrefillFact<BigDecimal>,
    val esperoApplicationDate: PrefillFact<LocalDate>,
    /** Preserved in the JSON's native decimal representation; no currency/event is created. */
    val esperoEmployeeRate: PrefillFact<BigDecimal>,
    val esperoEmployerRate: PrefillFact<BigDecimal>
) {
    /** Fills gaps with new reports while preserving an already reported value. */
    fun mergeNewReports(incoming: PrefillFacts): PrefillFacts = PrefillFacts(
        firstContractStart = firstContractStart.merge(incoming.firstContractStart),
        firstContractEnd = firstContractEnd.merge(incoming.firstContractEnd),
        allianzJoinDate = allianzJoinDate.merge(incoming.allianzJoinDate),
        allianzEquityAllocation = allianzEquityAllocation.merge(incoming.allianzEquityAllocation),
        esperoApplicationDate = esperoApplicationDate.merge(incoming.esperoApplicationDate),
        esperoEmployeeRate = esperoEmployeeRate.merge(incoming.esperoEmployeeRate),
        esperoEmployerRate = esperoEmployerRate.merge(incoming.esperoEmployerRate)
    )

    internal fun toJson(): JSONObject = JSONObject()
        .put("firstContractStart", firstContractStart.toJson { it.toString() })
        .put("firstContractEnd", firstContractEnd.toJson { it.toString() })
        .put("allianzJoinDate", allianzJoinDate.toJson { it.toString() })
        .put("allianzEquityAllocation", allianzEquityAllocation.toJson { it.toPlainString() })
        .put("esperoApplicationDate", esperoApplicationDate.toJson { it.toString() })
        .put("esperoEmployeeRate", esperoEmployeeRate.toJson { it.toPlainString() })
        .put("esperoEmployerRate", esperoEmployerRate.toJson { it.toPlainString() })

    fun validate() {
        allFacts().forEach { fact ->
            require(fact.source.length <= 500) { "Fonte del fatto precompilato troppo lunga." }
        }
        listOf(allianzEquityAllocation, esperoEmployeeRate, esperoEmployerRate).forEach { fact ->
            fact.value?.let { require(it.signum() >= 0 && it.precision() <= 24 && it.scale() in -16..16) { "Valore di elezione non valido." } }
        }
    }

    private fun allFacts(): List<PrefillFact<*>> = listOf(firstContractStart, firstContractEnd, allianzJoinDate,
        allianzEquityAllocation, esperoApplicationDate, esperoEmployeeRate, esperoEmployerRate)

    companion object {
        private const val MAX_INPUT_BYTES = 1_000_000
        private const val SOURCE_PREFIX = "JSON precompilato 2026 · "

        fun parse(bytes: ByteArray): PrefillFacts {
            require(bytes.size <= MAX_INPUT_BYTES) { "File troppo grande." }
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.optString("schemaVersion") == "2026-10-27.v1") { "Versione JSON non supportata." }
            require(root.optString("defaultCurrency") == "EUR") { "Valuta precompilata non supportata." }

            val confirmed = root.optionalObject("confirmed")
            val firstContract = confirmed.optionalObject("firstContract")
            val allianzAllocation = confirmed.optionalObject("allianzAllocation")
            return PrefillFacts(
                firstContractStart = firstContract.dateFact("start", "confirmed.firstContract.start"),
                firstContractEnd = firstContract.dateFact("end", "confirmed.firstContract.end"),
                allianzJoinDate = confirmed.dateFact("allianzJoinDate", "confirmed.allianzJoinDate"),
                allianzEquityAllocation = allianzAllocation.decimalFact("azionaria", "confirmed.allianzAllocation.azionaria"),
                esperoApplicationDate = confirmed.dateFact("esperoApplicationDate", "confirmed.esperoApplicationDate"),
                esperoEmployeeRate = confirmed.decimalFact("esperoEmployeeRate", "confirmed.esperoEmployeeRate"),
                esperoEmployerRate = confirmed.decimalFact("esperoEmployerRate", "confirmed.esperoEmployerRate")
            ).also(PrefillFacts::validate)
        }

        internal fun fromJson(json: JSONObject): PrefillFacts = PrefillFacts(
            firstContractStart = json.getJSONObject("firstContractStart").dateFact(),
            firstContractEnd = json.getJSONObject("firstContractEnd").dateFact(),
            allianzJoinDate = json.getJSONObject("allianzJoinDate").dateFact(),
            allianzEquityAllocation = json.getJSONObject("allianzEquityAllocation").decimalFact(),
            esperoApplicationDate = json.getJSONObject("esperoApplicationDate").dateFact(),
            esperoEmployeeRate = json.getJSONObject("esperoEmployeeRate").decimalFact(),
            esperoEmployerRate = json.getJSONObject("esperoEmployerRate").decimalFact()
        ).also(PrefillFacts::validate)

        private fun <T> PrefillFact<T>.toJson(encode: (T) -> String): JSONObject = JSONObject()
            .put("status", status.name).put("source", source)
            .put("value", value?.let(encode) ?: JSONObject.NULL)

        private fun <T> JSONObject.readFact(parse: (String) -> T): Pair<PrefillFactStatus, T?> {
            val status = PrefillFactStatus.valueOf(getString("status"))
            val source = getString("source")
            val value = if (isNull("value")) null else parse(getString("value"))
            require((status == PrefillFactStatus.REPORTED) == (value != null))
            return status to value
        }

        private fun JSONObject.dateFact(): PrefillFact<LocalDate> {
            val (status, value) = readFact { LocalDate.parse(it) }
            return PrefillFact(status, value, getString("source"))
        }

        private fun JSONObject.decimalFact(): PrefillFact<BigDecimal> {
            val (status, value) = readFact { BigDecimal(it) }
            return PrefillFact(status, value, getString("source"))
        }

        private fun JSONObject.dateFact(key: String, path: String): PrefillFact<LocalDate> =
            fact(key, path) { raw ->
                require(raw is String) { "Data precompilata non valida." }
                LocalDate.parse(raw)
            }

        private fun JSONObject.decimalFact(key: String, path: String): PrefillFact<BigDecimal> =
            fact(key, path) { raw ->
                require(raw is Number) { "Aliquota precompilata non valida." }
                BigDecimal(raw.toString()).also { require(it.signum() >= 0) { "Aliquota precompilata non valida." } }
            }

        private fun <T> JSONObject.fact(key: String, path: String, parse: (Any) -> T): PrefillFact<T> {
            val source = SOURCE_PREFIX + path
            if (!has(key)) return PrefillFact(PrefillFactStatus.MISSING, null, source)
            if (isNull(key)) return PrefillFact(PrefillFactStatus.UNKNOWN, null, source)
            return PrefillFact(PrefillFactStatus.REPORTED, parse(get(key)), source)
        }

        private fun JSONObject.optionalObject(key: String): JSONObject {
            if (!has(key)) return JSONObject()
            return get(key) as? JSONObject ?: throw IllegalArgumentException("Sezione JSON non valida.")
        }

        private fun <T> PrefillFact<T>.merge(incoming: PrefillFact<T>): PrefillFact<T> = when {
            status == PrefillFactStatus.REPORTED -> this
            incoming.status == PrefillFactStatus.REPORTED -> incoming
            status == PrefillFactStatus.UNKNOWN -> this
            incoming.status == PrefillFactStatus.UNKNOWN -> incoming
            else -> this
        }
    }
}
