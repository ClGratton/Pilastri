package xyz.grtlabs.pilastri.sync.gmail

import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * SYNTHETIC samples only. The shops, order ids, amounts and dates are invented, and the wording is generic Italian receipt vocabulary
 * written for these tests: it is NOT a copy of any lender's real e-mail template (those are not public, and no real mail was read).
 */
internal object GmailFixtures {
    val today: LocalDate = LocalDate.of(2026, 11, 10)
    val fixedNow: Instant = Instant.parse("2026-11-10T09:00:00Z")

    fun msg(id: String, from: String, subject: String, date: String, text: String) =
        MailMessage(id, from, subject, LocalDate.parse(date), text.trimIndent())

    const val PAYPAL = "PayPal <service@paypal.it>"
    const val KLARNA = "Klarna <no-reply@klarna.com>"
    const val SCALAPAY = "Scalapay <noreply@scalapay.com>"
    const val HEYLIGHT = "HeyLight <noreply@heylight.com>"
    const val COMPASS = "Compass <comunicazioni@compass.it>"
    const val FINDOMESTIC = "Findomestic <servizio.clienti@findomestic.it>"
    const val COFIDIS = "Cofidis <comunicazioni@cofidis.it>"
    const val YOUNITED = "Younited <clienti@younited-credit.it>"
    const val BANK = "Banca Esempio <avvisi@bancaesempio.example>"

    // ---- PayPal Paga in 3 rate ------------------------------------------------------------------------------------------------------
    val paypalPlan = msg("p1", PAYPAL, "Hai scelto Paga in 3 rate per il tuo acquisto da Negozio Esempio", "2026-11-05", """
        Ciao,
        hai scelto Paga in 3 rate per il tuo acquisto da Negozio Esempio.
        ID transazione: 7AB12345CD678901E
        Totale ordine: € 100,00
        Il tuo piano di pagamento
        Oggi € 33,34
        5 dic 2026 € 33,33
        5 gen 2027 € 33,33
        Il primo pagamento è stato addebitato oggi. Gli altri verranno addebitati automaticamente alle date indicate.
    """)
    val paypalReminder = msg("p2", PAYPAL, "Promemoria: la tua prossima rata Paga in 3 rate", "2026-12-03", """
        La rata 2 di 3 da € 33,33 verrà addebitata il 5 dic 2026 sul tuo metodo di pagamento.
        Acquisto da Negozio Esempio. ID transazione: 7AB12345CD678901E
    """)
    val paypalPaid = msg("p3", PAYPAL, "Pagamento ricevuto: rata 2 di 3 – Paga in 3 rate", "2026-12-05", """
        Hai pagato la rata 2 di 3 di € 33,33 per l'acquisto da Negozio Esempio.
        Prossima rata: € 33,33 il 5 gen 2027.
        ID transazione: 7AB12345CD678901E
    """)
    val paypalUnrelated = msg("p9", PAYPAL, "Hai ricevuto un pagamento di € 12,00", "2026-11-06", "Mario ti ha inviato € 12,00. ID transazione: 1XX99999YY888888Z")

    // ---- Klarna ----------------------------------------------------------------------------------------------------------------------
    val klarnaPlan = msg("k1", KLARNA, "Il tuo piano di pagamento per l'ordine da Shop Esempio", "2026-11-05", """
        Grazie per il tuo acquisto da Shop Esempio.
        Riferimento ordine: KL-2026-778899
        Paga in 3 rate senza interessi.
        Totale: 99,90 €
        Oggi 33,30 €
        5 dic 2026 33,30 €
        4 gen 2027 33,30 €
    """)
    val klarnaReminder = msg("k2", KLARNA, "Promemoria di pagamento da Shop Esempio", "2026-12-03", """
        Il pagamento di 33,30 € verrà addebitato tra 2 giorni.
        Riferimento ordine: KL-2026-778899
    """)
    val klarnaThirtyDays = msg("k3", KLARNA, "Il tuo ordine da Altro Shop – Paga in 30 giorni", "2026-11-06", """
        Hai scelto di pagare in 30 giorni.
        Riferimento ordine: KL-2026-445566
        Totale: 59,90 €
        Scadenza: 6 dic 2026
    """)
    val klarnaCancelled = msg("k4", KLARNA, "Il tuo ordine da Shop Esempio è stato annullato", "2026-12-10", """
        Abbiamo annullato il tuo ordine. I pagamenti futuri sono stati cancellati.
        Riferimento ordine: KL-2026-778899
    """)

