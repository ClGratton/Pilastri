package xyz.grtlabs.pilastri.ui.setup

import java.net.URI
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.ConnectorKind
import xyz.grtlabs.pilastri.data.Gestione
import xyz.grtlabs.pilastri.data.IncomePosition
import xyz.grtlabs.pilastri.data.PositionKind
import xyz.grtlabs.pilastri.data.SeveranceRegime
import xyz.grtlabs.pilastri.data.Sourced
import xyz.grtlabs.pilastri.data.coded
import xyz.grtlabs.pilastri.sync.ConnectorSpec
import xyz.grtlabs.pilastri.sync.Connectors
import xyz.grtlabs.pilastri.sync.SetupSlot
import xyz.grtlabs.pilastri.sync.eb.EbAspsp

/*
 * Slice 5b: the setup flow's state and rules, free of Android so they are unit-tested (COUNCIL.md Council 4 b; §7 failures).
 * Order: Benvenuto → Posizione → Scegli banca → (consents) → Profilo proposto → Altri conti → Fatto.
 * Nothing secret lives here: bank names and statuses only. The Enable Banking key, application id, session and consent
 * state stay in sync/eb's encrypted store.
 */

enum class SetupStep { BENVENUTO, POSIZIONE, SCEGLI_BANCA, CONSENSI, PROFILO, ALTRI_CONTI }

/** What an Altri conti row shows in place of an action: see [SetupRules.connectorShape]. */
enum class ConnectorShape { LINKED, LINK, CHOOSE }

/** A page of the setup stack: a step, or a provider page pushed over one. */
sealed interface SetupEntry {
    val key: String

    data class Step(val step: SetupStep) : SetupEntry {
        override val key: String get() = "step:${step.name}"
    }

    /** The person's own Enable Banking sign-up and application page. */
    data object SignUp : SetupEntry {
        override val key: String get() = "web:signup"
    }

    /** Setup's one-time offer of mail access (Gmail link, notification access), shown right before Scegli banca. Never saved. */
    data object MailAccess : SetupEntry {
        override val key: String get() = "web:mail"
    }

}

/** Whether this device has a verified Enable Banking application. */
enum class AccessState { UNKNOWN, NOT_REGISTERED, REGISTERED, FAILED }

/** The bank list read from Enable Banking. */
enum class ListState { IDLE, LOADING, READY, FAILED }

/** One chosen bank's consent. Only COLLEGATA claims a connection, and only after the client stored the session. */
enum class BankLink { IN_ATTESA, IN_CORSO, COLLEGATA, NON_RIUSCITA, DA_APPROVARE }

/**
 * A document read in setup (Posizione › Leggi da un documento): its name, SHA-256 and the position facts it stated.
 * Memory only: [SetupCodec] never saves it, so a restored setup shows those rows as declared.
 */
data class SetupDoc(val label: String, val sha: String, val gestione: Gestione? = null, val severance: SeveranceRegime? = null)

data class SetupModel(
    val entries: List<SetupEntry> = listOf(SetupEntry.Step(SetupStep.BENVENUTO)),
    /** Chosen positions in chip order. Empty is n/d, never a default. */
    val positions: List<PositionKind> = emptyList(),
    /** The positions a document selected: kind to the SHA-256 of that document (its [docs] entry). Not saved. */
    val positionSources: Map<PositionKind, String> = emptyMap(),
    /** The documents read in this setup. Not saved. */
    val docs: List<SetupDoc> = emptyList(),
    /** Gestione per position that has a gestione row; [Gestione.ND] is "Non so". */
    val gestioni: Map<PositionKind, Gestione> = emptyMap(),
    val positionsSkipped: Boolean = false,
    val access: AccessState = AccessState.UNKNOWN,
    val list: ListState = ListState.IDLE,
    val chosenBanks: List<String> = emptyList(),
    val links: Map<String, BankLink> = emptyMap(),
    /** "Dopo" on Scegli banca: the chosen banks wait as "Da approvare" and no consent runs. */
    val banksDeferred: Boolean = false,
    /** The bank whose consent just failed: the step offers Riprova or Continua senza. */
    val failedBank: String? = null,
    /** Connector ids set aside with "Dopo" in Altri conti. */
    val later: Set<String> = emptySet(),
    /** The mail-access offer was shown once in this setup (not saved: a restored setup may offer it again). */
    val mailOffered: Boolean = false,
) {
    init {
        require(entries.isNotEmpty() && entries.first() == SetupEntry.Step(SetupStep.BENVENUTO))
    }

    val top: SetupEntry get() = entries.last()
    val step: SetupStep get() = entries.filterIsInstance<SetupEntry.Step>().last().step
    val steps: List<SetupStep> get() = SetupRules.steps(this)
}

/**
 * A bank-only run of the setup (Impostazioni › Conti › Banche): the picker and the consents of "Aggiungi banca", or one bank's
 * consent again ([renew] names it). Nothing else of the setup runs and nothing but the waiting banks is written.
 */
data class BankMode(val renew: String? = null)

