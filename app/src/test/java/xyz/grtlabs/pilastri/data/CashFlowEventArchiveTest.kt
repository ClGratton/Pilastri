package xyz.grtlabs.pilastri.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.util.Base64
import xyz.grtlabs.pilastri.domain.AccountValuation
import xyz.grtlabs.pilastri.domain.Confidence
import xyz.grtlabs.pilastri.domain.EventType
import xyz.grtlabs.pilastri.domain.Finance
import xyz.grtlabs.pilastri.domain.LedgerEvent
import xyz.grtlabs.pilastri.domain.Money
import xyz.grtlabs.pilastri.domain.Valuation

class CashFlowEventArchiveTest {
    private val today = LocalDate.now()
    private val account = Account("general-4", "Conto sintetico", 4, "fixture sintetica", AccountType.GENERAL)

    private fun event(
        id: String,
        type: EventType,
        cents: Long,
        date: LocalDate = today,
        pillar: Int = 4,
        accountId: String? = account.id
    ) = LedgerEvent(id, date, pillar, type, Money(cents), Confidence.DOCUMENT, "fixture sintetica", accountId = accountId)

    private fun reseal(root: JSONObject, payload: JSONObject): ByteArray {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return root.put("sha256", digest).put("payload", Base64.getEncoder().encodeToString(bytes))
            .toString().toByteArray(Charsets.UTF_8)
    }

    /** Produces an authentic historical shape, removing fields only after their introduction version. */
    private fun encodeAtVersion(archive: Archive, version: Int): ByteArray {
        require(version in 1..6)
        val root = JSONObject(ArchiveCodec.encode(archive).toString(Charsets.UTF_8))
        val payload = JSONObject(Base64.getDecoder().decode(root.getString("payload")).toString(Charsets.UTF_8))
        if (version < 8) { payload.remove("instruments"); payload.remove("trades") }
        if (version < 9) payload.remove("transfers")
        if (version < 10) payload.remove("projections")
        if (version < 11) payload.remove("performanceAttestations")
        if (version < 12) { payload.remove("externalMovements"); payload.remove("connections"); payload.remove("planning"); payload.remove("pension"); payload.remove("holdings"); payload.remove("installments"); payload.remove("installmentSync"); V13.downgrade(payload) }
        if (version < 14) V13.downgrade(payload)
        if (version < 13) { payload.remove("installments"); payload.remove("installmentSync"); V13.downgrade(payload) }
        if (version < 7) {
            val events = payload.getJSONArray("events")
            for (i in 0 until events.length()) {
                val item = events.getJSONObject(i)
                if (EventType.valueOf(item.getString("type")) !in setOf(EventType.PERSONAL_CONTRIBUTION, EventType.EMPLOYER_CONTRIBUTION)) {
                    throw IllegalArgumentException("This fixture cannot represent new event types in an old archive")
                }
            }
        }
        if (version < 6) {
            val accounts = payload.getJSONArray("accounts")
            for (i in 0 until accounts.length()) accounts.getJSONObject(i).remove("type")
        }
        if (version < 5) payload.remove("accountValuations")
        if (version < 4) {
            val events = payload.getJSONArray("events")
            for (i in 0 until events.length()) events.getJSONObject(i).remove("accountId")
        }
        if (version < 3) payload.remove("prefillFacts")
        if (version == 1) payload.remove("accounts")
        return reseal(root.put("version", version), payload)
    }

    private fun currentRoundTripFixture(): Archive {
        val events = listOf(
            event("own-a", EventType.PERSONAL_CONTRIBUTION, 1_000),
            event("own-b", EventType.PERSONAL_CONTRIBUTION, 2_500),
            event("employer", EventType.EMPLOYER_CONTRIBUTION, 500),
            event("withdrawal", EventType.WITHDRAWAL, 700, accountId = null),
            event("income", EventType.INCOME, 80),
            event("fee", EventType.FEE, 25)
        )
        val facts = PrefillFacts.parse(
            """{"schemaVersion":"2026-10-27.v1","defaultCurrency":"EUR","confirmed":{"esperoEmployeeRate":0.0123}}""".toByteArray()
        )
        return Archive(
            events = events,
            valuations = listOf(Valuation(today, 4, Money(10_000), "fixture snapshot", Confidence.DOCUMENT)),
            indices = listOf(IndexPoint(YearMonth.now(), java.math.BigDecimal("100"), "synthetic index")),
            audit = listOf(AuditEntry("2026-10-28T10:00:00Z", "fixture", "previous")),
            plan = listOf(PlanItem("plan-1", YearMonth.now(), "Synthetic item", PlanKind.INCOME, PlanStatus.ACTUAL, Money(2_000), "fixture")),
            accounts = listOf(account),
            prefillFacts = facts,
            accountValuations = listOf(AccountValuation(account.id, today, Money(8_000), "fixture account value", Confidence.DOCUMENT))
        )
    }

