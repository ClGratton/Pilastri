package xyz.grtlabs.pilastri.data

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.domain.LedgerEvent

/**
 * Archive v13 → v14 (Council 4 a) on a synthetic fixture. Every value below is invented for the test ("sintetico"); none comes
 * from a real archive. The golden v14 is: every pre-existing collection identical as stored JSON (after the documented key
 * renames), plus the literal new collections below.
 */
class V14MigrationTest {
    private val v13 = """
    {"events":[
      {"id":"e-own","date":"2026-03-15","pillar":4,"type":"PERSONAL_CONTRIBUTION","cents":10000,"currency":"EUR","confidence":"USER_REPORTED","source":"Fixture sintetica · busta paga","transferPair":null,"accountId":"acc-fund-n"},
      {"id":"e-emp","date":"2026-03-15","pillar":4,"type":"EMPLOYER_CONTRIBUTION","cents":5000,"currency":"EUR","confidence":"DOCUMENT","source":"Fixture sintetica · contributo datore","transferPair":null,"accountId":"acc-fund-n"},
      {"id":"e-emp2","date":"2026-04-15","pillar":4,"type":"EMPLOYER_CONTRIBUTION","cents":5100,"currency":"EUR","confidence":"USER_REPORTED","source":"Fixture sintetica · senza conto","transferPair":null,"accountId":null},
      {"id":"e-inc","date":"2026-05-02","pillar":1,"type":"INCOME","cents":123456,"currency":"EUR","confidence":"DOCUMENT","source":"Fixture sintetica · entrata","transferPair":null,"accountId":"acc-bank"},
      {"id":"e-wd","date":"2026-05-03","pillar":1,"type":"WITHDRAWAL","cents":2500,"currency":"EUR","confidence":"DOCUMENT","source":"Fixture sintetica · uscita","transferPair":null,"accountId":"acc-bank"},
      {"id":"e-fee","date":"2026-05-04","pillar":4,"type":"FEE","cents":199,"currency":"EUR","confidence":"DOCUMENT","source":"Fixture sintetica · commissione","transferPair":null,"accountId":"acc-broker"}],
     "valuations":[{"date":"2026-06-30","pillar":1,"cents":250000,"currency":"EUR","confidence":"DOCUMENT","source":"Fixture sintetica · estratto"}],
     "indices":[{"month":"2026-01","value":"100.4","source":"Serie sintetica"},{"month":"2026-02","value":"101.1","source":"Serie sintetica"}],
     "audit":[{"at":"2026-10-01T10:00:00Z","kind":"rettifica","previous":"fixture sintetica"}],
     "plan":[{"id":"prefill-2026-9-salary","month":"2026-09","label":"Stipendio netto","kind":"INCOME","status":"ACTUAL","cents":11100,"currency":"EUR","source":"JSON precompilato 2026 · dichiarato"},
             {"id":"p-scn","month":"2030-01","label":"entrata ipotetica","kind":"INCOME","status":"SCENARIO","cents":5000,"currency":"EUR","source":"scenario sintetico"}],
     "accounts":[
      {"id":"acc-bank","name":"Banca sintetica","pillar":1,"source":"fixture","type":"GENERAL"},
      {"id":"acc-paypal","name":"Wallet sintetico","pillar":1,"source":"fixture","type":null},
      {"id":"acc-broker","name":"Broker sintetico","pillar":4,"source":"fixture","type":"GENERAL"},
      {"id":"acc-fund-n","name":"Fondo negoziale sintetico","pillar":4,"source":"fixture","type":"ESPERO"},
      {"id":"acc-fund-a","name":"Fondo aperto sintetico","pillar":4,"source":"fixture","type":"ALLIANZ_INSIEME"}],
     "prefillFacts":{
      "firstContractStart":{"status":"REPORTED","source":"JSON precompilato 2026 · confirmed.firstContract.start","value":"2020-09-01"},
      "firstContractEnd":{"status":"UNKNOWN","source":"JSON precompilato 2026 · confirmed.firstContract.end","value":null},
      "allianzJoinDate":{"status":"REPORTED","source":"JSON precompilato 2026 · confirmed.allianzJoinDate","value":"2021-01-15"},
      "allianzEquityAllocation":{"status":"REPORTED","source":"JSON precompilato 2026 · confirmed.allianzAllocation.azionaria","value":"0.5"},
      "esperoApplicationDate":{"status":"MISSING","source":"JSON precompilato 2026 · confirmed.esperoApplicationDate","value":null},
      "esperoEmployeeRate":{"status":"REPORTED","source":"JSON precompilato 2026 · confirmed.esperoEmployeeRate","value":"0.0123"},
      "esperoEmployerRate":{"status":"REPORTED","source":"JSON precompilato 2026 · confirmed.esperoEmployerRate","value":"0.0456"}},
     "accountValuations":[
      {"accountId":"acc-fund-n","date":"2026-06-30","cents":777700,"currency":"EUR","source":"Fixture sintetica · estratto fondo","confidence":"DOCUMENT"},
      {"accountId":"acc-fund-a","date":"2026-06-30","cents":333300,"currency":"EUR","source":"Fixture sintetica · estratto fondo","confidence":"DOCUMENT"}],
     "instruments":[{"id":"i:IE00B4L5Y983","name":"ETF sintetico","identifier":"IE00B4L5Y983","source":"fixture"}],
     "trades":[{"id":"t1","accountId":"acc-broker","instrumentId":"i:IE00B4L5Y983","date":"2026-03-02","side":"BUY","quantity":"2.5","grossCents":20100,"grossCurrency":"EUR","feeCents":100,"feeCurrency":"EUR","source":"fixture"}],
     "transfers":[{"id":"tr1","date":"2026-03-01","fromAccountId":"acc-bank","toAccountId":"acc-broker","cents":50000,"currency":"EUR","source":"fixture"}],
     "projections":[],
     "performanceAttestations":[],
     "externalMovements":[
      {"id":"ISYBANK:m1","provider":"ISYBANK","accountId":"acc-bank","date":"2026-05-03","signedCents":"-2500","currency":"EUR","description":"Pagamento sintetico","kind":"CARD_PAYMENT","rawType":null,"isin":null,"quantity":null,"status":"APPLIED","linkedRecordId":"e-wd","importedAt":"2026-05-04T08:00:00Z","category":"Spesa","categoryId":"cat-food","categorySource":"OVERRIDE","merchantKey":"pagamento sintetico","role":"SPEND"},
      {"id":"TRADE_REPUBLIC:m2","provider":"TRADE_REPUBLIC","accountId":"acc-broker","date":"2026-03-02","signedCents":"-20100","currency":"EUR","description":"ETF sintetico","kind":"BUY","rawType":"TRADE","isin":"IE00B4L5Y983","quantity":"2.5","status":"PENDING","linkedRecordId":null,"importedAt":"2026-03-03T08:00:00Z","category":null,"categoryId":null,"categorySource":null,"merchantKey":null,"role":null},
      {"id":"PAYPAL:m3","provider":"PAYPAL","accountId":"acc-paypal","date":"2026-06-10","signedCents":"123456","currency":"EUR","description":"Accredito sintetico","kind":"DEPOSIT","rawType":null,"isin":null,"quantity":null,"status":"PENDING","linkedRecordId":null,"importedAt":"2026-06-11T08:00:00Z","category":null,"categoryId":null,"categorySource":null,"merchantKey":"accredito sintetico","role":"SALARY"},
      {"id":"FUTURE_BANK:m4","provider":"FUTURE_BANK","accountId":"acc-bank","date":"2026-06-11","signedCents":"-999","currency":"EUR","description":"Riga di un fornitore futuro","kind":"OTHER","status":"PENDING","linkedRecordId":null,"importedAt":"2026-06-12T08:00:00Z","futureField":"kept"}],
     "connections":[
      {"provider":"ISYBANK","accountId":"acc-bank","lastSyncAt":"2026-06-30T08:00:00Z","coverageFrom":"2026-01-01","coverageTo":"2026-06-30","lastResult":"3 nuovi, 0 già presenti","securitiesAccountId":null},
      {"provider":"TRADE_REPUBLIC","accountId":"acc-broker","lastSyncAt":"2026-06-30T08:00:00Z","coverageFrom":"2026-03-01","coverageTo":"2026-06-30","lastResult":null,"securitiesAccountId":null},
      {"provider":"FUTURE_BANK","accountId":"acc-bank","lastSyncAt":null,"coverageFrom":null,"coverageTo":null,"lastResult":null,"securitiesAccountId":null}],
     "planning":{
      "categories":[{"id":"cat-food","name":"Alimentari","kind":"SPEND","essential":"ESSENTIAL","parentId":null}],
      "rules":[{"id":"r1","pattern":"supermercato","categoryId":"cat-food","origin":"USER","accountId":null,"minCents":null,"maxCents":null,"priority":0}],
      "providerCategories":[{"provider":"ISYBANK","rawLabel":"Spesa","categoryId":"cat-food"},{"provider":"FUTURE_BANK","rawLabel":"Spesa","categoryId":"cat-food"}],
      "budgets":[{"categoryId":"cat-food","monthlyCents":"30000","effectiveFrom":"2026-01"}],
      "goals":[{"id":"g1","name":"Obiettivo sintetico","targetCents":"500000","targetDate":"2027-12-31","accountId":"acc-bank","earmarkCents":null}],
      "targets":[{"pillar":1,"kind":"BUFFER_CENTS","cents":"100000","months":null,"userSet":true}],
      "salaryRules":[{"id":"s1","accountId":"acc-paypal","payerPattern":"accredito sintetico","dayFrom":5,"dayTo":12,"minCents":"100000","maxCents":"150000","confirmedAt":"2026-06-12T10:00:00Z"}]},
     "pension":{
      "statements":[{"id":"ps1","accountId":"acc-fund-n","positionDate":"2026-06-30","positionCents":"777700","ownCents":"10000","employerCents":"10100","tfrCents":null,"costsCents":null,"taxesCents":null,"periodFrom":"2026-01-01","periodTo":"2026-06-30","documentHash":null,"source":"Fixture sintetica · estratto","extractedByAgent":false,"confirmedAt":null}],
      "profiles":[{"accountId":"acc-fund-n","regime":"PUBLIC","tfrElection":"Esempio sintetico","effectiveFrom":"2021-01-01","electedRatePercent":"1.23","source":"Fixture sintetica · scheda"}],
      "deductionYears":[{"taxYear":2026,"ceilingCents":"123400","source":"Fixture sintetica","sourceDate":"2026-01-15"}],
      "inps":[{"asOf":"2026-02-01","monthlyNetCents":"99900","retirementDate":null,"assumptions":"Ipotesi sintetiche","source":"Fixture sintetica"}]},
     "holdings":[{"accountId":"acc-broker","isin":"IE00B4L5Y983","name":"ETF sintetico","quantity":"2.5","priceCents":"8040","date":"2026-06-30","source":"fixture"}],
     "installments":[{"id":"paypal:plan1","lender":"Rate sintetiche","merchant":"Negozio sintetico","totalCents":"30000","currency":"EUR","count":3,"purchaseDate":"2026-09-01","categoryId":null,"fundingAccountId":"acc-paypal","source":"PAYPAL","review":"CONFIRMED","extractedByAgent":false,"updatedAt":"2026-09-02T10:00:00Z","aliases":[],
       "installments":[{"seq":1,"dueDate":"2026-09-01","cents":"10000","state":"PAID","paidOn":"2026-09-01","movementId":null},{"seq":2,"dueDate":"2026-10-01","cents":"10000","state":"DUE","paidOn":null,"movementId":null}],
       "evidence":[{"ref":"paypal:tx1","date":"2026-09-01","summary":"Ricevuta sintetica"}]}],
     "installmentSync":[{"source":"GMAIL","lastScanAt":"2026-10-01T10:00:00Z","coverageFrom":"2026-01-01","coverageTo":"2026-10-01","lastResult":"1 piano"}]}
    """

