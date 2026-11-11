package xyz.grtlabs.pilastri.sync.tr

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ImportedBalance
import xyz.grtlabs.pilastri.data.ImportedMovement
import xyz.grtlabs.pilastri.data.ImportedPosition

/*
 * Pure mapping from Trade Republic timeline items and detail documents to the app's import contract. The event-type table
 * follows pytr's event handling (https://github.com/pytr-org/pytr, MIT; notices in assets/licenses/pytr-MIT.txt) and the
 * fixtures in its tests/events directory. The kind is only a hint for review; the user decides how each movement is booked.
 */

private val ISIN_PATTERN = Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")

/** Org.json differs between Android (optString turns JSON null into "null") and the JVM test library: read through these. */
internal fun JSONObject.str(key: String): String? = when (val v = opt(key)) {
    null, JSONObject.NULL -> null
    is String -> v
    is Number, is Boolean -> v.toString()
    else -> null
}

internal fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)
internal fun JSONObject.arr(key: String): JSONArray? = optJSONArray(key)

object TrMapper {
    enum class Skip { HIDDEN, DELETED, CANCELED, ORDER_EVENT, CARD_FAILED, NO_AMOUNT, ZERO_AMOUNT, INVALID }

    /** What mapping a batch of items produced. [incompleteTrades] are BUY/SELL movements still missing ISIN or quantity. */
    data class MapResult(val movements: List<ImportedMovement>, val skipped: Map<Skip, Int>, val incompleteTrades: Int)

    /** The facts read from a detail document (plus the item's own logo). Fee and tax are parsed but ImportedMovement has no field for them. */
    internal data class TrDetail(val isin: String?, val quantity: BigDecimal?, val feeCents: Long?, val taxCents: Long?)

    internal data class Base(
        val id: String, val date: LocalDate, val cents: Long, val currency: String, val kind: ExternalKind,
        val rawType: String?, val title: String, val subtitle: String,
    )

    internal sealed interface Resolved {
        data class Ok(val base: Base) : Resolved
        data class Skipped(val reason: Skip) : Resolved
    }

    private const val MAX_ID = 150

    // ---- event types -----------------------------------------------------------------------------------------------

    private val DEPOSIT = setOf(
        "ACCOUNT_TRANSFER_INCOMING", "BANK_TRANSACTION_INCOMING", "INCOMING_TRANSFER", "INCOMING_TRANSFER_DELEGATION",
    )
    /** A card refund reverses spending; it is never money put in, so it waits for review as OTHER (raw type kept). */
    private val CARD_REFUNDS = setOf("CARD_REFUND", "CARD_TR_REFUND")
    private val WITHDRAWAL = setOf("BANK_TRANSACTION_OUTGOING", "OUTGOING_TRANSFER", "OUTGOING_TRANSFER_DELEGATION", "PAYMENT_OUTBOUND")
    private val CARD = setOf("CARD_TRANSACTION", "CARD_SUCCESSFUL_TRANSACTION", "CARD_ORDER_BILLED", "CARD_SUCCESSFUL_ATM_WITHDRAWAL")
    private val TRADE = setOf(
        "ORDER_EXECUTED", "TRADE_INVOICE", "TRADE_CORRECTED", "TRADING_TRADE_EXECUTED", "IPO_TRADE_EXECUTED",
        "SAVINGS_PLAN_EXECUTED", "SAVINGS_PLAN_INVOICE_CREATED", "TRADING_SAVINGSPLAN_EXECUTED",
    )
    private val DIVIDEND = setOf("CREDIT", "SSP_CORPORATE_ACTION_CASH", "SSP_CORPORATE_ACTION_INVOICE_CASH")
    private val INTEREST = setOf("INTEREST_PAYOUT", "INTEREST_PAYOUT_CREATED")
    private val TAX = setOf("TAX_REFUND", "TAX_CORRECTION")
    private val BONUS = setOf("BENEFITS_SAVEBACK_EXECUTION", "SAVEBACK_AGGREGATE")
    private const val SPARE_CHANGE = "BENEFITS_SPARE_CHANGE_EXECUTION"
    private val ORDER_EVENTS = setOf("ORDER_CREATED", "ORDER_CANCELED", "ORDER_CANCELLED")
    private const val CARD_FAILED = "CARD_FAILED_TRANSACTION"
    private val CANCELLED_STATUSES = setOf("CANCELED", "CANCELLED", "REJECTED", "FAILED")

