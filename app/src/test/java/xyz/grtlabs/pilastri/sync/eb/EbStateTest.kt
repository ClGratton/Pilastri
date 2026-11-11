package xyz.grtlabs.pilastri.sync.eb

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.sync.auto.MemorySecretStore
import xyz.grtlabs.pilastri.sync.auto.SoftwareCipher

/** Synthetic ids only. */
class EbStateTest {
    private val raw = MemorySecretStore()
    private val store = EbStateStore(raw, SoftwareCipher())
    private val t = Instant.parse("2026-10-31T10:00:00Z")
    private val session = EbSessionState(
        "sess-synthetic-secret", "Isybank", "IT", t.plusSeconds(86_400), t, listOf("Psu-Ip-Address"),
        listOf(EbLinkedAccount("uid-a", "hash-a", "Conto A", "EUR"), EbLinkedAccount("uid-b", null, "Conto B", null)),
    )
    private val full = EbLocal(
        EbFx.APP_ID, EbFx.REDIRECT, listOf(session), EbPendingAuth("state-1", "Isybank", "IT", EbFx.REDIRECT, t.plusSeconds(10), t),
        mapOf("hash-a" to "app-1"), t.plusSeconds(21_600),
    )

    @Test fun everythingRoundTripsAndNothingIsStoredInClear() {
        store.save(full)
        val back = store.load()
        assertEquals(EbFx.APP_ID, back.applicationId)
        assertEquals(EbFx.REDIRECT, back.redirectUrl)
        assertEquals("sess-synthetic-secret", back.session!!.sessionId)
        assertEquals(session.accounts, back.session!!.accounts)
        assertEquals(listOf("Psu-Ip-Address"), back.session!!.requiredPsuHeaders)
        assertEquals(session.validUntil, back.session!!.validUntil)
        assertEquals("state-1", back.pending!!.state)
        assertEquals(mapOf("hash-a" to "app-1"), back.assignments)
        assertEquals(full.rateLimitedUntil, back.rateLimitedUntil)
        val blob = raw.get("eb.state.v1")!!
        assertFalse(blob.contains("synthetic") || blob.contains("hash-a") || blob.contains("pilastri"))
    }

    @Test fun anUndecryptableOrAbsentBlobReadsAsEmpty() {
        assertNull(store.load().session)
        store.save(full)
        val other = EbStateStore(raw, SoftwareCipher(ByteArray(32) { 9 }))
        assertEquals(EbLocal(), other.load())
    }

    @Test fun secretsNeverReachToString() {
        val text = listOf(full.toString(), session.toString(), full.pending.toString(), full.session!!.accounts.toString().let { "" })
        assertTrue(text.none { it.contains("secret") || it.contains("state-1") || it.contains(EbFx.APP_ID) })
    }

    @Test fun aNewSessionKeepsAssignmentsAndDropsThePendingAuthorization() {
        val next = full.withNewSession(session)
        assertNull(next.pending)
        assertNull(next.rateLimitedUntil)
        assertEquals(full.assignments, next.assignments)
        assertEquals("app-2", next.assign("hash-b", "app-2").assignments["hash-b"])
        assertEquals(emptyMap<String, String>(), full.assign("hash-a", null).assignments)
        assertEquals("hash-a", session.accounts[0].key)
        assertEquals("uid-b", session.accounts[1].key)
    }

    @Test fun forgettingTheLinkKeepsTheRegistrationAndClearEverything() {
        store.save(full)
        store.forgetLink()
        val kept = store.load()
        assertEquals(EbFx.APP_ID, kept.applicationId)
        assertNull(kept.session)
        assertNull(kept.pending)
        assertTrue(kept.assignments.isEmpty())
        store.clear()
        assertEquals(EbLocal(), store.load())
    }

    private fun other(bank: String, until: Instant?) = EbSessionState("sess-other", bank, "IT", until, t, emptyList(), emptyList())

    @Test fun severalBanksKeepOneSessionEachAndANewConsentReplacesItsOwnBank() {
        val two = full.withNewSession(other("Banca Beta", t.plusSeconds(100)))
        assertEquals(listOf("Isybank", "Banca Beta"), two.sessions.map { it.bankName })
        val renewed = two.withNewSession(other("isybank ", t.plusSeconds(999)))
        assertEquals(listOf("Banca Beta", "isybank "), renewed.sessions.map { it.bankName })
        assertEquals(t.plusSeconds(999), renewed.sessionFor("ISYBANK")!!.validUntil)
        assertEquals(listOf("Banca Beta"), renewed.withoutSession("Isybank").sessions.map { it.bankName })
    }

    @Test fun twoSessionsRoundTripAndTheEarlierSingleFormStillReads() {
        store.save(full.withNewSession(other("Banca Beta", null)))
        assertEquals(listOf("Isybank", "Banca Beta"), store.load().sessions.map { it.bankName })
        val legacy = EbStateStore.encode(full).let { org.json.JSONObject(it) }
        legacy.put("session", legacy.getJSONArray("sessions").getJSONObject(0)).remove("sessions")
        assertEquals(listOf("Isybank"), EbStateStore.decode(legacy.toString()).sessions.map { it.bankName })
    }

    @Test fun renewalIsOfferedOnlyForAConsentEndingWithinAWeekOrAlreadyEnded() {
        val s = full.copy(sessions = listOf(other("Soon", t.plusSeconds(3 * 86_400)), other("Late", t.plusSeconds(60 * 86_400)),
            other("Ended", t.minusSeconds(5)), other("Unknown", null)))
        assertEquals(listOf("Soon", "Ended"), s.needingRenewal(t).map { it.bankName })
    }
}
