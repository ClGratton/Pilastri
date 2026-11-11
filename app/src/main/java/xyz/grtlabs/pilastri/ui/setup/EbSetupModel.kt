package xyz.grtlabs.pilastri.ui.setup

import xyz.grtlabs.pilastri.sync.eb.EbAppStatus
import xyz.grtlabs.pilastri.sync.eb.EbAspsp
import xyz.grtlabs.pilastri.sync.eb.EbLog
import xyz.grtlabs.pilastri.sync.eb.EbPanel
import xyz.grtlabs.pilastri.sync.eb.EbPanelBank
import xyz.grtlabs.pilastri.sync.eb.EbPanelFailure
import xyz.grtlabs.pilastri.sync.eb.EbPanelResult
import xyz.grtlabs.pilastri.sync.eb.EbProductionConfig

/*
 * Wave 16: the approved native Enable Banking setup, EB-1 … EB-7 (DESIGN.md "EB native setup", board output/eb-native-board.html).
 * The one EB setup flow: setup's Scegli banca step and Impostazioni › "Collega un conto" both open it. Pure state and rules here,
 * the page work in [EbSetupRunner] (tested with a fake page), the screens in EbSetupScreens.kt.
 * Never in this state: the e-mail (it stays in the EB-1 field and goes into the page only), the token, the app id, a bank address.
 */

enum class EbScreen(val number: Int, val title: String) {
    EMAIL(1, "La tua e-mail"), LINK(2, "Apri il link"), REGISTER(3, "Registro Pilastri"), BANKS(4, "Scegli la banca"),
    APPROVE(5, "Approva in banca"), CONFIRM(6, "Ancora una conferma"), LINKED(7, "Banca collegata");

    companion object { const val TOTAL = 7 }
}

/** One page of the flow's stack. [bank] and [round] keep the per-bank pages and a repeated picker distinct. */
data class EbStep(val screen: EbScreen, val bank: String? = null, val round: Int = 0) {
    val key: String get() = "eb:${screen.name}:${bank.orEmpty()}:$round"
}

/** EB-3's checklist, ticking itself. */
enum class EbCheck(val label: String) {
    SIGNED_IN("Accesso fatto"), KEY("Chiave creata nel telefono"), REGISTERED("Pilastri registrata"), ACTIVATED("Attivazione con i tuoi conti")
}

/** The board's failure table: one line, one action (two where the board gives two). */
enum class EbFailKind(val primary: String, val secondary: String?) {
    UNREACHABLE("Riprova", "Apri la pagina"),
    HTTP_ERROR("Riprova", "Apri la pagina"),
    /** An exception of Pilastri's own page script, never the provider's refusal. */
    INTERNAL("Riprova", "Apri la pagina"),
    RATE_LIMIT("Riprova", null),
    LINK_INVALID("Invia un nuovo link", null),
    REFUSED("Riprova", "Apri la pagina"),
    EXPIRED("Invia un nuovo link", null),
    NOT_LINKED("Riprova", "Altra banca"),
    NO_ACCOUNTS("Riprova", null),
    SCA_DENIED("Riprova", "Dopo"),
    /** Pilastri was resumed after the bank's page and no return arrived within the grace: "Riprova" starts a NEW authorization. */
    NO_RETURN("Riprova", "Dopo"),
    /** The bank's page did not load: not the bank's refusal. */
    PAGE_UNREACHABLE("Riprova", "Dopo"),
    /** Not in the board's table: the Keystore key could not be created (EB-1 creates it). */
    KEY("Riprova", null),
    /** Kept only for an empty registration config (coordinator, 2026-11-09); the shipped config is complete. */
    CONFIG_MISSING("Dopo", null),
}

/** The one wording of "a party did not answer" (a page or a service that did not load), for every role. */
fun notResponding(name: String) = "$name non risponde"

/** Where a script of ours failed, in the one wording of the step ("Errore di Pilastri nel collegamento dei conti"). */
fun ownStep(at: EbScreen?): String = when (at) {
    EbScreen.EMAIL -> "nell'invio dell'e-mail"
    EbScreen.LINK -> "nell'apertura del link"
    EbScreen.BANKS -> "nell'elenco delle banche"
    EbScreen.APPROVE, EbScreen.CONFIRM -> "nel collegamento dei conti"
    else -> "nella registrazione"
}