    private val nd = """{"value":null,"origin":"ND","docId":null,"fieldLabel":null,"asOf":null}"""
    private fun declared(v: String) = """{"value":"$v","origin":"DICHIARATO","docId":null,"fieldLabel":null,"asOf":null}"""
    private fun fund(id: String, name: String, form: String) = """{"id":"$id","name":${declared(name)},"formType":$form,"covipNumber":$nd,"memberSince":$nd,"workerPct":$nd,"employerPct":$nd,"tfrShare":$nd,"positionId":$nd}"""

    /** The literal new parts of the golden v14 payload. */
    private val goldenAccounts = JSONArray("""[
      {"id":"acc-bank","name":"Banca sintetica","pillar":1,"source":"fixture","type":"GENERAL","fundId":null,"legacyType":null},
      {"id":"acc-paypal","name":"Wallet sintetico","pillar":1,"source":"fixture","type":null,"fundId":null,"legacyType":null},
      {"id":"acc-broker","name":"Broker sintetico","pillar":4,"source":"fixture","type":"GENERAL","fundId":null,"legacyType":null},
      {"id":"acc-fund-n","name":"Fondo negoziale sintetico","pillar":4,"source":"fixture","type":"PENSION_FUND","fundId":"fund:acc-fund-n","legacyType":"ESPERO"},
      {"id":"acc-fund-a","name":"Fondo aperto sintetico","pillar":4,"source":"fixture","type":"PENSION_FUND","fundId":"fund:acc-fund-a","legacyType":"ALLIANZ_INSIEME"}]""")
    private val goldenFunds = JSONArray("[" + fund("fund:acc-fund-n", "Fondo negoziale sintetico", declared("NEGOZIALE")) + "," +
        fund("fund:acc-fund-a", "Fondo aperto sintetico", nd) + "]")
    private val goldenIncomeRules = JSONArray("""[{"id":"s1","title":"Stipendio","accountId":"acc-paypal","payerPattern":"accredito sintetico","dayFrom":5,"dayTo":12,"minCents":"100000","maxCents":"150000","confirmedAt":"2026-06-12T10:00:00Z","positionId":null}]""")