    /** Kinds whose detail document is worth one extra request. */
    private val DETAIL_KINDS = setOf(ExternalKind.BUY, ExternalKind.SELL, ExternalKind.DIVIDEND, ExternalKind.INTEREST, ExternalKind.BONUS)
    private val ISIN_KINDS = setOf(ExternalKind.BUY, ExternalKind.SELL, ExternalKind.DIVIDEND, ExternalKind.BONUS)
    private val QUANTITY_KINDS = setOf(ExternalKind.BUY, ExternalKind.SELL, ExternalKind.BONUS)

    // Localized row titles (German is what the web app emits by default, Italian what the "it" locale should give).
    private val SHARES_TITLES = setOf("aktien", "anteile", "shares", "azioni", "quote", "quantità", "aktien entfernt", "aktien hinzugefügt")
    private val FEE_TITLES = setOf("gebühr", "gebühren", "fee", "fees", "commissione", "commissioni")
    private val TAX_TITLES = setOf("steuer", "steuern", "tax", "taxes", "imposta", "imposte", "tasse")
    private val TRANSACTION_TITLES = setOf("transaktion", "geschäft", "transaction", "transazione", "operazione")

    private fun kindFromType(t: String, cents: Long): ExternalKind? = when {
        t in CARD_REFUNDS -> ExternalKind.OTHER
        t in DEPOSIT || t.startsWith("PAYMENT_INBOUND") -> ExternalKind.DEPOSIT
        t in WITHDRAWAL -> ExternalKind.WITHDRAWAL
        t in CARD -> ExternalKind.CARD_PAYMENT
        t in TRADE -> if (cents < 0) ExternalKind.BUY else ExternalKind.SELL
        t == SPARE_CHANGE -> ExternalKind.BUY
        t in DIVIDEND -> ExternalKind.DIVIDEND
        t in INTEREST -> ExternalKind.INTEREST
        t in TAX || t.startsWith("SSP_TAX_CORRECTION") -> ExternalKind.TAX
        t in BONUS -> ExternalKind.BONUS
        else -> null
    }

    /**
     * Fallback when eventType is absent (pytr has a `*_no_eventType` fixture for each case). Keywords are German and English
     * from pytr plus Italian equivalents that are inferred, not verified against real Italian-locale data.
     */
    private fun kindFromText(title: String, subtitle: String, cents: Long, hasInstrument: Boolean): ExternalKind? {
        val t = "$title $subtitle".lowercase(Locale.ROOT)
        fun has(vararg words: String) = words.any { it in t }
        return when {
            has("dividend", "dividende", "dividendo") -> ExternalKind.DIVIDEND
            has("zinsen", "interest", "interessi") -> ExternalKind.INTEREST
            has("steuerkorrektur", "tax correction", "correzione fiscale", "correzione imposte") -> ExternalKind.TAX
            cents > 0 && has("einzahlung", "deposit", "deposito") -> ExternalKind.DEPOSIT
            cents < 0 && has("auszahlung", "withdrawal", "prelievo") -> ExternalKind.WITHDRAWAL
            has("kartenzahlung", "card payment", "pagamento con carta") -> ExternalKind.CARD_PAYMENT
            hasInstrument && has("verkauf", "sell", "vendita", "kauf", "buy", "acquisto", "sparplan", "savings plan", "piano di accumulo", "ordine") ->
                if (cents < 0) ExternalKind.BUY else ExternalKind.SELL
            else -> null
        }
    }

    // ---- items -----------------------------------------------------------------------------------------------------

