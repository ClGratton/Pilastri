package xyz.grtlabs.pilastri.ui.setup

import android.content.Context
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import xyz.grtlabs.pilastri.sync.eb.EbLog
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.grtlabs.pilastri.data.PositionKind
import xyz.grtlabs.pilastri.sync.eb.EbAspsp
import xyz.grtlabs.pilastri.sync.eb.EbPanel
import xyz.grtlabs.pilastri.sync.eb.EbPanelRules
import xyz.grtlabs.pilastri.sync.eb.EbSheet
import xyz.grtlabs.pilastri.sync.eb.WebViewEbPage
import xyz.grtlabs.pilastri.sync.gmail.GmailConnector
import xyz.grtlabs.pilastri.ui.Ic
import xyz.grtlabs.pilastri.ui.LocalPal
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.Motion
import xyz.grtlabs.pilastri.ui.StackHost
import xyz.grtlabs.pilastri.ui.Text
import xyz.grtlabs.pilastri.ui.Type
import xyz.grtlabs.pilastri.ui.pressable
import xyz.grtlabs.pilastri.ui.rememberHx

/*
 * Wave 16: the screens of the approved native Enable Banking setup (board output/eb-native-board.html, EB-1 … EB-7) on the shared
 * setup scaffold. One hidden page ([WebViewEbPage]) lives as long as the flow; it is shown, as a sheet with the provider bar,
 * only for a reCAPTCHA challenge, an MFA code, the bank, or "Apri la pagina". EB-6 is the existing bank consent page.
 */

private sealed interface EbEntry {
    val key: String
    data class Screen(val step: EbStep) : EbEntry { override val key get() = step.key }
    /** The paste page: opened by the wait screen's "Incolla il link" or by the 45 s timeout; Back pops to the wait screen. */
    data object Paste : EbEntry { override val key get() = "eb:paste" }
}

private const val EB_HELP_LINK = "Enable Banking manda un link di accesso alla tua e-mail. Pilastri lo prende da Gmail, dalle notifiche o dagli appunti e lo apre da sé."
private const val EB_HELP_REGISTER = "Pilastri si registra nel tuo account Enable Banking con la chiave creata nel telefono. Si attiva collegando i tuoi conti e vale solo per quelli."
private const val EB_HELP_BANK = "La banca chiede due conferme: la prima collega i conti all'accesso di Pilastri, la seconda permette di leggere i movimenti."
private const val EB_HELP_LINKED = "I movimenti arrivano con la prima lettura; la data dice fin dove sono letti."

/** The flow's action outcome (CR-86): working while a step runs, the failure's one line, otherwise idle. */
/** The one wording of running work per step: the primary's working label and the outcome's Working text. */
internal fun ebWorkingLabel(screen: EbScreen): String = when (screen) {
    EbScreen.EMAIL -> "Invio del link"; EbScreen.LINK -> "Accesso in corso"; EbScreen.REGISTER -> "Registrazione"
    EbScreen.BANKS -> "Collegamento"; EbScreen.APPROVE, EbScreen.CONFIRM -> "In attesa"; EbScreen.LINKED -> "Lettura"
}

/** The one line before a bank opens, on both bank steps. */
internal fun ebOpensLine(bank: String) = "Si apre $bank: conferma nell'app o con il codice. Può chiederlo più volte."

internal fun ebOutcome(m: EbFlowModel): ActionOutcome = when {
    m.failure != null -> ActionOutcome.Failed(m.failure.message)
    m.working || m.screen == EbScreen.CONFIRM && m.consentOpen -> ActionOutcome.Working(ebWorkingLabel(m.screen))
    else -> ActionOutcome.Idle
}

/**
 * The one EB setup flow. [onFinished] gets the banks linked in this run and the ones that wait ("Da approvare"); "Dopo" ends it
 * the same way. [onBack] at the first page returns to the host (setup's previous step, or the app).
 * [initial] is the capture seam: the flow renders that state and starts no page work, no channel and no network call
 * (synthetic states for the gate's captures; nothing reaches Enable Banking).
 */