data class EbFailure(val kind: EbFailKind, val bank: String? = null, val reason: String? = null, val status: Int? = null, val at: EbScreen? = null) {
    val message: String get() = when (kind) {
        EbFailKind.UNREACHABLE -> notResponding("Enable Banking")
        EbFailKind.PAGE_UNREACHABLE -> notResponding(bank ?: "La banca")
        EbFailKind.HTTP_ERROR -> "Enable Banking ha risposto con un errore" + (status?.let { " ($it)" } ?: "")
        EbFailKind.INTERNAL -> "Errore di Pilastri " + ownStep(at)
        EbFailKind.RATE_LIMIT -> "Troppi invii: riprova tra qualche minuto"
        EbFailKind.LINK_INVALID -> "Il link non vale più"
        EbFailKind.REFUSED -> "Enable Banking ha rifiutato la registrazione" + (reason?.let { ": $it" } ?: "")
        EbFailKind.EXPIRED -> "Accesso a Enable Banking scaduto"
        EbFailKind.NOT_LINKED -> "$bank non ha collegato i conti"
        EbFailKind.NO_ACCOUNTS -> "Nessun conto collegato da $bank"
        EbFailKind.SCA_DENIED -> "$bank: accesso ai movimenti non autorizzato"
        EbFailKind.NO_RETURN -> "La banca non ha mandato l'esito a Pilastri"
        EbFailKind.KEY -> "Chiave del telefono non creata"
        EbFailKind.CONFIG_MISSING -> "Pagine privacy/termini mancanti"
    }
}

data class EbFlowModel(
    val stack: List<EbStep> = listOf(EbStep(EbScreen.EMAIL)),
    val working: Boolean = false,
    val failure: EbFailure? = null,
    val checks: Set<EbCheck> = emptySet(),
    /** The picker's choice in this round. */
    val chosen: List<String> = emptyList(),
    /** Banks still to approve in this round; the first is the one on EB-5/EB-6. */
    val queue: List<String> = emptyList(),
    /** Every bank the person asked for in this run (for "Da approvare" when the run ends early). */
    val asked: List<String> = emptyList(),
    /** Usage type per bank when the person had a choice ("personal" otherwise). */
    val psu: Map<String, String> = emptyMap(),
    /** Banks linked in this run: accounts read, null when unknown. */
    val linked: Map<String, Int?> = emptyMap(),
    /** EB-2: when the link was sent (Rimanda after 60 s). */
    val sentAt: Long? = null,
    /** Rate limit: Riprova waits until then. */
    val retryAt: Long? = null,
    val round: Int = 0,
    /** Banks whose "already linked" skip is off for this run (step 6 found no accounts after a skip). Memory only, never stored. */
    val noSkip: Set<String> = emptySet(),
    /**
     * An authorization page for the current bank was started and has no outcome yet. Stored with the flow: a restore that finds it
     * set knows the page is gone and shows the named failure with its retry (nothing starts by itself).
     */
    val consentOpen: Boolean = false,
    /** Capture seam only: the flow starts with the paste page open (the live flow keeps that state in the screen). */
    val pasteOpen: Boolean = false,
) {
    init { require(stack.isNotEmpty()) }
    val top: EbStep get() = stack.last()
    val screen: EbScreen get() = top.screen
    val current: String? get() = queue.firstOrNull()
}

object EbFlowRules {
    const val RESEND_MS = 60_000L
    const val RATE_WAIT_MS = 120_000L
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

    /** Registered on this device: straight to the picker (the panel session is reused; a lost one asks for a new link). */
    fun start(registered: Boolean): EbFlowModel =
        if (registered) EbFlowModel(stack = listOf(EbStep(EbScreen.BANKS)), checks = setOf(EbCheck.SIGNED_IN, EbCheck.KEY, EbCheck.REGISTERED)) else EbFlowModel()

    fun validEmail(email: String): Boolean = EMAIL.matches(email.trim())

    fun push(m: EbFlowModel, step: EbStep): EbFlowModel =
        if (m.stack.any { it.key == step.key }) m.copy(working = false, failure = null) else m.copy(stack = m.stack + step, working = false, failure = null)

    /** Back: the exact pop; at the root the flow's host decides (Scegli banca's own Back). */
    fun back(m: EbFlowModel): EbFlowModel = if (m.stack.size > 1) m.copy(stack = m.stack.dropLast(1), working = false, failure = null) else m

