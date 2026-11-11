package xyz.grtlabs.pilastri.domain.rules

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Load rules on a synthetic mini pack (public placeholder values only). */
class ValidatorTest {
    private val url = "https://example.test/law"

    private inner class Mini {
        val rows = JSONArray()
        val snaps = JSONArray()
        val retired = JSONArray()

        fun snap(text: String, summaryOnly: Boolean = false): String {
            val h = SnapshotStore.sha256(text)
            snaps.put(JSONObject().put("hash", h).put("url", url).put("recordedOn", "2026-10-31").put("kind", "TEST")
                .put("summaryOnly", summaryOnly).put("text", text))
            return h
        }

        fun row(id: String, unit: String = "EUR", value: Any? = "100", status: String = "VERIFIED", quote: String = "rate is 100",
                text: String = "the rate is 100 per year", summaryOnly: Boolean = false): JSONObject {
            val o = JSONObject().put("id", id).put("unit", unit).put("value", value ?: JSONObject.NULL).put("status", status)
                .put("quote", quote).put("snapshotHash", snap(text, summaryOnly)).put("sourceUrl", url)
                .put("sources", JSONArray().put("S1")).put("checkedOn", "2026-10-31")
            rows.put(o)
            return o
        }

        fun rule(id: String, steps: String, output: String = "x", inputs: String = """{"a":"EUR"}""", slots: String = "{}"): JSONObject =
            row(id, value = null).put("steps", JSONArray(steps)).put("output", output).put("inputs", JSONObject(inputs)).put("personalSlots", JSONObject(slots))

        fun errors(): List<String> {
            val pack = JSONObject().put("packId", "mini").put("checkedOn", "2026-10-31")
                .put("sources", JSONObject().put("S1", JSONObject().put("url", url).put("read", "PRIMARY")))
                .put("rows", rows).put("retired", retired)
            return Pack.parse(pack.toString(), JSONObject().put("snapshots", snaps).toString()).validate()
        }
    }

    private fun assertError(errors: List<String>, fragment: String) =
        assertTrue("expected '$fragment' in $errors", errors.any { fragment in it })

    @Test fun validMiniPackHasNoErrors() {
        val m = Mini()
        m.row("p.rate", unit = "RATIO", value = "0.5")
        m.rule("r", """[{"out":"x","kind":"RATE","rounding":"HALF_UP_CENT","input":"in:a","rate":"par:p.rate"}]""")
        assertTrue(m.errors().toString(), m.errors().isEmpty())
    }

    @Test fun quoteMustBeVerbatimInTheSnapshot() {
        val m = Mini(); m.row("p", quote = "rate is 101")
        assertError(m.errors(), "verbatim substring")
    }

    @Test fun summaryOnlySnapshotIsNeverVerified() {
        val m = Mini(); m.row("p", summaryOnly = true)
        assertError(m.errors(), "summary-only")
        val ok = Mini(); ok.row("p", status = "UNVERIFIED", summaryOnly = true)
        assertTrue(ok.errors().isEmpty())
    }

    @Test fun quoteLongerThan200IsRejected() {
        val long = "x".repeat(201)
        val m = Mini(); m.row("p", quote = long, text = long)
        assertError(m.errors(), "longer than 200")
    }

    @Test fun forwardReferenceIsRejected() {
        val m = Mini()
        m.rule("r", """[{"out":"y","kind":"SUM","terms":["val:x"]},{"out":"x","kind":"SUM","terms":["in:a"]}]""")
        assertError(m.errors(), "not an earlier step")
    }

    @Test fun unitsAreChecked() {
        val m = Mini()
        m.rule("r1", """[{"out":"x","kind":"RATE","rounding":"HALF_UP_CENT","input":"in:a","rate":"eur:5"}]""")
        m.rule("r2", """[{"out":"x","kind":"BRACKETS","rounding":"HALF_UP_CENT","input":"in:a","bands":[{"upTo":null,"rate":"eur:1"}]}]""")
        m.rule("r3", """[{"out":"x","kind":"SUM","terms":["in:a","ratio:0.2"]}]""")
        m.rule("r4", """[{"out":"x","kind":"RATE","rounding":"NONE","input":"in:a","rate":"ratio:0.5"}]""", output = "x", inputs = """{"a":"RATIO"}""")
        val e = m.errors()
        assertError(e, "r1: step x rate: EUR is not RATIO/COUNT")
        assertError(e, "r2: step x band 0 rate: unit EUR, expected RATIO")
        assertError(e, "r3: step x: terms mix units")
        assertError(e, "r4: output unit RATIO differs from row unit EUR")
    }

    @Test fun moneyStepsMustRoundToCents() {
        val m = Mini()
        m.rule("r", """[{"out":"x","kind":"RATE","rounding":"NONE","input":"in:a","rate":"ratio:0.5"}]""")
        assertError(m.errors(), "rounding NONE not allowed for EUR RATE")
    }

    @Test fun slotsMustBeDeclaredAndNeverShadowLawRows() {
        val m = Mini()
        m.row("p")
        m.rule("r1", """[{"out":"x","kind":"SUM","terms":["slot:dom"]}]""")
        m.rule("r2", """[{"out":"x","kind":"SUM","terms":["slot:p"]}]""", slots = """{"p":"EUR"}""")
        val e = m.errors()
        assertError(e, "undeclared personalSlot 'dom'")
        assertError(e, "p: personalSlot shares a name with a law row")
    }

    @Test fun guardedStepNeedsElse() {
        val m = Mini()
        m.rule("r", """[{"out":"x","kind":"SUM","terms":["in:a"],"when":[{"op":"GT","a":"in:a","b":"eur:1"}]}]""")
        assertError(m.errors(), "guarded step needs 'else'")
    }

    @Test fun retiredNoteAndMissingRowsCannotBeReferenced() {
        val m = Mini()
        m.retired.put(JSONObject().put("id", "old").put("reason", "test"))
        m.row("n", unit = "NOTE", value = null)
        m.rule("r", """[{"out":"a1","kind":"SUM","terms":["par:old"]},{"out":"a2","kind":"SUM","terms":["par:n"]},{"out":"x","kind":"SUM","terms":["par:none"]}]""")
        val e = m.errors()
        assertError(e, "references retired row 'old'")
        assertError(e, "'n' is a NOTE row")
        assertError(e, "unknown row 'none'")
    }

    @Test fun overlappingVersionsAndMissingValuesAreRejected() {
        val m = Mini()
        m.row("p").put("validFrom", "2025-01-01")
        m.row("p", value = "200").put("validFrom", "2025-06-01")
        m.row("q", value = null, status = "UNVERIFIED")
        m.row("ok", value = null, status = "INCERTO")
        val e = m.errors()
        assertError(e, "p: overlapping versions")
        assertError(e, "q: missing value")
        assertTrue(e.none { it.startsWith("ok:") })
    }
}
