package xyz.grtlabs.pilastri.sync.tr

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.ExternalKind
import xyz.grtlabs.pilastri.data.ExternalKind.*
import xyz.grtlabs.pilastri.data.ImportedMovement
import xyz.grtlabs.pilastri.sync.tr.TrMapper.Skip

// Synthetic fixtures shaped like pytr's tests/events/*.json (field names and structure only; every value is invented).
private const val ISIN_A = "ZZ0000000011"
private const val ISIN_B = "ZZ0000000029"

internal fun tlItem(
    id: String = "i1",
    ts: String = "2025-10-10T19:29:43.123+0000",
    title: String? = "Example Corp",
    subtitle: String? = "Kauforder",
    value: Double? = -111.0,
    eventType: String? = "trading_trade_executed",
    status: String? = "EXECUTED",
    icon: String? = "logos/$ISIN_A/v2",
    hidden: Boolean = false,
    deleted: Boolean = false,
    currency: String = "EUR",
    detailAction: Boolean = true,
    extra: JSONObject.() -> Unit = {},
): JSONObject = JSONObject().apply {
    put("id", id)
    put("timestamp", ts)
    title?.let { put("title", it) }
    if (subtitle != null) put("subtitle", subtitle) else put("subtitle", JSONObject.NULL)
    value?.let { put("amount", JSONObject().put("currency", currency).put("value", it).put("fractionDigits", 2)) }
    eventType?.let { put("eventType", it) }
    status?.let { put("status", it) }
    icon?.let { put("icon", it) }
    put("hidden", hidden)
    put("deleted", deleted)
    if (detailAction) put("action", JSONObject().put("type", "timelineDetail").put("payload", id))
    extra()
}

/** A detail document: header (with the instrumentDetail action when [isin] is given) and one overview table. */
internal fun tlDetail(isin: String? = ISIN_A, vararg rows: Pair<String, String>): JSONObject {
    val table = JSONArray()
    rows.forEach { (t, v) -> table.put(JSONObject().put("title", t).put("detail", JSONObject().put("text", v).put("type", "text")).put("style", "plain")) }
    val header = JSONObject().put("type", "header").put("title", "Example").put("data", JSONObject().put("status", "executed"))
    if (isin != null) header.put("action", JSONObject().put("payload", isin).put("type", "instrumentDetail"))
    return JSONObject().put("id", "d").put(
        "sections",
        JSONArray().put(header).put(JSONObject().put("type", "table").put("title", "Übersicht").put("data", table)),
    )
}

private fun one(item: JSONObject, detail: JSONObject? = null): ImportedMovement {
    val id = item.getString("id")
    val result = TrMapper.map(listOf(item), if (detail == null) emptyMap() else mapOf(id to detail))
    return result.movements.single()
}

private fun skipped(item: JSONObject): Map<Skip, Int> {
    val r = TrMapper.map(listOf(item), emptyMap())
    assertTrue("expected no movement", r.movements.isEmpty())
    return r.skipped
}

class TrMapperTest {
    // ---- kinds -----------------------------------------------------------------------------------------------------

    @Test fun `a buy keeps signed cents, ISIN, quantity and the title plus subtitle`() {
        val m = one(
            tlItem(),
            tlDetail(ISIN_A, "Portfolio" to "Brokerage", "Transaktion" to "0,685102 × 160,56 €", "Aktien" to "0,685102", "Gebühr" to "1,00 €", "Summe" to "111,00 €"),
        )
        assertEquals("i1", m.externalId)
        assertEquals(LocalDate.of(2025, 10, 10), m.date)
        assertEquals(-11100L, m.signedCents)
        assertEquals("EUR", m.currency)
        assertEquals("Example Corp · Kauforder", m.description)
        assertEquals(BUY, m.kind)
        assertEquals("trading_trade_executed", m.rawType)
        assertEquals(ISIN_A, m.isin)
        assertEquals("0.685102", m.quantity!!.toPlainString())
        assertNull(m.category)
    }