    fun working(m: EbFlowModel): EbFlowModel = m.copy(working = true, failure = null)
    fun idle(m: EbFlowModel): EbFlowModel = m.copy(working = false)

    fun fail(m: EbFlowModel, f: EbFailure, now: Long = 0L): EbFlowModel =
        m.copy(working = false, failure = f, retryAt = if (f.kind == EbFailKind.RATE_LIMIT) now + RATE_WAIT_MS else m.retryAt)

    /** The panel's failure as the board's row; [bank] is the bank of EB-5 when the failure came from linking. */
    fun failureOf(f: EbPanelResult.Failed, bank: String? = null, at: EbScreen? = null): EbFailure = when (f.failure) {
        EbPanelFailure.UNREACHABLE, EbPanelFailure.PAGE_CHANGED -> EbFailure(EbFailKind.UNREACHABLE)
        EbPanelFailure.HTTP_ERROR -> EbFailure(EbFailKind.HTTP_ERROR, status = f.status)
        EbPanelFailure.INTERNAL -> EbFailure(EbFailKind.INTERNAL, at = at)
        EbPanelFailure.RATE_LIMIT -> EbFailure(EbFailKind.RATE_LIMIT)
        EbPanelFailure.LINK_INVALID -> EbFailure(EbFailKind.LINK_INVALID)
        EbPanelFailure.EXPIRED -> EbFailure(EbFailKind.EXPIRED)
        EbPanelFailure.REFUSED -> if (bank != null) EbFailure(EbFailKind.NOT_LINKED, bank) else EbFailure(EbFailKind.REFUSED, reason = f.reason)
        EbPanelFailure.NOT_LINKED -> EbFailure(EbFailKind.NOT_LINKED, bank)
        EbPanelFailure.NO_ACCOUNTS -> EbFailure(EbFailKind.NO_ACCOUNTS, bank)
        EbPanelFailure.CONFIG_MISSING -> EbFailure(EbFailKind.CONFIG_MISSING)
    }

    fun canRetry(m: EbFlowModel, now: Long): Boolean = m.retryAt == null || now >= m.retryAt

    /* EB-1, EB-2 */

    fun keyReady(m: EbFlowModel): EbFlowModel = m.copy(checks = m.checks + EbCheck.KEY)
    fun emailSent(m: EbFlowModel, now: Long): EbFlowModel = push(m, EbStep(EbScreen.LINK, round = m.round)).copy(sentAt = now, retryAt = null)
    fun resent(m: EbFlowModel, now: Long): EbFlowModel = m.copy(working = false, failure = null, sentAt = now)
    fun canResend(m: EbFlowModel, now: Long): Boolean = m.sentAt != null && now - m.sentAt >= RESEND_MS
    /** Seconds until "Rimanda" is offered, for its "· 0:45" label. */
    fun resendIn(m: EbFlowModel, now: Long): Long = m.sentAt?.let { ((it + RESEND_MS - now).coerceAtLeast(0) + 999) / 1000 } ?: 0

    /** "Cambia e-mail" and "Invia un nuovo link": back to EB-1 (a new round, so the stack never repeats a key). */
    fun newLink(m: EbFlowModel): EbFlowModel {
        val i = m.stack.indexOfLast { it.screen == EbScreen.EMAIL }
        return if (i >= 0 && m.screen == EbScreen.LINK) m.copy(stack = m.stack.take(i + 1), working = false, failure = null, sentAt = null)
        else push(m.copy(round = m.round + 1), EbStep(EbScreen.EMAIL, round = m.round + 1)).copy(sentAt = null)
    }

    fun signedIn(m: EbFlowModel): EbFlowModel = push(m.copy(checks = m.checks + EbCheck.SIGNED_IN), EbStep(EbScreen.REGISTER, round = m.round))

    /* EB-3 */

    /** Registered: a sign-in that interrupted a bank (expired session) resumes that bank; otherwise the picker. */
    fun registered(m: EbFlowModel, active: Boolean): EbFlowModel {
        val ticked = m.copy(checks = m.checks + EbCheck.REGISTERED + (if (active) setOf(EbCheck.ACTIVATED) else emptySet()))
        val bank = m.current
        return if (bank != null) push(ticked, EbStep(EbScreen.APPROVE, bank, m.round)) else push(ticked, EbStep(EbScreen.BANKS, round = m.round))
    }