/** Labels and gestione choices per position. Generic: no employer, fund or contract. */
object PositionCatalog {
    val order: List<PositionKind> = listOf(
        PositionKind.STUDENTE, PositionKind.DIPENDENTE_PRIVATO, PositionKind.DIPENDENTE_PUBBLICO, PositionKind.AUTONOMO_FORFETTARIO,
        PositionKind.AUTONOMO_ORDINARIO, PositionKind.COLLABORATORE, PositionKind.PENSIONATO, PositionKind.NESSUN_REDDITO,
    )

    fun label(kind: PositionKind): String = when (kind) {
        PositionKind.STUDENTE -> "Studente"
        PositionKind.DIPENDENTE_PRIVATO -> "Dipendente privato"
        PositionKind.DIPENDENTE_PUBBLICO -> "Dipendente pubblico"
        PositionKind.AUTONOMO_FORFETTARIO -> "Partita IVA forfettaria"
        PositionKind.AUTONOMO_ORDINARIO -> "Partita IVA ordinaria"
        PositionKind.COLLABORATORE -> "Collaborazione"
        PositionKind.PENSIONATO -> "Pensione"
        PositionKind.NESSUN_REDDITO -> "Nessun reddito"
    }

    /** The inline gestione row for Partita IVA and Collaborazione ("Non so" = [Gestione.ND], always last); empty = no row. */
    fun gestioni(kind: PositionKind): List<Gestione> = when (kind) {
        PositionKind.AUTONOMO_FORFETTARIO, PositionKind.AUTONOMO_ORDINARIO ->
            listOf(Gestione.GESTIONE_SEPARATA, Gestione.ARTIGIANI, Gestione.COMMERCIANTI, Gestione.CASSA, Gestione.ND)
        PositionKind.COLLABORATORE -> listOf(Gestione.GESTIONE_SEPARATA, Gestione.ND)
        else -> emptyList()
    }

    fun gestioneLabel(g: Gestione): String = when (g) {
        Gestione.GESTIONE_SEPARATA -> "Gestione separata"
        Gestione.ARTIGIANI -> "Artigiani"
        Gestione.COMMERCIANTI -> "Commercianti"
        Gestione.CASSA -> "Cassa"
        Gestione.FPLD -> "FPLD"
        Gestione.CTPS -> "CTPS"
        Gestione.CPDEL -> "CPDEL"
        Gestione.NESSUNA -> "Nessuna"
        Gestione.ND -> "Non so"
    }

    val isEmployee: (PositionKind) -> Boolean = { it == PositionKind.DIPENDENTE_PRIVATO || it == PositionKind.DIPENDENTE_PUBBLICO }
}

enum class RowTone { SOURCE, GOOD, WARN, PLAIN }

/** One line of the Profilo proposto card: what setup knows, everything else n/d. */
data class ProfileRow(val label: String, val value: String, val tag: String?, val tone: RowTone)

object SetupRules {
    const val ND = "n/d"

    fun steps(m: SetupModel): List<SetupStep> = buildList {
        add(SetupStep.BENVENUTO); add(SetupStep.POSIZIONE); add(SetupStep.SCEGLI_BANCA)
        // U-01: the total never changes. Consensi stays counted when the banks are skipped (the step is then done/skipped).
        add(SetupStep.CONSENSI)
        add(SetupStep.PROFILO); add(SetupStep.ALTRI_CONTI)
    }

    /** The boards 8a-8c count nine pages; the total is 9 for every person and never changes (D7). */
    const val BOARD_TOTAL = 9

    /**
     * The board page number of the current page, for "n di 9" and the nine segments: 1 Benvenuto, 2 Posizione (3 after a document),
     * 4 Scegli banca (5 the sign-up page), 6 Consensi (7 when a consent failed), 8 Profilo, 9 Altri conti. A skipped or failed
     * step never renumbers the others.
     */
    fun progress(m: SetupModel): Pair<Int, Int> = pageNumber(m.step, m) to BOARD_TOTAL

    /** [progress] for one page of the stack (a page under the top keeps its own number while the top moves). */
    fun pageNumber(step: SetupStep, m: SetupModel): Int {
        val current = step == m.step
        return when (step) {
            SetupStep.BENVENUTO -> 1
            SetupStep.POSIZIONE -> if (current && m.docs.isNotEmpty()) 3 else 2
            SetupStep.SCEGLI_BANCA -> if (current && m.top == SetupEntry.SignUp) 5 else 4
            SetupStep.CONSENSI -> if (current && m.failedBank != null) 7 else 6
            SetupStep.PROFILO -> 8
            SetupStep.ALTRI_CONTI -> 9
        }
    }

    fun push(m: SetupModel, entry: SetupEntry): SetupModel = if (m.top == entry) m else m.copy(entries = m.entries + entry)
    fun push(m: SetupModel, step: SetupStep): SetupModel = push(m, SetupEntry.Step(step))