@Composable
internal fun EbSetupFlow(
    access: BankAccess, positions: List<PositionKind>, onBack: () -> Unit, onFinished: (List<String>, List<String>) -> Unit, initial: EbFlowModel? = null,
    /** Capture/test seam: receives the sign-in link the flow would open (the capture flow starts no page work). */
    onLink: ((String) -> Unit)? = null,
    /** Resume across a kill or an update (EbFlowCodec); null for a bank-only run, a preview and the capture seam. */
    store: SetupProgressStore? = null,
) {
    val capture = initial != null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hx = rememberHx()
    val page = remember { WebViewEbPage(context) }
    // The bank authorization's page view belongs to the flow, not to the page's composition (ConsentSessions).
    val sessions = remember { ConsentSessions() }
    DisposableEffect(Unit) { onDispose { EbLinkInbox.endWait("cancel"); sessions.releaseAll("flow_end"); page.destroy() } }
    val runner = remember { EbSetupRunner(EbPanel(page), access) }
    val restored = remember { if (initial == null) store?.loadEb()?.let(EbFlowCodec::decode) else null }
    var model by remember { mutableStateOf<EbFlowModel?>(restored) }
    // The e-mail lives in the EB-1 field only (remember, not saved): it goes into the page with the sign-in and the registration.
    var email by remember { mutableStateOf("") }
    var pasteOpen by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var pasteOutcome by remember { mutableStateOf<ActionOutcome>(ActionOutcome.Idle) }
    val clip = LocalClipboardSource.current ?: remember(context) { AndroidClipboardSource(context) }
    var aspsps by remember { mutableStateOf<List<EbAspsp>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val selfEmployed = positions.any { it == PositionKind.AUTONOMO_FORFETTARIO || it == PositionKind.AUTONOMO_ORDINARIO }

    LaunchedEffect(initial) {   // keyed: the capture seam swaps `initial` while the flow stays composed
        if (initial != null) { model = initial; email = "nome@esempio.it"; pasteOpen = initial.pasteOpen; return@LaunchedEffect }
        val registered = BankSteps.registered(access) == AccessState.REGISTERED
        if (model == null || model?.screen == EbScreen.EMAIL && registered) model = EbFlowRules.start(registered)
        if (email.isBlank()) GmailConnector.mailboxAddress(context)?.let { if (email.isBlank()) email = it }
    }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val m = model ?: run { Box(Modifier.fillMaxSize().background(LocalPal.current.bg)); return }

    fun apply(t: (EbFlowModel) -> EbFlowModel) {
        val before = model ?: return
        val next = t(before)
        if (next.failure != null && before.failure == null) hx.reject()
        else if (next.screen == EbScreen.LINKED && before.screen != EbScreen.LINKED || next.screen == EbScreen.CONFIRM && before.screen != EbScreen.CONFIRM) hx.confirm()
        model = next
    }
    fun act(block: suspend () -> (EbFlowModel) -> EbFlowModel) {
        if (model?.working == true) return
        model = model?.let(EbFlowRules::working)
        job = scope.launch { apply(block()) }
    }
    LaunchedEffect(model) { if (!capture && store != null) store.saveEb(model?.let(EbFlowCodec::encode)) }
    LaunchedEffect(Unit) { if (restored != null && EbFlowCodec.needsStatusCheck(restored)) act { EbFlowCodec.resumeApprove(EbPanel(page), access, restored.current) } }
    val backOut: () -> Unit = { store?.saveEb(null); onBack() }
    fun leave(done: Boolean) {
        store?.saveEb(null)
        job?.cancel(); page.show(null); sessions.releaseAll("flow_end")
        val cur = model ?: return
        onFinished(cur.linked.keys.toList(), if (done) EbFlowRules.pending(cur) else (EbFlowRules.pending(cur) + cur.chosen).distinct().filter { it !in cur.linked })
    }
    fun aspspOf(bank: String) = aspsps?.firstOrNull { it.name == bank }
    fun register() = act { runner.register(email) }
    fun link(bank: String) {
        val options = EbFlowRules.psuOptions(selfEmployed, aspspOf(bank)?.psuTypes.orEmpty())
        act { runner.linkAccounts(bank, EbFlowRules.psuFor(model ?: m, bank, options), skip = bank !in (model ?: m).noSkip) }
    }
    fun beginConsent(bank: String, cause: String = "button") {
        if (m.working) return
        model = EbFlowRules.working(m)
        job = scope.launch {
            val (url, t) = runner.beginConsent(bank, aspspOf(bank), sessions, cause)
            // D1: the bank's address opens in the person's browser; the step stays on screen with its wait state and its actions.
            if (url != null && !BankBrowser.open(context, url)) { sessions.finish(bank, "no_browser"); apply { EbFlowRules.fail(EbFlowRules.consentEnded(it), EbFailure(EbFailKind.PAGE_UNREACHABLE, bank)) } }
            else apply(t)
        }
    }
    fun retry() {
        val cur = model ?: return
        when (cur.screen) {
            EbScreen.EMAIL -> act { runner.sendEmail(email) }
            EbScreen.LINK -> act { runner.resend(email) }
            EbScreen.REGISTER -> register()
            EbScreen.BANKS -> { aspsps = null; model = cur.copy(failure = null) }
            EbScreen.APPROVE -> cur.current?.let(::link)
            EbScreen.CONFIRM -> cur.current?.let { b ->
                // A bank that sent no return: the old authorization ends and a NEW one starts (never the old address again).
                if (cur.failure?.kind == EbFailKind.NO_RETURN) sessions.finish(b, "restarted")
                beginConsent(b, "retry")
            }
            EbScreen.LINKED -> model = cur.copy(failure = null)
        }
    }
    fun later() { val cur = model ?: return; val n = EbFlowRules.later(cur); if (n != null) { job?.cancel(); page.show(null); sessions.releaseAll("bank_skipped"); model = n } else leave(false) }
    fun failureAction(label: String) {
        val cur = model ?: return
        when (label) {
            "Riprova" -> retry()
            "Apri la pagina" -> { model = cur.copy(failure = null); page.show(EbSheet.FALLBACK, "Enable Banking") }
            "Invia un nuovo link" -> model = EbFlowRules.newLink(cur)
            "Altra banca" -> model = EbFlowRules.chooseAnother(cur)
            "Dopo" -> later()
        }
    }

    // EB-3 runs by itself.
    LaunchedEffect(m.top.key) { if (!capture && m.screen == EbScreen.REGISTER) register() }
    // EB-4: the panel's own list, else the shipped one.
    LaunchedEffect(m.top.key, aspsps == null) {
        if (m.screen == EbScreen.BANKS && aspsps == null) aspsps = (if (capture) null else runner.banks()) ?: runCatching { SetupRules.fallbackBanks(BankCatalog.load(context)) }.getOrDefault(emptyList())
    }
    val inbox = remember { BankReturnInbox.of(context) }
    // EB-2: the existing channels race (Gmail read, mail notification); the paste page is the person's own way, open from the first frame.
    var attempt by remember { mutableIntStateOf(0) }
    val seen = remember { mutableSetOf<String>() }
    // EB-2: the wait card reads "E-mail arrivata" from the moment any channel (Gmail, notification, paste) hands the flow the sign-in link.
    var linkInHand by remember { mutableStateOf(false) }
    val mailSeen by EbLinkInbox.mailSeen.collectAsState()
    val arrived = mailSeen || linkInHand
    LaunchedEffect(m.sentAt, m.screen, m.failure) { linkInHand = false; EbLinkInbox.clearMailSeen() }
    fun take(link: String?) {
        if (link == null || !EbPanelRules.isSignInLink(link) || !seen.add(link) || model?.screen != EbScreen.LINK) return
        linkInHand = true
        if (capture) onLink?.invoke(link) else act { runner.openLink(link, email) }
    }
    /** The text is already in the field; this only judges it and, when it is the link, lets the flow go on. */
    fun judgePaste(r: PasteResult) {
        pasteOutcome = PasteRules.outcome(r)
        if (r is PasteResult.Valid) { hx.confirm(); take(r.link) } else hx.reject()
    }
    fun pasteFromClipboard() {
        val raw = clip.text()
        pasteText = raw?.toString()?.trim().orEmpty()
        judgePaste(PasteRules.classify(raw))
    }
    // On return to the app: only a text clip newer than this sign-in e-mail's request; an older clip is never read.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // D4: back in Pilastri without a return: nothing resets, nothing starts; the step shows the wait and "Apri la banca" opens the same address.
        model?.let { cur ->
            if (!capture && cur.screen == EbScreen.CONFIRM && cur.consentOpen) {
                EbLog.line(EbLog.EB, "return resume without return")
                scope.launch {
                    delay(EbFlowRules.RETURN_GRACE_MS)
                    val now = model
                    if (now != null && now.screen == EbScreen.CONFIRM && now.consentOpen && inbox.pending.value == null) {
                        EbLog.line(EbLog.EB, "return none after grace ms=${EbFlowRules.RETURN_GRACE_MS}")
                        apply { EbFlowRules.noReturn(it) }
                    }
                }
            }
        }
        if (model?.screen != EbScreen.LINK) return@LifecycleEventEffect
        PasteRules.freshLink(clip, model?.sentAt)?.let { l -> if (pasteOpen) pasteText = l; judgePaste(PasteResult.Valid(l)) }
    }
    LaunchedEffect(m.top.key, attempt) {
        if (capture || m.screen != EbScreen.LINK) return@LaunchedEffect
        var shown = false
        val since = System.currentTimeMillis() / 1000 - 120
        while (true) {
            EbLinkInbox.reset(); EbLinkInbox.beginWait()
            var link: String? = null
            try {
                link = EbLinkRace.await(channels = listOf({ GmailConnector.magicLink(context, since) }, { EbLinkInbox.next() }))
            } finally { EbLinkInbox.endWait(if (link != null) "found" else if (!kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.isActive) "cancel" else "timeout") }
            if (link != null) { take(link); break }
            if (!shown) { shown = true; pasteOpen = true }   // the timeout opens the same page once; Back pops it and it stays closed
        }
    }
    LaunchedEffect(m.screen) { if (m.screen != EbScreen.LINK) pasteOpen = false }
    // A failure after the link was taken (G-16, K19): the wait screen shows it with its fix; the paste page never hides it.
    LaunchedEffect(m.failure) { if (m.failure != null) pasteOpen = false }

    // D3: the bank's return (MainActivity -> BankReturnHandler) finished the link; it is taken by the step that started it, also after a process death.
    val returned by inbox.pending.collectAsState()
    LaunchedEffect(returned, m.screen, m.current) {
        val r = returned ?: return@LaunchedEffect
        if (capture || m.screen != EbScreen.CONFIRM || m.current != r.bank) return@LaunchedEffect
        inbox.consume(r); sessions.finish(r.bank, "outcome")
        apply(runner.consentResult(r.bank, r.ok, r.accounts))
    }
    val entries: List<EbEntry> = m.stack.map { EbEntry.Screen(it) } + listOfNotNull(EbEntry.Paste.takeIf { pasteOpen && m.screen == EbScreen.LINK })
    Box(Modifier.fillMaxSize()) {
        StackHost(entries, onBack = {
            when {
                pasteOpen -> pasteOpen = false
                m.stack.size > 1 -> { job?.cancel(); model = EbFlowRules.back(m) }
                else -> backOut()
            }
        }, keyOf = { it.key }, modifier = Modifier.fillMaxSize()) { entry ->
            when (entry) {
                EbEntry.Paste -> EbPastePage(pasteText, pasteOutcome,
                    onText = { t ->
                        pasteText = t
                        val r = PasteRules.classify(t)
                        pasteOutcome = if (r is PasteResult.Valid) PasteRules.outcome(r) else ActionOutcome.Idle
                        if (r is PasteResult.Valid) { hx.confirm(); take(r.link) }
                    },
                    onPaste = ::pasteFromClipboard, onSubmit = { judgePaste(PasteRules.classify(pasteText)) },
                    onRetry = { pasteOpen = false; pasteText = ""; pasteOutcome = ActionOutcome.Idle; EbLinkInbox.reset(); attempt++ },
                    onBack = { pasteOpen = false }, onLater = { leave(false) }, working = m.working)
                is EbEntry.Screen -> EbScreenPage(entry.step, m, email, { email = it }, aspsps, now, selfEmployed, arrived,
                    onBack = { if (m.stack.size > 1) { job?.cancel(); model = EbFlowRules.back(m) } else backOut() },
                    onPrimary = {
                        when (m.screen) {
                            EbScreen.EMAIL -> act { runner.sendEmail(email) }
                            EbScreen.BANKS -> model = EbFlowRules.banksChosen(m)
                            EbScreen.APPROVE -> m.current?.let(::link)
                            EbScreen.CONFIRM -> m.current?.let(::beginConsent)
                            EbScreen.LINKED -> leave(true)
                            else -> Unit
                        }
                    },
                    onLater = ::later, onFailure = ::failureAction,
                    onToggle = { hx.tick(); model = EbFlowRules.toggle(m, it) },
                    onPsu = { bank, type -> hx.tick(); model = EbFlowRules.choosePsu(m, bank, type) },
                    onResend = { act { runner.resend(email) } }, onChangeEmail = { model = EbFlowRules.newLink(m) },
                    onOpenPaste = { pasteOpen = true },
                    onLegal = { terms -> scope.launch { if (!EbPanel(page).openLegal(terms)) page.show(EbSheet.FALLBACK, "Enable Banking") } },
                    onAddAnother = { model = EbFlowRules.addAnother(m) })
            }
        }
        EbPageSheet(page, onCancel = {
            val sheet = page.sheet
            job?.cancel(); page.show(null)
            val cur = model
            model = when {
                cur == null -> null
                sheet == EbSheet.BANK && cur.current != null -> EbFlowRules.fail(cur, EbFailure(EbFailKind.NOT_LINKED, cur.current))
                else -> EbFlowRules.idle(cur)
            }
        })
    }
}