    @Test fun `a sale has a positive amount`() {
        val m = one(tlItem(value = 250.5, eventType = "ORDER_EXECUTED", subtitle = "Verkaufsorder"), tlDetail(ISIN_A, "Aktien" to "3"))
        assertEquals(SELL, m.kind)
        assertEquals(25050L, m.signedCents)
        assertEquals("3", m.quantity!!.toPlainString())
    }

    @Test fun `every documented event type maps to its kind, in upper and lower case`() {
        val cases = listOf(
            listOf("ACCOUNT_TRANSFER_INCOMING", "BANK_TRANSACTION_INCOMING", "INCOMING_TRANSFER", "INCOMING_TRANSFER_DELEGATION",
                "PAYMENT_INBOUND", "PAYMENT_INBOUND_CREDIT_CARD", "PAYMENT_INBOUND_SEPA_DIRECT_DEBIT") to (DEPOSIT to 100.0),
            listOf("CARD_REFUND", "CARD_TR_REFUND") to (OTHER to 100.0),
            listOf("BANK_TRANSACTION_OUTGOING", "OUTGOING_TRANSFER", "OUTGOING_TRANSFER_DELEGATION", "PAYMENT_OUTBOUND") to (WITHDRAWAL to -50.0),
            listOf("CARD_TRANSACTION", "CARD_SUCCESSFUL_TRANSACTION", "CARD_ORDER_BILLED", "CARD_SUCCESSFUL_ATM_WITHDRAWAL") to (CARD_PAYMENT to -4.28),
            listOf("ORDER_EXECUTED", "TRADE_INVOICE", "TRADE_CORRECTED", "TRADING_TRADE_EXECUTED", "IPO_TRADE_EXECUTED",
                "SAVINGS_PLAN_EXECUTED", "SAVINGS_PLAN_INVOICE_CREATED", "TRADING_SAVINGSPLAN_EXECUTED") to (BUY to -25.0),
            listOf("BENEFITS_SPARE_CHANGE_EXECUTION") to (BUY to -0.8),
            listOf("CREDIT", "SSP_CORPORATE_ACTION_CASH", "SSP_CORPORATE_ACTION_INVOICE_CASH") to (DIVIDEND to 2.24),
            listOf("INTEREST_PAYOUT", "INTEREST_PAYOUT_CREATED") to (INTEREST to 13.56),
            listOf("TAX_REFUND", "TAX_CORRECTION", "SSP_TAX_CORRECTION", "SSP_TAX_CORRECTION_INVOICE") to (TAX to 4.1),
            listOf("BENEFITS_SAVEBACK_EXECUTION", "SAVEBACK_AGGREGATE") to (BONUS to 15.0),
        )
        for ((types, expected) in cases) for (type in types) for (variant in listOf(type, type.lowercase())) {
            val m = one(tlItem(eventType = variant, value = expected.second))
            assertEquals("$variant", expected.first, m.kind)
            assertEquals(variant, m.rawType)
        }
    }

    @Test fun `trade event types are sells when the cash comes in`() {
        listOf("ORDER_EXECUTED", "TRADE_INVOICE", "TRADE_CORRECTED", "TRADING_TRADE_EXECUTED", "IPO_TRADE_EXECUTED",
            "SAVINGS_PLAN_EXECUTED", "SAVINGS_PLAN_INVOICE_CREATED", "TRADING_SAVINGSPLAN_EXECUTED").forEach {
            assertEquals(it, SELL, one(tlItem(eventType = it, value = 40.0)).kind)
        }
    }

    @Test fun `round-up is a buy even if the sign is odd`() {
        assertEquals(BUY, one(tlItem(eventType = "BENEFITS_SPARE_CHANGE_EXECUTION", value = 0.5)).kind)
    }

