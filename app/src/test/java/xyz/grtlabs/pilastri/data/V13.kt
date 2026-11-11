package xyz.grtlabs.pilastri.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Test helper: turns a current (v14) payload into the shape archive v13 stored, so the legacy-version tests can keep deriving
 * their old payloads from current encodings. Idempotent and tolerant of keys an older-version test already removed.
 */
internal object V13 {
    fun downgrade(payload: JSONObject): JSONObject {
        listOf("positions", "funds", "packVersions", "personalParams", "snapshots", "payslipLineClasses").forEach { payload.remove(it) }
        val incomeRules = payload.remove("incomeRules") as? JSONArray
        payload.optJSONArray("accounts")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { o ->
                val legacy = o.remove("legacyType")
                o.remove("fundId")
                if (legacy is String && o.opt("type") == AccountType.PENSION_FUND.name) o.put("type", legacy)
            }
        }
        fun rename(arr: JSONArray?) {
            if (arr != null) for (i in 0 until arr.length()) arr.getJSONObject(i).let { o -> if (o.has("providerId")) o.put("provider", o.remove("providerId")) }
        }
        rename(payload.optJSONArray("externalMovements"))
        rename(payload.optJSONArray("connections"))
        payload.optJSONObject("planning")?.let { p ->
            rename(p.optJSONArray("providerCategories"))
            if (!p.has("salaryRules")) p.put("salaryRules", JSONArray().apply {
                if (incomeRules != null) for (i in 0 until incomeRules.length()) put(JSONObject(incomeRules.getJSONObject(i).toString()).apply { remove("title"); remove("positionId") })
            })
        }
        return payload
    }

    /** Wraps a payload in a sealed envelope of [version]. */
    fun seal(version: Int, payload: JSONObject): ByteArray {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return JSONObject().put("format", "pilastri-archive").put("version", version).put("sha256", digest)
            .put("payload", java.util.Base64.getEncoder().encodeToString(bytes)).toString().toByteArray(Charsets.UTF_8)
    }

    fun payloadOf(envelope: ByteArray): JSONObject =
        JSONObject(String(java.util.Base64.getDecoder().decode(JSONObject(envelope.toString(Charsets.UTF_8)).getString("payload")), Charsets.UTF_8))
}