    internal fun resolve(item: JSONObject): Resolved {
        fun skip(r: Skip) = Resolved.Skipped(r)
        val id = item.str("id")?.trim()
        if (id.isNullOrEmpty() || id.length > MAX_ID) return skip(Skip.INVALID)
        if (item.optBoolean("hidden", false)) return skip(Skip.HIDDEN)
        if (item.optBoolean("deleted", false)) return skip(Skip.DELETED)
        if (item.str("status")?.trim()?.uppercase(Locale.ROOT) in CANCELLED_STATUSES) return skip(Skip.CANCELED)
        val rawType = item.str("eventType")?.trim()?.takeIf { it.isNotEmpty() }
        val type = rawType?.uppercase(Locale.ROOT)
        if (type in ORDER_EVENTS) return skip(Skip.ORDER_EVENT)
        if (type == CARD_FAILED) return skip(Skip.CARD_FAILED)
        val amount = item.obj("amount") ?: return skip(Skip.NO_AMOUNT)
        val value = TrProtocol.toBigDecimal(amount.opt("value")) ?: return skip(Skip.NO_AMOUNT)
        val cents = TrProtocol.toCents(value) ?: return skip(Skip.INVALID)
        if (cents == 0L) return skip(Skip.ZERO_AMOUNT)
        val currency = amount.str("currency")?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: return skip(Skip.INVALID)
        val date = TrProtocol.parseDate(item.str("timestamp")) ?: return skip(Skip.INVALID)
        val title = item.str("title")?.trim().orEmpty()
        val subtitle = item.str("subtitle")?.trim().orEmpty()
        val kind = if (type != null) kindFromType(type, cents) ?: ExternalKind.OTHER
        else kindFromText(title, subtitle, cents, hasInstrument = logoIsin(item) != null) ?: ExternalKind.OTHER
        return Resolved.Ok(Base(id, date, cents, currency, kind, rawType?.take(100), title, subtitle))
    }

    /** True when the item has a detail document and its kind makes ISIN/quantity/interest breakdown worth a request. */
    fun wantsDetail(item: JSONObject): Boolean {
        val base = (resolve(item) as? Resolved.Ok)?.base ?: return false
        if (base.kind !in DETAIL_KINDS) return false
        val action = item.obj("action")
        return action?.str("type") == "timelineDetail" && action.str("payload") == base.id
    }

    fun map(items: List<JSONObject>, details: Map<String, JSONObject>): MapResult {
        val movements = ArrayList<ImportedMovement>()
        val skipped = HashMap<Skip, Int>()
        var incomplete = 0
        val seen = HashSet<String>()
        for (item in items) {
            when (val r = resolve(item)) {
                is Resolved.Skipped -> skipped.merge(r.reason, 1, Int::plus)
                is Resolved.Ok -> {
                    if (!seen.add(r.base.id)) continue
                    val m = build(r.base, item, details[r.base.id])
                    if ((m.kind == ExternalKind.BUY || m.kind == ExternalKind.SELL) && (m.isin == null || m.quantity == null)) incomplete++
                    movements += m
                }
            }
        }
        return MapResult(movements, skipped, incomplete)
    }