@Composable
private fun EbScreenPage(
    step: EbStep, m: EbFlowModel, email: String, onEmail: (String) -> Unit, aspsps: List<EbAspsp>?, now: Long, selfEmployed: Boolean, arrived: Boolean,
    onBack: () -> Unit, onPrimary: () -> Unit, onLater: () -> Unit, onFailure: (String) -> Unit, onToggle: (String) -> Unit,
    onPsu: (String, String) -> Unit, onResend: () -> Unit, onChangeEmail: () -> Unit, onOpenPaste: () -> Unit,
    onLegal: (Boolean) -> Unit, onAddAnother: () -> Unit,
) {
    val current = step == m.top
    val failure = m.failure?.takeIf { current }
    val working = current && m.working
    val later = DockAction("Dopo", enabled = !working, onClick = onLater)
    val primary: DockAction?
    val secondary: DockAction?
    if (failure != null) {
        val waitRate = failure.kind == EbFailKind.RATE_LIMIT && !EbFlowRules.canRetry(m, now)
        primary = DockAction(failure.kind.primary, enabled = !waitRate) { onFailure(failure.kind.primary) }
        secondary = failure.kind.secondary?.let { s -> DockAction(s) { onFailure(s) } } ?: later.takeIf { failure.kind.primary != "Dopo" }
    } else {
        primary = when (step.screen) {
            EbScreen.EMAIL -> if (working) DockAction(ebWorkingLabel(step.screen), enabled = false, working = true, onClick = {})
                else DockAction("Continua", enabled = EbFlowRules.validEmail(email), description = "Continua, scrivi prima l'e-mail".takeIf { !EbFlowRules.validEmail(email) }, onClick = onPrimary)
            EbScreen.LINK, EbScreen.REGISTER -> if (working) DockAction(ebWorkingLabel(step.screen), enabled = false, working = true, onClick = {}) else DockAction("Continua", enabled = false, onClick = {})
            EbScreen.BANKS -> if (working) DockAction(ebWorkingLabel(step.screen), enabled = false, working = true, onClick = {})
                else DockAction(SetupRules.bankPrimaryLabel(m.chosen.size), enabled = m.chosen.isNotEmpty() && aspsps != null, onClick = onPrimary)
            EbScreen.APPROVE, EbScreen.CONFIRM -> if (working) DockAction(ebWorkingLabel(step.screen), enabled = false, working = true, onClick = {}) else DockAction("Apri la banca", onClick = onPrimary)
            EbScreen.LINKED -> DockAction("Fatto", onClick = onPrimary)
        }
        secondary = if (step.screen == EbScreen.LINKED) null else later
    }
    val subtitle = when (step.screen) {
        EbScreen.EMAIL -> "Per il tuo accesso gratuito a Enable Banking."
        else -> null
    }
    val help = when (step.screen) {
        EbScreen.EMAIL, EbScreen.BANKS -> "Enable Banking" to EbDisclosureText
        EbScreen.LINK -> "Il link" to EB_HELP_LINK
        EbScreen.REGISTER -> "Registro" to EB_HELP_REGISTER
        EbScreen.APPROVE, EbScreen.CONFIRM -> "Due conferme" to EB_HELP_BANK
        EbScreen.LINKED -> "Dati" to EB_HELP_LINKED
    }
    StepScaffold(step.screen.number, EbScreen.TOTAL, onBack, null, step.screen.title, help, working || (current && failure == null && m.consentOpen) || (step.screen == EbScreen.BANKS && aspsps == null),
        primary = primary, secondary = secondary, subtitle = subtitle) { pad ->
        if (step.screen == EbScreen.BANKS) {
            Column(Modifier.fillMaxSize()) {
                if (failure != null) EbFailureLine(ebOutcome(m))
                BankList(m.chosen, aspsps.orEmpty(), emptyMap(), pad, onToggle, enabled = aspsps != null)
            }
            return@StepScaffold
        }
        ScrollBody(pad) {
            when (step.screen) {
                EbScreen.EMAIL -> EmailBody(email, onEmail, enabled = !working, onLegal)
                EbScreen.LINK -> LinkBody(m, EbPanelRules.maskEmail(email.trim()), now, arrived, onResend, onChangeEmail, onOpenPaste)
                EbScreen.REGISTER -> RegisterBody(m)
                EbScreen.APPROVE -> BankBody(step.bank.orEmpty(), second = false, options = EbFlowRules.psuOptions(selfEmployed, aspsps?.firstOrNull { it.name == step.bank }?.psuTypes.orEmpty()), m, onPsu)
                EbScreen.CONFIRM -> BankBody(step.bank.orEmpty(), second = true, options = emptyList(), m, onPsu)
                EbScreen.LINKED -> LinkedBody(m, onAddAnother)
                EbScreen.BANKS -> Unit
            }
            if (failure != null) { Spacer(Modifier.height(12.dp)); EbFailureLine(ebOutcome(m)) }
        }
    }
}