    @Test fun `unknown event types become OTHER and keep their raw type`() {
        val m = one(tlItem(eventType = "SOMETHING_NEW_FROM_TR", value = 7.0, title = "Novità", subtitle = null))
        assertEquals(OTHER, m.kind)
        assertEquals("SOMETHING_NEW_FROM_TR", m.rawType)
        assertEquals("Novità", m.description)
        assertNull(m.isin)
    }

    @Test fun `missing event type falls back to title and subtitle in three languages`() {
        assertEquals(DIVIDEND, one(tlItem(eventType = null, title = "Example Corp", subtitle = "Bardividende", value = 2.24)).kind)
        assertEquals(DIVIDEND, one(tlItem(eventType = null, title = "Example Corp", subtitle = "Dividendo in contanti", value = 2.24)).kind)
        assertEquals(INTEREST, one(tlItem(eventType = null, title = "Zinsen", subtitle = null, value = 13.56, icon = null)).kind)
        assertEquals(INTEREST, one(tlItem(eventType = null, title = "Interessi", subtitle = null, value = 13.56, icon = null)).kind)
        assertEquals(BUY, one(tlItem(eventType = null, subtitle = "Sparplan ausgeführt", value = -25.0)).kind)
        assertEquals(BUY, one(tlItem(eventType = null, subtitle = "Ordine di acquisto", value = -25.0)).kind)
        assertEquals(SELL, one(tlItem(eventType = null, subtitle = "Verkaufsorder", value = 25.0)).kind)
        assertEquals(DEPOSIT, one(tlItem(eventType = null, title = "Einzahlung", subtitle = null, value = 500.0, icon = null)).kind)
        val unknown = one(tlItem(eventType = null, title = "Boh", subtitle = null, value = 1.0, icon = null))
        assertEquals(OTHER, unknown.kind)
        assertNull(unknown.rawType)
    }

    @Test fun `a trade-like subtitle without an instrument logo is not guessed`() {
        assertEquals(OTHER, one(tlItem(eventType = null, subtitle = "Ordine di acquisto", value = -25.0, icon = null)).kind)
    }

    // ---- skipping --------------------------------------------------------------------------------------------------

    @Test fun `hidden, deleted and canceled items are skipped`() {
        assertEquals(mapOf(Skip.HIDDEN to 1), skipped(tlItem(hidden = true)))
        assertEquals(mapOf(Skip.DELETED to 1), skipped(tlItem(deleted = true)))
        assertEquals(mapOf(Skip.CANCELED to 1), skipped(tlItem(status = "CANCELED")))
        assertEquals(mapOf(Skip.CANCELED to 1), skipped(tlItem(status = "canceled")))
        assertEquals(mapOf(Skip.CANCELED to 1), skipped(tlItem(status = "cancelled")))
        assertEquals(mapOf(Skip.CANCELED to 1), skipped(tlItem(status = "REJECTED")))
    }

    @Test fun `order creation and cancellation events and failed card payments are skipped`() {
        assertEquals(mapOf(Skip.ORDER_EVENT to 1), skipped(tlItem(eventType = "ORDER_CREATED")))
        assertEquals(mapOf(Skip.ORDER_EVENT to 1), skipped(tlItem(eventType = "order_canceled")))
        assertEquals(mapOf(Skip.CARD_FAILED to 1), skipped(tlItem(eventType = "CARD_FAILED_TRANSACTION")))
    }

    @Test fun `items without a usable amount are skipped and counted`() {
        assertEquals(mapOf(Skip.NO_AMOUNT to 1), skipped(tlItem(value = null)))
        assertEquals(mapOf(Skip.ZERO_AMOUNT to 1), skipped(tlItem(value = 0.0)))
        assertEquals(mapOf(Skip.INVALID to 1), skipped(tlItem(id = "")))
        assertEquals(mapOf(Skip.INVALID to 1), skipped(tlItem(ts = "not a date")))
        assertEquals(mapOf(Skip.INVALID to 1), skipped(tlItem(currency = "EURO")))
    }

