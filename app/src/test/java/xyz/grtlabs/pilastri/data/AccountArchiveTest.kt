package xyz.grtlabs.pilastri.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.LocalDate
import xyz.grtlabs.pilastri.domain.Confidence
import xyz.grtlabs.pilastri.domain.EventType
import xyz.grtlabs.pilastri.domain.LedgerEvent
import xyz.grtlabs.pilastri.domain.Money

class AccountArchiveTest {
    private fun legacyV1Archive(): ByteArray {
        val payload = JSONObject()
            .put("events", JSONArray().put(JSONObject()
                .put("id", "legacy-event").put("date", "2026-01-15").put("pillar", 1)
                .put("type", EventType.PERSONAL_CONTRIBUTION.name).put("cents", 12345)
                .put("currency", "EUR").put("confidence", Confidence.USER_REPORTED.name)
                .put("source", "legacy backup").put("transferPair", JSONObject.NULL)))
            .put("valuations", JSONArray()).put("indices", JSONArray()).put("audit", JSONArray()).put("plan", JSONArray())
            .toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) }
        return JSONObject().put("format", "pilastri-archive").put("version", 1)
            .put("sha256", digest).put("payload", java.util.Base64.getEncoder().encodeToString(payload))
            .toString().toByteArray(Charsets.UTF_8)
    }

    @Test fun legacyV1DecodesWithoutInventingAccountsAndKeepsItsEvent() {
        val archive = ArchiveCodec.decode(legacyV1Archive())

        assertTrue(archive.accounts.isEmpty())
        assertTrue(archive.performanceAttestations.isEmpty())
        assertEquals("legacy-event", archive.events.single().id)
        assertEquals(12345L, archive.events.single().amount.cents)
    }

    @Test fun accountRegistryRoundTripsInCurrentArchive() {
        val account = Account("account-1", "Fondo Espero", 4, "utente: dichiarazione 2026-10-27")
        val archive = Archive(accounts = listOf(account))
        val encoded = ArchiveCodec.encode(archive)

        assertEquals(14, JSONObject(encoded.toString(Charsets.UTF_8)).getInt("version"))
        assertEquals(archive, ArchiveCodec.decode(encoded))
    }

    @Test fun accountIdsMustBeUniqueAndBoundedAndSourcesMustBeBounded() {
        val account = Account("account-1", "Conto", 1, "fonte")
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(accounts = listOf(account, account.copy(name = "Altro"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(accounts = listOf(account.copy(id = "x".repeat(101)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(accounts = listOf(account.copy(source = "s".repeat(501)))))
        }
    }

    @Test fun futureLedgerDatesAndUnsupportedArchiveVersionsAreRejected() {
        val futureEvent = LedgerEvent("future", LocalDate.now().plusDays(1), 1, EventType.PERSONAL_CONTRIBUTION,
            Money(100), Confidence.USER_REPORTED, "utente")
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.encode(Archive(events = listOf(futureEvent))) }

        val unsupported = JSONObject(ArchiveCodec.encode(Archive()).toString(Charsets.UTF_8)).put("version", 15)
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.decode(unsupported.toString().toByteArray()) }
    }

    @Test fun currentVersionMustCarryAccountRegistryField() {
        val archive = ArchiveCodec.encode(Archive())
        val root = JSONObject(archive.toString(Charsets.UTF_8))
        val payload = JSONObject(java.util.Base64.getDecoder().decode(root.getString("payload")).toString(Charsets.UTF_8))
        payload.remove("accounts")
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        root.put("sha256", digest).put("payload", java.util.Base64.getEncoder().encodeToString(bytes))

        assertThrows(Exception::class.java) { ArchiveCodec.decode(root.toString().toByteArray()) }
    }
}