    /** Back: the exact pop. Leaving a bank page before its redirect keeps the authorization (the bank stays "in corso"; the page can be reopened). */
    fun back(m: SetupModel): SetupModel {
        if (m.entries.size <= 1) return m
        val popped = m.copy(entries = m.entries.dropLast(1))
        return when (val top = m.top) {
            SetupEntry.SignUp -> if (popped.access == AccessState.REGISTERED) popped else popped.copy(access = AccessState.FAILED)
            else -> popped
        }
    }

    /** Closes a provider page that finished by itself (redirect claimed, registration verified): not a failure. */
    fun closePage(m: SetupModel): SetupModel = if (m.entries.size > 1 && m.top !is SetupEntry.Step) m.copy(entries = m.entries.dropLast(1)) else m

    /* Posizione */

    fun togglePosition(m: SetupModel, kind: PositionKind): SetupModel {
        val on = kind !in m.positions
        val chosen = when {
            !on -> m.positions - kind
            // "Nessun reddito" excludes every other position, and any position excludes it.
            kind == PositionKind.NESSUN_REDDITO -> listOf(kind)
            else -> m.positions - PositionKind.NESSUN_REDDITO + kind
        }
        val ordered = PositionCatalog.order.filter { it in chosen }
        // Tapping a chip is the person's own declaration: the toggled kind loses its document source.
        return m.copy(positions = ordered, gestioni = m.gestioni.filterKeys { it in ordered },
            positionSources = m.positionSources.filterKeys { it in ordered && it != kind }, positionsSkipped = false)
    }

    /**
     * A document that was read: its position is pre-selected (a chip already chosen stays) and takes the document as its source;
     * the document's gestione and end-of-service regime stay with it. Nothing the person declared is overwritten.
     */
    fun applyDocument(m: SetupModel, r: SetupDocReader.Result.Read): SetupModel {
        var next = m
        r.positions.forEach { k -> if (k !in next.positions) next = togglePosition(next, k) }
        val sources = next.positionSources + r.positions.filter { it in next.positions }.associateWith { r.sha }
        val docs = m.docs.filterNot { it.sha == r.sha } + SetupDoc(r.label, r.sha, r.gestione, r.severance)
        return next.copy(positionSources = sources, docs = docs, positionsSkipped = false)
    }

    /** The document that selected [kind], if the chip still comes from one. */
    fun docOf(m: SetupModel, kind: PositionKind): SetupDoc? = m.positionSources[kind]?.let { sha -> m.docs.firstOrNull { it.sha == sha } }

    fun setGestione(m: SetupModel, kind: PositionKind, g: Gestione): SetupModel {
        require(kind in m.positions && g in PositionCatalog.gestioni(kind)) { "Gestione non prevista" }
        // Tapping the selected gestione again clears it back to n/d (unanswered).
        return m.copy(gestioni = if (m.gestioni[kind] == g) m.gestioni - kind else m.gestioni + (kind to g))
    }

    /** Scegli banca, preceded once by the mail-access offer (Gmail link and notification access feed the Enable Banking sign-in). */
    fun toBanks(m: SetupModel): SetupModel {
        val banks = push(m, SetupStep.SCEGLI_BANCA)
        return if (banks.mailOffered) banks else push(banks.copy(mailOffered = true), SetupEntry.MailAccess)
    }

    /** "Continua" or "Dopo" on the mail-access page: back to Scegli banca. */
    fun mailDone(m: SetupModel): SetupModel = closePage(m)

    /** "Continua" on Posizione (U-02): enabled only once a position is chosen; "Dopo" is the explicit skip. */
    fun canContinuePositions(m: SetupModel): Boolean = m.positions.isNotEmpty()

    /** "Continua" on Posizione (the screen keeps it disabled until [canContinuePositions]). */
    fun positionsDone(m: SetupModel): SetupModel = toBanks(m.copy(positionsSkipped = m.positions.isEmpty()))

    /** "Dopo" on Posizione: nothing is written, everything stays n/d. */
    fun skipPositions(m: SetupModel): SetupModel =
        toBanks(m.copy(positions = emptyList(), gestioni = emptyMap(), positionSources = emptyMap(), docs = emptyList(), positionsSkipped = true))

    /* Scegli banca */

    fun toggleBank(m: SetupModel, name: String): SetupModel {
        val chosen = if (name in m.chosenBanks) m.chosenBanks - name else m.chosenBanks + name
        return m.copy(chosenBanks = chosen, links = m.links.filterKeys { it in chosen })
    }

    /**
     * The banks offered before the application is registered (the gateway lists its own banks only afterwards) come from the dated
     * data asset [BankCatalog]; after registration the gateway's list replaces it ([matchChosen] maps the choices).
     */
    fun fallbackBanks(catalog: BankCatalog): List<EbAspsp> = catalog.aspsps()

    /** Maps the chosen names to the gateway's own names (case-insensitive, one contains the other); a name with no single match is dropped. */
    fun matchChosen(chosen: List<String>, banks: List<EbAspsp>): List<String> = chosen.mapNotNull { c ->
        val q = c.trim().lowercase()
        banks.firstOrNull { it.name.lowercase() == q }?.name
            ?: banks.filter { it.name.lowercase().let { n -> q in n || n in q } }.singleOrNull()?.name
    }.distinct()