/**
 * The failure's one line, in the neutral role (CR-60 keeps the warning colour for over-pace / over-budget), announced politely;
 * working and idle show nothing here (the scaffold's line runs).
 */
@Composable
private fun EbFailureLine(outcome: ActionOutcome) {
    if (outcome !is ActionOutcome.Failed) return
    val pal = LocalPal.current
    SetupCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically) {
            Text(outcome.reason, style = SType.rowStrong, color = pal.ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatusChip("non riuscito", RowTone.WARN)
        }
    }
}

@Composable
private fun ColumnScope.EmailBody(email: String, onEmail: (String) -> Unit, enabled: Boolean, onLegal: (Boolean) -> Unit) {
    val pal = LocalPal.current
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(email, onEmail, enabled = enabled, singleLine = true, textStyle = SType.value.copy(color = pal.ink), cursorBrush = SolidColor(pal.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(shape).background(pal.card).border(1.5.dp, pal.primary, shape).semantics { contentDescription = "E-mail" },
        decorationBox = { inner ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("E-mail", style = SType.label, color = pal.primary, modifier = Modifier.clearAndSetSemantics { })   // the field's own description names it; a second node would overlap the field node
                Box(Modifier.padding(top = 2.dp)) {
                    if (email.isEmpty()) Text("nome@esempio.it", style = SType.value, color = pal.ink2)
                    inner()
                }
            }
        })
    Spacer(Modifier.height(12.dp))
    // EB's Terms: the person's consent on this native line; the app then submits EB's own form for them (DESIGN, open decision).
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Continuando accetti ", style = SType.label, color = pal.ink2)
        LegalLink("Privacy") { onLegal(false) }
        Text(" e ", style = SType.label, color = pal.ink2)
        LegalLink("Termini") { onLegal(true) }
        Text(" di Enable Banking.", style = SType.label, color = pal.ink2)
    }
}

