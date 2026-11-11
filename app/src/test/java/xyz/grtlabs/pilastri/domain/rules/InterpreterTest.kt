package xyz.grtlabs.pilastri.domain.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.Origin
import xyz.grtlabs.pilastri.data.ParamClass
import xyz.grtlabs.pilastri.data.PositionKind
import java.math.BigDecimal
import java.time.LocalDate

/** Step kinds, guards, layers and status propagation on the shipped pack (public law values only). */
class InterpreterTest {
    private val engine = Interpreter(Shipped.pack)
    private val d2026 = LocalDate.of(2026, 12, 31)

    private fun cents(r: Res) = (r.value as RValue.Num).amount.longValueExact()
    private fun ratio(r: Res) = (r.value as RValue.Num).amount

    private fun detr(reddito: Long, days: Long) = engine.evaluate("detr.dipendente.piecewise",
        EvalContext(d2026, mapOf("reddito" to RuleInput.cents(reddito), "days" to RuleInput.exact(RValue.count(days))))).output

    @Test fun piecewiseOnCarriedRowIsStima() {
        // 1910 + 1190 * trunc4(8000/13000 = 0.6153) = 2642.207 -> 2642.21
        detr(2_000_000, 365).let { assertEquals(RuleStatus.STIMA, it.status); assertEquals(264_221L, cents(it)) }
        // 1910 * trunc4(10000/22000 = 0.4545) = 868.095 -> 868.10
        assertEquals(86_810L, cents(detr(4_000_000, 365)))
        assertEquals(195_500L, cents(detr(1_000_000, 365)))
        assertEquals(0L, cents(detr(6_000_000, 365)))
        // 2642.21 * 182/365 = 1317.4855... -> 1317.49
        assertEquals(131_749L, cents(detr(2_000_000, 182)))
    }

    @Test fun guardElseNdKeepsMissingFloorsHonest() {
        val r = detr(1_000_000, 180)
        assertEquals(RuleStatus.ND, r.status)
        assertNull(r.value)
    }

    @Test fun missingInputIsNdNeverZero() {
        val r = engine.evaluate("irpef.gross", EvalContext(d2026)).output
        assertEquals(RuleStatus.ND, r.status)
        assertTrue(r.reason!!.contains("taxable"))
        val stima = engine.evaluate("irpef.gross", EvalContext(d2026, mapOf("taxable" to RuleInput.cents(5_000_000, RuleStatus.STIMA)))).output
        assertEquals(RuleStatus.STIMA, stima.status)
        assertEquals(1_370_000L, cents(stima))
    }

    @Test fun lookupTableByKeyAndDate() {
        fun coef(age: Long, start: String) = engine.evaluate("pension.coef", EvalContext(d2026,
            mapOf("age" to RuleInput.exact(RValue.count(age)), "decorrenza" to RuleInput.exact(RValue.date(LocalDate.parse(start)))))).output
        coef(67, "2027-03-01").let { assertEquals(RuleStatus.EXACT, it.status); assertEquals(0, BigDecimal("0.05514").compareTo(ratio(it))) }
        coef(67, "2026-05-01").let { assertEquals(RuleStatus.STIMA, it.status); assertEquals(0, BigDecimal("0.05608").compareTo(ratio(it))) }
        assertEquals(RuleStatus.ND, coef(60, "2026-05-01").status)
        assertEquals(RuleStatus.ND, coef(67, "2029-01-01").status)
    }

    @Test fun lookupFamilyAndRequiresIncerto() {
        fun coeff(gruppo: String, date: String) = engine.evaluate("forf.coeff",
            EvalContext(LocalDate.parse(date), mapOf("gruppo" to RuleInput.exact(RValue.text(gruppo))))).output
        coeff("professionali_sanitarie_istruzione_finanza", "2025-06-30").let {
            assertEquals(RuleStatus.STIMA, it.status); assertEquals(0, BigDecimal("0.78").compareTo(ratio(it)))
        }
        coeff("professionali_sanitarie_istruzione_finanza", "2026-06-30").let {
            assertEquals(RuleStatus.ND, it.status); assertTrue(it.reason!!.contains("forf.coeff.table_valid_2026"))
        }
        assertEquals(RuleStatus.ND, coeff("sconosciuto", "2025-06-30").status)
    }

    private fun declared(v: String) = LayerValue(RValue.text(v), ParamClass.PERSONAL_DECLARED, Origin.DICHIARATO)

    private fun regional(personal: Map<String, LayerValue> = emptyMap(), scenario: Map<String, LayerValue> = emptyMap(), mode: Mode = Mode.ACTUALS,
                         irpef: Long = 500_000, kinds: Set<PositionKind>? = null) =
        engine.evaluate("addiz.regionale", EvalContext(d2026, mapOf("base" to RuleInput.cents(3_000_000), "irpef_netta" to RuleInput.cents(irpef)),
            personal, scenario, mode, kinds)).output

    @Test fun personalSlotSelectsScheduleAndDomicileIsRequired() {
        // Lombardia (region 10): 15000*1.23% + 13000*1.58% + 2000*1.72% = 184.50 + 205.40 + 34.40
        regional(mapOf("domicilio_regione" to declared("10"))).let {
            assertEquals(42_430L, cents(it)); assertEquals(RuleStatus.STIMA, it.status)
            assertTrue(it.sources.contains("slot:domicilio_regione"))
        }
        regional().let { assertEquals(RuleStatus.ND, it.status); assertTrue(it.reason!!.contains("domicilio_regione")) }
        assertEquals(RuleStatus.ND, regional(mapOf("domicilio_regione" to declared("05"))).status)
        // Imposta at or below 10.33: not due, whatever the domicile.
        regional(irpef = 1_000).let { assertEquals(RuleStatus.EXACT, it.status); assertEquals(0L, cents(it)) }
    }

    @Test fun scenarioLayerOnlyInFuturo() {
        val scenario = mapOf("domicilio_regione" to LayerValue(RValue.text("10"), ParamClass.SCENARIO, Origin.DICHIARATO))
        assertEquals(RuleStatus.ND, regional(scenario = scenario).status)
        regional(scenario = scenario, mode = Mode.FUTURO).let { assertEquals(RuleStatus.STIMA, it.status); assertEquals(42_430L, cents(it)) }
    }

    @Test fun personalValuesNeverOverrideLawRows() {
        assertThrows(IllegalArgumentException::class.java) { regional(mapOf("irpef.bracket.2.rate" to declared("0"))) }
        assertThrows(IllegalArgumentException::class.java) { regional(mapOf("non_dichiarato" to declared("x"))) }
        assertThrows(IllegalArgumentException::class.java) {
            EvalContext(d2026, personal = mapOf("domicilio_regione" to LayerValue(RValue.text("10"), ParamClass.SCENARIO, Origin.DICHIARATO)))
        }
    }

    @Test fun undefinedCombinationIsNd() {
        val r = regional(mapOf("domicilio_regione" to declared("10")), kinds = setOf(PositionKind.DIPENDENTE_PRIVATO, PositionKind.AUTONOMO_FORFETTARIO))
        assertEquals(RuleStatus.ND, r.status)
        assertTrue(r.reason!!.contains("combinazione"))
    }

    @Test fun ruleOutsideItsValidityIsNd() {
        val r = engine.evaluate("irpef.gross", EvalContext(LocalDate.of(2024, 12, 31), mapOf("taxable" to RuleInput.cents(100)))).output
        assertEquals(RuleStatus.ND, r.status)
    }
}
