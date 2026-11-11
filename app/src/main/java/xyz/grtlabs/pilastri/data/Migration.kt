package xyz.grtlabs.pilastri.data

import java.io.File
import org.json.JSONObject

/**
 * Account type codes stored by archives before v14. They are read only to migrate and to keep [Account.legacyType]; new records
 * never carry them, and no rule depends on them.
 */
object LegacyAccountType {
    const val ALLIANZ_INSIEME = "ALLIANZ_INSIEME"
    const val ESPERO = "ESPERO"
    val FUND_CODES = setOf(ALLIANZ_INSIEME, ESPERO)
}

/**
 * Archive v13 → v14 (Council 4 a), pure Kotlin. The decoder has already turned every stored legacy fund code into
 * PENSION_FUND + [Account.legacyType] and every salary rule into an [IncomeRule] titled [IncomeRule.LEGACY_SALARY_TITLE];
 * this step describes each such account's fund. Nothing is invented:
 * - one [PensionFund] per legacy fund account, named from the account (declared). Only ESPERO carries a form type (NEGOZIALE,
 *   the type the person chose); every other field stays ND.
 * - no [IncomePosition] is created: v13 data states none. Setup asks.
 * - prefill facts, fund profiles, deduction years and INPS estimates stay where they are, unchanged.
 * Idempotent: an account that already has a fund is left alone.
 */
object ArchiveMigration {
    fun toV14(a: Archive): Archive = a.accounts
        .filter { it.type == AccountType.PENSION_FUND && it.fundId == null }
        .fold(a) { acc, account -> acc.withAccountFund(account.id, legacyFormType(account.legacyType)) }

    private fun legacyFormType(legacy: String?): Sourced<Coded<FundFormType>> =
        if (legacy == LegacyAccountType.ESPERO) Sourced.declared(coded(FundFormType.NEGOZIALE)) else Sourced.nd()
}

/**
 * The untouched copy of an archive written before v14 (DATA-CONTRACT "Council 4 data rules"): made once, before the first v14
 * write over an older file, in the archive's own directory. Never overwritten and never deleted by the app.
 */
object PremigrationCopy {
    const val FILE_NAME = "portfolio-v13-premigration.json"

    /** Stored envelope version, or null when the bytes are not a readable archive envelope. */
    fun storedVersion(bytes: ByteArray): Int? = try {
        JSONObject(bytes.toString(Charsets.UTF_8)).takeIf { it.optString("format") == "pilastri-archive" }?.getInt("version")
    } catch (_: Exception) { null }

    /**
     * Copies [stored] to [FILE_NAME] in [dir] when it is older than the current version (or unreadable) and no copy exists yet.
     * The copy is written to a temporary file, synced, verified and renamed, so a crash never leaves a partial copy in place.
     * Returns true when it wrote the copy. Throws when the copy cannot be made: the caller must then not write.
     */
    fun preserve(dir: File, stored: ByteArray?): Boolean {
        if (stored == null) return false
        val version = storedVersion(stored)
        if (version != null && version >= ArchiveCodec.CURRENT_VERSION) return false
        val target = File(dir, FILE_NAME)
        if (target.exists()) return false
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.outputStream().use { it.write(stored); it.fd.sync() }
        check(tmp.readBytes().contentEquals(stored)) { "Copia dell'archivio non verificata" }
        check(!target.exists() && tmp.renameTo(target)) { "Copia dell'archivio non riuscita" }
        return true
    }
}
