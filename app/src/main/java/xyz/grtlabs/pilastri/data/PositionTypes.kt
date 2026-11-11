package xyz.grtlabs.pilastri.data

import java.time.LocalDate

/*
 * Shared contract for Council 4 ("any person, nothing hardcoded"), slice 1 of TODO.md's implementation plan.
 * Owned by the primary agent. The data owner (archive v14), the rules owner (domain/rules) and the UI owner
 * build on these types; change them only through the primary agent.
 *
 * Rules (DATA-CONTRACT.md "Council 4 data rules"):
 * - These are closed code enums: only an app update adds a value.
 * - The archive keeps an unknown future value as its raw string and never drops it ([Coded]).
 * - Missing is ND, never zero and never a default ([Sourced.nd]).
 */

/** The person's working position. Combinations are several [PositionKind]s at once. */
enum class PositionKind {
    STUDENTE,
    DIPENDENTE_PRIVATO,
    DIPENDENTE_PUBBLICO,
    AUTONOMO_FORFETTARIO,
    AUTONOMO_ORDINARIO,
    COLLABORATORE,
    PENSIONATO,
    NESSUN_REDDITO,
}

/** The social-security fund (gestione) that collects the position's contributions. ND = not known yet. */
enum class Gestione { FPLD, CTPS, CPDEL, GESTIONE_SEPARATA, ARTIGIANI, COMMERCIANTI, CASSA, NESSUNA, ND }

enum class SeveranceRegime { TFR, TFS, ND }

enum class SeveranceDestination { AZIENDA, TESORERIA_INPS, FONDO, ND }

/** Form of a complementary pension scheme (COVIP categories). */
enum class FundFormType { NEGOZIALE, APERTO, PIP, PREESISTENTE, ND }

/** Where a value came from. ND carries no value. */
enum class Origin { DOCUMENTO, DICHIARATO, CALCOLATO, ND }

/**
 * A value with its provenance: {value, origin, docId, fieldLabel, asOf}.
 * Invariant: `origin == ND` exactly when `value == null`. ND is a state, never zero.
 */
data class Sourced<out T : Any>(
    val value: T?,
    val origin: Origin,
    val docId: String? = null,
    val fieldLabel: String? = null,
    val asOf: LocalDate? = null,
) {
    init {
        require((origin == Origin.ND) == (value == null)) { "Sourced: ND exactly when the value is missing" }
        require(docId == null || docId.isNotBlank())
        require(fieldLabel == null || fieldLabel.isNotBlank())
        require(origin == Origin.DOCUMENTO || docId == null) { "Sourced: docId only for a document origin" }
    }

    val isKnown: Boolean get() = origin != Origin.ND

    companion object {
        private val ND = Sourced<Nothing>(null, Origin.ND)
        fun <T : Any> nd(): Sourced<T> = ND
        fun <T : Any> document(value: T, docId: String, fieldLabel: String, asOf: LocalDate?): Sourced<T> =
            Sourced(value, Origin.DOCUMENTO, docId, fieldLabel, asOf)
        fun <T : Any> declared(value: T, asOf: LocalDate? = null): Sourced<T> = Sourced(value, Origin.DICHIARATO, asOf = asOf)
        fun <T : Any> calculated(value: T, asOf: LocalDate? = null): Sourced<T> = Sourced(value, Origin.CALCOLATO, asOf = asOf)
    }
}

/** Per-step rounding of the rule engine. Money is Long cents; rates are exact decimals. */
enum class Rounding { NONE, HALF_UP_CENT, FLOOR_CENT, TRUNC_4DEC, FLOOR_UNIT }

/** Confirmation tier class of a parameter; set by code, never by the agent. */
enum class ParamClass { LAW, PERSONAL_DOCUMENT, PERSONAL_DECLARED, SCENARIO }

/**
 * Source status of a law row. VERIFIED computes exactly; CARRIED and UNVERIFIED compute as "stima";
 * INCERTO is n/d. A summary-only source is never VERIFIED.
 */
enum class PackStatus { VERIFIED, CARRIED, UNVERIFIED, INCERTO }

/** Kind of an optional connector in the static registry (`sync/ConnectorSpec.kt`). */
enum class ConnectorKind { BANK_AGGREGATOR, BROKER, WALLET, PAYROLL, MAIL, ASSISTANT }

/**
 * A stored enum code that survives app downgrades and future values: a known constant, or the raw string
 * as read. Write back [raw] so an unknown value is never dropped or rewritten.
 */
sealed interface Coded<out E : Enum<out E>> {
    val raw: String
    data class Known<out E : Enum<out E>>(val value: E) : Coded<E> { override val raw: String get() = value.name }
    data class Unknown(override val raw: String) : Coded<Nothing> {
        init { require(raw.isNotBlank()) }
    }

    /** The known constant, or null for an unknown code. */
    val known: E? get() = (this as? Known<E>)?.value
}

/** Reads a stored code: an exact constant name becomes [Coded.Known], anything else is kept as [Coded.Unknown]. */
inline fun <reified E : Enum<E>> codedOf(raw: String): Coded<E> =
    enumValues<E>().firstOrNull { it.name == raw }?.let { Coded.Known(it) } ?: Coded.Unknown(raw)

fun <E : Enum<E>> coded(value: E): Coded<E> = Coded.Known(value)
