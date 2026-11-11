package xyz.grtlabs.pilastri.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Synthetic records only. */
class RulesRecordsTest {
    private val snapText = "Testo sintetico salvato con una regola."
    private val hash = RulesCodec.sha256(snapText)

    /** Sorted keys, null members dropped: absent and null mean the same to the codec. */
    private fun canon(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().filter { !v.isNull(it) }.toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canon(v.opt(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.opt(it)) }
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }

    private fun stored(): RulesData = RulesData(
        RawJsonArray.of(JSONArray("""[
            {"id":"pv:1","parentId":null,"kind":"CONFIRMED","at":"2026-11-01T09:00:00Z","base":"sha256:00","sourceUrl":"https://www.gazzettaufficiale.it/x",
             "primary":true,"quote":"salvato","snapshotHash":"$hash","sources":{"A1":{"url":"https://www.gazzettaufficiale.it/x","read":"PRIMARY"}},
             "changes":[{"layer":"LAW","target":"t.rate","key":"2026-01-01","before":null,"after":{"id":"t.rate","value":"0.1","rowExtra":true},"changeExtra":{"n":1}}],
             "futureField":"kept"},
            {"id":"pv:2","parentId":"pv:1","kind":"FUTURE_KIND","at":"2026-11-02T09:00:00Z","base":"sha256:00","primary":false,
             "changes":[{"layer":"FUTURE_LAYER","target":"x","key":null,"winner":"FUTURE_WINNER"}]}
        ]""")),
        RawJsonArray.of(JSONArray("""[
            {"id":"pp:1","slot":"quota","scope":null,"value":"0.05","unit":"RATIO","paramClass":"PERSONAL_DECLARED","origin":"DICHIARATO","recordedAt":"2026-11-01T09:00:00Z","extra":[1,2]},
            {"id":"pp:2","slot":"quota","value":"0.07","unit":"RATIO","paramClass":"FUTURE_CLASS","origin":"FUTURE_ORIGIN","recordedAt":"2026-11-01T09:00:00Z"}
        ]""")),
        RawJsonArray.of(JSONArray("""[{"hash":"$hash","url":null,"recordedOn":"2026-11-01","kind":"AGENT_CAPTURE","summaryOnly":false,"text":"$snapText","note":"extra"}]""")),
        RawJsonArray.of(JSONArray("""[{"line":"sintetica","future":1}]""")),
    )

    @Test fun typedCollectionsRoundTripAndKeepUnknownFieldsAndCodes() {
        val data = stored()
        val book = RulesCodec.decode(data)
        assertEquals(VersionKind.CONFIRMED, book.versions[0].kind.known)
        assertEquals("FUTURE_KIND", book.versions[1].kind.raw)
        assertNull(book.versions[1].kind.known)
        assertEquals("FUTURE_LAYER", book.versions[1].changes.single().layer.raw)
        assertEquals("FUTURE_CLASS", book.params[1].paramClass.raw)
        assertEquals(LocalDate.of(2026, 11, 1), book.snapshots.single().recordedOn)
        val again = RulesCodec.encode(book)
        for (key in RulesData.KEYS) assertEquals(key, canon(data.byKey().getValue(key).toJsonArray()), canon(again.byKey().getValue(key).toJsonArray()))
        // A typed record added beside them is written in full; the old ones are untouched.
        val added = book.copy(params = book.params + PersonalParam("pp:3", "quota", null, "0.06", "RATIO", Coded.Known(ParamClass.SCENARIO),
            Coded.Known(Origin.DICHIARATO), recordedAt = Instant.parse("2026-11-02T00:00:00Z")))
        val written = RulesCodec.encode(added).personalParams.toJsonArray()
        assertEquals(3, written.length())
        assertEquals("kept", RulesCodec.encode(added).packVersions.toJsonArray().getJSONObject(0).getString("futureField"))
        assertEquals(canon(data.personalParams.toJsonArray().getJSONObject(0)), canon(written.getJSONObject(0)))
        assertEquals("SCENARIO", written.getJSONObject(2).getString("paramClass"))
        RulesCodec.validate(RulesCodec.encode(added))
    }

    @Test fun archiveV14KeepsTheRuleCollectionsWithUnknownFields() {
        assertEquals(14, ArchiveCodec.CURRENT_VERSION)
        val decoded = ArchiveCodec.decode(ArchiveCodec.encode(Archive(rulesData = stored())))
        for (key in RulesData.KEYS) assertEquals(key, canon(stored().byKey().getValue(key).toJsonArray()), canon(decoded.rulesData.byKey().getValue(key).toJsonArray()))
        // The empty arrays a device holds today still decode and type.
        val empty = RulesCodec.decode(ArchiveCodec.decode(ArchiveCodec.encode(Archive())).rulesData)
        assertTrue(empty.versions.isEmpty() && empty.params.isEmpty() && empty.snapshots.isEmpty())
    }

    /** The rule writers check the history they write; a stored inconsistency never blocks saving anything else. */
    @Test fun anInconsistentHistoryIsDetectedButNeverBlocksOtherSaves() {
        val orphan = stored().copy(packVersions = RawJsonArray.of(JSONArray("""[{"id":"pv:9","parentId":"pv:missing","kind":"CONFIRMED","at":"2026-11-01T09:00:00Z","base":"x"}]""")))
        val badHash = stored().copy(snapshots = RawJsonArray.of(JSONArray("""[{"hash":"sha256:00","recordedOn":"2026-11-01","kind":"K","summaryOnly":false,"text":"altro"}]""")))
        for (bad in listOf(orphan, badHash)) {
            try { RulesCodec.validate(bad); fail() } catch (_: IllegalArgumentException) {}
            ArchiveCodec.encode(Archive(rulesData = bad))
        }
    }
}