    /* EB-4 */

    fun toggle(m: EbFlowModel, bank: String): EbFlowModel = m.copy(chosen = if (bank in m.chosen) m.chosen - bank else m.chosen + bank)

    fun banksChosen(m: EbFlowModel): EbFlowModel {
        val queue = m.chosen.filter { it !in m.linked }
        if (queue.isEmpty()) return m
        return push(m.copy(queue = queue, asked = (m.asked + queue).distinct()), EbStep(EbScreen.APPROVE, queue.first(), m.round))
    }

    /**
     * The usage types offered for one bank: "personal"; "business" only when the person's Posizione includes self-employment and
     * the bank offers it (then both, as one compact choice), or when the bank offers nothing else.
     */
    fun psuOptions(selfEmployed: Boolean, offered: List<String>): List<String> {
        val personal = offered.isEmpty() || "personal" in offered
        val business = "business" in offered
        return when {
            selfEmployed && personal && business -> listOf("personal", "business")
            !personal && business -> listOf("business")
            else -> listOf("personal")
        }
    }

    fun psuFor(m: EbFlowModel, bank: String, options: List<String>): String = m.psu[bank]?.takeIf { it in options } ?: options.first()
    fun choosePsu(m: EbFlowModel, bank: String, type: String): EbFlowModel = m.copy(psu = m.psu + (bank to type))

    /* EB-5, EB-6 */

    fun accountsLinked(m: EbFlowModel): EbFlowModel {
        val bank = m.current ?: return m
        return push(m.copy(checks = m.checks + EbCheck.ACTIVATED), EbStep(EbScreen.CONFIRM, bank, m.round))
    }

    /**
     * THE decision "this bank is already linked at Enable Banking" (step-5 entry and a resumed step 5): the application is active
     * and either an account mentions the bank, or accounts exist and this device holds no session for any bank yet.
     */
    fun alreadyLinked(s: EbAppStatus, hasBankSession: Boolean): Boolean = s.active && (s.here >= 1 || (s.linked >= 1 && !hasBankSession))

    /** After a clean bank return: linked accounts exist and none went missing; an inactive application or none linked is not linked. */
    fun linkedAfterReturn(s: EbAppStatus, before: Int): Boolean = s.active && s.linked >= 1 && s.linked >= before

    /** Step 6 found no accounts for a bank the skip had sent there: back to step 5 for that bank, skip off for this run (once). */
    fun relink(m: EbFlowModel, bank: String): EbFlowModel = back(m.copy(noSkip = m.noSkip + bank))

    /** The authorization page opened (or reopened): until its outcome, a restore must not trust it. */
    fun consentOpened(m: EbFlowModel): EbFlowModel = m.copy(consentOpen = true, working = false, failure = null)

    /** The authorization ended (any outcome): nothing in flight. */
    fun consentEnded(m: EbFlowModel): EbFlowModel = if (m.consentOpen) m.copy(consentOpen = false) else m

    /** A restored step 6 whose page was in flight: the page died with the process, so the bank shows the named failure and its retry. */
    fun restoredConsent(m: EbFlowModel): EbFlowModel =
        if (m.screen == EbScreen.CONFIRM && m.consentOpen) m.copy(consentOpen = false, working = false, failure = EbFailure(EbFailKind.SCA_DENIED, m.current)) else m

    fun consentDone(m: EbFlowModel, accounts: Int?): EbFlowModel {
        val bank = m.current ?: return m
        return next(m.copy(linked = m.linked + (bank to accounts), consentOpen = false))
    }

    /** Back in Pilastri, the grace passed and the bank sent nothing: a named failure; the authorization itself stays pending until "Riprova" ends it. */
    fun noReturn(m: EbFlowModel): EbFlowModel =
        if (m.screen == EbScreen.CONFIRM && m.consentOpen) m.copy(consentOpen = false, working = false, failure = EbFailure(EbFailKind.NO_RETURN, m.current)) else m

    /** The wait after a resume before the missing return is a failure (the real return came 38 ms after the resume). */
    const val RETURN_GRACE_MS = 2_000L

    /** SCA failed, "Dopo": the bank stays n/d (it waits as "Da approvare") and the queue moves on. */
    fun skipBank(m: EbFlowModel): EbFlowModel = next(m.copy(consentOpen = false))

