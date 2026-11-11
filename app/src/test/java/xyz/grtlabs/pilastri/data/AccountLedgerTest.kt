package xyz.grtlabs.pilastri.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest
import java.time.LocalDate
import xyz.grtlabs.pilastri.domain.Confidence
import xyz.grtlabs.pilastri.domain.EventType
import xyz.grtlabs.pilastri.domain.LedgerEvent
import xyz.grtlabs.pilastri.domain.Money

class AccountLedgerTest {
    private val date = LocalDate.of(2026, 1, 15)
    private val account = Account("account-1", "Fondo", 4, "estratto 2026")
    private val legacyEvent = LedgerEvent("legacy", date, 4, EventType.PERSONAL_CONTRIBUTION,
        Money(12_000), Confidence.USER_REPORTED, "storico")

    private fun encodeAsOlderVersion(archive: Archive, version: Int): ByteArray {
        val root = JSONObject(ArchiveCodec.encode(archive).toString(Charsets.UTF_8))
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
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return root.put("version", version).put("sha256", digest)
            .put("payload", java.util.Base64.getEncoder().encodeToString(bytes))
            .toString().toByteArray(Charsets.UTF_8)
    }

    @Test fun versionsOneThroughSixDecodeAssociationsOnlyWhenTheirSchemaSupportsThem() {
        val assigned = legacyEvent.copy(id = "assigned", accountId = account.id)
        val source = Archive(events = listOf(legacyEvent, assigned), accounts = listOf(account))
        for (version in 1..6) {
            val decoded = ArchiveCodec.decode(encodeAsOlderVersion(source, version))
            val expectedEvents = if (version < 4) listOf(legacyEvent, assigned.copy(accountId = null)) else listOf(legacyEvent, assigned)
            assertEquals("version $version", expectedEvents, decoded.events)
            assertNull("version $version", decoded.events.first().accountId)
            assertEquals("version $version", if (version == 1) emptyList<Account>() else listOf(account.copy(type = null)), decoded.accounts)
        }
    }

    @Test fun currentVersionRoundTripsOptionalAssociationAndRejectsUnknownNewerVersions() {
        val assigned = legacyEvent.copy(id = "assigned", accountId = account.id)
        val archive = Archive(events = listOf(legacyEvent, assigned), accounts = listOf(account))
        val encoded = ArchiveCodec.encode(archive)

        assertEquals(14, JSONObject(encoded.toString(Charsets.UTF_8)).getInt("version"))
        assertEquals(archive, ArchiveCodec.decode(encoded))
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.decode(JSONObject(encoded.toString(Charsets.UTF_8)).put("version", 15).toString().toByteArray())
        }
    }

    @Test fun currentVersionRequiresAccountIdOnEveryEvent() {
        val root = JSONObject(ArchiveCodec.encode(Archive(events = listOf(legacyEvent))).toString(Charsets.UTF_8))
        val payload = JSONObject(java.util.Base64.getDecoder().decode(root.getString("payload")).toString(Charsets.UTF_8))
        payload.getJSONArray("events").getJSONObject(0).remove("accountId")
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        root.put("sha256", digest).put("payload", java.util.Base64.getEncoder().encodeToString(bytes))

        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.decode(root.toString().toByteArray()) }
    }

    @Test fun referencedAccountsMustExistAndMatchTheEventPillar() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(events = listOf(legacyEvent.copy(accountId = "missing"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(events = listOf(legacyEvent.copy(pillar = 3, accountId = account.id)), accounts = listOf(account)))
        }
    }

    @Test fun referencedAccountsCannotBeDeletedButUnusedAccountsCan() {
        val assignedArchive = Archive(events = listOf(legacyEvent.copy(accountId = account.id)), accounts = listOf(account))
        assertThrows(IllegalArgumentException::class.java) { assignedArchive.withoutAccount(account.id) }

        val removed = Archive(accounts = listOf(account)).withoutAccount(account.id)
        assertEquals(emptyList<Account>(), removed.accounts)
    }

    @Test fun changingAnEventAssociationIsKeptInCorrectionHistory() {
        val original = legacyEvent
        val data = Archive(events = listOf(original), accounts = listOf(account))
        val replacement = original.copy(accountId = account.id)
        val corrected = data.correctContribution(original, replacement, "2026-10-27T12:00:00Z")

        assertEquals(account.id, ArchiveCodec.decode(ArchiveCodec.encode(corrected)).events.single().accountId)
        assertEquals(original.toString(), corrected.audit.single().previous)
    }
}
