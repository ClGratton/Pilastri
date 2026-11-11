package xyz.grtlabs.pilastri.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import xyz.grtlabs.pilastri.domain.*

class PerformanceAttestationArchiveTest {
    private val attestation = PerformanceAttestation(
        id = "review-1", pillar = 4, start = LocalDate.of(2025, 1, 1), end = LocalDate.of(2025, 12, 31),
        source = "synthetic annual statement", reviewedAt = "2026-01-10T12:00:00Z", inputFingerprint = "a".repeat(64),
        externalFlowsComplete = true, valuationRolesConfirmed = true,
    )

    private fun parts(bytes: ByteArray): Pair<JSONObject, JSONObject> {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        return root to JSONObject(String(java.util.Base64.getDecoder().decode(root.getString("payload")), Charsets.UTF_8))
    }

    private fun seal(root: JSONObject, payload: JSONObject): ByteArray {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return root.put("sha256", digest).put("payload", java.util.Base64.getEncoder().encodeToString(bytes)).toString().toByteArray()
    }

    @Test fun v11RoundTripsAttestationAndStaleFingerprintIsPreserved() {
        val archive = Archive(performanceAttestations = listOf(attestation))
        val (root, payload) = parts(ArchiveCodec.encode(archive))
        assertEquals(14, root.getInt("version"))
        assertEquals(archive, ArchiveCodec.decode(ArchiveCodec.encode(archive)))
        assertEquals("a".repeat(64), payload.getJSONArray("performanceAttestations").getJSONObject(0).getString("inputFingerprint"))
        val stale = archive.copy(valuations = listOf(Valuation(LocalDate.of(2026, 1, 1), 4, Money(100), "synthetic", Confidence.USER_REPORTED)))
        assertEquals(listOf(attestation), ArchiveCodec.decode(ArchiveCodec.encode(stale)).performanceAttestations)
    }

    @Test fun legacyV10HasNoAttestationsAndV11RequiresItsArray() {
        val (currentRoot, currentPayload) = parts(ArchiveCodec.encode(Archive()))
        currentPayload.remove("performanceAttestations"); currentPayload.remove("externalMovements"); currentPayload.remove("connections"); currentPayload.remove("planning"); currentPayload.remove("pension"); currentPayload.remove("holdings"); currentPayload.remove("installments"); currentPayload.remove("installmentSync"); V13.downgrade(currentPayload)
        val v10 = ArchiveCodec.decode(seal(currentRoot.put("version", 10), currentPayload))
        assertTrue(v10.performanceAttestations.isEmpty())
        val legacyWithNewField = JSONObject(currentPayload.toString()).put("performanceAttestations", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.decode(seal(JSONObject(currentRoot.toString()).put("version", 10), legacyWithNewField)) }
        val missing = JSONObject(String(java.util.Base64.getDecoder().decode(currentRoot.getString("payload")), Charsets.UTF_8))
        missing.remove("performanceAttestations"); missing.remove("externalMovements"); missing.remove("connections"); missing.remove("planning"); missing.remove("pension"); missing.remove("holdings"); missing.remove("installments"); missing.remove("installmentSync"); V13.downgrade(missing)
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.decode(seal(JSONObject(currentRoot.toString()).put("version", 11), missing)) }
    }

    @Test fun malformedAndIncompleteReviewsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { attestation.copy(inputFingerprint = "A".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { attestation.copy(externalFlowsComplete = false) }
        assertThrows(IllegalArgumentException::class.java) { attestation.copy(valuationRolesConfirmed = false) }
        assertThrows(IllegalArgumentException::class.java) { attestation.copy(start = attestation.end) }
        assertThrows(IllegalArgumentException::class.java) { attestation.copy(end = LocalDate.now().plusDays(1)) }
        assertThrows(IllegalArgumentException::class.java) { attestation.copy(pillar = 5) }
        assertThrows(IllegalArgumentException::class.java) { attestation.copy(source = "s".repeat(501)) }
        assertThrows(IllegalArgumentException::class.java) { attestation.copy(reviewedAt = "not-an-instant") }
    }

    @Test fun uniqueReviewIdsAndWindowsAreValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(performanceAttestations = listOf(attestation, attestation.copy(start = attestation.start.minusDays(1), end = attestation.end.minusDays(1)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(performanceAttestations = listOf(attestation, attestation.copy(id = "review-2"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(performanceAttestations = List(PerformanceAttestation.MAX_COUNT + 1) { i ->
                val start = LocalDate.of(2000, 1, 1).plusDays(i.toLong())
                attestation.copy(id = "review-$i", start = start, end = start.plusDays(1))
            }))
        }
    }

    @Test fun correctionAndRemovalKeepAuditHistoryAndStableId() {
        val archive = Archive(performanceAttestations = listOf(attestation))
        val replacement = attestation.copy(source = "corrected synthetic source")
        val corrected = archive.correctPerformanceAttestation(attestation, replacement, "2026-01-11T00:00:00Z")
        assertEquals("review-1", corrected.performanceAttestations.single().id)
        assertEquals(attestation.toString(), corrected.audit.single().previous)
        assertSame(archive, archive.correctPerformanceAttestation(attestation, attestation, "2026-01-11T00:00:00Z"))
        assertThrows(IllegalArgumentException::class.java) { archive.correctPerformanceAttestation(attestation, replacement.copy(id = "other"), "2026-01-11T00:00:00Z") }
        val removed = corrected.withoutPerformanceAttestation("review-1")
        assertTrue(removed.performanceAttestations.isEmpty())
        assertEquals(replacement.toString(), removed.audit.last().previous)
        assertThrows(IllegalArgumentException::class.java) { removed.withoutPerformanceAttestation("missing") }
    }
}