    @Test fun `the same id twice is mapped once`() {
        val r = TrMapper.map(listOf(tlItem(), tlItem()), emptyMap())
        assertEquals(1, r.movements.size)
    }

    // ---- ISIN and quantity -----------------------------------------------------------------------------------------

    @Test fun `ISIN comes from the instrumentDetail action, else the logo path`() {
        assertEquals(ISIN_B, one(tlItem(icon = "logos/$ISIN_A/v2"), tlDetail(ISIN_B, "Aktien" to "1")).isin)
        assertEquals(ISIN_A, one(tlItem(icon = "logos/$ISIN_A/v2")).isin)                       // no detail needed
        assertEquals(ISIN_A, one(tlItem(icon = null) { put("avatar", JSONObject().put("asset", "logos/$ISIN_A/v2")) }).isin)
        val fromHeaderIcon = tlDetail(null, "Aktien" to "1")
        fromHeaderIcon.getJSONArray("sections").getJSONObject(0).getJSONObject("data").put("icon", "logos/$ISIN_B/v2")
        assertEquals(ISIN_B, one(tlItem(icon = null), fromHeaderIcon).isin)
        assertNull(one(tlItem(icon = "logos/not-an-isin/v2")).isin)
    }

    @Test fun `ISIN and quantity are attached only where they mean something`() {
        val card = one(tlItem(eventType = "CARD_TRANSACTION", value = -4.28, icon = "logos/$ISIN_A/v2"), tlDetail(ISIN_A, "Aktien" to "2"))
        assertNull(card.isin); assertNull(card.quantity)
        val dividend = one(tlItem(eventType = "SSP_CORPORATE_ACTION_INVOICE_CASH", value = 2.24), tlDetail(ISIN_A, "Aktien" to "10.640298"))
        assertEquals(ISIN_A, dividend.isin); assertNull(dividend.quantity)           // shares held, not a traded quantity
        val tax = one(tlItem(eventType = "TAX_CORRECTION", value = 1.0), tlDetail(ISIN_A, "Aktien" to "2"))
        assertNull(tax.isin); assertNull(tax.quantity)
    }

    @Test fun `quantity is found under German, English and Italian titles`() {
        for (title in listOf("Aktien", "Anteile", "Shares", "Azioni", "Quote", "aktien:", " SHARES ")) {
            val m = one(tlItem(), tlDetail(ISIN_A, title to "1,5"))
            assertEquals(title, "1.5", m.quantity!!.toPlainString())
        }
        assertNull(one(tlItem(), tlDetail(ISIN_A, "Aktienkurs" to "160,56 €")).quantity)   // a price row is not a quantity
    }

    @Test fun `quantity accepts decimal comma, decimal point and a sign`() {
        assertEquals("0.685102", one(tlItem(), tlDetail(ISIN_A, "Aktien" to "0,685102")).quantity!!.toPlainString())
        assertEquals("10.640298", one(tlItem(), tlDetail(ISIN_A, "Aktien" to "10.640298")).quantity!!.toPlainString())
        assertEquals("2", one(tlItem(value = 10.0, eventType = "ORDER_EXECUTED"), tlDetail(ISIN_A, "Aktien" to "-2")).quantity!!.toPlainString())
        assertNull(one(tlItem(), tlDetail(ISIN_A, "Aktien" to "0")).quantity)
        assertNull(one(tlItem(), tlDetail(ISIN_A, "Aktien" to "n.d.")).quantity)
    }

    @Test fun `quantity is clamped to what ExternalMovement accepts`() {
        val m = one(tlItem(), tlDetail(ISIN_A, "Aktien" to "0,1234567890123456"))
        assertTrue(m.quantity!!.scale() <= 12)
        assertTrue(m.quantity!!.signum() > 0)
        assertNull(TrMapper.normalizeQuantity(BigDecimal("0.0000000000001")))
        assertEquals("1000", TrMapper.normalizeQuantity(BigDecimal("1E+3"))!!.toPlainString())
    }