    // ---- Scalapay ----------------------------------------------------------------------------------------------------------------------
    val scalapayPlan = msg("s1", SCALAPAY, "Il tuo ordine su Moda Esempio con Scalapay", "2026-11-12", """
        Hai pagato con Scalapay il tuo ordine #SC554433 su Moda Esempio.
        Paga in 3 rate da € 20,00.
        Prima rata: oggi
        Seconda rata: 12 dic 2026
        Terza rata: 12 gen 2027
    """)
    val scalapayReminder = msg("s2", SCALAPAY, "Promemoria rata Scalapay", "2026-12-10", """
        La seconda rata di € 20,00 per l'ordine #SC554433 verrà addebitata il 12/12/2026.
    """)
    val scalapayLate = msg("s3", SCALAPAY, "Pagamento rata non riuscito", "2026-12-13", """
        Non siamo riusciti ad addebitare la rata 2 di 3 di € 20,00, scaduta il 12/12/2026. Ordine #SC554433.
        Puoi pagare ora dall'app.
    """)

    // ---- Consumer-credit lenders -----------------------------------------------------------------------------------------------------
    val heylightPlan = msg("h1", HEYLIGHT, "Finanziamento approvato: conferma del tuo piano di pagamento", "2026-11-05", """
        La tua richiesta di finanziamento è stata approvata.
        Numero pratica: HL2026-123456
        Acquisto presso Elettro Esempio
        Importo finanziato: € 600,00
        Pagamento in 12 rate mensili da € 52,50
        La prima rata sarà addebitata il 5 dic 2026.
    """)
    val compassPlan = msg("c1", COMPASS, "Conferma del tuo finanziamento", "2026-11-05", """
        Gentile cliente, il tuo finanziamento è stato erogato.
        N. pratica: 77889900
        Importo: € 1.200,00 in 24 rate da € 55,00
        Prima rata il 05/12/2026
    """)
    val findomesticReminder = msg("f1", FINDOMESTIC, "Findomestic – Promemoria scadenza rata", "2026-12-01", """
        La rata n. 5 di 12 di € 55,00 scade il 05/12/2026.
        Pratica n. 77889900
    """)
    val findomesticPaid = msg("f2", FINDOMESTIC, "Pagamento rata ricevuto", "2026-12-06", """
        Abbiamo ricevuto il pagamento della rata n. 5 di 12 di € 55,00.
        Pratica n. 77889900
    """)
    val cofidisPlan = msg("o1", COFIDIS, "Il tuo finanziamento è stato attivato", "2026-11-02", """
        Il finanziamento relativo al tuo acquisto presso Amazon è stato attivato.
        Numero pratica: 55667788
        Importo finanziato € 450,00, rimborsabile in 9 rate da € 52,00.
        La prima rata sarà addebitata il 02/12/2026.
    """)
    val cofidisCancelled = msg("o2", COFIDIS, "Finanziamento annullato", "2026-11-20", """
        Il finanziamento relativo al tuo ordine è stato annullato a seguito del reso.
        Pratica n. 55667788
    """)
    val younitedPlan = msg("y1", YOUNITED, "Conferma del tuo pagamento a rate", "2026-11-03", """
        Il tuo ordine presso Casa Esempio è stato finanziato.
        Numero contratto: YN20261103A
        Totale ordine: € 360,00
        Pagherai in 6 rate mensili da € 60,00
        5 dic 2026 € 60,00
        5 gen 2027 € 60,00
    """)
    val younitedPaid = msg("y2", YOUNITED, "Pagamento della rata ricevuto", "2026-12-05", """
        Abbiamo ricevuto il pagamento di € 60,00 relativo alla rata 1 di 6.
        Numero contratto: YN20261103A
    """)

    // ---- Credit card (unknown sender, unmistakable shape) ---------------------------------------------------------------------------
    val cardPlan = msg("b1", BANK, "Il tuo acquisto è stato trasformato in rate", "2026-11-05", """
        Il tuo acquisto di € 300,00 presso Elettro Esempio del 02/10/2026 è stato trasformato in 6 rate da € 50,00.
        La prima rata sarà addebitata il 05/11/2026.
        Riferimento operazione: OP2026100201
    """)