@Composable
private fun LegalLink(label: String, onClick: () -> Unit) {
    val pal = LocalPal.current
    Box(Modifier.heightIn(min = 48.dp).pressable(RoundedCornerShape(8.dp), pal.pressTint, onClick = onClick).semantics { contentDescription = "$label di Enable Banking" },
        contentAlignment = Alignment.Center) {
        Text(label, style = SType.tag, color = pal.primary)
    }
}

@Composable
private fun LinkBody(m: EbFlowModel, sentTo: String, now: Long, arrived: Boolean, onResend: () -> Unit, onChangeEmail: () -> Unit, onOpenPaste: () -> Unit) {
    val context = LocalContext.current
    // The mail app that will open: Gmail when it is installed (label "Apri Gmail"), else the system's default e-mail app ("Apri la posta"), else none.
    val mail = remember {
        val pm = context.packageManager
        pm.getLaunchIntentForPackage("com.google.android.gm")?.let { "Apri Gmail" to it }
            ?: android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_APP_EMAIL)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).takeIf { pm.resolveActivity(it, 0) != null }?.let { "Apri la posta" to it }
    }
    EbWaitBody(sentTo, arrived, m.working, EbFlowRules.resendIn(m, now).toInt().takeIf { !EbFlowRules.canResend(m, now) } ?: 0,
        openMail = mail?.let { (_, i) -> { runCatching { context.startActivity(i) }; Unit } }, onResend = onResend, onChangeEmail = onChangeEmail, onOpenPaste = onOpenPaste, openMailLabel = mail?.first ?: "Apri Gmail")
}