    /** The primary of Scegli banca: "Continua (n)", never the gateway's name (U-03). */
    fun bankPrimaryLabel(n: Int): String = if (n > 0) "Continua ($n)" else "Continua"

    /** The search box: a trimmed, case-insensitive part of the bank name. Blank shows every bank. */
    fun filterBanks(banks: List<EbAspsp>, query: String): List<EbAspsp> {
        val q = query.trim().lowercase()
        return if (q.isEmpty()) banks else banks.filter { q in it.name.lowercase() }
    }

    /** The empty-result line names the search, so the person sees why nothing is listed (the "Cancella ricerca" action sits beside it). */
    fun noBankLine(query: String): String {
        val q = query.trim().take(40)
        return if (q.isEmpty()) "Nessuna banca" else "Nessuna banca per «$q»"
    }

    /** "Continua" with banks: their consents run one at a time. Without banks it is "Dopo". */
    fun banksDone(m: SetupModel): SetupModel {
        if (m.chosenBanks.isEmpty()) return skipBanks(m)
        val links = m.chosenBanks.associateWith { b -> if (m.links[b] == BankLink.COLLEGATA) BankLink.COLLEGATA else BankLink.IN_ATTESA }
        return push(m.copy(banksDeferred = false, links = links, failedBank = null), SetupStep.CONSENSI)
    }

    /** "Dopo" / "Continua senza" on Scegli banca: chosen banks wait as "Da approvare"; nothing claims a connection. */
    fun skipBanks(m: SetupModel): SetupModel {
        val links = m.chosenBanks.associateWith { b -> if (m.links[b] == BankLink.COLLEGATA) BankLink.COLLEGATA else BankLink.DA_APPROVARE }
        return push(m.copy(banksDeferred = true, links = links, failedBank = null), SetupStep.PROFILO)
    }

    /**
     * Wave 16: the native Enable Banking flow ended ("Fatto" on EB-7, or its "Dopo"). Its consents ran inside it, so Profilo is
     * next: [linked] banks are COLLEGATA, [pending] ones wait as "Da approvare"; a bank linked earlier keeps its place.
     */
    fun ebDone(m: SetupModel, linked: List<String>, pending: List<String>): SetupModel {
        val kept = m.links.filterValues { it == BankLink.COLLEGATA }
        val links = kept + pending.filter { it !in kept }.associateWith { BankLink.DA_APPROVARE } + linked.associateWith { BankLink.COLLEGATA }
        val chosen = (kept.keys + pending + linked).distinct()
        return push(m.copy(chosenBanks = chosen, links = links, banksDeferred = linked.isEmpty() && pending.isNotEmpty(), failedBank = null,
            access = if (linked.isNotEmpty()) AccessState.REGISTERED else m.access), SetupStep.PROFILO)
    }

    /* Consents, one bank at a time */

    /** The bank whose consent runs now: one being authorized, else the next waiting one. */
    fun currentBank(m: SetupModel): String? =
        m.chosenBanks.firstOrNull { m.links[it] == BankLink.IN_CORSO } ?: m.chosenBanks.firstOrNull { m.links[it] == BankLink.IN_ATTESA }

    /** 1-based index of [bank] among the chosen banks, for "1 di 2 · isybank". */
    fun bankIndex(m: SetupModel, bank: String): Pair<Int, Int> = (m.chosenBanks.indexOf(bank) + 1) to m.chosenBanks.size

    /**
     * The client keeps one bank session: while one exists, starting another consent would replace it. Such a bank waits as
     * "Da approvare" instead of silently disconnecting the first.
     */
    fun consentBlocked(m: SetupModel, bank: String): SetupModel = m.copy(links = m.links + (bank to BankLink.DA_APPROVARE))

    fun consentStarted(m: SetupModel, bank: String): SetupModel = m.copy(links = m.links + (bank to BankLink.IN_CORSO), failedBank = null)

    fun consentLinked(m: SetupModel, bank: String): SetupModel = m.copy(links = m.links + (bank to BankLink.COLLEGATA), failedBank = null)

    fun consentFailed(m: SetupModel, bank: String): SetupModel =
        if (bank !in m.chosenBanks) m else m.copy(links = m.links + (bank to BankLink.NON_RIUSCITA), failedBank = bank)

    /** "Riprova": the same bank again. */
    fun retryConsent(m: SetupModel): SetupModel {
        val bank = m.failedBank ?: return m
        return m.copy(links = m.links + (bank to BankLink.IN_ATTESA), failedBank = null)
    }

    /** "Continua senza": the bank stays n/d (unfinished, so it waits in Conti) and the queue moves on. */
    fun continueWithout(m: SetupModel): SetupModel = m.copy(failedBank = null)

    /** "Salta": the bank waits as "Da approvare". */
    fun skipConsent(m: SetupModel, bank: String): SetupModel = m.copy(links = m.links + (bank to BankLink.DA_APPROVARE), failedBank = null)

