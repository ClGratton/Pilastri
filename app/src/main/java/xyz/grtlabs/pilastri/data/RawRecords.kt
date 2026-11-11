package xyz.grtlabs.pilastri.data

import org.json.JSONArray
import org.json.JSONObject

/*
 * Records this app version stores without understanding them, kept so a decode → encode round trip never drops data.
 * toString never prints their content (they may carry provider text), only their size.
 */

/** A JSON array kept as read (normalized by org.json) until a later slice types it. */
class RawJsonArray private constructor(val json: String) {
    val size: Int get() = JSONArray(json).length()
    fun toJsonArray(): JSONArray = JSONArray(json)
    override fun equals(other: Any?) = other is RawJsonArray && other.json == json
    override fun hashCode() = json.hashCode()
    override fun toString() = "RawJsonArray($size)"

    companion object {
        val EMPTY = RawJsonArray("[]")
        fun of(array: JSONArray): RawJsonArray {
            require(array.length() <= 100_000) { "Troppi elementi" }
            return RawJsonArray(array.toString())
        }
    }
}

/** One stored JSON object kept as read. */
class RawJsonObject private constructor(val json: String) {
    fun toJsonObject(): JSONObject = JSONObject(json)
    /** An account id the record refers to, read without interpreting anything else. */
    fun refersToAccount(id: String): Boolean = toJsonObject().let { o ->
        listOf("accountId", "securitiesAccountId").any { k -> o.has(k) && !o.isNull(k) && o.opt(k) == id }
    }
    override fun equals(other: Any?) = other is RawJsonObject && other.json == json
    override fun hashCode() = json.hashCode()
    override fun toString() = "RawJsonObject"

    companion object {
        fun of(obj: JSONObject) = RawJsonObject(obj.toString())
    }
}

/**
 * The rule-pack collections of archive v14 (Council 4 c), stored verbatim until the rules slice types them; adding their types
 * later needs no version bump because the stored shape is theirs.
 */
data class RulesData(
    val packVersions: RawJsonArray = RawJsonArray.EMPTY,
    val personalParams: RawJsonArray = RawJsonArray.EMPTY,
    val snapshots: RawJsonArray = RawJsonArray.EMPTY,
    val payslipLineClasses: RawJsonArray = RawJsonArray.EMPTY,
) {
    companion object { val KEYS = listOf("packVersions", "personalParams", "snapshots", "payslipLineClasses") }
    fun byKey(): Map<String, RawJsonArray> = mapOf("packVersions" to packVersions, "personalParams" to personalParams,
        "snapshots" to snapshots, "payslipLineClasses" to payslipLineClasses)
}

/**
 * Provider records whose stored `providerId` this app version does not know (a newer connector). They are written back unchanged
 * and are not shown, synced or reviewed here; an account they refer to cannot be removed.
 */
data class UnknownProviderRows(
    val externalMovements: List<RawJsonObject> = emptyList(),
    val connections: List<RawJsonObject> = emptyList(),
    val providerCategories: List<RawJsonObject> = emptyList(),
) {
    val isEmpty: Boolean get() = externalMovements.isEmpty() && connections.isEmpty() && providerCategories.isEmpty()
    fun refersToAccount(id: String): Boolean = (externalMovements + connections).any { it.refersToAccount(id) }
}
