package xyz.grtlabs.pilastri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.Base64

class PrefillFactsTest {
    @Test fun parsesOnlyConfirmedDatesAndReportedElections() {
        val json = """{
            "schemaVersion":"2026-10-27.v1","defaultCurrency":"EUR",
            "confirmed":{
                "firstContract":{"start":"2026-09-01","end":"2027-08-31"},
                "allianzJoinDate":"2026-09-15","allianzAllocation":{"azionaria":0.6},
                "esperoApplicationDate":"2026-10-02",
                "esperoEmployeeRate":0.0123,"esperoEmployerRate":0.0456,
                "netPaid2026September":999999
            },
            "illustrativeAssumptions":{"annualInflation":0.99},
            "computedWithExplicitAssumptions":{"2026":{"salaryGross":999999}}
        }"""

        val facts = PrefillFacts.parse(json.toByteArray())

        assertEquals(LocalDate.parse("2026-09-01"), facts.firstContractStart.value)
        assertEquals(LocalDate.parse("2027-08-31"), facts.firstContractEnd.value)
        assertEquals(LocalDate.parse("2026-09-15"), facts.allianzJoinDate.value)
        assertEquals(BigDecimal("0.6"), facts.allianzEquityAllocation.value)
        assertEquals(LocalDate.parse("2026-10-02"), facts.esperoApplicationDate.value)
        assertEquals(BigDecimal("0.0123"), facts.esperoEmployeeRate.value)
        assertEquals(BigDecimal("0.0456"), facts.esperoEmployerRate.value)
        assertEquals(PrefillFactStatus.REPORTED, facts.esperoEmployeeRate.status)
        assertEquals("JSON precompilato 2026 · confirmed.esperoEmployeeRate", facts.esperoEmployeeRate.source)
    }

    @Test fun missingAndExplicitNullRemainDifferentStates() {
        val facts = PrefillFacts.parse(
            """{"schemaVersion":"2026-10-27.v1","defaultCurrency":"EUR","confirmed":{"allianzJoinDate":null}}""".toByteArray()
        )

        assertEquals(PrefillFactStatus.MISSING, facts.firstContractStart.status)
        assertNull(facts.firstContractStart.value)
        assertEquals(PrefillFactStatus.UNKNOWN, facts.allianzJoinDate.status)
        assertNull(facts.allianzJoinDate.value)
        assertEquals(PrefillFactStatus.MISSING, facts.allianzEquityAllocation.status)
        assertEquals(PrefillFactStatus.MISSING, facts.esperoEmployerRate.status)
    }

    @Test fun rejectsUnsupportedSchemaAndMalformedReportedFields() {
        assertThrows(IllegalArgumentException::class.java) {
            PrefillFacts.parse("""{"schemaVersion":"other","defaultCurrency":"EUR"}""".toByteArray())
        }
        assertThrows(Exception::class.java) {
            PrefillFacts.parse("""{"schemaVersion":"2026-10-27.v1","defaultCurrency":"EUR","confirmed":{"allianzJoinDate":"yesterday"}}""".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrefillFacts.parse("""{"schemaVersion":"2026-10-27.v1","defaultCurrency":"EUR","confirmed":{"esperoEmployeeRate":-0.1}}""".toByteArray())
        }
    }

    @Test fun archiveRoundTripPreservesFactsAndExplicitUnknownStatuses() {
        val facts = PrefillFacts.parse("""{
            "schemaVersion":"2026-10-27.v1","defaultCurrency":"EUR",
            "confirmed":{"firstContract":{"start":null},"esperoEmployeeRate":0.015}
        }""".toByteArray())
        val restored = ArchiveCodec.decode(ArchiveCodec.encode(Archive(prefillFacts = facts)))

        assertEquals(facts, restored.prefillFacts)
        assertEquals(PrefillFactStatus.UNKNOWN, restored.prefillFacts?.firstContractStart?.status)
        assertEquals(PrefillFactStatus.MISSING, restored.prefillFacts?.firstContractEnd?.status)
        assertEquals(BigDecimal("0.015"), restored.prefillFacts?.esperoEmployeeRate?.value)
    }

    @Test fun v1AndV2ArchivesRemainReadableAndFactsCannotBeDowngradedToV2() {
        assertEquals(Archive(), ArchiveCodec.decode(legacyArchive(1, includeFacts = false)))
        assertEquals(Archive(), ArchiveCodec.decode(legacyArchive(2, includeFacts = false)))
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.decode(legacyArchive(2, includeFacts = true))
        }
    }

    @Test fun repeatedImportFillsGapsWithoutReplacingReportedFacts() {
        val first = PrefillFacts.parse("""{
            "schemaVersion":"2026-10-27.v1","defaultCurrency":"EUR",
            "confirmed":{"allianzJoinDate":"2026-09-15","esperoEmployeeRate":0.0123}
        }""".toByteArray())
        val second = PrefillFacts.parse("""{
            "schemaVersion":"2026-10-27.v1","defaultCurrency":"EUR",
            "confirmed":{"allianzJoinDate":"2027-01-01","esperoEmployeeRate":0.02,"esperoEmployerRate":0.03}
        }""".toByteArray())

        val merged = first.mergeNewReports(second)

        assertEquals(LocalDate.parse("2026-09-15"), merged.allianzJoinDate.value)
        assertEquals(BigDecimal("0.0123"), merged.esperoEmployeeRate.value)
        assertEquals(BigDecimal("0.03"), merged.esperoEmployerRate.value)
        assertEquals(PrefillFactStatus.MISSING, first.esperoEmployerRate.status)
        assertEquals(merged, merged.mergeNewReports(second))
    }

    @Test fun archivePrefillImportIsIdempotentAndKeepsExistingPayment() {
        val facts = PrefillFacts.parse("""{"schemaVersion":"2026-10-27.v1","defaultCurrency":"EUR","confirmed":{"esperoEmployeeRate":0.0123}}""".toByteArray())
        val importedItems = Prefill.parse("""{"schemaVersion":"2026-10-27.v1","defaultCurrency":"EUR","confirmed":{"netPaid2026September":1000,"netPaid2026October":2000}}""".toByteArray())
        val existing = PlanItem("prefill-2026-9-salary", YearMonth.of(2026, 9), "Existing payment", PlanKind.INCOME,
            PlanStatus.ACTUAL, xyz.grtlabs.pilastri.domain.Money(123400), "existing source")
        val archive = Archive(plan = listOf(existing))

        val once = archive.withPrefillImport(importedItems, facts)
        val twice = once.withPrefillImport(importedItems, facts)

        assertEquals(2, once.plan.size)
        assertEquals(existing, once.plan.first())
        assertEquals(once, twice)
        assertEquals(facts, once.prefillFacts)
    }

    private fun legacyArchive(version: Int, includeFacts: Boolean): ByteArray {
        val payload = JSONObject().put("events", JSONArray()).put("valuations", JSONArray())
        if (version >= 2) payload.put("accounts", JSONArray())
        if (includeFacts) payload.put("prefillFacts", PrefillFacts.parse(
            """{"schemaVersion":"2026-10-27.v1","defaultCurrency":"EUR"}""".toByteArray()
        ).toJson())
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return JSONObject().put("format", "pilastri-archive").put("version", version)
            .put("sha256", digest).put("payload", Base64.getEncoder().encodeToString(bytes))
            .toString().toByteArray(Charsets.UTF_8)
    }
}