/** Stable tags of the wait page (EB-2): the four actions and the two pair rows, for the K26 action groups. */
internal object EbWaitTag {
    const val OPEN_MAIL = "eb_link_open_mail"
    const val RESEND = "eb_link_resend"
    const val CHANGE_EMAIL = "eb_link_change_email"
    const val LINK_PAIR = "eb_link_pair_link"
    const val RECOVERY_PAIR = "eb_link_pair_recovery"
    const val CARD = "eb_link_status_card"
}

/**
 * EB-2, the approved board 10b: one status card (title, "Inviata a ...", the progress line, and the recovery pair "Rimanda" /
 * "Cambia e-mail") and under it the link pair (open the mail app, "Incolla il link"), both pairs on the same two-column grid.
 * The card keeps its height in every state (waiting, e-mail arrived, sign-in running: the badge's room is reserved, the line is
 * always 2 dp); while the sign-in runs both pairs are dimmed and not pressable and nothing is added.
 */
@Composable
internal fun EbWaitBody(
    sentTo: String, arrived: Boolean, working: Boolean, resendSeconds: Int, openMail: (() -> Unit)?,
    onResend: () -> Unit, onChangeEmail: () -> Unit, onOpenPaste: () -> Unit, openMailLabel: String = "Apri Gmail",
) {
    val pal = LocalPal.current
    val canResend = resendSeconds <= 0
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.testTag(EbWaitTag.CARD)) {
            SetupCard {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 14.dp)) {
                    Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.animation.Crossfade(arrived, animationSpec = Motion.effects(), label = "waitTitle") { got ->
                            Text(if (got) "E-mail arrivata" else "In attesa dell'e-mail", style = SType.rowTitle, color = pal.ink, maxLines = 1)
                        }
                        Spacer(Modifier.width(8.dp))
                        // The badge's room is always reserved (K16): only its alpha changes.
                        val badge by animateFloatAsState(if (arrived) 1f else 0f, Motion.effects(), label = "badge")
                        Box(Modifier.size(20.dp).graphicsLayer { alpha = badge }.clip(CircleShape).background(pal.goodBg).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                            Icon(Ic.check, null, Modifier.size(12.dp), tint = pal.good)
                        }
                    }
                    Text("Inviata a $sentTo", style = SType.caption, color = pal.ink2, modifier = Modifier.padding(top = 4.dp))
                    Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(2.dp).testTag(EbTag.WAIT)) {
                        WorkingLine(!arrived, Modifier.fillMaxWidth())
                        val done by animateFloatAsState(if (arrived) 0.35f else 0f, Motion.effects(), label = "lineDone")
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = done }.background(pal.good, RoundedCornerShape(1.dp)))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp).testTag(EbWaitTag.RECOVERY_PAIR), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SetupPill(if (canResend) "Rimanda il link" else "Rimanda tra %d:%02d".format(resendSeconds / 60, resendSeconds % 60), PillStyle.Outlined, onResend,
                            Modifier.weight(1f), 48.dp, enabled = canResend && !working, description = "Rimanda il link", tag = EbWaitTag.RESEND)
                        SetupPill("Cambia e-mail", PillStyle.Outlined, onChangeEmail, Modifier.weight(1f), 48.dp, enabled = !working, tag = EbWaitTag.CHANGE_EMAIL)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).testTag(EbWaitTag.LINK_PAIR), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (openMail != null) SetupPill(openMailLabel, PillStyle.Tonal, openMail, Modifier.weight(1f), 56.dp, PillIcons.mail, enabled = !working,
                description = "$openMailLabel, per aprire l'e-mail di Enable Banking", tag = EbWaitTag.OPEN_MAIL)
            SetupPill("Incolla il link", PillStyle.Tonal, onOpenPaste, Modifier.weight(1f), 56.dp, PillIcons.clipboard, enabled = !working,
                description = "Incolla il link, apre la pagina per incollare il link copiato dall'e-mail", tag = EbTag.PASTE_OPEN)
        }
    }
}