    // ---- Not installment mail --------------------------------------------------------------------------------------------------------
    val newsletter = msg("n1", "Klarna <news@klarna.com>", "Le offerte della settimana", "2026-11-07", "Scopri le offerte. Fino al 50% di sconto su moda e casa. Annulla l'iscrizione in fondo.")
    val unrelatedShop = msg("n2", "Shop <info@shopqualsiasi.example>", "Il tuo ordine è stato spedito", "2026-11-07", """
        Il tuo ordine n. 998877 è stato spedito.
        Consegna prevista: 9 nov 2026
        Totale: € 49,90
    """)

    /** An unknown sender whose mail mentions installments in a shape no parser reads, and carries data that must never leave the device. */
    val unknownInstallmentMail = msg("u1", "Negozio Esempio <info@negozio-esempio.test>", "Il tuo ordine è confermato", "2026-11-04", """
        Grazie per l'ordine di Mario Rossi! Hai scelto il pagamento rateale (4 comode rate, la prima oggi).
        Pagamento con IBAN IT60X0542811101000000123456 sul conto di mario.rossi@example.com.
        Per assistenza chiama +39 333 1234567 oppure apri https://negozio-esempio.test/o?token=ABCDEF1234567890XYZ
        Codice fiscale RSSMRA80A01H501U. Numero ordine interno 123456789012.
        Totale dell'ordine 120,00 euro. Prima rata 30,00 euro oggi, poi 30,00 euro il 4 dic 2026, il 4 gen 2027 e il 4 feb 2027.
    """)

    // ---- Gmail REST JSON -------------------------------------------------------------------------------------------------------------
    fun b64(s: String, charset: java.nio.charset.Charset = Charsets.UTF_8): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(charset))

    fun part(mime: String, data: String? = null, filename: String = "", parts: List<JSONObject> = emptyList(), headers: Map<String, String> = emptyMap()): JSONObject =
        JSONObject().put("mimeType", mime).put("filename", filename)
            .put("headers", JSONArray().apply { headers.forEach { (k, v) -> put(JSONObject().put("name", k).put("value", v)) } })
            .put("body", JSONObject().apply { if (data != null) put("data", data) }).apply { if (parts.isNotEmpty()) put("parts", JSONArray(parts)) }

    fun gmailJson(id: String, from: String, subject: String, internalDateMs: Long, payloadBody: JSONObject): JSONObject {
        // Keep the part's own headers (Content-Type) and add the message headers, as Gmail does for the top-level part.
        val headers = payloadBody.optJSONArray("headers") ?: JSONArray()
        headers.put(JSONObject().put("name", "From").put("value", from))
            .put(JSONObject().put("name", "Subject").put("value", subject))
            .put(JSONObject().put("name", "Received").put("value", "by mx.example.test"))
        payloadBody.put("headers", headers)
        return JSONObject().put("id", id).put("internalDate", internalDateMs.toString()).put("payload", payloadBody)
    }

    /** Wraps a [MailMessage] as the JSON `messages.get` would return it (plain text part only). */
    fun asGmailJson(m: MailMessage): JSONObject = gmailJson(
        m.id, m.from, m.subject, m.date.atStartOfDay(MailText.ZONE).plusHours(10).toInstant().toEpochMilli(),
        part("text/plain", b64(m.text), headers = mapOf("Content-Type" to "text/plain; charset=UTF-8")),
    )
}

/** In-memory mailbox: ids listed per query in pages, messages fetched by id. Counts calls so limits can be asserted. */
internal class FakeMailSource(private val byQuery: (String) -> List<String>, private val messages: Map<String, MailMessage>) : MailSource {
    val listCalls = ArrayList<Triple<String, String?, Int>>()
    val fetched = ArrayList<String>()

    override suspend fun list(query: String, pageToken: String?, pageSize: Int): MailPage {
        listCalls += Triple(query, pageToken, pageSize)
        val all = byQuery(query)
        val start = pageToken?.toInt() ?: 0
        val end = minOf(all.size, start + pageSize)
        return MailPage(all.subList(start, end), if (end < all.size) end.toString() else null)
    }

    override suspend fun fetch(id: String): MailMessage? { synchronized(fetched) { fetched += id }; return messages[id] }

    companion object {
        /** Every query returns every message (the scanner de-duplicates ids across queries). */
        fun of(vararg ms: MailMessage) = FakeMailSource({ ms.map { it.id } }, ms.associateBy { it.id })
    }
}