    fun consentsDone(m: SetupModel): Boolean = m.failedBank == null && currentBank(m) == null

    /** Banks whose consent is unfinished: they wait as "Da approvare" in Conti (no dot). */
    fun pendingBanks(m: SetupModel): List<String> = m.chosenBanks.filter { m.links[it] != BankLink.COLLEGATA }

    /* Bank-only run ("Aggiungi banca" / "Rinnova") */

    /**
     * The model a bank-only run starts from. The model requires Benvenuto as its first entry, so it stays as an unseen root:
     * the page shown first is the picker ([renew] null) or the consent of [renew], already registered and waiting.
     */
    fun bankFlow(renew: String?): SetupModel =
        if (renew == null) SetupModel(entries = listOf(SetupEntry.Step(SetupStep.BENVENUTO), SetupEntry.Step(SetupStep.SCEGLI_BANCA)))
        else SetupModel(
            entries = listOf(SetupEntry.Step(SetupStep.BENVENUTO), SetupEntry.Step(SetupStep.CONSENSI)),
            access = AccessState.REGISTERED, list = ListState.READY, chosenBanks = listOf(renew), links = mapOf(renew to BankLink.IN_ATTESA),
        )

    /** The steps a bank-only run counts for "n di N": picker and consents, or the consent alone when renewing. */
    fun bankSteps(m: SetupModel): List<SetupStep> =
        if (m.entries.getOrNull(1) == SetupEntry.Step(SetupStep.CONSENSI)) listOf(SetupStep.CONSENSI)
        else listOf(SetupStep.SCEGLI_BANCA, SetupStep.CONSENSI)

    /** True when a bank-only run reaches Profilo (every path out of the consents): it is the run's end, never shown. */
    fun bankRunEnded(m: SetupModel): Boolean = m.top == SetupEntry.Step(SetupStep.PROFILO)

    /**
     * The banks waiting for approval after a bank-only run: the earlier ones minus every bank this run linked, plus the ones this
     * run left unfinished. A bank linked earlier and not touched here keeps its place.
     */
    fun mergePending(old: Set<String>, m: SetupModel): Set<String> =
        (old - m.links.filterValues { it == BankLink.COLLEGATA }.keys) + pendingBanks(m)

    /* Altri conti */

    fun offered(m: SetupModel): List<ConnectorSpec> = Connectors.offered(m.positions.toSet(), SetupSlot.ALTRI_CONTI)

    fun toggleLater(m: SetupModel, id: String): SetupModel = m.copy(later = if (id in m.later) m.later - id else m.later + id)

    /**
     * How an Altri conti row offers its connector, with no extra hop: a linked one only reads "collegato"; the assistant shows
     * its Claude / ChatGPT choices right on the row (8c-04 "Claude o ChatGPT"); every other connector has one "Collega".
     */
    fun connectorShape(spec: ConnectorSpec, linked: Boolean): ConnectorShape = when {
        linked -> ConnectorShape.LINKED
        spec.kind == ConnectorKind.ASSISTANT -> ConnectorShape.CHOOSE
        else -> ConnectorShape.LINK
    }

    /* Profilo proposto */

    /**
     * Each row carries its source: the document's name when the row is copied from a document read in setup, "dichiarato" when
     * the person chose it. What setup cannot store (Fondo pensione, Stipendio netto, Addizionale comunale) stays n/d and, once a
     * document was read, says "saltato": the document is not mined for it here.
     */
    fun profileRows(m: SetupModel): List<ProfileRow> = buildList {
        val read = m.docs.isNotEmpty()
        val skipped = if (read) "saltato" else null
        if (m.positions.isEmpty()) add(ProfileRow("Posizione", ND, if (m.positionsSkipped) "saltato" else null, RowTone.WARN))
        m.positions.forEach { k -> add(ProfileRow("Posizione", PositionCatalog.label(k), docOf(m, k)?.label ?: "dichiarato", RowTone.SOURCE)) }
        m.positions.forEach { k ->
            val d = docOf(m, k)
            val declared = m.gestioni[k]?.takeIf { it != Gestione.ND }
            val fromDoc = d?.gestione
            if (PositionCatalog.gestioni(k).isEmpty() && fromDoc == null) return@forEach
            val g = declared ?: fromDoc
            add(ProfileRow("Gestione · ${PositionCatalog.label(k)}", g?.let(PositionCatalog::gestioneLabel) ?: ND,
                if (declared != null) "dichiarato" else if (fromDoc != null) d?.label else null, if (g != null) RowTone.SOURCE else RowTone.WARN))
        }
        if (m.positions.any(PositionCatalog.isEmployee)) {
            val d = m.positions.filter(PositionCatalog.isEmployee).mapNotNull { docOf(m, it) }.firstOrNull { it.severance != null }
            val regime = d?.severance
            add(if (d != null && regime != null) ProfileRow("Fine rapporto", regime.name, d.label, RowTone.SOURCE) else ProfileRow("Fine rapporto", ND, skipped, RowTone.WARN))
        }
        add(ProfileRow("Fondo pensione", ND, skipped, RowTone.WARN))
        if (read) {
            add(ProfileRow("Stipendio netto", ND, skipped, RowTone.WARN))
            add(ProfileRow("Addizionale comunale", ND, skipped, RowTone.WARN))
        }
        if (m.chosenBanks.isEmpty()) add(ProfileRow("Banca", ND, null, RowTone.WARN))
        m.chosenBanks.forEach { b ->
            when (m.links[b]) {
                BankLink.COLLEGATA -> add(ProfileRow("Banca", b, "collegata", RowTone.GOOD))
                BankLink.NON_RIUSCITA -> add(ProfileRow("Banca", b, "non riuscito", RowTone.WARN)) // N-04: it failed, not "da approvare"
                else -> add(ProfileRow("Banca", b, "da approvare", RowTone.PLAIN))
            }
        }
    }

