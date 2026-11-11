package xyz.grtlabs.pilastri.data

import java.math.BigDecimal
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.domain.ProjectionPriceReference
import xyz.grtlabs.pilastri.domain.ProjectionAssumptions

class SavedProjectionArchiveTest {
    private val projection = SavedProjection(
        id = "scenario-1",
        name = "Synthetic base case",
        assumptions = ProjectionAssumptions(2026, 2030, 123_456, 12_345, 6_789, BigDecimal("4.25"), BigDecimal("2.0")),
        source = "user-entered assumptions",
        savedAt = "2026-10-28T12:34:56Z",
    )

    private fun seal(root: JSONObject, payload: JSONObject): ByteArray {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return root.put("sha256", hash).put("payload", java.util.Base64.getEncoder().encodeToString(bytes))
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun parts(archive: Archive = Archive(projections = listOf(projection))): Pair<JSONObject, JSONObject> {
        val root = JSONObject(ArchiveCodec.encode(archive).toString(Charsets.UTF_8))
        val payload = JSONObject(String(java.util.Base64.getDecoder().decode(root.getString("payload")), Charsets.UTF_8))
        return root to payload
    }

    @Test fun v11RoundTripKeepsUserScenarioAndExactDecimalStrings() {
        val archive = Archive(projections = listOf(projection))
        val encoded = ArchiveCodec.encode(archive)
        val root = JSONObject(encoded.toString(Charsets.UTF_8))
        assertEquals(14, root.getInt("version"))
        val payload = JSONObject(String(java.util.Base64.getDecoder().decode(root.getString("payload")), Charsets.UTF_8))
        val assumptions = payload.getJSONArray("projections").getJSONObject(0).getJSONObject("assumptions")
        assertEquals("123456", assumptions.getString("initialWealthCents"))
        assertEquals("12345", assumptions.getString("annualPersonalAdditionCents"))
        assertEquals("4.25", assumptions.getString("annualNominalGrowthPercent"))
        assertEquals("START_2026", assumptions.getString("priceReference"))
        assertEquals(archive, ArchiveCodec.decode(encoded))
    }

    @Test fun v10ScenarioKeepsItsLegacyPriceReference() {
        val (root, payload) = parts()
        payload.remove("performanceAttestations"); payload.remove("externalMovements"); payload.remove("connections"); payload.remove("planning"); payload.remove("pension"); payload.remove("holdings"); payload.remove("installments"); payload.remove("installmentSync"); V13.downgrade(payload)
        payload.getJSONArray("projections").getJSONObject(0).getJSONObject("assumptions").remove("priceReference")
        val decoded = ArchiveCodec.decode(seal(root.put("version", 10), payload))
        assertEquals(ProjectionPriceReference.END_2026_LEGACY, decoded.projections.single().assumptions.priceReference)

        val (currentRoot, currentPayload) = parts()
        currentPayload.getJSONArray("projections").getJSONObject(0).getJSONObject("assumptions").remove("priceReference")
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.decode(seal(currentRoot, currentPayload)) }
    }