    @Test fun `quantity is read from the nested breakdown inside the transaction row`() {
        val nested = JSONObject(
            """{"id":"d","sections":[
                 {"type":"header","title":"x","data":{"icon":"logos/$ISIN_A/v2"},"action":{"payload":"$ISIN_A","type":"instrumentDetail"}},
                 {"type":"table","title":"Übersicht","data":[
                   {"title":"Transaktion","detail":{"text":"2,5 × 10,00 €","type":"text","action":{"type":"infoPage","payload":{"id":"z","sections":[
                     {"type":"title","title":"Transaktion"},
                     {"type":"table","data":[{"title":"Aktien","detail":{"text":"2,5","type":"text"}},{"title":"Aktienkurs","detail":{"text":"10,00 €","type":"text"}}]}]}}},"style":"plain"}]}]}""",
        )
        assertEquals("2.5", one(tlItem(), nested).quantity!!.toPlainString())
    }

    @Test fun `quantity falls back to the transaction row when there is no shares row`() {
        val prefix = JSONObject(
            """{"id":"d","sections":[{"type":"table","title":"Übersicht","data":[
                 {"title":"Transaktion","detail":{"text":"0,25 × 40,00 €","displayValue":{"text":"40,00 €","prefix":"0,25 × "},"type":"text"}}]}]}""",
        )
        assertEquals("0.25", one(tlItem(), prefix).quantity!!.toPlainString())
        val textOnly = tlDetail(ISIN_A, "Operazione" to "4 × 10,00 €")
        assertEquals("4", one(tlItem(), textOnly).quantity!!.toPlainString())
    }

    @Test fun `fee and tax rows are parsed in three languages`() {
        fun parse(vararg rows: Pair<String, String>) = TrMapper.parseDetail(tlItem(), tlDetail(ISIN_A, *rows))
        assertEquals(100L, parse("Gebühr" to "1,00 €").feeCents)
        assertEquals(78L, parse("Steuer" to "-0,78 €").taxCents)
        assertEquals(100L, parse("Fee" to "1.00 €").feeCents)
        assertEquals(250L, parse("Tax" to "2,50 €").taxCents)
        assertEquals(150L, parse("Commissione" to "1,50 €").feeCents)
        assertEquals(521L, parse("Imposte" to "5,21 €").taxCents)
        assertEquals(521L, parse("Steuern" to "5,21 €").taxCents)
        assertNull(parse("Gebühr" to "Kostenlos").feeCents)
    }

    @Test fun `trades missing ISIN or quantity are counted as incomplete`() {
        val r = TrMapper.map(listOf(tlItem(id = "a", icon = null), tlItem(id = "b")), mapOf("b" to tlDetail(ISIN_A, "Aktien" to "1")))
        assertEquals(2, r.movements.size)
        assertEquals(1, r.incompleteTrades)           // "a" has neither ISIN nor quantity; "b" has both
    }

    // ---- other fields ----------------------------------------------------------------------------------------------

    @Test fun `late UTC timestamps belong to the next Italian day`() {
        assertEquals(LocalDate.of(2025, 10, 24), one(tlItem(ts = "2025-10-23T22:30:00.000+0000", eventType = "CARD_TRANSACTION", value = -3.0)).date)
    }

    @Test fun `card merchant category is kept only for card payments and only if present`() {
        val withCategory = one(tlItem(eventType = "CARD_TRANSACTION", value = -4.28, title = "REWE", subtitle = null) { put("merchantCategory", "Supermercati") })
        assertEquals("Supermercati", withCategory.category)
        assertEquals("REWE", withCategory.description)
        assertNull(one(tlItem(eventType = "CARD_TRANSACTION", value = -4.28)).category)
        assertNull(one(tlItem(eventType = "ACCOUNT_TRANSFER_INCOMING", value = 4.28) { put("merchantCategory", "x") }).category)
    }