@Composable
private fun RegisterBody(m: EbFlowModel) {
    val pal = LocalPal.current
    // The first unticked step is the one running (while working); the rest wait.
    val running = EbCheck.entries.firstOrNull { it !in m.checks }
    SetupCard {
        EbCheck.entries.forEach { c ->
            val done = c in m.checks
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp).semantics(mergeDescendants = true) {
                contentDescription = c.label + if (done) ", fatto" else if (c == running) ", in corso" else ", in attesa"
            }, verticalAlignment = Alignment.CenterVertically) {
                val tick by animateFloatAsState(if (done) 1f else 0f, Motion.selector(), label = "tick")
                Box(Modifier.size(28.dp).clip(CircleShape).background(pal.primary.copy(alpha = tick)).border(1.5.dp, if (done) Color.Transparent else pal.btnLine, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Ic.check, null, Modifier.size(16.dp).graphicsLayer { scaleX = tick; scaleY = tick; alpha = tick }, tint = pal.onPrimary)
                }
                Spacer(Modifier.width(12.dp))
                Text(c.label, style = SType.rowName, color = if (done || c == running) pal.ink else pal.ink2)
            }
        }
    }
}

@Composable
private fun BankBody(bank: String, second: Boolean, options: List<String>, m: EbFlowModel, onPsu: (String, String) -> Unit) {
    val pal = LocalPal.current
    SetupCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(pal.chip), contentAlignment = Alignment.Center) { Text(initials(bank), style = SType.section, color = pal.onChip) }
                Spacer(Modifier.width(12.dp))
                Text(bank, style = SType.rowStrong, color = pal.ink, modifier = Modifier.weight(1f))
                if (second) Text("Conti collegati", style = SType.tag, color = pal.good)
            }
            Spacer(Modifier.height(14.dp))
            BankStepLine("1 di 2", "Collega i conti", active = !second)
            Spacer(Modifier.height(8.dp))
            BankStepLine("2 di 2", if (second) "Permetti a Pilastri di leggere i movimenti" else "Permetti la lettura", active = second)
            if (!second && options.size > 1) {
                Spacer(Modifier.height(12.dp))
                val chosen = EbFlowRules.psuFor(m, bank, options)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { o -> StatusChipToggle(if (o == "business") "Business" else "Personale", o == chosen) { onPsu(bank, o) } }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(ebOpensLine(bank), style = SType.label, color = pal.ink2)
}

