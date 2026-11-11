package xyz.grtlabs.pilastri.sync.eb

import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * SYNTHETIC TEST DATA ONLY. Every name, id, amount, hash and URL below is invented; none is a real account, bank record,
 * session or key. Hosts use the reserved `.test` and `example` names.
 */
internal object EbFx {
    const val APP_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    const val REDIRECT = "https://pilastri.example.test/eb-callback"

    val software: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }

    /** A synthetic self-signed certificate (CN=Pilastri synthetic test), exported once with keytool for the PEM tests. */
    val CERT_PEM = """
        -----BEGIN CERTIFICATE-----
        MIIC5zCCAc+gAwIBAgIIIH29vj3mjEAwDQYJKoZIhvcNAQELBQAwIjEgMB4GA1UE
        AxMXUGlsYXN0cmkgc3ludGhldGljIHRlc3QwHhcNMjYxMDMxMTkwODE2WhcNMzYx
        MDI4MTkwODE2WjAiMSAwHgYDVQQDExdQaWxhc3RyaSBzeW50aGV0aWMgdGVzdDCC
        ASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAJzBZ+7F60t2DX8wmu9pvlG3
        8lkYdV7U2Tma+mmR1r+JBZAckw5tzR4J995NSdjUHfCOndiBBEAQmb1lxzdiUmBx
        ujyNuihDh38OfixXJEnf11uXmQ9IwxS0+GI6KHR/VIIHz5pI/PoJ2OlbxI17lgIM
        lWd+xpqDTg0FgsWeHpGyJLgnnU79J2uDf8q7EcLDNK3AeM9wzDchDHkTsKSimRns
        xwalQa6LmbBKXmwH0N2Noa/iiINMNOpPoNU9YYJk0VRjOY8M2AVXeqr5oMaE+VVm
        YhmdbTxxN3Elve61Uq9qlUL1aEYGc7yA9ChzVvKgfvdF7V6JIVIooDzs5d7HvskC
        AwEAAaMhMB8wHQYDVR0OBBYEFAcAhguXT3Q5ckK43L4MQf4o59TyMA0GCSqGSIb3
        DQEBCwUAA4IBAQCTkIOo0JNlOZedVR1ZZefpJNPcf+Dz94tHNUQaevVoppRNQgdQ
        SCpaUBr7ZTnZ+bMN28rsPWDuVvW+n7Og7uGUQr6GncSlJQId8Ke7xQVPb1vGCDvS
        O+fUUN6/dG1SLgzTZjp1xQkn2qy159QQJ1DEL2wSEuylCrUAjZZFll373NhgFQgg
        Gjt51pEYRAw+sps2NggGC0doMUvXzYnbljLkJeDt4SZM27et3+curlY8UnZcgUS1
        OLIuGWNEKDzm3GqZYb4PJu7G8bmHE+1LfvkPRTwpO8jU1iD5FopYN5UQlVlP9lUh
        DXFaX7cHdeV5wbFI2rXlE5KxESb0bak0i6Ih
        -----END CERTIFICATE-----
    """.trimIndent() + "\n"

    val ASPSPS = """{"aspsps":[
        {"name":"Isybank","country":"IT","bic":"SYNTHIT00","beta":false,"maximum_consent_validity":15552000,"psu_types":["personal"],
         "auth_methods":[{"name":"redirect","title":"Sintetico","psu_type":"personal"}],"required_psu_headers":["Psu-Ip-Address"]},
        {"name":"Banca Sintetica Due","country":"IT","psu_types":["personal","business"]}]}"""

    const val AUTH = """{"url":"https://auth.example.test/start?id=synthetic","authorization_id":"auth-synthetic-1","psu_id_hash":"x"}"""

    const val SESSION = """{"session_id":"sess-synthetic-1","psu_type":"personal","aspsp":{"name":"Isybank","country":"IT"},
        "access":{"valid_until":"2027-04-29T10:00:00.000000+00:00"},
        "accounts":[{"uid":"uid-a","identification_hash":"hash-a","name":"Conto sintetico A","currency":"EUR"},
                    {"uid":"uid-b","identification_hash":"hash-b","product":"Deposito sintetico","currency":"EUR"}]}"""

    fun tx(amount: String?, indicator: String?, status: String? = "BOOK", booking: String? = "2026-10-20", remittance: List<String> = listOf("Pagamento sintetico"),
           creditor: String? = "Negozio Sintetico", debtor: String? = null, currency: String? = "EUR", code: String? = null): String {
        val parts = ArrayList<String>()
        if (amount != null) parts += """"transaction_amount":{"currency":${currency?.let { "\"$it\"" } ?: "null"},"amount":"$amount"}"""
        indicator?.let { parts += """"credit_debit_indicator":"$it"""" }
        status?.let { parts += """"status":"$it"""" }
        booking?.let { parts += """"booking_date":"$it"""" }
        parts += """"remittance_information":[${remittance.joinToString(",") { "\"$it\"" }}]"""
        creditor?.let { parts += """"creditor":{"name":"$it"}""" }
        debtor?.let { parts += """"debtor":{"name":"$it"}""" }
        code?.let { parts += """"bank_transaction_code":{"description":"$it"}""" }
        return "{" + parts.joinToString(",") + "}"
    }

    fun page(rows: List<String>, continuation: String? = null) =
        """{"transactions":[${rows.joinToString(",")}]${continuation?.let { ""","continuation_key":"$it"""" } ?: ""}}"""

    fun balances(vararg items: String) = """{"balances":[${items.joinToString(",")}]}"""
    fun balance(type: String, amount: String, date: String? = "2026-10-30", currency: String = "EUR") =
        """{"name":"x","balance_type":"$type","balance_amount":{"currency":"$currency","amount":"$amount"}${date?.let { ""","reference_date":"$it"""" } ?: ""}}"""
}

/** A scripted Enable Banking: no network, every request recorded. */
internal class FakeEb {
    class Reply(val code: Int = 200, val body: String = "{}", val headers: Map<String, String> = emptyMap())

    val requests = ArrayList<Request>()
    val bodies = ArrayList<String>()
    var failWith: IOException? = null
    var route: (Request) -> Reply = { Reply(404, """{"error":"NOT_FOUND"}""") }

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request()
        requests += req
        bodies += req.body?.let { Buffer().also { b -> it.writeTo(b) }.readUtf8() } ?: ""
        failWith?.let { throw it }
        val reply = route(req)
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(reply.code).message("synthetic")
            .body(reply.body.toResponseBody("application/json".toMediaType())).apply { reply.headers.forEach { (k, v) -> header(k, v) } }.build()
    }.build()

    fun paths(): List<String> = requests.map { it.method + " " + it.url.encodedPath }
    fun api(tokens: EbTokens = EbTokens { "synthetic-token" }): EbApi = EbApi(client, tokens, "https://api.example.test")
}