    @Test fun `description falls back to the raw type when title and subtitle are blank`() {
        assertEquals("CARD_TRANSACTION", one(tlItem(eventType = "CARD_TRANSACTION", value = -1.0, title = " ", subtitle = null)).description)
    }

    @Test fun `all mapped movements satisfy what ExternalMovement requires`() {
        val m = one(tlItem(), tlDetail(ISIN_A, "Aktien" to "0,685102"))
        val external = xyz.grtlabs.pilastri.data.ExternalMovement(
            id = xyz.grtlabs.pilastri.data.externalId(xyz.grtlabs.pilastri.data.ImportProvider.TRADE_REPUBLIC, m.externalId),
            provider = xyz.grtlabs.pilastri.data.ImportProvider.TRADE_REPUBLIC, accountId = "acc", date = m.date, signedCents = m.signedCents,
            currency = m.currency, description = m.description, kind = m.kind, rawType = m.rawType, isin = m.isin, quantity = m.quantity,
            status = xyz.grtlabs.pilastri.data.ExternalStatus.PENDING, linkedRecordId = null, importedAt = "2026-10-28T00:00:00Z", category = m.category,
        )
        assertNotNull(external)
    }

    @Test fun `wantsDetail is true only for kinds that use a detail document`() {
        assertTrue(TrMapper.wantsDetail(tlItem()))                                               // buy
        assertTrue(TrMapper.wantsDetail(tlItem(eventType = "INTEREST_PAYOUT", value = 1.0)))
        assertTrue(TrMapper.wantsDetail(tlItem(eventType = "SSP_CORPORATE_ACTION_INVOICE_CASH", value = 1.0)))
        assertFalse(TrMapper.wantsDetail(tlItem(eventType = "CARD_TRANSACTION", value = -1.0)))
        assertFalse(TrMapper.wantsDetail(tlItem(eventType = "ACCOUNT_TRANSFER_INCOMING", value = 1.0)))
        assertFalse(TrMapper.wantsDetail(tlItem(hidden = true)))
        assertFalse(TrMapper.wantsDetail(tlItem(detailAction = false)))
        assertFalse(TrMapper.wantsDetail(tlItem { put("action", JSONObject().put("type", "timelineDetail").put("payload", "other-id")) }))
    }

    // ---- portfolio and balance -------------------------------------------------------------------------------------

    private val today = LocalDate.of(2026, 10, 28)

    @Test fun `positions skip empty holdings and tolerate missing fields`() {
        val root = JSONObject(
            """{"categories":[{"categoryType":"stocksAndETFs","positions":[
                 {"isin":"$ISIN_A","name":"A","netSize":"2","averageBuyIn":"9.5"},
                 {"isin":"$ISIN_B","name":"B","netSize":"0.5"},
                 {"isin":"$ISIN_B","netSize":"0"},
                 {"name":"no isin","netSize":"1"}]}]}""",
        )
        val p = TrMapper.parsePositions(root)!!
        assertEquals(3, p.size)
        assertEquals("", p.last().isin)
        assertNull(TrMapper.parsePositions(JSONObject("""{"nothing":1}""")))
    }

    @Test fun `cash accepts string and numeric amounts and rejects non arrays`() {
        val cash = TrMapper.parseCash(JSONArray("""[{"accountNumber":"1","currencyId":"EUR","amount":"100.25"},{"currencyId":"eur","amount":3}]"""))!!
        assertEquals(2, cash.size)
        assertEquals(0, BigDecimal("100.25").compareTo(cash[0].amount))
        assertEquals("EUR", cash[1].currency)
        assertNull(TrMapper.parseCash(JSONObject("""{"a":1}""")))
    }

    private val positions = listOf(
        TrMapper.TrPosition(ISIN_A, "A", BigDecimal("2")),
        TrMapper.TrPosition(ISIN_B, "B", BigDecimal("0.5")),
    )
    private val cash = listOf(TrMapper.TrCash("EUR", BigDecimal("100.25")))

