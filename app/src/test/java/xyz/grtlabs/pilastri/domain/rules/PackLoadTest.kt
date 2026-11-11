package xyz.grtlabs.pilastri.domain.rules

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import xyz.grtlabs.pilastri.data.PackStatus
import java.io.File

/** The shipped LAW pack and its snapshot store, read from the module's assets. */
internal object Shipped {
    private fun asset(name: String): String =
        listOf(File("src/main/assets/rules/$name"), File("app/src/main/assets/rules/$name")).first { it.exists() }.readText()

    val packJson: String by lazy { asset("law-2026.json") }
    val snapshotJson: String by lazy { asset("snapshots-2026.json") }
    val pack: Pack by lazy { Pack.load(packJson, snapshotJson) }
    val rules: Rules by lazy { Rules(pack) }
}

class PackLoadTest {
    @Test fun shippedPackValidates() {
        val errors = Pack.parse(Shipped.packJson, Shipped.snapshotJson).validate()
        assertTrue("pack errors:\n" + errors.joinToString("\n"), errors.isEmpty())
        val counts = Shipped.pack.rows.groupingBy { it.status }.eachCount()
        println("law-2026 rows=${Shipped.pack.rows.size} rules=${Shipped.pack.rows.count { it.rule != null }} byStatus=$counts")
        assertTrue(Shipped.pack.rows.size > 150)
    }

    @Test fun everyVerifiedQuoteIsInItsHashedNonSummarySnapshot() {
        val pack = Shipped.pack
        for (row in pack.rows) {
            val snap = pack.snapshots[row.snapshotHash]
            assertNotNull(row.id, snap)
            assertEquals(row.id, SnapshotStore.sha256(snap!!.text), snap.hash)
            assertTrue(row.id, row.quote.length in 1..200 && snap.text.contains(row.quote))
            if (row.status == PackStatus.VERIFIED) {
                assertFalse(row.id, snap.summaryOnly)
                assertTrue(row.id, row.sourceUrl!!.startsWith("https://"))
            }
        }
    }

    @Test fun councilFourStatusRulingsHold() {
        val pack = Shipped.pack
        val five = setOf("cuneo.somma.rate", "addiz.regionale.brackets", "forf.acconto.threshold", "ord.acconto.irpef", "ord.acconto.isa")
        val fromFive = pack.rows.filter { it.research in five && it.rule == null }
        assertEquals(five, fromFive.map { it.research }.toSet())
        fromFive.forEach { assertEquals(it.id, PackStatus.UNVERIFIED, it.status) }
        assertEquals(PackStatus.INCERTO, pack.versions("inps.minimale.reddito.formula").single().status)
        for (gone in listOf("occ.autonomo.inps_rate", "gs.occasional.franchigia")) {
            assertTrue(pack.versions(gone).isEmpty())
            assertTrue(pack.retired.any { it.id == gone })
        }
        assertEquals("domicilio_regione", pack.versions("addiz.regionale.default_rate").single().personalSlot)
        assertEquals(PackStatus.INCERTO, pack.versions("pc.deduction.firstjob.unused").single().status)
        // Summary-only sources never confirm: S35/S36-only forfettario rows are not VERIFIED.
        assertEquals(PackStatus.UNVERIFIED, pack.versions("forf.tax.rate").single().status)
    }

    @Test fun tamperedQuoteOrSnapshotRefusesToLoad() {
        val root = JSONObject(Shipped.packJson)
        val rows = root.getJSONArray("rows")
        val i = (0 until rows.length()).first { rows.getJSONObject(it).getString("status") == "VERIFIED" }
        rows.getJSONObject(i).put("quote", "a quote that no snapshot contains")
        try {
            Pack.load(root.toString(), Shipped.snapshotJson); fail("expected PackInvalidException")
        } catch (e: PackInvalidException) {
            assertTrue(e.errors.joinToString(), e.errors.any { "verbatim substring" in it })
        }
        val snaps = JSONObject(Shipped.snapshotJson)
        val first = snaps.getJSONArray("snapshots").getJSONObject(0)
        first.put("text", first.getString("text") + " (edited)")
        val errors = Pack.parse(Shipped.packJson, snaps.toString()).validate()
        assertTrue(errors.any { "does not match its hash" in it })
    }
}
