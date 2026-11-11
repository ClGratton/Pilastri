package xyz.grtlabs.pilastri.data

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paid social-security contributions per year (DESIGN 9-05). Every value below is invented for the test ("sintetico"). */
class PaidContributionTest {
    private val today = LocalDate.of(2026, 11, 1)
    private val nd = """{"value":null,"origin":"ND","docId":null,"fieldLabel":null,"asOf":null}"""
    private val base = Archive(positions = listOf(
        IncomePosition("pos-1", coded(PositionKind.AUTONOMO_ORDINARIO)),
        IncomePosition("pos-2", coded(PositionKind.DIPENDENTE_PRIVATO)),
    ))

    private fun payload(a: Archive): JSONObject = V13.payloadOf(ArchiveCodec.encode(a))
    private fun roundTrip(a: Archive): Archive = ArchiveCodec.decode(ArchiveCodec.encode(a))
    private fun Archive.position(id: String) = positions.single { it.id == id }

    /** JSON as plain values so key order never matters. */
    private fun plain(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { plain(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { plain(v.get(it)) }
        is Number -> java.math.BigDecimal(v.toString()).stripTrailingZeros()
        JSONObject.NULL -> null
        else -> v
    }

    private val legacyPosition = """{"id":"pos-old","kind":"AUTONOMO_ORDINARIO","validFrom":$nd,"validTo":$nd,"label":$nd,"gestione":$nd,
        "severanceRegime":$nd,"severanceDestination":$nd,"contractTerm":$nd,"hireDate":$nd,"ateco":$nd,"regimeStart":$nd,"incomeSource":$nd}"""

    @Test fun archiveWithoutTheKeyDecodesAndReEncodesByteIdentical() {
        // New code never writes the key for a position without paid contributions.
        val bytes = ArchiveCodec.encode(base)
        val written = payload(base).getJSONArray("positions")
        assertEquals(2, written.length())
        for (i in 0 until written.length()) assertFalse(written.getJSONObject(i).has("paidContributions"))
        val decoded = ArchiveCodec.decode(bytes)
        assertTrue(decoded.positions.all { it.paidContributions.isEmpty() })
        assertArrayEquals(bytes, ArchiveCodec.encode(decoded))

        // An old file: a hand-written position with the 13 pre-existing keys and no "paidContributions".
        val old = payload(base).put("positions", JSONArray().put(JSONObject(legacyPosition)))
        val version = JSONObject(String(bytes, Charsets.UTF_8)).getInt("version")
        val oldBytes = V13.seal(version, old)
        val oldDecoded = ArchiveCodec.decode(oldBytes)
        assertTrue(oldDecoded.position("pos-old").paidContributions.isEmpty())
        val again = payload(oldDecoded)
        assertEquals(plain(old.getJSONArray("positions")), plain(again.getJSONArray("positions")))
        assertFalse(again.getJSONArray("positions").getJSONObject(0).has("paidContributions"))
        assertArrayEquals(ArchiveCodec.encode(oldDecoded), ArchiveCodec.encode(ArchiveCodec.decode(ArchiveCodec.encode(oldDecoded))))
    }

    @Test fun twoYearsRoundTripAndOnlyThePositionThatHasThemWritesTheKey() {
        val doc = Sourced.document(412_300L, "doc-1", "Contributi versati", LocalDate.of(2026, 3, 31))
        val a = base.withPaidContribution("pos-1", 2025, doc).withDeclaredPaidContribution("pos-1", 2024, 398_000L, today)
        val back = roundTrip(a)
        assertEquals(a, back)
        assertEquals(listOf(2024, 2025), back.position("pos-1").paidContributions.map { it.year })
        assertEquals(Sourced.declared(398_000L, today), back.position("pos-1").paidContributions[0].amount)
        assertEquals(doc, back.position("pos-1").paidContributions[1].amount)
        assertTrue(back.position("pos-2").paidContributions.isEmpty())
        val written = payload(a).getJSONArray("positions")
        val keys = (0 until written.length()).associate { i -> written.getJSONObject(i).getString("id") to written.getJSONObject(i).has("paidContributions") }
        assertEquals(mapOf("pos-1" to true, "pos-2" to false), keys)
        assertArrayEquals(ArchiveCodec.encode(a), ArchiveCodec.encode(back))
    }

    @Test fun declaredFigureIsAddedReplacedAndRemoved() {
        val added = base.withDeclaredPaidContribution("pos-1", 2025, 100_000L, today)
        assertEquals(Sourced.declared(100_000L, today), added.position("pos-1").paidContributions.single().amount)
        assertEquals(Origin.DICHIARATO, added.position("pos-1").paidContributions.single().amount.origin)

        val replaced = added.withDeclaredPaidContribution("pos-1", 2025, 125_050L, today.plusDays(1))
        assertEquals(1, replaced.position("pos-1").paidContributions.size)
        assertEquals(Sourced.declared(125_050L, today.plusDays(1)), replaced.position("pos-1").paidContributions.single().amount)

        val two = replaced.withDeclaredPaidContribution("pos-1", 2023, 0L, today)
        assertEquals(listOf(2023, 2025), two.position("pos-1").paidContributions.map { it.year })
        assertEquals(0L, two.position("pos-1").paidContributions[0].amount.value)

        val removed = two.withDeclaredPaidContribution("pos-1", 2025, null, today)
        assertEquals(listOf(2023), removed.position("pos-1").paidContributions.map { it.year })
        assertTrue(removed.withPaidContribution("pos-1", 2023, null).position("pos-1").paidContributions.isEmpty())
        assertEquals(base, removed.withPaidContribution("pos-1", 2023, null))
        // Removing a year that was never there changes nothing.
        assertEquals(base, base.withDeclaredPaidContribution("pos-1", 2020, null, today))
        // The other position is untouched throughout.
        assertTrue(two.position("pos-2").paidContributions.isEmpty())
    }

    @Test fun invalidYearNegativeOrUnknownAmountAndBadPositionAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { base.withDeclaredPaidContribution("pos-1", 1999, 100L, today) }
        assertThrows(IllegalArgumentException::class.java) { base.withDeclaredPaidContribution("pos-1", 2101, 100L, today) }
        assertThrows(IllegalArgumentException::class.java) { base.withDeclaredPaidContribution("pos-1", 2025, -1L, today) }
        assertThrows(IllegalArgumentException::class.java) { base.withPaidContribution("pos-1", 2025, Sourced.nd()) }
        assertThrows(IllegalArgumentException::class.java) { base.withDeclaredPaidContribution("pos-missing", 2025, 100L, today) }
        assertThrows(IllegalArgumentException::class.java) { PaidContribution(1999, Sourced.declared(1L)) }
        assertThrows(IllegalArgumentException::class.java) { PaidContribution(2025, Sourced.declared(-5L)) }
        assertThrows(IllegalArgumentException::class.java) { PaidContribution(2025, Sourced.nd()) }
        val one = PaidContribution(2025, Sourced.declared(1L))
        assertThrows(IllegalArgumentException::class.java) {
            IncomePosition("p", coded(PositionKind.AUTONOMO_ORDINARIO), paidContributions = listOf(one, one))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IncomePosition("p", coded(PositionKind.AUTONOMO_ORDINARIO), paidContributions = (2000..2060).map { PaidContribution(it, Sourced.declared(1L)) })
        }
        assertEquals(60, IncomePosition("p", coded(PositionKind.AUTONOMO_ORDINARIO),
            paidContributions = (2000..2059).map { PaidContribution(it, Sourced.declared(1L)) }).paidContributions.size)
        // A rejected edit leaves the original archive as it was; a stored file with an unknown amount is refused on load.
        val stored = payload(base.withDeclaredPaidContribution("pos-1", 2025, 100L, today))
        stored.getJSONArray("positions").getJSONObject(0).getJSONArray("paidContributions").getJSONObject(0).put("amount", JSONObject(nd))
        val version = JSONObject(String(ArchiveCodec.encode(base), Charsets.UTF_8)).getInt("version")
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.decode(V13.seal(version, stored)) }
    }