@Composable
private fun StatusChipToggle(label: String, on: Boolean, onClick: () -> Unit) {
    val pal = LocalPal.current
    Box(Modifier.heightIn(min = 48.dp).pressable(RoundedCornerShape(20.dp), pal.pressTint, onClick = onClick)
        .semantics { contentDescription = label + if (on) ", scelto" else "" }, contentAlignment = Alignment.Center) {
        StatusChip(label, if (on) RowTone.SOURCE else RowTone.PLAIN)
    }
}

@Composable
private fun BankStepLine(num: String, text: String, active: Boolean) {
    val pal = LocalPal.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (active) pal.chip else pal.card).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(num, style = SType.tag, color = if (active) pal.onChip else pal.ink2)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = SType.rowName, color = if (active) pal.ink else pal.ink2)
    }
}

@Composable
private fun ColumnScope.LinkedBody(m: EbFlowModel, onAddAnother: () -> Unit) {
    val pal = LocalPal.current
    m.linked.forEach { (bank, n) ->
        // The shared AccentCard (CR-61, one implementation per concept).
        xyz.grtlabs.pilastri.ui.AccentCard(Modifier.semantics(mergeDescendants = true) {}) {
            Column(Modifier.weight(1f)) {
                Text(bank, style = SType.label, color = pal.accentLabel)
                Text(when (n) { null -> "Collegata"; 1 -> "1 conto"; else -> "$n conti" }, style = SType.title, color = pal.onAccent)
                // No "dati fino al" before the first read has rows (AGENTS "Connectors"): the read in progress is a thin line.
                WorkingLine(true, Modifier.padding(top = 8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    val shape = RoundedCornerShape(24.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(pal.card).border(2.dp, pal.cardLine, shape)
        .pressable(shape, pal.pressTint, onClick = onAddAnother).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(pal.chip), contentAlignment = Alignment.Center) { Icon(Ic.plus, null, Modifier.size(18.dp), tint = pal.onChip) }
        Spacer(Modifier.width(12.dp))
        Text("Aggiungi un'altra banca", style = SType.rowStrong, color = pal.ink, modifier = Modifier.weight(1f))
        Icon(Ic.chevronRight, null, Modifier.size(18.dp), tint = pal.ink2)
    }
}

/**
 * The hidden page's host. Always composed (the WebView stays attached and runs), offscreen below the window; it slides in as a
 * sheet with the provider bar (initials, title, "Annulla") only while [WebViewEbPage.sheet] is set.
 */
@Composable
private fun EbPageSheet(page: WebViewEbPage, onCancel: () -> Unit) {
    val pal = LocalPal.current
    val reduce = LocalReduceMotion.current
    val sheet = page.sheet
    BackHandler(enabled = sheet != null) { onCancel() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = min(maxHeight, (maxHeight * 0.62f).coerceAtLeast(480.dp))
        val off by animateDpAsState(if (sheet != null) 0.dp else height + 32.dp, if (reduce) Motion.reducedFade() else Motion.spatial(), label = "ebSheet")
        val scrim by animateFloatAsState(if (sheet != null) 0.32f else 0f, Motion.effects(), label = "ebScrim")
        if (sheet != null || scrim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)).pressable(RoundedCornerShape(0.dp), Color.Transparent, onClick = onCancel))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height).offset(y = off)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(pal.card)) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(pal.btnLine))
            }
            val title = page.sheetTitle ?: "Enable Banking"
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(pal.chip), contentAlignment = Alignment.Center) { Text(initials(title), style = SType.tag, color = pal.onChip) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = SType.rowStrong, color = pal.ink)
                    when (sheet) {
                        EbSheet.CHALLENGE -> Text("Enable Banking chiede una conferma", style = SType.label, color = pal.ink2)
                        EbSheet.MFA -> Text("Inserisci il codice di Enable Banking", style = SType.label, color = pal.ink2)
                        else -> Unit
                    }
                }
                Box(Modifier.heightIn(min = 48.dp).pressable(RoundedCornerShape(12.dp), pal.pressTint, onClick = onCancel).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    Text("Annulla", style = SType.tag, color = pal.ink2)
                }
            }
            AndroidView(factory = { page.view.also { (it.parent as? ViewGroup)?.removeView(it) } }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** Capture seam (debug tooling): the EB pages from a synthetic state, nothing runs. */
@Composable
internal fun EbCaptureSeam(activity: android.app.Activity, m: EbFlowModel) =
    androidx.compose.runtime.CompositionLocalProvider(xyz.grtlabs.pilastri.ui.LocalReduceMotion provides true) {   // no endless sweep: the capture settles
        EbSetupFlow(EbBankAccess(activity), emptyList(), onBack = {}, onFinished = { _, _ -> }, initial = m)
    }