    @Test fun v9RoundTripPreservesEveryArchiveFieldAndKeepsFlowTypesSeparateFromContributions() {
        val archive = currentRoundTripFixture()
        val encoded = ArchiveCodec.encode(archive)

        assertEquals(14, JSONObject(encoded.toString(Charsets.UTF_8)).getInt("version"))
        assertEquals(archive, ArchiveCodec.decode(encoded))
        assertEquals(Money(3_500), Finance.ownContributions(archive.events, 4))
        assertEquals(Money(500), Finance.employerContributions(archive.events, 4))
        assertEquals(700L, archive.events.single { it.type == EventType.WITHDRAWAL }.amount.cents)
        assertNull(archive.events.single { it.type == EventType.WITHDRAWAL }.accountId)
        assertEquals(80L, archive.events.single { it.type == EventType.INCOME }.amount.cents)
        assertEquals(25L, archive.events.single { it.type == EventType.FEE }.amount.cents)
        assertEquals(setOf(account.id), archive.events.mapNotNull { it.accountId }.toSet())
    }

    @Test fun v1ThroughV6RemainReadableWithoutInventingFieldsOrChangingLegacyEvents() {
        val originalEvent = event("legacy-own", EventType.PERSONAL_CONTRIBUTION, 1_250)
        val original = Archive(
            events = listOf(originalEvent),
            accounts = listOf(account),
            accountValuations = listOf(AccountValuation(account.id, today, Money(4_000), "fixture", Confidence.DOCUMENT))
        )

        for (version in 1..6) {
            val restored = ArchiveCodec.decode(encodeAtVersion(original, version))
            assertEquals("event in v$version", listOf(originalEvent.copy(accountId = if (version >= 4) account.id else null)), restored.events)
            assertEquals("account in v$version", if (version == 1) emptyList<Account>() else listOf(account.copy(type = if (version >= 6) account.type else null)), restored.accounts)
            assertEquals("account values in v$version", if (version >= 5) original.accountValuations else emptyList<AccountValuation>(), restored.accountValuations)
            assertNull("prefill facts in v$version", restored.prefillFacts)
        }
    }

    @Test fun olderArchiveVersionsRejectNewCashFlowSemantics() {
        val newer = Archive(events = listOf(event("income", EventType.INCOME, 100, accountId = null)))
        for (version in 1..6) {
            val root = JSONObject(ArchiveCodec.encode(newer).toString(Charsets.UTF_8))
            val payload = JSONObject(Base64.getDecoder().decode(root.getString("payload")).toString(Charsets.UTF_8))
            payload.remove("instruments"); payload.remove("trades")
            payload.remove("transfers")
            payload.remove("projections")
            payload.remove("performanceAttestations"); payload.remove("externalMovements"); payload.remove("connections"); payload.remove("planning"); payload.remove("pension"); payload.remove("holdings"); payload.remove("installments"); payload.remove("installmentSync"); V13.downgrade(payload)
            if (version < 6) payload.getJSONArray("accounts").let { accounts ->
                for (i in 0 until accounts.length()) accounts.getJSONObject(i).remove("type")
            }
            if (version < 5) payload.remove("accountValuations")
            if (version < 4) payload.getJSONArray("events").getJSONObject(0).remove("accountId")
            if (version < 3) payload.remove("prefillFacts")
            if (version == 1) payload.remove("accounts")
            assertThrows("version $version", IllegalArgumentException::class.java) {
                ArchiveCodec.decode(reseal(root.put("version", version), payload))
            }
        }
        val future = JSONObject(ArchiveCodec.encode(Archive()).toString(Charsets.UTF_8)).put("version", 15)
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.decode(future.toString().toByteArray()) }
    }

    @Test fun datedNonnegativeEurEventsRequireValidAccountReferencesAndRejectOverflow() {
        assertThrows(IllegalArgumentException::class.java) { event("negative", EventType.FEE, -1) }
        assertThrows(IllegalArgumentException::class.java) {
            LedgerEvent("future", today.plusDays(1), 4, EventType.INCOME, Money(100), Confidence.DOCUMENT, "fixture")
                .let { ArchiveCodec.encode(Archive(events = listOf(it))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(events = listOf(event("orphan", EventType.WITHDRAWAL, 100, accountId = "missing"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(events = listOf(event("wrong-pillar", EventType.INCOME, 100, pillar = 3)), accounts = listOf(account)))
        }
        assertThrows(ArithmeticException::class.java) {
            ArchiveCodec.encode(Archive(events = listOf(
                event("large", EventType.INCOME, Long.MAX_VALUE, accountId = null),
                event("one-more", EventType.FEE, 1, accountId = null)
            )))
        }
    }

    @Test fun transfersAndCorrectionMarkersRemainUnsupported() {
        val transfer = LedgerEvent("transfer", today, 4, EventType.TRANSFER, Money(100), Confidence.DOCUMENT, "fixture", transferPair = "pair")
        val correction = LedgerEvent("correction", today, 4, EventType.CORRECTION, Money(100), Confidence.DOCUMENT, "fixture")

        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.encode(Archive(events = listOf(transfer))) }
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.encode(Archive(events = listOf(correction))) }
        assertThrows(IllegalArgumentException::class.java) { Money(100, "USD") }
    }
}