    @Test fun latestPaidIsTheHighestYearAndNullWhenNone() {
        assertNull(base.position("pos-1").latestPaid())
        val p = IncomePosition("p", coded(PositionKind.AUTONOMO_ORDINARIO), paidContributions = listOf(
            PaidContribution(2024, Sourced.declared(200L, today)),
            PaidContribution(2026, Sourced.declared(300L, today)),
            PaidContribution(2025, Sourced.declared(100L, today)),
        ))
        assertEquals(2026, p.latestPaid()?.year)
        assertEquals(300L, p.latestPaid()?.amount?.value)
        assertEquals(2025, base.withDeclaredPaidContribution("pos-1", 2025, 5L, today).position("pos-1").latestPaid()?.year)
    }

    @Test fun originLabelNamesWhoProvidedTheFigure() {
        assertEquals("inserito da te", PaidContribution(2025, Sourced.declared(1L, today)).originLabel())
        assertEquals("Contributi versati", PaidContribution(2025, Sourced.document(1L, "doc-1", "Contributi versati", today)).originLabel())
        assertEquals("documento", PaidContribution(2025, Sourced(1L, Origin.DOCUMENTO, "doc-1", null, today)).originLabel())
        assertEquals("calcolato", PaidContribution(2025, Sourced.calculated(1L, today)).originLabel())
    }
}