    internal fun build(base: Base, item: JSONObject, detail: JSONObject?): ImportedMovement {
        val d = parseDetail(item, detail)
        val description = listOf(base.title, base.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
            .ifBlank { base.rawType ?: "Movimento Trade Republic" }.take(500)
        val category = if (base.kind == ExternalKind.CARD_PAYMENT) {
            // The fixtures carry only the merchant name (the title); a category field is read if the server ever sends one.
            (item.str("merchantCategory") ?: item.str("category"))?.trim()?.take(60)?.ifBlank { null }
        } else null
        return ImportedMovement(
            externalId = base.id,
            date = base.date,
            signedCents = base.cents,
            currency = base.currency,
            description = description,
            kind = base.kind,
            rawType = base.rawType,
            isin = if (base.kind in ISIN_KINDS) d.isin else null,
            quantity = if (base.kind in QUANTITY_KINDS) d.quantity else null,
            category = category,
        )
    }

    // ---- detail documents ------------------------------------------------------------------------------------------

    private fun normTitle(s: String) = s.trim().lowercase(Locale.ROOT).trimEnd(':').trim()

    private fun forEachObject(node: Any?, depth: Int = 0, visit: (JSONObject) -> Unit) {
        if (depth > 14) return
        when (node) {
            is JSONObject -> {
                visit(node)
                val keys = node.keys()
                while (keys.hasNext()) forEachObject(node.opt(keys.next()), depth + 1, visit)
            }
            is JSONArray -> for (i in 0 until node.length()) forEachObject(node.opt(i), depth + 1, visit)
        }
    }

    /** "logos/IE00B4L5Y983/v2" -> the ISIN segment, when one segment is a valid ISIN. */
    internal fun isinFromLogo(path: String?): String? =
        path?.split('/')?.firstOrNull { ISIN_PATTERN.matches(it) }

    private fun logoIsin(item: JSONObject): String? = isinFromLogo(item.str("icon")) ?: isinFromLogo(item.obj("avatar")?.str("asset"))

    /**
     * ISIN, in order: an `instrumentDetail` action in the detail (payload is the bare ISIN), then the item's own logo path,
     * then any logo path in the detail. Quantity: a row titled Aktien/Anteile/Shares/Azioni/Quote, else the part before "×"
     * of the transaction row. Rows are found anywhere in the tree, including the breakdown nested in an action payload.
     */
    internal fun parseDetail(item: JSONObject, detail: JSONObject?): TrDetail {
        var actionIsin: String? = null
        var logo: String? = null
        var shares: BigDecimal? = null
        var transactionShares: BigDecimal? = null
        var fee: Long? = null
        var tax: Long? = null
        forEachObject(detail) { o ->
            if (actionIsin == null && o.str("type") == "instrumentDetail") {
                actionIsin = o.str("payload")?.trim()?.takeIf { ISIN_PATTERN.matches(it) }
            }
            if (logo == null) logo = isinFromLogo(o.str("icon")) ?: isinFromLogo(o.str("asset"))
            val title = o.str("title")?.let(::normTitle)
            val row = o.obj("detail")
            if (title != null && row != null) {
                val display = row.obj("displayValue")
                val text = row.str("text") ?: display?.str("text")
                when (title) {
                    in SHARES_TITLES -> if (shares == null) shares = TrProtocol.parseDecimal(text)?.abs()
                    in FEE_TITLES -> if (fee == null) fee = TrProtocol.parseDecimal(text)?.abs()?.let { TrProtocol.toCents(it) }
                    in TAX_TITLES -> if (tax == null) tax = TrProtocol.parseDecimal(text)?.abs()?.let { TrProtocol.toCents(it) }
                    in TRANSACTION_TITLES -> if (transactionShares == null) {
                        val head = display?.str("prefix") ?: text?.takeIf { '×' in it }?.substringBefore('×')
                        transactionShares = TrProtocol.parseDecimal(head)?.abs()
                    }
                }
            }
        }
        val itemAction = item.obj("action")?.takeIf { it.str("type") == "instrumentDetail" }?.str("payload")?.trim()?.takeIf { ISIN_PATTERN.matches(it) }
        return TrDetail(
            isin = actionIsin ?: itemAction ?: logoIsin(item) ?: logo,
            quantity = normalizeQuantity(shares ?: transactionShares),
            feeCents = fee,
            taxCents = tax,
        )
    }

    /** ExternalMovement requires a strictly positive quantity with at most 12 decimals. */
    internal fun normalizeQuantity(q: BigDecimal?): BigDecimal? {
        val a = q?.abs()?.takeIf { it.signum() > 0 } ?: return null
        var r = a.stripTrailingZeros()
        if (r.scale() > 12) r = r.setScale(12, RoundingMode.HALF_UP).stripTrailingZeros()
        if (r.scale() < 0) r = r.setScale(0)
        return r.takeIf { it.signum() > 0 }
    }

    // ---- portfolio and valuation -----------------------------------------------------------------------------------

    /** [isin] is empty when the server gave none; [netSize] null when it was unreadable. Both make the position unpriceable. */
    data class TrPosition(val isin: String, val name: String?, val netSize: BigDecimal?)
    data class TrCash(val currency: String, val amount: BigDecimal?)

    /**
     * compactPortfolioByType -> positions with a non-zero size. Null (unknown, not "holds nothing") when the answer has no `categories`, or
     * has category entries but none carries a `positions` list: that is a shape this reader does not understand, and reporting it as an
     * empty portfolio would value the securities at zero. No categories at all (`[]`) and a `positions` list that is present and empty are
     * both a portfolio that holds nothing.
     */
    fun parsePositions(root: JSONObject): List<TrPosition>? {
        val categories = root.arr("categories") ?: return null
        val out = ArrayList<TrPosition>()
        var understood = categories.length() == 0
        for (i in 0 until categories.length()) {
            val positions = categories.optJSONObject(i)?.arr("positions") ?: continue
            understood = true
            for (j in 0 until positions.length()) {
                val p = positions.optJSONObject(j) ?: continue
                val size = TrProtocol.toBigDecimal(p.opt("netSize"))
                if (size != null && size.signum() == 0) continue
                val isin = p.str("isin")?.trim()?.uppercase(Locale.ROOT)?.takeIf { ISIN_PATTERN.matches(it) }.orEmpty()
                out += TrPosition(isin, p.str("name")?.trim()?.take(120), size)
            }
        }
        return if (understood) out else null
    }

    /** cash -> `[{accountNumber,currencyId,amount}]`; null when the answer is not an array. */
    fun parseCash(value: Any?): List<TrCash>? {
        val array = value as? JSONArray ?: return null
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map {
            TrCash((it.str("currencyId") ?: "").trim().uppercase(Locale.ROOT), TrProtocol.toBigDecimal(it.opt("amount")))
        }
    }

    // ---- quote currency --------------------------------------------------------------------------------------------
    // The valuation is in euro and prices are multiplied by sizes as they come. A quote in another currency would silently become a wrong
    // euro value, so wherever a response states its currency only EUR is accepted; anything else is left unpriced (which nulls the total).

    private fun JSONObject.firstValue(vararg keys: String): Any? =
        keys.firstNotNullOfOrNull { k -> opt(k).takeIf { it != null && it != JSONObject.NULL } }

    /** A currency as a response writes it: "EUR", "eur", "€", or an object with an id/code. Unreadable gives "?", which is never EUR. */
    private fun currencyCodeOf(raw: Any?): String? = when (raw) {
        null, JSONObject.NULL -> null
        is String -> raw.trim().uppercase(Locale.ROOT).let { if (it == "€" || it == "EURO") "EUR" else it }.ifEmpty { null }
        is JSONObject -> currencyCodeOf(raw.firstValue("id", "code", "isoCode", "currencyId")) ?: "?"
        else -> "?"
    }

    /** The currency a response states for its prices, upper case; null when it states none. */
    internal fun statedCurrency(o: JSONObject?): String? = currencyCodeOf(o?.firstValue("currency", "currencyId", "currencyCode"))

    /** The currency one exchange's own entry in the instrument's `exchanges` names, if it names one. */
    private fun exchangeCurrency(instrument: JSONObject?, exchange: String): String? {
        val exchanges = instrument?.arr("exchanges") ?: return null
        for (i in 0 until exchanges.length()) {
            val e = exchanges.optJSONObject(i) ?: continue
            val id = e.str("exchangeId") ?: e.str("slug") ?: continue
            if (id.equals(exchange, ignoreCase = true)) statedCurrency(e)?.let { return it }
        }
        return null
    }

    /** The currencies the instrument says it is quoted in on [exchange] (its own entry, else the instrument's `currencies`/`currency`); null when it says nothing. */
    internal fun quoteCurrencies(instrument: JSONObject?, exchange: String?): Set<String>? {
        if (instrument == null) return null
        if (exchange != null) exchangeCurrency(instrument, exchange)?.let { return setOf(it) }
        val list = instrument.arr("currencies")
        if (list != null && list.length() > 0) return (0 until list.length()).map { currencyCodeOf(list.opt(it)) ?: "?" }.toSet()
        return statedCurrency(instrument)?.let { setOf(it) }
    }

    /**
     * instrument -> the exchange to quote on: the preferred one when offered, else the first, else the preferred anyway. An exchange the
     * instrument says quotes in another currency than the euro is passed over while another one is offered.
     */
    fun pickExchange(instrument: JSONObject?, preferred: String): String {
        val ids = instrument?.arr("exchangeIds")
        val list = (0 until (ids?.length() ?: 0)).mapNotNull { ids?.optString(it) }.filter { it.matches(Regex("[A-Z0-9]{1,10}")) }
        if (list.isEmpty()) return preferred
        val pool = list.filter { exchangeCurrency(instrument, it).let { c -> c == null || c == "EUR" } }.ifEmpty { list }
        return if (preferred in pool) preferred else pool.first()
    }

    /**
     * ticker -> `last.price`, only when strictly positive and not stated in another currency than the euro: the ticker's own `currency`, and
     * (when the caller passes the [instrument] answer and the [exchange] it chose) the currencies the instrument lists for that exchange.
     * A price in another currency is null, never a converted or mislabelled euro price.
     */
    fun lastPrice(ticker: JSONObject?, instrument: JSONObject? = null, exchange: String? = null): BigDecimal? {
        val price = TrProtocol.toBigDecimal(ticker?.obj("last")?.opt("price"))?.takeIf { it.signum() > 0 } ?: return null
        if (listOfNotNull(statedCurrency(ticker), statedCurrency(ticker?.obj("last"))).any { it != "EUR" }) return null
        val quoted = quoteCurrencies(instrument, exchange)
        if (quoted != null && quoted != setOf("EUR")) return null
        return price
    }

    /**
     * [balance] is the whole account; [cash] and [securities] are the two sleeves, each reported when it is complete on its own,
     * so a missing quote leaves the cash usable. [positions] are the readable positions with their quote when one was read.
     */
    data class Valuation(
        val balance: ImportedBalance?, val warnings: List<String>,
        val cash: ImportedBalance? = null, val securities: ImportedBalance? = null, val positions: List<ImportedPosition> = emptyList(),
    )

    /**
     * Account value = cash + sum(netSize x last price). It is reported only when every part is known: a missing price,
     * unreadable size, missing cash or non-EUR cash gives a null balance and a warning, never a partial total.
     */
    fun valuation(positions: List<TrPosition>?, cash: List<TrCash>?, prices: Map<String, BigDecimal>, today: LocalDate): Valuation {
        val cashProblem = when {
            cash.isNullOrEmpty() -> "Liquidità non letta: il valore del conto non è stato calcolato."
            cash.any { it.amount == null } -> "Liquidità non leggibile: il valore del conto non è stato calcolato."
            cash.any { it.currency != "EUR" && it.amount!!.signum() != 0 } -> "Liquidità in valuta diversa dall'euro: il valore del conto non è stato calcolato."
            else -> null
        }
        val cashCents = if (cashProblem == null) TrProtocol.toCents(cash!!.fold(BigDecimal.ZERO) { acc, c -> if (c.currency == "EUR") acc + c.amount!! else acc }) else null
        val cashBalance = cashCents?.takeIf { it >= 0 }?.let { ImportedBalance(today, it) }
        val readable = positions.orEmpty().filter { it.netSize != null && it.netSize.signum() > 0 && it.isin.isNotEmpty() }.map { p ->
            ImportedPosition(p.isin, p.name ?: p.isin, p.netSize!!, prices[p.isin]?.let { TrProtocol.toCents(it) })
        }
        if (positions == null) return Valuation(null, listOf("Posizioni non lette: il valore del conto non è stato calcolato."), cash = cashBalance)
        if (cashProblem != null) return Valuation(null, listOf(cashProblem), positions = readable)
        val unpriced = positions.filter { it.netSize == null || it.isin.isEmpty() || prices[it.isin] == null }
        if (unpriced.isNotEmpty()) {
            val named = unpriced.map { it.isin.ifEmpty { "senza ISIN" } }.distinct().take(3).joinToString(", ")
            return Valuation(null, listOf("Prezzo non disponibile per ${unpriced.size} posizioni ($named): il valore del conto non è stato calcolato."), cash = cashBalance, positions = readable)
        }
        var securities = BigDecimal.ZERO
        for (p in positions) securities += p.netSize!! * prices.getValue(p.isin)
        val securitiesCents = TrProtocol.toCents(securities)
        val total = if (cashCents != null && securitiesCents != null) Math.addExact(cashCents, securitiesCents) else null
        if (total == null || cashCents == null) return Valuation(null, listOf("Valore del conto fuori intervallo: non calcolato."), positions = readable)
        return Valuation(ImportedBalance(today, total), emptyList(), cashBalance, securitiesCents?.takeIf { it >= 0 }?.let { ImportedBalance(today, it) }, readable)
    }
}