    private fun renamed(a: JSONArray): JSONArray = JSONArray(a.toString()).also { arr ->
        for (i in 0 until arr.length()) arr.getJSONObject(i).let { o -> if (o.has("provider")) o.put("providerId", o.remove("provider")) }
    }
    /** JSON as plain values (the Android org.json API compiled against has no similar()); numbers compare by value. */
    private fun plain(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { plain(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { plain(v.get(it)) }
        is Number -> java.math.BigDecimal(v.toString()).stripTrailingZeros()
        JSONObject.NULL -> null
        else -> v
    }
    private fun assertSimilar(name: String, expected: Any, actual: Any) =
        assertTrue("$name differs:\nexpected $expected\nactual   $actual", plain(expected) == plain(actual))
    private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()

    private val v13Payload get() = JSONObject(v13)
    private fun migrated() = ArchiveCodec.decode(V13.seal(13, v13Payload))

    @Test fun v13FixtureMigratesToTheGoldenV14() {
        val before = v13Payload
        val v14Bytes = ArchiveCodec.encode(migrated())
        assertEquals(14, PremigrationCopy.storedVersion(v14Bytes))
        val after = V13.payloadOf(v14Bytes)

        // Every stored figure, source label and date of the pre-existing collections, unchanged.
        listOf("events", "valuations", "indices", "audit", "plan", "prefillFacts", "accountValuations", "instruments", "trades",
            "transfers", "projections", "performanceAttestations", "pension", "holdings", "installments", "installmentSync")
            .forEach { assertSimilar(it, before.get(it), after.get(it)) }
        // Provider rows: only the key "provider" → "providerId"; the unknown provider's row is kept with every field.
        assertSimilar("externalMovements", renamed(before.getJSONArray("externalMovements")), after.getJSONArray("externalMovements"))
        assertSimilar("connections", renamed(before.getJSONArray("connections")), after.getJSONArray("connections"))
        val planning = JSONObject(before.getJSONObject("planning").toString()).apply {
            remove("salaryRules"); put("providerCategories", renamed(getJSONArray("providerCategories")))
        }
        assertSimilar("planning", planning, after.getJSONObject("planning"))
        // The literal new parts.
        assertSimilar("accounts", goldenAccounts, after.getJSONArray("accounts"))
        assertSimilar("funds", goldenFunds, after.getJSONArray("funds"))
        assertSimilar("incomeRules", goldenIncomeRules, after.getJSONArray("incomeRules"))
        (listOf("positions") + RulesData.KEYS).forEach { assertSimilar(it, JSONArray(), after.getJSONArray(it)) }
        assertEquals((before.keyNames() - "planning") + "planning" + "positions" + "funds" + "incomeRules" + RulesData.KEYS, after.keyNames())
    }

    @Test fun migrationKeepsEveryCountCreatesNoPositionAndRoundTrips() {
        val archive = migrated()
        val before = ArchiveCounts.before(v13Payload)
        val after = ArchiveCounts.after(archive)
        ArchiveCounts.PRESERVED.forEach { assertEquals(it, before[it], after[it]) }
        assertTrue(archive.positions.isEmpty())
        assertEquals(2, after["funds"]); assertEquals(before["legacyFundAccounts"], after["funds"])
        assertEquals(archive, ArchiveCodec.decode(ArchiveCodec.encode(archive)))
        assertEquals(archive, ArchiveMigration.toV14(archive))
        // The unknown provider's rows stay out of the typed lists and come back unchanged.
        assertEquals(listOf("ISYBANK:m1", "TRADE_REPUBLIC:m2", "PAYPAL:m3"), archive.externalMovements.map { it.id })
        assertEquals(1, archive.unknownProviderRows.externalMovements.size)
        assertEquals("kept", archive.unknownProviderRows.externalMovements.single().toJsonObject().getString("futureField"))
        assertEquals(1, archive.unknownProviderRows.connections.size)
        assertEquals(1, archive.unknownProviderRows.providerCategories.size)
        assertThrows(IllegalArgumentException::class.java) { archive.withoutAccount("acc-bank") }
        // Legacy data stays reachable where it was.
        assertEquals("1.23", archive.pension.profiles.single().electedRatePercent?.toPlainString())
        assertEquals(1, archive.pension.deductionYears.size)
        assertEquals("0.0123", archive.prefillFacts?.esperoEmployeeRate?.value?.toPlainString())
        assertEquals(IncomeRule("s1", "Stipendio", "acc-paypal", "accredito sintetico", 5, 12, 100_000, 150_000, "2026-06-12T10:00:00Z"),
            archive.planning.incomeRules.single())
    }

    @Test fun employerContributionsAttachThroughTheAccountsFundAndPositionStaysNd() {
        val archive = migrated()
        fun event(id: String): LedgerEvent = archive.events.single { it.id == id }
        assertEquals(FundAttachment("fund:acc-fund-n", Sourced.nd()), archive.attachmentOf(event("e-emp")))
        assertEquals(FundAttachment(null, Sourced.nd()), archive.attachmentOf(event("e-emp2")))
        assertNull(archive.attachmentOf(event("e-inc")))
        val negoziale = archive.funds.single { it.id == "fund:acc-fund-n" }
        assertEquals(Sourced.declared(coded(FundFormType.NEGOZIALE)), negoziale.formType)
        assertFalse(archive.funds.single { it.id == "fund:acc-fund-a" }.formType.isKnown)
    }

    @Test fun v14KeepsUnknownCodesAndRawRuleCollectionsVerbatim() {
        val payload = V13.payloadOf(ArchiveCodec.encode(migrated()))
        payload.put("positions", JSONArray().put(JSONObject()
            .put("id", "pos-1").put("kind", "FUTURE_KIND")
            .put("validFrom", JSONObject(declared("2026-01-01"))).put("validTo", JSONObject(nd)).put("label", JSONObject(declared("Lavoro sintetico")))
            .put("gestione", JSONObject(declared("FUTURE_GESTIONE"))).put("severanceRegime", JSONObject(declared("TFR")))
            .put("severanceDestination", JSONObject(nd)).put("contractTerm", JSONObject(nd)).put("hireDate", JSONObject(nd))
            .put("ateco", JSONObject(nd)).put("regimeStart", JSONObject(nd))
            .put("incomeSource", JSONObject("""{"value":"Datore sintetico","origin":"DOCUMENTO","docId":"doc-1","fieldLabel":"Datore","asOf":"2026-02-01"}"""))))
        payload.getJSONArray("funds").getJSONObject(1).put("formType", JSONObject(declared("FUTURE_FORM")))
            .put("positionId", JSONObject(declared("pos-1"))).put("workerPct", JSONObject(declared("1.50")))
        payload.put("packVersions", JSONArray("""[{"id":"pv1","parent":null,"rows":[{"k":"x","v":"0.23"}],"nested":{"a":[1,2,3]}}]"""))
        payload.put("payslipLineClasses", JSONArray("""[{"code":"Z1","class":"FUTURE_CLASS"}]"""))
        payload.getJSONArray("externalMovements").put(JSONObject().put("id", "NEW:x").put("providerId", "NEW_PROVIDER").put("accountId", "acc-paypal").put("anything", 1))

        val decoded = ArchiveCodec.decode(V13.seal(14, payload))
        val position = decoded.positions.single()
        assertEquals(Coded.Unknown("FUTURE_KIND"), position.kind)
        assertEquals("FUTURE_GESTIONE", position.gestione.value?.raw)
        assertEquals(coded(SeveranceRegime.TFR), position.severanceRegime.value)
        assertEquals(Sourced.document("Datore sintetico", "doc-1", "Datore", java.time.LocalDate.of(2026, 2, 1)), position.incomeSource)
        assertEquals("FUTURE_FORM", decoded.funds[1].formType.value?.raw)
        assertEquals(java.math.BigDecimal("1.50"), decoded.funds[1].workerPct.value)
        assertEquals(1, decoded.rulesData.packVersions.size)
        assertEquals(2, decoded.unknownProviderRows.externalMovements.size)
        // An account an unknown provider's row refers to cannot be removed.
        val lone = Account("acc-x", "Conto sintetico", 1, "fixture")
        val withRow = Archive(accounts = listOf(lone), unknownProviderRows = UnknownProviderRows(listOf(RawJsonObject.of(JSONObject().put("providerId", "NEW").put("accountId", "acc-x")))))
        assertThrows(IllegalArgumentException::class.java) { withRow.withoutAccount("acc-x") }
        assertTrue(Archive(accounts = listOf(lone)).withoutAccount("acc-x").accounts.isEmpty())

        val again = V13.payloadOf(ArchiveCodec.encode(decoded))
        assertEquals(decoded, ArchiveCodec.decode(ArchiveCodec.encode(decoded)))
        listOf("positions", "funds", "packVersions", "personalParams", "snapshots", "payslipLineClasses", "externalMovements", "connections", "planning", "accounts", "incomeRules")
            .forEach { assertSimilar(it, payload.get(it), again.get(it)) }
    }

    @Test fun invalidV14ReferencesAndOlderFilesWithV14KeysAreRejected() {
        val payload = V13.payloadOf(ArchiveCodec.encode(migrated()))
        val danglingFund = JSONObject(payload.toString()).apply { getJSONArray("accounts").getJSONObject(3).put("fundId", "fund:missing") }
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.decode(V13.seal(14, danglingFund)) }
        val danglingPosition = JSONObject(payload.toString()).apply { getJSONArray("funds").getJSONObject(0).put("positionId", JSONObject(declared("nowhere"))) }
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.decode(V13.seal(14, danglingPosition)) }
        val missingKey = JSONObject(payload.toString()).apply { remove("snapshots") }
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.decode(V13.seal(14, missingKey)) }
        val v13WithNewKey = v13Payload.put("funds", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.decode(V13.seal(13, v13WithNewKey)) }
        val unknownLegacyType = v13Payload.apply { getJSONArray("accounts").getJSONObject(0).put("type", "SOMETHING_ELSE") }
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.decode(V13.seal(13, unknownLegacyType)) }
    }

    @Test fun premigrationCopyIsMadeOnceAndNeverOverwritten() {
        val dir = Files.createTempDirectory("pilastri-premigration").toFile()
        try {
            val old = V13.seal(13, v13Payload)
            val copy = File(dir, PremigrationCopy.FILE_NAME)
            assertFalse(PremigrationCopy.preserve(dir, null))
            assertFalse(PremigrationCopy.preserve(dir, ArchiveCodec.encode(migrated())))
            assertFalse(copy.exists())
            assertTrue(PremigrationCopy.preserve(dir, old))
            assertTrue(copy.readBytes().contentEquals(old))
            assertFalse(PremigrationCopy.preserve(dir, V13.seal(12, JSONObject().put("events", JSONArray()).put("valuations", JSONArray()))))
            assertTrue(copy.readBytes().contentEquals(old))
            assertFalse(File(dir, PremigrationCopy.FILE_NAME + ".tmp").exists())
            assertEquals(13, PremigrationCopy.storedVersion(copy.readBytes()))
            assertNull(PremigrationCopy.storedVersion("not an archive".toByteArray()))
        } finally { dir.deleteRecursively() }
    }
}
