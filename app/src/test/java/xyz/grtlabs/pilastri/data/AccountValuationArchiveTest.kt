package xyz.grtlabs.pilastri.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest
import java.time.LocalDate
import xyz.grtlabs.pilastri.domain.AccountValuation
import xyz.grtlabs.pilastri.domain.Confidence
import xyz.grtlabs.pilastri.domain.EventType
import xyz.grtlabs.pilastri.domain.LedgerEvent
import xyz.grtlabs.pilastri.domain.Money
import xyz.grtlabs.pilastri.domain.Valuation

class AccountValuationArchiveTest {
    private val today = LocalDate.now()
    private val account = Account("account-1", "Conto", 1, "anagrafica utente")

    private fun accountValue(date: LocalDate = today, source: String = "estratto") =
        AccountValuation(account.id, date, Money(12_345), source, Confidence.DOCUMENT)

    private fun reseal(root: JSONObject, payload: JSONObject): ByteArray {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return root.put("sha256", digest).put("payload", java.util.Base64.getEncoder().encodeToString(bytes))
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun archiveAtOldVersion(version: Int): ByteArray {
        val root = JSONObject(ArchiveCodec.encode(Archive(accounts = listOf(account), accountValuations = listOf(accountValue())))
            .toString(Charsets.UTF_8))
        val payload = JSONObject(java.util.Base64.getDecoder().decode(root.getString("payload")).toString(Charsets.UTF_8))
        if (version < 8) { payload.remove("instruments"); payload.remove("trades") }
        if (version < 9) payload.remove("transfers")
        if (version < 10) payload.remove("projections")
        if (version < 11) payload.remove("performanceAttestations")
        if (version < 12) { payload.remove("externalMovements"); payload.remove("connections"); payload.remove("planning"); payload.remove("pension"); payload.remove("holdings"); payload.remove("installments"); payload.remove("installmentSync"); V13.downgrade(payload) }
        if (version < 14) V13.downgrade(payload)
        if (version < 13) { payload.remove("installments"); payload.remove("installmentSync"); V13.downgrade(payload) }
        if (version < 5) payload.remove("accountValuations")
        if (version < 6) {
            val accounts = payload.getJSONArray("accounts")
            for (i in 0 until accounts.length()) accounts.getJSONObject(i).remove("type")
        }
        if (version < 4) {
            val events = payload.getJSONArray("events")
            for (i in 0 until events.length()) events.getJSONObject(i).remove("accountId")
        }
        if (version == 1) {
            payload.remove("accounts")
            payload.remove("prefillFacts")
        } else if (version == 2) {
            payload.remove("prefillFacts")
        }
        return reseal(root.put("version", version), payload)
    }

    @Test fun v9RoundTripKeepsAccountValuationHistoryAndDoesNotInferItFromEvents() {
        val event = LedgerEvent("contribution", today, 1, EventType.PERSONAL_CONTRIBUTION,
            Money(5_000), Confidence.USER_REPORTED, "utente", accountId = account.id)
        val values = listOf(accountValue(today.minusDays(1), "estratto precedente"), accountValue(today, "estratto corrente"))
        val archive = Archive(events = listOf(event), accounts = listOf(account), accountValuations = values)
        val restored = ArchiveCodec.decode(ArchiveCodec.encode(archive))

        assertEquals(archive, restored)
        assertEquals(listOf("estratto corrente", "estratto precedente"), restored.accountValuations.sortedByDescending { it.date }.map { it.source })
        assertEquals(emptyList<AccountValuation>(), ArchiveCodec.decode(ArchiveCodec.encode(Archive(events = listOf(event), accounts = listOf(account)))).accountValuations)
    }

    @Test fun versionsOneThroughSixRemainReadableWithOnlyIntroducedAccountFields() {
        for (version in 1..6) {
            val restored = ArchiveCodec.decode(archiveAtOldVersion(version))
            assertEquals("version $version", if (version >= 5) listOf(accountValue()) else emptyList<AccountValuation>(), restored.accountValuations)
            assertEquals("version $version", if (version == 1) emptyList<Account>() else listOf(account.copy(type = null)), restored.accounts)
        }
        val current = JSONObject(ArchiveCodec.encode(Archive()).toString(Charsets.UTF_8))
        assertEquals(14, current.getInt("version"))
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.decode(current.put("version", 15).toString().toByteArray())
        }
    }

    @Test fun rejectsMissingV7FieldsDuplicateAccountDatesOrphanAndFutureRecords() {
        val root = JSONObject(ArchiveCodec.encode(Archive()).toString(Charsets.UTF_8))
        val missingFieldPayload = JSONObject(java.util.Base64.getDecoder().decode(root.getString("payload")).toString(Charsets.UTF_8))
        missingFieldPayload.remove("accountValuations")
        assertThrows(Exception::class.java) { ArchiveCodec.decode(reseal(root, missingFieldPayload)) }

        val missingTypePayload = JSONObject(java.util.Base64.getDecoder().decode(root.getString("payload")).toString(Charsets.UTF_8))
        missingTypePayload.getJSONArray("accounts").put(JSONObject().put("id", account.id).put("name", account.name)
            .put("pillar", account.pillar).put("source", account.source))
        assertThrows(Exception::class.java) { ArchiveCodec.decode(reseal(root, missingTypePayload)) }

        val duplicate = listOf(accountValue(source = "prima fonte"), accountValue(source = "seconda fonte"))
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(accounts = listOf(account), accountValuations = duplicate))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(accountValuations = listOf(accountValue())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(accounts = listOf(account), accountValuations = listOf(accountValue(today.plusDays(1)))))
        }

        val secondAccount = Account("account-2", "Altro conto", 1, "anagrafica utente")
        assertEquals(2, ArchiveCodec.decode(ArchiveCodec.encode(Archive(accounts = listOf(account, secondAccount),
            accountValuations = listOf(accountValue(), accountValue().copy(accountId = secondAccount.id))))).accountValuations.size)
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(accounts = listOf(account), accountValuations = listOf(accountValue(source = "s".repeat(501)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountValuation(account.id, today, Money(100), "fonte", Confidence.UNKNOWN)
        }
    }

    @Test fun accountValuationReferencesPreventAccountDeletion() {
        val archive = Archive(accounts = listOf(account), accountValuations = listOf(accountValue()))
        assertThrows(IllegalArgumentException::class.java) { archive.withoutAccount(account.id) }
        assertEquals(emptyList<AccountValuation>(), Archive(accounts = listOf(account)).withoutAccount(account.id).accountValuations)
    }

    @Test fun accountValuesStaySeparateFromPillarTotals() {
        val pillarValues = (1..4).map { pillar -> Valuation(today, pillar, Money(1_000), "pillar snapshot", Confidence.DOCUMENT) }
        val archive = Archive(valuations = pillarValues, accounts = listOf(account), accountValuations = listOf(accountValue()))

        assertEquals(4_000L, xyz.grtlabs.pilastri.domain.Finance.completeTotal(archive.valuations)?.cents)
        assertEquals(12_345L, archive.accountValuations.single().amount.cents)
    }
}