    /**
     * "Conferma tutto" (8c-03) instead of "Continua": a document was read, at least one position still comes from it and no row
     * is the person's own declaration. Choosing a chip after reading, or a gestione, makes that row "dichiarato" and ends this.
     */
    fun confirmAll(m: SetupModel): Boolean =
        m.docs.isNotEmpty() && m.positions.any { docOf(m, it) != null } && profileRows(m).none { it.tag == "dichiarato" }

    /* Writing at "Fatto" */

    /**
     * The chosen positions. A position a document selected is written as that document stated it: the label, the gestione
     * and the end-of-service regime it named are Sourced.document with the document's SHA-256 as docId. Otherwise as the
     * person declared them: kind and label DICHIARATO, the gestione DICHIARATO when chosen ("Non so" or unanswered stays ND).
     * Every other field is ND. Skipped = nothing.
     */
    fun positionsToWrite(m: SetupModel, today: LocalDate, newId: (PositionKind) -> String): List<IncomePosition> =
        m.positions.map { k ->
            val d = docOf(m, k)
            val g = m.gestioni[k]?.takeIf { it != Gestione.ND && it in PositionCatalog.gestioni(k) }
            val docGestione = d?.gestione
            val docSeverance = d?.severance?.takeIf { PositionCatalog.isEmployee(k) }
            IncomePosition(
                id = newId(k), kind = coded(k),
                label = if (d != null) Sourced.document(PositionCatalog.label(k), d.sha, d.label, today) else Sourced.declared(PositionCatalog.label(k), today),
                gestione = when {
                    g != null -> Sourced.declared(coded(g), today)
                    d != null && docGestione != null -> Sourced.document(coded(docGestione), d.sha, d.label, today)
                    else -> Sourced.nd()
                },
                severanceRegime = if (d != null && docSeverance != null) Sourced.document(coded(docSeverance), d.sha, d.label, today) else Sourced.nd(),
            )
        }

    /** Adds the setup's positions; a kind the archive already holds is left as it is (never overwritten). */
    fun applyToArchive(a: Archive, positions: List<IncomePosition>): Archive {
        val add = positions.filter { p -> a.positions.none { it.kind == p.kind } && a.positions.none { it.id == p.id } }
        return if (add.isEmpty()) a else a.copy(positions = a.positions + add)
    }
}

/**
 * Finds the person's Enable Banking application id on the control panel: in the address of an application page, or as the
 * only id shown on that page. A heuristic (the page has not been seen yet): every candidate is verified with a signed call
 * before it counts, and a wrong one is discarded.
 */
object EbRegistrationProbe {
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private fun uri(url: String): URI? = try { URI(url) } catch (_: Exception) { null }

    fun isEnableBanking(url: String?): Boolean {
        val host = url?.let(::uri)?.host?.lowercase() ?: return false
        return host == "enablebanking.com" || host.endsWith(".enablebanking.com")
    }

    /** The control panel, where the application is registered (the key and return address are offered there). */
    fun isControlPanel(url: String?): Boolean = isEnableBanking(url) && (uri(url!!)?.path ?: "").startsWith("/cp")

    fun isApplicationsPage(url: String?): Boolean = isEnableBanking(url) && (uri(url!!)?.path ?: "").startsWith("/cp/applications")

    fun fromUrl(url: String?): String? {
        if (!isApplicationsPage(url)) return null
        return UUID.find(uri(url!!)?.path ?: return null)?.value?.lowercase()
    }

    fun fromPageText(text: String?): String? = text?.let { t -> UUID.findAll(t).map { it.value.lowercase() }.distinct().toList().singleOrNull() }
}

/**
 * What setup needs from Enable Banking (sync/eb). The real one is [EbBankAccess]; tests use a fake. Every call may throw;
 * the flow turns any failure into "Non riuscito" with Riprova / Continua senza and never into a connection.
 */