    /**
     * "Dopo" leaves one unit only: on a bank's approval with other banks queued it is this bank (it waits as "Da approvare", the queue moves
     * on); anywhere else the unit is this setup step, so the caller leaves the flow (null here).
     */
    fun later(m: EbFlowModel): EbFlowModel? =
        if ((m.screen == EbScreen.APPROVE || m.screen == EbScreen.CONFIRM) && m.queue.size > 1) skipBank(m) else null

    private fun next(m: EbFlowModel): EbFlowModel {
        val rest = m.queue.drop(1)
        val moved = m.copy(queue = rest, working = false, failure = null)
        return if (rest.isEmpty()) push(moved, EbStep(EbScreen.LINKED, round = m.round)) else push(moved, EbStep(EbScreen.APPROVE, rest.first(), m.round))
    }

    /** "Altra banca" after a failed linking: the picker again, without that bank chosen. */
    fun chooseAnother(m: EbFlowModel): EbFlowModel {
        val r = m.round + 1
        return push(m.copy(round = r, chosen = m.chosen - setOfNotNull(m.current), queue = emptyList()), EbStep(EbScreen.BANKS, round = r))
    }

    /* EB-7 */

    /** "Aggiungi un'altra banca": EB-4 … EB-6 again; the panel session is kept, so no new link. */
    fun addAnother(m: EbFlowModel): EbFlowModel {
        val r = m.round + 1
        return push(m.copy(round = r, chosen = emptyList(), queue = emptyList()), EbStep(EbScreen.BANKS, round = r))
    }

    /** Banks the person asked for that are not linked: they wait as "Da approvare". */
    fun pending(m: EbFlowModel): List<String> = m.asked.filter { it !in m.linked }

    /** The bank list the picker shows: the panel's own banks as [EbAspsp] (country IT). */
    fun aspsps(banks: List<EbPanelBank>, country: String = EbPanel.COUNTRY): List<EbAspsp> =
        banks.distinctBy { it.name }.sortedBy { it.name.lowercase() }.map { EbAspsp(it.name, country, null, it.psuTypes, emptyList(), emptyList(), false) }
}

/**
 * The page work behind each screen. Each call returns a transform the screen applies to the LATEST state, so a navigation during
 * the call is never overwritten. The e-mail is a parameter only: never stored here, never logged.
 */