    @Test fun `balance is cash plus size times last price`() {
        val v = TrMapper.valuation(positions, cash, mapOf(ISIN_A to BigDecimal("10.10"), ISIN_B to BigDecimal("200")), today)
        assertEquals(22045L, v.balance!!.cents)               // 100.25 + 20.20 + 100.00
        assertEquals(today, v.balance!!.date)
        assertTrue(v.warnings.isEmpty())
    }

    @Test fun `balance is null with a warning when any position has no price, never a partial total`() {
        val v = TrMapper.valuation(positions, cash, mapOf(ISIN_A to BigDecimal("10.10")), today)
        assertNull(v.balance)
        assertEquals(1, v.warnings.size)
        assertTrue(v.warnings.single(), ISIN_B in v.warnings.single() && "1 posizioni" in v.warnings.single())
    }

    @Test fun `balance is null when cash, positions or sizes are unknown`() {
        val prices = mapOf(ISIN_A to BigDecimal.ONE, ISIN_B to BigDecimal.ONE)
        assertNull(TrMapper.valuation(null, cash, prices, today).balance)
        assertNull(TrMapper.valuation(positions, null, prices, today).balance)
        assertNull(TrMapper.valuation(positions, emptyList(), prices, today).balance)
        assertNull(TrMapper.valuation(positions, listOf(TrMapper.TrCash("EUR", null)), prices, today).balance)
        assertNull(TrMapper.valuation(listOf(TrMapper.TrPosition(ISIN_A, "A", null)), cash, prices, today).balance)
        assertNull(TrMapper.valuation(listOf(TrMapper.TrPosition("", "no isin", BigDecimal.ONE)), cash, prices, today).balance)
        listOf(
            TrMapper.valuation(null, cash, prices, today), TrMapper.valuation(positions, null, prices, today),
        ).forEach { assertEquals(1, it.warnings.size) }
    }

    @Test fun `non-euro cash blocks the total unless it is zero`() {
        val usd = listOf(TrMapper.TrCash("EUR", BigDecimal("10")), TrMapper.TrCash("USD", BigDecimal("5")))
        assertNull(TrMapper.valuation(emptyList(), usd, emptyMap(), today).balance)
        val usdZero = listOf(TrMapper.TrCash("EUR", BigDecimal("10")), TrMapper.TrCash("USD", BigDecimal.ZERO))
        assertEquals(1000L, TrMapper.valuation(emptyList(), usdZero, emptyMap(), today).balance!!.cents)
    }

    @Test fun `no positions and known cash is a valid balance`() {
        assertEquals(10025L, TrMapper.valuation(emptyList(), cash, emptyMap(), today).balance!!.cents)
    }

    @Test fun `exchange choice prefers LSX, then the first offered, then the preferred anyway`() {
        assertEquals("LSX", TrMapper.pickExchange(JSONObject("""{"exchangeIds":["TDG","LSX"]}"""), "LSX"))
        assertEquals("TDG", TrMapper.pickExchange(JSONObject("""{"exchangeIds":["TDG","GER"]}"""), "LSX"))
        assertEquals("LSX", TrMapper.pickExchange(JSONObject("""{"exchangeIds":[]}"""), "LSX"))
        assertEquals("LSX", TrMapper.pickExchange(null, "LSX"))
        assertEquals("LSX", TrMapper.pickExchange(JSONObject("""{"exchangeIds":["bad id!"]}"""), "LSX"))
    }

    @Test fun `last price must be a positive number`() {
        assertEquals("160.56", TrMapper.lastPrice(JSONObject("""{"bid":{"price":"1"},"last":{"price":"160.56","size":1}}"""))!!.toPlainString())
        assertNull(TrMapper.lastPrice(JSONObject("""{"last":{"price":"0"}}""")))
        assertNull(TrMapper.lastPrice(JSONObject("""{"bid":{"price":"3"}}""")))
        assertNull(TrMapper.lastPrice(null))
    }
}