interface BankAccess {
    val signUpUrl: String
    /** The https address the bank flow returns to; claimed inside the WebView, never loaded. */
    val redirectUrl: String
    suspend fun registered(): Boolean
    /** True when a consent for [bank] is already stored (another bank's does not count: several banks can be linked). */
    suspend fun hasSession(bank: String): Boolean
    /** The public half of the Keystore key (created on first use), for the application form. Never the private key. */
    suspend fun publicKeyPem(): String?
    /** Stores [applicationId] only if a signed call with it succeeds; otherwise leaves the previous state. */
    suspend fun verifyRegistration(applicationId: String): Boolean
    suspend fun banks(): List<EbAspsp>
    /** Starts the consent and returns the bank's authorization address. */
    suspend fun startLink(bank: EbAspsp): String
    fun isRedirect(url: String): Boolean
    /** True only when the client stored the bank session. */
    suspend fun completeLink(url: String, bank: EbAspsp): Boolean
    /** The stored application id (EbState), for reuse by the native registration (wave 16). Never shown or logged. */
    suspend fun applicationId(): String? = null
    /** Keeps an id the control panel itself returned for this account and key (wave 16). Defaults to the verified path. */
    suspend fun storeRegistration(applicationId: String): Boolean = verifyRegistration(applicationId)
    /** Accounts stored with [bank]'s session, for EB-7's "N conti"; null when unknown. */
    suspend fun accountCount(bank: String): Int? = null
    /** True when [url] is a return whose `state` matches the authorization kept for this device (it survives the process). False: ignore it. */
    suspend fun matchesPending(url: String): Boolean = false
    /** The bank of that pending authorization, or null. */
    suspend fun pendingBank(): String? = null
}

/** What starting one bank's consent gave. */
sealed interface BeginOutcome {
    /** Another bank session exists: the client keeps one, so this bank waits as "Da approvare". */
    data object Blocked : BeginOutcome
    data class Page(val url: String) : BeginOutcome
    data object Failed : BeginOutcome
    /** A begin for this bank is already running (ConsentSessions): nothing starts, nothing changes. */
    data object Busy : BeginOutcome
}

/**
 * The network steps of Scegli banca and the consents. Each returns an outcome; the matching reducer applies it to the
 * latest state, so a navigation during the call is never overwritten. No exception escapes as a connection.
 */
object BankSteps {
    suspend fun registered(access: BankAccess): AccessState =
        if (runCatching { access.registered() }.getOrDefault(false)) AccessState.REGISTERED else AccessState.NOT_REGISTERED

    /** The banks, or null when the list could not be read (an empty list is a failure too: Italy is never empty). */
    suspend fun loadBanks(access: BankAccess): List<EbAspsp>? = try {
        access.banks().distinctBy { it.name }.sortedBy { it.name.lowercase() }.takeIf { it.isNotEmpty() }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }

    /**
     * [replace]: the bank is the one whose session this consent renews, so the one kept session is not a block (Rinnova); any
     * other bank still waits as "Da approvare" while a session exists.
     */
    internal suspend fun begin(
        aspsp: EbAspsp?, access: BankAccess, replace: Boolean = false,
        sessions: ConsentSessions? = null, cause: String = "effect",
    ): BeginOutcome {
        val bank = aspsp?.name
        // One authorization per bank at a time: a live one is reopened (its address again), a running begin is not doubled.
        val reserved = sessions != null && bank != null
        if (reserved) when (val c = sessions!!.claim(bank!!, cause)) {
            is ConsentClaim.Reopen -> return BeginOutcome.Page(c.live.url)
            ConsentClaim.Busy -> return BeginOutcome.Busy
            ConsentClaim.Go -> Unit
        }
        val r = try {
            when {
                !replace && aspsp != null && access.hasSession(aspsp.name) -> BeginOutcome.Blocked
                aspsp == null -> BeginOutcome.Failed
                else -> access.startLink(aspsp).let { if (BankBrowser.allowed(it)) BeginOutcome.Page(it) else BeginOutcome.Failed }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { if (reserved) sessions!!.released(bank!!); throw e } catch (e: Exception) {
            if (reserved) sessions!!.released(bank!!)
            xyz.grtlabs.pilastri.sync.eb.EbLog.line(xyz.grtlabs.pilastri.sync.eb.EbLog.EB, "bank begin outcome=failed cause=$cause error=" + xyz.grtlabs.pilastri.sync.eb.EbLog.kind(e)); return BeginOutcome.Failed
        }
        if (reserved) { if (r is BeginOutcome.Page) sessions!!.opened(bank!!, r.url) else sessions!!.released(bank!!) }
        xyz.grtlabs.pilastri.sync.eb.EbLog.line(xyz.grtlabs.pilastri.sync.eb.EbLog.EB, "bank begin outcome=" + when (r) { is BeginOutcome.Page -> "started"; BeginOutcome.Blocked -> "blocked_has_session"; BeginOutcome.Failed -> "failed"; BeginOutcome.Busy -> "busy" } + " cause=$cause" + (if (aspsp == null) " aspsp=none" else ""))
        return r
    }

    /** True only when the client stored the session after the redirect. */
    suspend fun complete(aspsp: EbAspsp?, url: String, access: BankAccess): Boolean = try {
        aspsp != null && access.completeLink(url, aspsp)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }

    fun applyList(m: SetupModel, banks: List<EbAspsp>?): SetupModel =
        if (banks == null) m.copy(list = ListState.FAILED)
        else SetupRules.matchChosen(m.chosenBanks, banks).let { chosen -> m.copy(list = ListState.READY, chosenBanks = chosen, links = m.links.filterKeys { it in chosen }) }

    /** Applies [begin]'s outcome if the person is still on the consents step and the bank is still waiting. */
    fun applyBegin(m: SetupModel, bank: String, outcome: BeginOutcome): SetupModel {
        if (m.top != SetupEntry.Step(SetupStep.CONSENSI) || m.links[bank] != BankLink.IN_ATTESA) return m
        return when (outcome) {
            BeginOutcome.Busy -> m
            BeginOutcome.Blocked -> SetupRules.consentBlocked(m, bank)
            BeginOutcome.Failed -> SetupRules.consentFailed(m, bank)
            is BeginOutcome.Page -> SetupRules.consentStarted(m, bank)
        }
    }

    /** Which bank state takes a stored return: the one waiting (IN_CORSO), or NON_RIUSCITA when a restore ran after the process died and the return had already linked it (a linked result only). */
    fun takesReturn(state: BankLink?, linked: Boolean): Boolean = state == BankLink.IN_CORSO || (linked && state == BankLink.NON_RIUSCITA)

    /** The redirect's result: COLLEGATA only when [linked]. */
    fun applyComplete(m: SetupModel, bank: String, linked: Boolean): SetupModel =
        if (linked) SetupRules.consentLinked(m, bank) else SetupRules.consentFailed(m, bank)
}

/**
 * Saved state across process death: steps (provider pages are dropped, a consent in progress waits again), choices and
 * statuses. Bank names only: no address, id, key or consent.
 */
object SetupCodec {
    fun encode(m: SetupModel): String = JSONObject()
        .put("v", 1)
        .put("steps", JSONArray(m.entries.filterIsInstance<SetupEntry.Step>().map { it.step.name }))
        .put("positions", JSONArray(m.positions.map { it.name }))
        .put("gestioni", JSONObject().apply { m.gestioni.forEach { (k, g) -> put(k.name, g.name) } })
        .put("skipped", m.positionsSkipped)
        .put("access", m.access.name)
        .put("banks", JSONArray(m.chosenBanks))
        .put("links", JSONObject().apply { m.links.forEach { (b, s) -> put(b, s.name) } })
        .put("deferred", m.banksDeferred)
        .put("failed", m.failedBank ?: JSONObject.NULL)
        .put("later", JSONArray(m.later.toList()))
        .toString()

    fun decode(text: String): SetupModel = try {
        val o = JSONObject(text)
        fun strings(k: String): List<String> = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val steps = strings("steps").mapNotNull { s -> SetupStep.entries.firstOrNull { it.name == s } }
            .let { if (it.firstOrNull() == SetupStep.BENVENUTO) it else listOf(SetupStep.BENVENUTO) }
        val positions = strings("positions").mapNotNull { s -> PositionKind.entries.firstOrNull { it.name == s } }
        val g = o.optJSONObject("gestioni")
        val gestioni = g?.keys()?.asSequence()?.mapNotNull { k ->
            val kind = PositionKind.entries.firstOrNull { it.name == k } ?: return@mapNotNull null
            val value = Gestione.entries.firstOrNull { it.name == g.getString(k) } ?: return@mapNotNull null
            kind to value
        }?.toMap().orEmpty()
        val banks = strings("banks")
        val l = o.optJSONObject("links")
        val links = l?.keys()?.asSequence()?.mapNotNull { b ->
            val s = BankLink.entries.firstOrNull { it.name == l.getString(b) } ?: return@mapNotNull null
            // A consent in progress cannot survive: its page is gone. It is a named failure with its retry; nothing starts by itself.
            b to if (s == BankLink.IN_CORSO) BankLink.NON_RIUSCITA else s
        }?.toMap().orEmpty()
        val access = AccessState.entries.firstOrNull { it.name == o.optString("access") } ?: AccessState.UNKNOWN
        val lost = l?.keys()?.asSequence()?.filter { b -> l.optString(b) == BankLink.IN_CORSO.name && b in banks }?.toList().orEmpty()
        if (lost.isNotEmpty()) xyz.grtlabs.pilastri.sync.eb.EbLog.line(xyz.grtlabs.pilastri.sync.eb.EbLog.EB, "restore consent status=in_progress->failed banks=${lost.size}")
        SetupModel(
            entries = steps.map { SetupEntry.Step(it) }, positions = positions, gestioni = gestioni,
            positionsSkipped = o.optBoolean("skipped"), access = if (access == AccessState.REGISTERED) AccessState.UNKNOWN else access,
            chosenBanks = banks, links = links.filterKeys { it in banks }, banksDeferred = o.optBoolean("deferred"),
            failedBank = (if (o.isNull("failed")) null else o.optString("failed").takeIf { it in banks }) ?: lost.firstOrNull(), later = strings("later").toSet(),
        )
    } catch (_: Exception) { SetupModel() }
}