class EbSetupRunner(
    private val panel: EbPanel,
    private val access: BankAccess,
    private val config: EbProductionConfig = EbProductionConfig.PILASTRI,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private suspend fun <T> safe(block: suspend () -> T): T? = try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    private val unreachable = EbPanelResult.Failed(EbPanelFailure.UNREACHABLE)

    /** One line per setup step (`Pilastri.EB`, every build): step, outcome kind, duration. Never the e-mail, link, token or app id. */
    private suspend fun <T> logged(step: String, block: suspend () -> EbPanelResult<T>): EbPanelResult<T> {
        val t0 = now()
        xyz.grtlabs.pilastri.sync.eb.EbLog.line("Pilastri.EB", "step $step start")
        val r = try { block() } catch (e: kotlinx.coroutines.CancellationException) {
            xyz.grtlabs.pilastri.sync.eb.EbLog.line("Pilastri.EB", "step $step cancelled ms=${now() - t0}"); throw e
        }
        xyz.grtlabs.pilastri.sync.eb.EbLog.line("Pilastri.EB", "step $step outcome=" + (if (r is EbPanelResult.Failed) "failed:${r.failure}" + (r.reason?.let { " reason=" + xyz.grtlabs.pilastri.sync.eb.EbLog.scrub(it) } ?: "") else "ok") + " ms=${now() - t0}")
        return r
    }

    /** EB-1: the Keystore key (created if missing), then the e-mail into EB's form. */
    suspend fun sendEmail(email: String): (EbFlowModel) -> EbFlowModel {
        if (safe { access.publicKeyPem() } == null) return { EbFlowRules.fail(it, EbFailure(EbFailKind.KEY)) }
        return when (val r = logged("sendEmail") { safe { panel.requestLink(email.trim()) } ?: unreachable }) {
            is EbPanelResult.Ok -> if (r.value) { m -> EbFlowRules.signedIn(EbFlowRules.keyReady(m)) } else { m -> EbFlowRules.emailSent(EbFlowRules.keyReady(m), now()) }
            is EbPanelResult.Failed -> { m -> EbFlowRules.fail(EbFlowRules.keyReady(m), EbFlowRules.failureOf(r, at = EbScreen.EMAIL), now()) }
        }
    }

    /** EB-2 "Rimanda": the same form again; the screen stays. */
    suspend fun resend(email: String): (EbFlowModel) -> EbFlowModel = when (val r = logged("resend") { safe { panel.requestLink(email.trim()) } ?: unreachable }) {
        is EbPanelResult.Ok -> if (r.value) EbFlowRules::signedIn else { m -> EbFlowRules.resent(m, now()) }
        is EbPanelResult.Failed -> { m -> EbFlowRules.fail(m, EbFlowRules.failureOf(r, at = EbScreen.LINK), now()) }
    }

    /** EB-2: the link the channels picked up. */
    suspend fun openLink(link: String, email: String): (EbFlowModel) -> EbFlowModel = when (val r = logged("openLink") { safe { panel.completeLink(link, email.trim()) } ?: unreachable }) {
        is EbPanelResult.Ok -> EbFlowRules::signedIn
        is EbPanelResult.Failed -> { m -> EbFlowRules.fail(m, EbFlowRules.failureOf(r, at = EbScreen.LINK), now()) }
    }

    /** EB-3: reuse or register, then keep the app id in EbState (as before, through [BankAccess]). */
    suspend fun register(email: String): (EbFlowModel) -> EbFlowModel {
        val pem = safe { access.publicKeyPem() } ?: return { EbFlowRules.fail(it, EbFailure(EbFailKind.KEY)) }
        val known = safe { access.applicationId() }
        return when (val r = logged("register") { safe { panel.register(known, pem, access.redirectUrl, email.trim(), config) } ?: unreachable }) {
            is EbPanelResult.Ok -> if (safe { access.storeRegistration(r.value.appId) } == true) { m -> EbFlowRules.registered(m, r.value.active) }
                else { m -> EbFlowRules.fail(m, EbFailure(EbFailKind.REFUSED, reason = "chiave non accettata")) }
            is EbPanelResult.Failed -> { m -> EbFlowRules.fail(m, EbFlowRules.failureOf(r, at = EbScreen.REGISTER), now()) }
        }
    }

    /** EB-4: the panel's banks; null falls back to the shipped list (BankCatalog). */
    suspend fun banks(): List<EbAspsp>? = (safe { panel.banks() } as? EbPanelResult.Ok)?.value?.takeIf { it.isNotEmpty() }?.let { EbFlowRules.aspsps(it) }

    private fun log(text: String) = EbLog.line(EbLog.EB, text)

    /**
     * EB-5: one place decides "already linked" ([EbFlowRules.alreadyLinked]); otherwise link_accounts, the bank, the return, then the
     * application must be active with linked accounts, none fewer than before. [skip] false: this run's skip is off (step 6 found none).
     */
    suspend fun linkAccounts(bank: String, psuType: String, skip: Boolean = true): (EbFlowModel) -> EbFlowModel {
        fun failed(f: EbPanelResult.Failed): (EbFlowModel) -> EbFlowModel = { m -> EbFlowRules.fail(m, EbFlowRules.failureOf(f, bank, EbScreen.APPROVE), now()) }
        val appId = safe { access.applicationId() } ?: return { m -> EbFlowRules.fail(m, EbFailure(EbFailKind.EXPIRED)) }
        val hasSession = safe { access.hasSession(bank) } ?: false
        val st = when (val s = safe { panel.status(appId, bank) } ?: unreachable) {
            is EbPanelResult.Ok -> s.value
            // the app id is not in this account's list: register again after a new sign-in
            is EbPanelResult.Failed -> return if (s.failure == EbPanelFailure.REFUSED) { m -> EbFlowRules.fail(m, EbFailure(EbFailKind.EXPIRED)) } else failed(s)
        }
        val already = skip && EbFlowRules.alreadyLinked(st, hasSession)
        log("step5 status active=${EbLog.yn(st.active)} linked=${st.linked} here=${st.here} keys=${st.keys} session=${EbLog.yn(hasSession)} skipEnabled=${EbLog.yn(skip)} accountsOfBank=${st.here}" + if (already) " skip=already-linked" else " skip=no reason=" + (if (!skip) "skip_off" else if (!st.active) "not_active" else "no_accounts_here_or_no_session"))
        if (already) return EbFlowRules::accountsLinked
        val before = st.linked
        val url = when (val u = safe { panel.linkAccounts(appId, bank, psuType) } ?: unreachable) {
            is EbPanelResult.Ok -> u.value
            is EbPanelResult.Failed -> return failed(u)
        }
        when (val a = panel.approveAtBank(url, bank)) {
            is EbPanelResult.Ok -> Unit
            is EbPanelResult.Failed -> return failed(a)
        }
        return when (val s = safe { panel.status(appId, bank) } ?: unreachable) {
            is EbPanelResult.Ok -> {
                val ok = EbFlowRules.linkedAfterReturn(s.value, before)
                log("step5 after-return active=${EbLog.yn(s.value.active)} linked=${s.value.linked} here=${s.value.here} before=$before decision=${if (ok) "linked" else "no_accounts"}")
                if (ok) EbFlowRules::accountsLinked else { m -> EbFlowRules.fail(m, EbFailure(EbFailKind.NO_ACCOUNTS, bank)) }
            }
            is EbPanelResult.Failed -> failed(s)
        }
    }

    /**
     * EB-6, start: the existing native API flow (JWT kid = app id, POST /auth). A page address, or the transform to apply now.
     * [sessions] gates it: a live authorization for this bank gives its own address again and starts nothing. [cause]: button | retry | effect | restore.
     */
    internal suspend fun beginConsent(bank: String, fallback: EbAspsp?, sessions: ConsentSessions? = null, cause: String = "button"): Pair<String?, (EbFlowModel) -> EbFlowModel> {
        sessions?.reopen(bank, cause)?.let { live -> return live.url to { m: EbFlowModel -> EbFlowRules.consentOpened(m) } }
        val aspsp = safe { BankSteps.loadBanks(access) }?.let { l -> l.firstOrNull { it.name == bank } ?: SetupRules.matchChosen(listOf(bank), l).firstOrNull()?.let { n -> l.first { it.name == n } } } ?: fallback
        return when (val o = BankSteps.begin(aspsp, access, sessions = sessions, cause = cause)) {
            is BeginOutcome.Page -> o.url to { m: EbFlowModel -> EbFlowRules.consentOpened(m) }
            BeginOutcome.Busy -> null to EbFlowRules::idle
            // a consent for this bank is already stored: it is linked
            BeginOutcome.Blocked -> safe { access.accountCount(bank) }.let { n -> null to { m: EbFlowModel -> EbFlowRules.consentDone(m, n) } }
            BeginOutcome.Failed -> null to { m -> EbFlowRules.fail(m, EbFailure(EbFailKind.SCA_DENIED, bank)) }
        }
    }

    /**
     * EB-6, the redirect: linked only when the client stored the session. No accounts for a bank the "already linked" skip sent here
     * means the skip was wrong: back to step 5 for that bank with the skip off, once per run; a second time it is NO_ACCOUNTS.
     */
    suspend fun completeConsent(bank: String, url: String, aspsp: EbAspsp?): (EbFlowModel) -> EbFlowModel {
        val ok = BankSteps.complete(aspsp ?: EbAspsp(bank, EbPanel.COUNTRY, null, emptyList(), emptyList(), emptyList(), false), url, access)
        return consentResult(bank, ok, if (ok) safe { access.accountCount(bank) } else null)
    }

    /** EB-6, the outcome of a return the app already completed (BankReturnHandler, also after a process death): the same transform as above. */
    fun consentResult(bank: String, ok: Boolean, n: Int?): (EbFlowModel) -> EbFlowModel {
        if (!ok) { log("step6 completeConsent outcome=failed"); return { m -> EbFlowRules.fail(EbFlowRules.consentEnded(m), EbFailure(EbFailKind.SCA_DENIED, bank)) } }
        log("step6 completeConsent outcome=linked accounts=${n ?: "?"}")
        return { m0 ->
            val m = EbFlowRules.consentEnded(m0)
            when {
                n != 0 -> EbFlowRules.consentDone(m, n)
                bank !in m.noSkip -> { log("step6 zero accounts: back to step 5 with skip off"); EbFlowRules.relink(m, bank) }
                else -> EbFlowRules.fail(m, EbFailure(EbFailKind.NO_ACCOUNTS, bank))
            }
        }
    }
}