    @Test fun v9DecodesWithoutScenariosAndRejectsTheNewField() {
        val (root, payload) = parts(Archive())
        payload.remove("projections")
        payload.remove("performanceAttestations"); payload.remove("externalMovements"); payload.remove("connections"); payload.remove("planning"); payload.remove("pension"); payload.remove("holdings"); payload.remove("installments"); payload.remove("installmentSync"); V13.downgrade(payload)
        val v9 = seal(root.put("version", 9), payload)
        assertEquals(Archive(), ArchiveCodec.decode(v9))

        val (currentRoot, currentPayload) = parts(Archive())
        val invalidOldVersion = seal(currentRoot.put("version", 9), currentPayload)
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.decode(invalidOldVersion) }
    }

    @Test fun currentVersionRequiresProjectionArrayAndExactBoundedValues() {
        val (root, payload) = parts()
        payload.remove("projections")
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.decode(seal(root, payload)) }

        val invalidShapes = listOf<(JSONObject) -> Unit>(
            { it.put("initialWealthCents", "1.00") },
            { it.put("annualPersonalAdditionCents", 100) },
            { it.put("annualEmployerAdditionCents", "9223372036854775808") },
            { it.put("annualNominalGrowthPercent", "1e2") },
            { it.put("annualInflationPercent", "1.1234567") },
            { it.put("annualInflationPercent", "-100") },
            { it.put("annualNominalGrowthPercent", "1000.000001") },
        )
        invalidShapes.forEach { mutate ->
            val (invalidRoot, invalidPayload) = parts()
            mutate(invalidPayload.getJSONArray("projections").getJSONObject(0).getJSONObject("assumptions"))
            assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.decode(seal(invalidRoot, invalidPayload)) }
        }
    }

    @Test fun duplicateIdsAndExcessSavedScenariosAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(projections = listOf(projection, projection.copy(name = "duplicate"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.encode(Archive(projections = List(SavedProjection.MAX_COUNT + 1) { index -> projection.copy(id = "scenario-$index") }))
        }
        assertThrows(IllegalArgumentException::class.java) { Archive().withProjection(projection).withProjection(projection) }
    }

    @Test fun correctionsKeepStableIdAuditOldScenarioAndReturnSameForNoOp() {
        val archive = Archive(projections = listOf(projection))
        val replacement = projection.copy(name = "Synthetic revised case", assumptions = projection.assumptions.copy(annualNominalGrowthPercent = BigDecimal("3.5")))
        val corrected = archive.correctProjection(projection, replacement, "2026-10-28T13:00:00Z")
        assertEquals(replacement, corrected.projections.single())
        assertTrue(corrected.audit.single().previous.contains("Synthetic base case"))
        assertEquals(corrected, ArchiveCodec.decode(ArchiveCodec.encode(corrected)))
        assertSame(archive, archive.correctProjection(projection, projection, "unused"))
        assertThrows(IllegalArgumentException::class.java) {
            archive.correctProjection(projection, replacement.copy(id = "other"), "2026-10-28T13:00:00Z")
        }
        assertThrows(IllegalArgumentException::class.java) {
            archive.correctProjection(projection.copy(name = "missing"), replacement, "2026-10-28T13:00:00Z")
        }
    }

    @Test fun scenarioRemovalIsAuditedAndThenCanBeRemovedFromArchive() {
        val archive = Archive(projections = listOf(projection))
        val removed = archive.withoutProjection(projection.id)
        assertTrue(removed.projections.isEmpty())
        assertTrue(removed.audit.single().previous.contains(projection.id))
        assertEquals(removed, ArchiveCodec.decode(ArchiveCodec.encode(removed)))
        assertThrows(IllegalArgumentException::class.java) { archive.withoutProjection("missing") }
    }

    @Test fun savedProjectionMetadataIsBoundedAndTimestampMustBeIsoInstant() {
        assertThrows(IllegalArgumentException::class.java) { projection.copy(id = " ") }
        assertThrows(IllegalArgumentException::class.java) { projection.copy(name = " ") }
        assertThrows(IllegalArgumentException::class.java) { projection.copy(source = " ") }
        assertThrows(Exception::class.java) { projection.copy(savedAt = "not a timestamp") }
        assertThrows(IllegalArgumentException::class.java) { projection.copy(id = "x".repeat(SavedProjection.MAX_ID_LENGTH + 1)) }
        assertThrows(IllegalArgumentException::class.java) { projection.copy(name = "x".repeat(SavedProjection.MAX_NAME_LENGTH + 1)) }
        assertThrows(IllegalArgumentException::class.java) { projection.copy(source = "x".repeat(SavedProjection.MAX_SOURCE_LENGTH + 1)) }
    }
}
