package xyz.grtlabs.pilastri.sync

import xyz.grtlabs.pilastri.data.ConnectorKind
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.data.PositionKind

/*
 * Slice 6: the static connector registry (COUNCIL.md, Council 4 e; AGENTS.md "Any person, nothing hardcoded").
 * Providers are optional connectors offered only when the person's position uses them. Nothing here names an employer,
 * fund or contract. The archive stores the [ConnectorSpec.id] string (`providerId`); unknown ids are preserved by the archive.
 */

/** Where setup offers a connector. NONE: never in setup (it lives in Impostazioni › Conti). */
enum class SetupSlot { SCEGLI_BANCA, ALTRI_CONTI, NONE }

/** When a connector's data counts as old. Read by the screens that show freshness; setup only declares it. */
sealed interface Staleness {
    /** Data older than [days] is shown as stale (the app's existing 35-day rule for values and bank coverage). */
    data class AfterDays(val days: Int) : Staleness {
        init { require(days > 0) }
    }

    /** As [AfterDays], and the consent itself expires: the bank asks again ("Rinnova"), usually within 180 days. */
    data class ConsentAndDays(val days: Int) : Staleness {
        init { require(days > 0) }
    }

    /** Holds no dated data of its own (the assistant). */
    data object Never : Staleness
}

/**
 * One optional connector.
 * @param id the archive's string providerId; for the existing providers it equals [ImportProvider.id].
 * @param offeredWhen positions that use it; empty means everyone.
 * @param implemented false means the app has no working connector yet: it is never offered (no control for an unimplemented function).
 */
data class ConnectorSpec(
    val id: String,
    val label: String,
    /** What it brings, as a label ("Portafoglio", "Movimenti"); never an explanation. */
    val caption: String,
    val kind: ConnectorKind,
    val offeredWhen: Set<PositionKind>,
    val slot: SetupSlot,
    val staleness: Staleness,
    val implemented: Boolean,
) {
    init {
        require(id.isNotBlank() && id.length <= 60 && id.none { it.isWhitespace() }) { "Connettore non valido" }
        require(label.isNotBlank() && caption.isNotBlank())
    }

    /** True when this connector is meant for at least one of [positions] (or for everyone). Says nothing about [implemented]. */
    fun matches(positions: Set<PositionKind>): Boolean = offeredWhen.isEmpty() || offeredWhen.any { it in positions }
}

object Connectors {
    private const val STALE_DAYS = 35

    /** Banks through PSD2 (each person's own Enable Banking account). isybank and every other bank are reached only here. */
    val ENABLE_BANKING = ConnectorSpec("ENABLE_BANKING", "Banca", "Conti correnti", ConnectorKind.BANK_AGGREGATOR,
        emptySet(), SetupSlot.SCEGLI_BANCA, Staleness.ConsentAndDays(STALE_DAYS), implemented = true)

    val TRADE_REPUBLIC = ConnectorSpec(ImportProvider.TRADE_REPUBLIC.id, ImportProvider.TRADE_REPUBLIC.label, "Portafoglio",
        ConnectorKind.BROKER, emptySet(), SetupSlot.ALTRI_CONTI, Staleness.AfterDays(STALE_DAYS), implemented = true)

    val PAYPAL = ConnectorSpec(ImportProvider.PAYPAL.id, ImportProvider.PAYPAL.label, "Movimenti",
        ConnectorKind.WALLET, emptySet(), SetupSlot.ALTRI_CONTI, Staleness.AfterDays(STALE_DAYS), implemented = true)

    /** Public-sector payroll. No working connector exists yet, so it is never offered. */
    val NOIPA = ConnectorSpec("NOIPA", "NoiPA", "Busta paga", ConnectorKind.PAYROLL,
        setOf(PositionKind.DIPENDENTE_PUBBLICO), SetupSlot.ALTRI_CONTI, Staleness.AfterDays(STALE_DAYS), implemented = false)

    /** Instalment plans read from mail. Out of setup; optional in Impostazioni › Conti. */
    val GMAIL = ConnectorSpec("GMAIL", "Gmail", "Rate", ConnectorKind.MAIL,
        emptySet(), SetupSlot.NONE, Staleness.AfterDays(STALE_DAYS), implemented = true)

    val ASSISTANT = ConnectorSpec("ASSISTANT", "Assistente", "Claude o ChatGPT", ConnectorKind.ASSISTANT,
        emptySet(), SetupSlot.ALTRI_CONTI, Staleness.Never, implemented = true)

    val all: List<ConnectorSpec> = listOf(ENABLE_BANKING, TRADE_REPUBLIC, PAYPAL, NOIPA, GMAIL, ASSISTANT)

    init {
        require(all.map { it.id }.toSet().size == all.size) { "Connettori duplicati" }
    }

    fun byId(id: String): ConnectorSpec? = all.firstOrNull { it.id == id }

    /**
     * What to offer to a person with [positions] (none chosen = only the connectors meant for everyone), optionally in one
     * setup [slot]. An unimplemented connector is never offered.
     */
    fun offered(positions: Set<PositionKind>, slot: SetupSlot? = null): List<ConnectorSpec> =
        all.filter { it.implemented && (slot == null || it.slot == slot) && it.matches(positions) }
}
