package xyz.grtlabs.pilastri.data

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import xyz.grtlabs.pilastri.domain.EventType
import xyz.grtlabs.pilastri.domain.LedgerEvent

/**
 * A complementary pension scheme the person belongs to (Council 4 a), described only by the person's data. Every field except [id]
 * is [Sourced]; missing is ND. Rates are percent as stated (5 = 5 %); [tfrShare] is the percent of TFR paid into the fund.
 * A PENSION_FUND account points here through [Account.fundId]; [positionId] links the position whose employer pays in.
 */
data class PensionFund(
    val id: String,
    val name: Sourced<String>,
    val formType: Sourced<Coded<FundFormType>> = Sourced.nd(),
    val covipNumber: Sourced<String> = Sourced.nd(),
    val memberSince: Sourced<LocalDate> = Sourced.nd(),
    val workerPct: Sourced<BigDecimal> = Sourced.nd(),
    val employerPct: Sourced<BigDecimal> = Sourced.nd(),
    val tfrShare: Sourced<BigDecimal> = Sourced.nd(),
    val positionId: Sourced<String> = Sourced.nd(),
) {
    init {
        require(id.isNotBlank() && id.length <= 120) { "Fondo non valido" }
        listOf(name, covipNumber, positionId).forEach { s -> require(s.value == null || (s.value.isNotBlank() && s.value.length <= 200)) { "Fondo non valido" } }
        listOf(workerPct, employerPct, tfrShare).forEach { s -> require(s.value == null || s.value.signum() >= 0) { "Aliquota del fondo non valida" } }
    }
}

/** Where an employer contribution belongs: (fundId, positionId), through the account's fund. Either side may be ND. */
data class FundAttachment(val fundId: String?, val positionId: Sourced<String>)

fun Archive.fundOf(accountId: String?): PensionFund? {
    val fundId = accounts.firstOrNull { it.id == accountId }?.fundId ?: return null
    return funds.firstOrNull { it.id == fundId }
}

/** The (fundId, positionId) of a contribution; null for any other event. Nothing is inferred: an unlinked account gives ND. */
fun Archive.attachmentOf(event: LedgerEvent): FundAttachment? {
    if (event.type != EventType.EMPLOYER_CONTRIBUTION && event.type != EventType.PERSONAL_CONTRIBUTION) return null
    val fund = fundOf(event.accountId)
    return FundAttachment(fund?.id, fund?.positionId ?: Sourced.nd())
}

/**
 * Gives a PENSION_FUND account without a described fund a new [PensionFund] named from the account (declared by the person,
 * since the person named the account). Other fields stay ND until a document or the setup states them. No-op otherwise.
 */
fun Archive.withAccountFund(accountId: String, formType: Sourced<Coded<FundFormType>> = Sourced.nd()): Archive {
    val account = accounts.singleOrNull { it.id == accountId } ?: throw IllegalArgumentException("Il conto non è più disponibile.")
    if (account.type != AccountType.PENSION_FUND || account.fundId != null) return this
    val taken = funds.map { it.id }.toSet()
    val base = "fund:" + account.id.take(100)
    val id = generateSequence(1) { it + 1 }.map { n -> if (n == 1) base else "$base-$n" }.first { it !in taken }
    val fund = PensionFund(id, Sourced.declared(account.name.take(200)), formType)
    return copy(funds = funds + fund, accounts = accounts.map { if (it.id == account.id) it.copy(fundId = id) else it })
}

internal object FundCodec {
    fun encode(list: List<PensionFund>): JSONArray = JSONArray().apply {
        list.forEach { f -> put(JSONObject()
            .put("id", f.id).put("name", SourcedJson.text(f.name)).put("formType", SourcedJson.code(f.formType))
            .put("covipNumber", SourcedJson.text(f.covipNumber)).put("memberSince", SourcedJson.date(f.memberSince))
            .put("workerPct", SourcedJson.decimal(f.workerPct)).put("employerPct", SourcedJson.decimal(f.employerPct))
            .put("tfrShare", SourcedJson.decimal(f.tfrShare)).put("positionId", SourcedJson.text(f.positionId))) }
    }

    fun decode(a: JSONArray): List<PensionFund> {
        require(a.length() <= 1000) { "Troppi fondi" }
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            PensionFund(o.getString("id"), SourcedJson.text(o, "name"), SourcedJson.code(o, "formType"), SourcedJson.text(o, "covipNumber"),
                SourcedJson.date(o, "memberSince"), SourcedJson.decimal(o, "workerPct"), SourcedJson.decimal(o, "employerPct"),
                SourcedJson.decimal(o, "tfrShare"), SourcedJson.text(o, "positionId"))
        } }
    }

    /** Referential integrity of positions, funds and the account → fund link. */
    fun validate(a: Archive) {
        val positionIds = a.positions.map { it.id }.toSet()
        require(positionIds.size == a.positions.size) { "Posizione duplicata" }
        val fundIds = a.funds.map { it.id }.toSet()
        require(fundIds.size == a.funds.size) { "Fondo duplicato" }
        require(a.funds.all { it.positionId.value == null || it.positionId.value in positionIds }) { "Posizione del fondo non disponibile" }
        require(a.accounts.all { it.fundId == null || it.fundId in fundIds }) { "Fondo del conto non disponibile" }
    }
}
