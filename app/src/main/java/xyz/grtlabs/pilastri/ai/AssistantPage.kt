package xyz.grtlabs.pilastri.ai

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import xyz.grtlabs.pilastri.ui.setup.ActionOutcome
import xyz.grtlabs.pilastri.ui.setup.AndroidClipboardSource
import xyz.grtlabs.pilastri.ui.setup.LocalClipboardSource
import xyz.grtlabs.pilastri.ui.setup.PasteField
import xyz.grtlabs.pilastri.ui.setup.PasteSpec
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import xyz.grtlabs.pilastri.ai.docs.AndroidImageTools
import xyz.grtlabs.pilastri.ai.docs.AndroidPdfPages
import xyz.grtlabs.pilastri.ai.docs.AttachedDocuments
import xyz.grtlabs.pilastri.ai.docs.AttachmentItem
import xyz.grtlabs.pilastri.ai.docs.AttachmentTray
import xyz.grtlabs.pilastri.ai.docs.DocumentPipeline
import xyz.grtlabs.pilastri.ai.docs.FileSupport
import xyz.grtlabs.pilastri.ai.docs.PdfText
import xyz.grtlabs.pilastri.ai.docs.ProcessedDocument
import java.io.File
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.domain.rules.ShippedRules
import xyz.grtlabs.pilastri.ui.LocalAction
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.LocalSemantic
import xyz.grtlabs.pilastri.ui.Motion
import xyz.grtlabs.pilastri.ui.PageHeader
import xyz.grtlabs.pilastri.ui.CardInset
import xyz.grtlabs.pilastri.ui.CardShape
import xyz.grtlabs.pilastri.ui.LocalPal
import xyz.grtlabs.pilastri.ui.PageSpec
import xyz.grtlabs.pilastri.ui.contentRow
import xyz.grtlabs.pilastri.ui.pageContentTag
import xyz.grtlabs.pilastri.ui.pressable
import xyz.grtlabs.pilastri.ui.rememberHx
import java.time.LocalDate

enum class ChangeStatus(val label: String) {
    APPLIED("Applicata"),
    PENDING("In attesa di conferma"),
    UNDONE("Annullata"),
    DISCARDED("Scartata"),
    QUEUED("In coda: salvataggio non riuscito"),
    FAILED("Non applicata"),
}

sealed class ChatEntry {
    val key: Long = nextKey++

    private companion object { var nextKey = 0L }
}

/** [context] is the screen the question came from ("Spese · ottobre"). */
class UserEntry(val text: String, val context: String? = null, val attachments: List<String> = emptyList()) : ChatEntry()

/** [reads] is the "letto dall'archivio" line; [via] the route that answered; [tainted] when third-party text was involved. */
class AssistantEntry(val text: String, val reads: String? = null, val via: String? = null, val tainted: Boolean = false, val at: Long? = null) : ChatEntry()

/** The placeholder shown when a reply carried no text: such a reply has no time and no copy row. */
internal const val NO_REPLY_TEXT = "(nessun testo)"

/** What an info card can offer: sign in again, fix an undelivered question (AG-ERROR: the label comes from its cause), open the setup, manage the plan usage. */
enum class InfoAction { RELINK, FIX, SETUP, MANAGE_USAGE }

/** [cause] is set on the AG-ERROR row of an undelivered question: its [InfoAction.FIX] button is labelled by it. */
class InfoEntry(val text: String, val error: Boolean = true, val actions: List<InfoAction> = emptyList(), val cause: SendCause? = null) : ChatEntry()

class ChangeEntry(change: AiChange, status: ChangeStatus) : ChatEntry() {
    /** Replaced when another change of the same request is merged into this card. */
    var change by mutableStateOf(change)
    var status by mutableStateOf(status)
    var note by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
}

/**
 * A Tier 3 rule row (Council 4 c): one law, step or personal change, confirmed or refused on its own (board G3-5b). It is
 * never merged with another change, and while it waits it keeps the Agente dot on. [status] is PENDING, APPLIED (it can be
 * undone), UNDONE, DISCARDED (refused) or FAILED (the confirmation was refused; the note says why).
 */
class RuleEntry(val change: AiChange, status: ChangeStatus) : ChatEntry() {
    val view get() = change.rule!!
    var status by mutableStateOf(status)
    var note by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
}

/**
 * The automatic categorization after a sync (slice 1b): one quiet line, "12 spese categorizzate · Annulla". It is not a
 * change card, it adds no dot, and its one "Annulla" undoes the whole run.
 */
class AutoCategoryEntry(val change: AiChange, val count: Int, val label: String? = null) : ChatEntry() {
    var undone by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var note by mutableStateOf<String?>(null)
}

/**
 * Conversation state; hoist it to keep the chat while the page is not on screen. Held in memory only. Runs live in [scope],
 * not in the page's composition, so a reply keeps arriving while the user looks at another tab. The host may call
 * [close] when it discards the state for good.
 */
class AssistantState {
    val entries = mutableStateListOf<ChatEntry>()
    /** Text waiting in the composer: the Pilastri and Spese composers hand their question here before switching to Agente. */
    var draft by mutableStateOf("")
    /** The screen the question came from ("Spese · ottobre"), shown with the composer and sent as context. */
    var draftContext by mutableStateOf<String?>(null)
    /** The run's status line ("Claude sta rispondendo…"); cleared whenever a run ends (5a-06). */
    val run = RunStatus()
    val running: Boolean get() = run.running
    val stage: String? get() = run.stage
    /** The provider page is on screen (for a sign-in, a bot check, or because the user asked). */
    var showPage by mutableStateOf(false)
    /** The question that did not go through (AG-ERROR) and its cause; one at a time. */
    internal var pending: PendingSend? = null
    internal var pendingCause: SendCause? = null
    /** A challenge was present while the page was shown: only then does its disappearance close the page (AG-ERROR). */
    internal var challengeSeen = false
    /** Called with the live challenge flag. Closes the page once a seen challenge is gone and returns true then: the caller re-sends the question. */
    internal fun onChallenge(challenge: Boolean): Boolean {
        if (!showPage) { challengeSeen = false; return false }
        if (challenge) { challengeSeen = true; return false }
        if (challengeSeen) {
            challengeSeen = false
            showPage = false
            return true
        }
        return false
    }
    /**
     * Capture seam only (CaptureHook routes "agente-reply" and "agente-nodrive": capture-forced states, never reached by a person).
     * [captureLinked] shows a synthetic linked assistant in place of the provider choice (no WebView session, no account, no network);
     * every other capture route resets it and removes the synthetic entries, so nothing leaks from one frame to the next.
     */
    internal var captureLinked by mutableStateOf(false)
    private var captureSeeded = false
    internal fun prepareForCapture(kind: String?) {
        if (captureSeeded) { entries.clear(); draft = ""; captureSeeded = false }
        captureLinked = kind != null
        when (kind) {
            "reply" -> {
                entries.add(UserEntry("Quanto ho speso a ottobre?"))
                entries.add(AssistantEntry("A ottobre hai speso 1.240 € (esempio sintetico).", via = "Assistente di esempio", at = System.currentTimeMillis()))
                captureSeeded = true
            }
            "nodrive" -> {
                // AG-ERROR sample, cause 2: the question stays in the thread, the error row stands where the answer would be.
                entries.add(UserEntry("Quanto ho speso a ottobre?"))
                onUndelivered(PendingSend("Quanto ho speso a ottobre?", null, null), SendCause.SESSION_ENDED, "Assistente di esempio", "capture")
                captureSeeded = true
            }
        }
    }
    val history = ArrayList<AiMessage>()
    val queue = AiWriteQueue()
    /** Proposed changes still waiting for the user: the Agente button's discreet dot. */
    val waiting: Int get() = entries.count {
        (it is ChangeEntry && (it.status == ChangeStatus.PENDING || it.status == ChangeStatus.QUEUED)) || (it is RuleEntry && it.status == ChangeStatus.PENDING)
    }

    /** Tier 3 rule rows still waiting ("Da confermare · N"). */
    val ruleRowsWaiting: Int get() = entries.count { it is RuleEntry && it.status == ChangeStatus.PENDING }
    internal var web: WebChatSession? = null

    /**
     * Taint of the open conversation, on every route: once a turn read third-party text (or came from an account page, the
     * routine or a paste), every later turn starts tainted until [newConversation].
     */
    val conversationTaint = AiConversationTaint()

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Files and photos waiting above the composer. They are read on the device at once; see [AttachmentTray]. */
    val tray = AttachmentTray(scope, { ctx -> PdfText.init(ctx); DocumentPipeline(AndroidImageTools(ctx), AndroidPdfPages(ctx)) }) { name, why ->
        entries.add(InfoEntry("«$name»: $why"))
    }

    /** "Nuova": the assistant no longer sees earlier turns, so their taint ends too. The provider page is reset by the caller. */
    fun newConversation() {
        history.clear()
        conversationTaint.clear()
    }

    /** A change that already has a card (for example a queued write that went through) is updated, not repeated. */
    fun show(change: AiChange, status: ChangeStatus) {
        // A Tier 3 rule row has its own row, never merged into a request's card.
        if (change.rule != null) {
            val row = entries.filterIsInstance<RuleEntry>().firstOrNull { it.change.id == change.id }
            if (row != null) { row.status = status; row.note = null } else entries.add(RuleEntry(change, status))
            return
        }
        val existing = entries.filterIsInstance<ChangeEntry>().firstOrNull { change.id in it.change.memberIds }
        if (existing != null) { existing.status = status; existing.note = null; return }
        // One card and one Accetta per request: changes of the same state that arrive in the same request merge into one card.
        if (status == ChangeStatus.PENDING || status == ChangeStatus.APPLIED) {
            val lastUser = entries.indexOfLast { it is UserEntry }
            val open = entries.drop(lastUser + 1).filterIsInstance<ChangeEntry>().lastOrNull { it.status == status && !it.busy }
            if (open != null) { open.change = open.change.mergedWith(change); return }
        }
        entries.add(ChangeEntry(change, status))
    }

    /** The quiet line of an automatic categorization run ([AutoCategorizer]). */
    fun showAuto(change: AiChange, count: Int, label: String? = null) {
        if (count > 0) entries.add(AutoCategoryEntry(change, count, label))
    }

    /** The provider page for [account], created on first use and recreated after a renderer crash or an account switch. */
    internal fun webFor(account: WebAccount, context: Context): WebChatSession {
        val current = web
        if (current != null && current.account == account && !current.crashed) return current
        current?.destroy()
        return WebChatSession(account, context).also { web = it; it.load() }
    }

    internal fun dropWeb() { web?.destroy(); web = null; showPage = false }

    fun close() { scope.cancel(); dropWeb(); run.end() }
}

@Composable
fun AssistantPage(
    archive: Archive,
    commit: suspend ((Archive) -> Archive) -> Boolean,
    modifier: Modifier = Modifier,
    state: AssistantState = remember { AssistantState() },
    /** Room the frame needs (the island's right inset, the cover's bottom group); the surface runs under it. */
    insets: PaddingValues = PaddingValues(),
    /** True on the open Fold: the page gutter of [PageSpec]. */
    wide: Boolean = false,
) {
    val context = LocalContext.current
    val settings = remember { AiSettings.create(context) }
    val vault = remember { AiVault.createOrNull(context) }
    val hx = rememberHx()
    val reduce = LocalReduceMotion.current
    remember(context) { ShippedRules.install(context.applicationContext.assets) }
    val latestArchive by rememberUpdatedState(archive)
    val latestCommit by rememberUpdatedState(commit)
    var route by remember { mutableStateOf(settings.route) }
    var refresh by remember { mutableIntStateOf(0) }
    var showSetup by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { route = settings.route; refresh++ }
    // The page's WebView belongs to this activity: it must not outlive it. (Compose disposes the page before ON_DESTROY
    // reaches observers, so the activity's own flags decide: a tab switch keeps the page, finishing or recreating drops it.)
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        onDispose { if (activity == null || activity.isFinishing || activity.isChangingConfigurations || activity.isDestroyed) state.dropWeb() }
    }
    val signInRevision = ChatGptSignIn.revision
    val status = remember(route, refresh, signInRevision, state.captureLinked) {
        // linkState stays NOT_LINKED on purpose: the capture shows the linked chat without opening a provider web session.
        if (state.captureLinked) RouteStatus("collegato", "Assistente di esempio (cattura)", true, AiLinkState.NOT_LINKED) else routeStatus(context, route, settings, vault)
    }

    // AG-ERROR: set below once the runner exists; a fixed cause (sign-in done, challenge gone) sends the same question again by itself.
    var resendFn by remember { mutableStateOf<() -> Unit>({}) }
    val relink = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        route.web?.let { account ->
            if (result.resultCode == Activity.RESULT_OK) {
                settings.markLinked(account, nowIso())
                state.web?.takeIf { it.account == account }?.load()
                if (state.pending != null) resendFn()
                else state.entries.add(InfoEntry("Collegato a ${account.label}. Ora puoi inviare.", error = false))
                hx.confirm()
            }
        }
        refresh++
    }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank()) state.draft = (state.draft.trimEnd() + " " + heard).trim()
    }

    // ---- attachments: Foto (camera), Galleria (photo picker), File (document picker) ----
    val tray = state.tray
    var choiceOpen by rememberSaveable { mutableStateOf(false) }
    var shotPath by rememberSaveable { mutableStateOf<String?>(null) }
    remember(context) {
        // Leftovers of an interrupted capture or render (older than ten minutes) are removed.
        File(context.cacheDir, "attach").listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 600_000L) it.delete() }
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AttachmentTray.MAX_ITEMS)) { uris ->
        uris.forEach { tray.addUri(context, it) }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { tray.addUri(context, it) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = shotPath?.let(::File)
        shotPath = null
        if (file != null) {
            if (ok) tray.addUri(context, FileProvider.getUriForFile(context, "${context.packageName}.attach", file), "image/jpeg", deleteAfter = file) else file.delete()
        }
    }
    fun takePhoto() {
        try {
            val dir = File(context.cacheDir, "attach").apply { mkdirs() }
            val file = File.createTempFile("shot-", ".jpg", dir)
            shotPath = file.absolutePath
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.attach", file))
        } catch (_: ActivityNotFoundException) {
            shotPath?.let { File(it).delete() }
            shotPath = null
            state.entries.add(InfoEntry("Su questo telefono non c'è un'app fotocamera."))
        } catch (_: java.io.IOException) {
            state.entries.add(InfoEntry("Non riesco a preparare la foto."))
        }
    }

    // The provider page is preloaded only for a linked account route, so the hidden view never sits on a sign-in page.
    val account = route.web
    val session = if (account != null && status.linkState == AiLinkState.LINKED) state.webFor(account, context) else null
    LaunchedEffect(session, state.showPage) { session?.shown = state.showPage }
    // Hidden like Cattedra's background chat: the page appears only for a challenge the user must solve, and goes back under
    // the chat as soon as the challenge is gone.
    val challenge = session?.snap?.challenge == true
    LaunchedEffect(challenge, state.showPage) { if (state.onChallenge(challenge)) resendFn() }

    val runner = remember(state) { AssistantRunner(state, context.applicationContext, settings, vault) }
    runner.archive = { latestArchive }
    runner.commit = latestCommit
    runner.onHaptic = { ok -> if (ok) hx.confirm() else hx.reject() }

    fun send() {
        if (tray.busy) return
        val docs = tray.documents()
        // Attachments alone are a question: read them and propose.
        val question = state.draft.trim().ifEmpty { if (docs.isNotEmpty()) "Leggi gli allegati e proponi le modifiche all'archivio." else "" }
        if (question.isEmpty() || state.running) return
        if (!status.ready) {
            state.entries.add(InfoEntry("${route.label}: ${status.label}. ${status.detail}", actions = listOf(if (account != null && status.linkState != AiLinkState.LINKED) InfoAction.RELINK else InfoAction.SETUP)))
            hx.reject()
            return
        }
        val origin = state.draftContext
        state.entries.add(UserEntry(question, origin, docs.map { it.label }))
        state.draft = ""
        state.draftContext = null
        val attached = if (docs.isEmpty()) null else AttachedSet(docs, settings.attachmentRedaction)
        choiceOpen = false
        tray.clear() // the originals are dropped from memory; the run keeps only what it must send
        runner.start(route, question, origin, attached)
    }

    /** AG-ERROR: the button's act succeeded (or was a plain retry): the same question goes again, no new bubble, the composer untouched. */
    fun resend() {
        val p = state.takePending() ?: return
        runner.start(route, p.question, p.origin, p.attached, resend = true)
    }
    resendFn = { resend() }

    fun act(entry: ChangeEntry, undo: Boolean) {
        if (entry.busy) return
        entry.busy = true
        entry.note = null
        state.scope.launch {
            try {
                val outcome = AiCommits.run(latestCommit, if (undo) listOf(0L) else AiCommits.DEFAULT_RETRY_DELAYS_MS) { latest ->
                    if (undo) entry.change.undoFrom(latest) else entry.change.applyTo(latest)
                }
                when (outcome) {
                    is AiCommitResult.Done -> {
                        entry.status = if (undo) ChangeStatus.UNDONE else ChangeStatus.APPLIED
                        state.queue.remove(entry.change)
                        hx.confirm()
                    }
                    is AiCommitResult.Rejected -> {
                        entry.note = outcome.message
                        if (!undo) { entry.status = ChangeStatus.FAILED; state.queue.remove(entry.change) }
                        hx.reject()
                    }
                    is AiCommitResult.Failed -> {
                        entry.note = outcome.message
                        if (!undo) { state.queue.add(entry.change, outcome.message); entry.status = ChangeStatus.QUEUED }
                        hx.reject()
                    }
                }
            } finally {
                entry.busy = false
            }
        }
    }

    fun discard(entry: ChangeEntry) {
        entry.status = ChangeStatus.DISCARDED
        entry.note = null
        state.queue.remove(entry.change)
        hx.tick()
    }

    /** Tier 3: one row at a time. A refused result (invalid pack, changed row) keeps the row with its reason and a reject haptic. */
    fun decideRule(entry: RuleEntry, undo: Boolean) {
        if (entry.busy) return
        if (!undo && !entry.view.confirmable) { hx.reject(); return }
        entry.busy = true
        entry.note = null
        state.scope.launch {
            try {
                val outcome = AiCommits.run(latestCommit, if (undo) listOf(0L) else AiCommits.DEFAULT_RETRY_DELAYS_MS) { latest ->
                    if (undo) entry.change.undoFrom(latest) else entry.change.applyTo(latest)
                }
                when (outcome) {
                    is AiCommitResult.Done -> { entry.status = if (undo) ChangeStatus.UNDONE else ChangeStatus.APPLIED; hx.confirm() }
                    is AiCommitResult.Rejected -> { entry.note = outcome.message; if (!undo) entry.status = ChangeStatus.FAILED; hx.reject() }
                    is AiCommitResult.Failed -> { entry.note = outcome.message; hx.reject() }
                }
            } finally {
                entry.busy = false
            }
        }
    }

    fun refuseRule(entry: RuleEntry) {
        if (entry.busy) return
        entry.status = ChangeStatus.DISCARDED
        entry.note = null
        hx.tick()
    }

    fun undoAuto(entry: AutoCategoryEntry) {
        if (entry.busy || entry.undone) return
        entry.busy = true
        entry.note = null
        state.scope.launch {
            try {
                when (val outcome = AiCommits.run(latestCommit, listOf(0L)) { latest -> entry.change.undoFrom(latest) }) {
                    is AiCommitResult.Done -> { entry.undone = true; hx.confirm() }
                    is AiCommitResult.Rejected -> { entry.note = outcome.message; hx.reject() }
                    is AiCommitResult.Failed -> { entry.note = outcome.message; hx.reject() }
                }
            } finally {
                entry.busy = false
            }
        }
    }

    fun onInfoAction(action: InfoAction) {
        when (action) {
            InfoAction.RELINK -> account?.let { relink.launch(AiWebActivity.loginIntent(context, it)) }
            InfoAction.FIX -> when (state.pendingCause) {
                SendCause.SESSION_ENDED -> account?.let { relink.launch(AiWebActivity.loginIntent(context, it)) }
                SendCause.VERIFICATION -> state.showPage = true // the page opens for the challenge only and closes by itself
                else -> resend()
            }
            InfoAction.MANAGE_USAGE -> ChatGptAccount.openUsage(context)
            InfoAction.SETUP -> showSetup = true
        }
    }

    /*
     * Never linked: the provider choice in place (Council 3 §2 amendment). Lapsed: one inline "Accedi di nuovo · <provider>".
     * Both use the existing sign-ins: Claude's account page, ChatGPT's official sign-in; an API key opens the routes inline.
     */
    fun choose(next: AiRoute) { route = next; settings.route = next; refresh++ }
    fun signIn(target: AiRoute) {
        when (target.kind) {
            AiRoute.Kind.WEB -> target.web?.let { relink.launch(AiWebActivity.loginIntent(context, it)) }
            AiRoute.Kind.PLAN -> ChatGptSignIn.restart(context) // starts, or starts again while a sign-in is waiting for the browser
            else -> showSetup = true
        }
    }

    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().background(cs.surface).padding(insets).pageContentTag()) { // CR-05: the page starts at its header, like Page
        Header(
            route = route,
            status = status,
            running = state.running,
            waiting = state.ruleRowsWaiting,
            onNew = {
                state.newConversation()
                state.entries.add(InfoEntry("Nuova conversazione", error = false))
                session?.let { s -> state.scope.launch { try { s.newConversation() } catch (_: Exception) { } } }
                hx.tick()
            },
            onCloseSetup = { showSetup = false },
            setupOpen = showSetup,
            wide = wide,
        )
        AnimatedVisibility(
            visible = showSetup,
            enter = if (reduce) fadeIn(tween(120)) else expandVertically(Motion.spatial()) + fadeIn(),
            exit = if (reduce) fadeOut(tween(90)) else shrinkVertically(Motion.spatial()) + fadeOut(),
        ) {
            Box(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
                AiConnectionsSection(
                    onRouteChanged = { route = it; refresh++ },
                    onChanged = {
                        val wasReady = status.ready // the state before this change (consent is already saved now)
                        route = settings.route
                        refresh++
                        // Consent (or a sign-in) was the last missing step: the panel closes so the chat is in view again.
                        if (routeStatus(context, route, settings, vault).ready && !wasReady) showSetup = false
                    },
                )
            }
        }
        if (state.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = LocalAction.current.mid, trackColor = Color.Transparent)
        } else Spacer(Modifier.height(2.dp))

        if (showsProviderChoice(status.linkState, state.captureLinked)) { // capture seam: the synthetic linked chat is not the provider choice
            if (!showSetup) Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopStart) { // D-13: anchored top-left like boards 5a/5c
                LaunchedEffect(Unit) { ChatGptSignIn.restore(context) }
                val outcome = ChatGptSignIn.lastOutcome
                ProviderChoice(wide = wide,
                    chatGptBusy = ChatGptSignIn.running,
                    note = when {
                        ChatGptSignIn.running || route != AiRoute.CHATGPT_ACCOUNT -> null
                        else -> ChatGptSignIn.failureText(outcome)
                    },
                    onClaude = { choose(AiRoute.CLAUDE_ACCOUNT); signIn(AiRoute.CLAUDE_ACCOUNT) },
                    onChatGpt = { choose(AiRoute.CHATGPT_ACCOUNT); signIn(AiRoute.CHATGPT_ACCOUNT) },
                    onApiKey = { showSetup = true },
                )
            } else Spacer(Modifier.weight(1f))
        } else {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The provider page under the chat. Covered it takes no touch (shield below and its own touch listener).
            if (session != null) {
                key(session) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> FrameLayout(ctx).also { session.attach(it, ctx) } },
                        update = { container -> if (session.web.parent !== container) session.attach(container, container.context) },
                        onRelease = { session.detach() },
                    )
                }
                DisposableEffect(session) { onDispose { session.detach() } }
            }
            androidx.compose.animation.AnimatedVisibility(visible = !state.showPage || session == null, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
                Box(
                    Modifier.fillMaxSize().background(cs.surface)
                        // A pointer-input node on the cover stops touches from reaching the sibling page below.
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    ChatList(
                        state = state,
                        status = status,
                        route = route,
                        onApply = { act(it, false) },
                        onUndo = { act(it, true) },
                        onDiscard = { discard(it) },
                        onAutoUndo = { undoAuto(it) },
                        onRuleConfirm = { decideRule(it, false) },
                        onRuleUndo = { decideRule(it, true) },
                        onRuleRefuse = { refuseRule(it) },
                        onAction = { onInfoAction(it) },
                        onExample = { state.draft = it },
                    )
                }
            }
            if (state.showPage && session != null) PageBanner(account!!) { state.showPage = false }
        }

        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp).widthIn(max = 760.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                status.ready -> {
            state.draftContext?.let { origin ->
                ContextChip(origin) { state.draftContext = null; hx.tick() }
            }
            // One block for the chips and the choice, emitted only while it has something to show (no empty gap above the composer).
            var blockShown by remember { mutableStateOf(false) }
            val wantBlock = tray.items.isNotEmpty() || choiceOpen
            LaunchedEffect(wantBlock) { if (wantBlock) blockShown = true else { delay(260); blockShown = false } }
            if (blockShown || wantBlock) {
                Column(Modifier.fillMaxWidth().animateContentSize(Motion.spatial())) {
                    AttachmentChips(tray, onRemove = { tray.remove(it); hx.tick() })
                    AttachChoice(
                        visible = choiceOpen && !state.running,
                        onPhoto = { choiceOpen = false; hx.tick(); takePhoto() },
                        onGallery = { choiceOpen = false; hx.tick(); pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onFile = { choiceOpen = false; hx.tick(); pickFiles.launch(arrayOf("application/pdf", "image/*")) },
                    )
                }
            }
            Composer(
                text = state.draft,
                onText = { state.draft = it },
                attachOpen = choiceOpen,
                onAttach = { choiceOpen = !choiceOpen; hx.tick() },
                subtitle = when {
                    tray.busy -> "Leggo gli allegati…"
                    state.running -> state.stage ?: "Sto lavorando…"
                    !status.ready -> "${route.label} · ${status.label}"
                    route.kind == AiRoute.Kind.PLAN -> "contesto: " + (state.draftContext ?: "archivio") + " · usa il piano ChatGPT"
                    else -> "contesto: " + (state.draftContext ?: "archivio") + " · " + route.label
                },
                enabled = !state.running,
                canSend = (state.draft.isNotBlank() || tray.hasAny) && !tray.busy && !state.running,
                onSend = { send() },
                onDictate = {
                    try {
                        dictation.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT"))
                    } catch (_: ActivityNotFoundException) {
                        state.entries.add(InfoEntry("Su questo telefono non c'è un servizio di dettatura: usa il microfono della tastiera."))
                    }
                },
            )
                }
                status.linkState == AiLinkState.EXPIRED -> AiButton("Accedi di nuovo · ${route.providerName}", onClick = { signIn(route) }, modifier = Modifier.fillMaxWidth())
                !settings.optedIn(route) -> ConsentRow(route, settings) { refresh++ }
                else -> AiButton("Completa · ${route.providerName}", onClick = { showSetup = true }, modifier = Modifier.fillMaxWidth())
            }
        }
        }
    }
}

/**
 * Runs one question on the chosen route, in [AssistantState.scope]. Every route ends in the same places: change cards
 * through [AiWriteRouter] callbacks, an assistant entry with what was read, or an info entry saying what went wrong.
 */
private class AssistantRunner(
    val state: AssistantState,
    val app: Context,
    val settings: AiSettings,
    val vault: AiVault?,
) {
    var archive: () -> Archive = { Archive() }
    var commit: suspend ((Archive) -> Archive) -> Boolean = { false }
    var onHaptic: (Boolean) -> Unit = {}

    private val onApplied: (AiChange) -> Unit = { state.show(it, ChangeStatus.APPLIED) }
    private val onPending: (AiChange) -> Unit = { state.show(it, ChangeStatus.PENDING) }
    private val onQueued: (AiChange) -> Unit = { state.show(it, ChangeStatus.QUEUED) }

    fun start(route: AiRoute, question: String, origin: String?, attached: AttachedSet? = null, resend: Boolean = false) {
        state.run.begin()
        state.scope.launch {
            try {
                when (route.kind) {
                    AiRoute.Kind.API -> api(route.api!!, question, origin, attached)
                    AiRoute.Kind.PLAN -> plan(question, origin, attached)
                    AiRoute.Kind.WEB -> web(route.web!!, question, origin, attached, resend)
                    AiRoute.Kind.ROUTINE -> routine(question, origin, attached)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.entries.add(InfoEntry("Errore imprevisto dell'assistente: ${e.message ?: e::class.java.simpleName}"))
            } finally {
                state.run.end()
            }
        }
    }

    private fun show(result: AiRunResult, via: String, plan: Boolean = false) {
        result.failures.forEach { state.entries.add(InfoEntry("${it.summary}: ${it.reason}")) }
        result.error?.let { error ->
            val actions = when {
                // OpenAI's guideline for a plan usage limit: "Gestisci utilizzo" as the primary action.
                plan && result.errorKind == AiTransportException.Kind.RATE_LIMIT -> listOf(InfoAction.MANAGE_USAGE)
                plan && result.errorKind == AiTransportException.Kind.AUTH -> listOf(InfoAction.SETUP)
                else -> emptyList()
            }
            state.entries.add(InfoEntry(error, actions = actions))
        }
        if (result.error == null) state.entries.add(AssistantEntry(result.text.trim().ifEmpty { NO_REPLY_TEXT }, result.readsLine, via, result.tainted, System.currentTimeMillis()))
        onHaptic(result.error == null)
    }

    private suspend fun api(provider: AiProvider, question: String, origin: String?, attached: AttachedSet?) {
        val key = vault?.get(provider)
        if (key == null) {
            state.entries.add(InfoEntry("Non riesco a leggere la chiave salvata: inseriscila di nuovo.", actions = listOf(InfoAction.SETUP)))
            return
        }
        state.run.report("${provider.label} sta rispondendo…")
        val text = origin?.let { "[Domanda partita dalla schermata: $it]\n$question" } ?: question
        val agent = AiAgent(AiTransports.create(provider, key), model = settings.model(provider), queue = state.queue)
        // API keys: the original photo or PDF goes along (option off), with the text read on the phone as a cross-check.
        val documents = attached?.compose(FileSupport.IMAGES_AND_PDF)
        val result = agent.run(text, state.history.toList(), archive(), commit, settings.redactionOn, onApplied, onPending, onQueued,
            conversation = state.conversationTaint, documents = documents)
        show(result, provider.label)
        state.history.addAll(result.historyDelta(text + (documents?.let { "\n\n" + it.textBlock } ?: "")))
    }

    /** ChatGPT through Sign in with ChatGPT: the same agent loop, tools, tiers, taint and undo as the API routes. */
    private suspend fun plan(question: String, origin: String?, attached: AttachedSet?) {
        val session = ChatGptAccount.session(app)
        val store = ChatGptAccount.store(app)
        if (session == null || store == null) {
            state.entries.add(InfoEntry("Il deposito sicuro del telefono non funziona.", actions = listOf(InfoAction.SETUP)))
            return
        }
        val label = AiRoute.CHATGPT_ACCOUNT.label
        state.run.report("ChatGPT sta rispondendo…")
        val text = origin?.let { "[Domanda partita dalla schermata: $it]\n$question" } ?: question
        val agent = AiAgent(ChatGptPlanTransport(session), model = store.model, queue = state.queue)
        // ChatGPT plan: photos go as images; a PDF goes as its extracted text only (the plan backend's file input is not verified).
        val documents = attached?.compose(FileSupport.IMAGES)
        val result = agent.run(text, state.history.toList(), archive(), commit, settings.redactionOn, onApplied, onPending, onQueued,
            conversation = state.conversationTaint, documents = documents)
        show(result, label, plan = true)
        state.history.addAll(result.historyDelta(text + (documents?.let { "\n\n" + it.textBlock } ?: "")))
    }

    private suspend fun web(account: WebAccount, question: String, origin: String?, attached: AttachedSet?, resend: Boolean = false) {
        val session = state.webFor(account, app.let { state.web?.web?.context ?: it })
        state.run.report("Leggo l'archivio…")
        val snapshot = withContext(Dispatchers.Default) { WebContext.build(archive(), question, origin) }
        // The provider's page takes text only here: the text read on the phone goes (redacted when the option is on), no file.
        val documents = attached?.compose(FileSupport.NONE)
        documents?.applyTo(snapshot.turn)
        // Composed at each pass: after a reload the page is a new chat and the message carries the instructions again.
        fun message() = WebPrompt.compose(question, snapshot, LocalDate.now(), first = !session.conversationStarted, draftContext = origin, documents = documents?.textBlock)
        val via = AiRoute.entries.first { it.web == account }.label
        val outcome = deliverWithRetry(
            ask = { readOnly -> session.ask(message(), { state.run.report(it) }, readOnly) },
            canRead = { session.hasPendingSend },
            reload = { session.load() },
            readOnly = resend && session.hasPendingSend,
        )
        when (val r = outcome) {
            is WebAsk.Reply -> {
                session.conversationStarted = true
                state.run.report("Controllo le proposte…")
                // The page keeps its own chat: its taint is carried too (it may outlive a failed «Nuova»).
                session.conversationTaint.carryInto(snapshot.turn)
                val result = try {
                    AiWebTurns.process(WebReplyText.fromAccountPage(r.text, r.external), snapshot.turn, archive(), commit, via, state.queue,
                        onApplied = onApplied, onPending = onPending, onQueued = onQueued, conversation = state.conversationTaint)
                } finally {
                    session.conversationTaint.absorb(snapshot.turn)
                }
                show(result, via)
            }
            else -> {
                // AG-ERROR: one row where the answer would stand; the technical reason goes to logs only.
                if (r === WebAsk.NeedsLogin) settings.markRejected(account, nowIso())
                state.onUndelivered(PendingSend(question, origin, attached), r.cause ?: SendCause.NOT_SENT, account.label, (r as? WebAsk.NotDriven)?.why ?: (r as? WebAsk.Failed)?.why ?: r::class.java.simpleName)
                onHaptic(false)
            }
        }
    }

    private suspend fun routine(question: String, origin: String?, attached: AttachedSet?) {
        val config = vault?.let { RoutineConfig.load(it) }
        val folderUri = settings.routineFolder
        if (config == null || folderUri == null) {
            state.entries.add(InfoEntry("La routine non è collegata del tutto (token o cartella).", actions = listOf(InfoAction.SETUP)))
            return
        }
        state.run.report("Leggo l'archivio…")
        val snapshot = withContext(Dispatchers.Default) { WebContext.build(archive(), question, origin) }
        val documents = attached?.compose(FileSupport.NONE)
        documents?.applyTo(snapshot.turn)
        val message = WebPrompt.compose(question, snapshot, LocalDate.now(), first = true, draftContext = origin, documents = documents?.textBlock)
        state.run.report("La routine sta lavorando (di solito decine di secondi)…")
        val folder = SafExchangeFolder(app, Uri.parse(folderUri), settings.routineFolderName ?: "Pilastri")
        when (val outcome = RoutineAsk({ RoutineFire(config).fire(it) }, folder).ask(message)) {
            is RoutineOutcome.Answer -> {
                val result = AiWebTurns.process(WebReplyText(outcome.text), snapshot.turn, archive(), commit, AiRoute.CLAUDE_ROUTINE.label, state.queue,
                    forcedTaint = "la routine lavora nel cloud con accesso al tuo Drive: le sue proposte richiedono conferma",
                    onApplied = onApplied, onPending = onPending, onQueued = onQueued, conversation = state.conversationTaint)
                show(result, AiRoute.CLAUDE_ROUTINE.label)
            }
            RoutineOutcome.NotAuthorized -> {
                settings.routineRejectedAt = nowIso()
                state.draft = question
                state.entries.add(InfoEntry("Claude ha rifiutato il token della routine: generane uno nuovo sul sito e salvalo.", actions = listOf(InfoAction.SETUP)))
                onHaptic(false)
            }
            is RoutineOutcome.Failed -> {
                state.entries.add(InfoEntry("Routine: ${outcome.why}."))
                onHaptic(false)
            }
        }
    }
}

/** The documents of one send and the option's value at that moment; turned into what a route gets by [compose]. */
internal class AttachedSet(val docs: List<ProcessedDocument>, val redact: Boolean) {
    fun compose(files: FileSupport) = AttachedDocuments.compose(docs, redact, LocalDate.now(), files)
}

// ---- attachments ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentChips(tray: AttachmentTray, onRemove: (AttachmentItem) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tray.items.forEach { item ->
            key(item.id) { AttachmentChip(item, onRemove = { onRemove(item) }, onGone = { tray.finishRemoval(item) }) }
        }
    }
}

/** A chip enters and leaves with a spring (a short fade with reduced motion); the item is dropped from the tray when the exit ends. */
@Composable
private fun AttachmentChip(item: AttachmentItem, onRemove: () -> Unit, onGone: () -> Unit) {
    val action = LocalAction.current
    val reduce = LocalReduceMotion.current
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(item.leaving) { if (item.leaving) shown.targetState = false }
    LaunchedEffect(shown.currentState, shown.targetState) { if (!shown.currentState && !shown.targetState) onGone() }
    AnimatedVisibility(
        visibleState = shown,
        enter = if (reduce) fadeIn(Motion.reducedFade()) else expandHorizontally(Motion.spatial()) + scaleIn(Motion.spatial(), initialScale = 0.85f) + fadeIn(Motion.effects()),
        exit = if (reduce) fadeOut(Motion.reducedFade()) else shrinkHorizontally(Motion.spatial()) + scaleOut(Motion.spatial(), targetScale = 0.85f) + fadeOut(Motion.effects()),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp)
                .pressable(RoundedCornerShape(24.dp), MaterialTheme.colorScheme.primary, onClick = onRemove)
                .semantics { contentDescription = "Allegato: ${item.label}" + (if (item.ready) "" else ", lettura in corso") + ". Tocca per toglierlo." }
                .padding(vertical = 6.dp)
                .background(action.soft, RoundedCornerShape(18.dp))
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!item.ready) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = action.onSoft)
            Text(item.label, style = MaterialTheme.typography.labelMedium, color = action.onSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            GlyphIcon(Glyph.CLOSE, action.onSoft, 16.dp)
        }
    }
}

/** The compact choice that opens above the composer: Foto, Galleria, File. */
@Composable
private fun AttachChoice(visible: Boolean, onPhoto: () -> Unit, onGallery: () -> Unit, onFile: () -> Unit) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if (reduce) fadeIn(Motion.reducedFade()) else expandVertically(Motion.spatial()) + fadeIn(Motion.effects()),
        exit = if (reduce) fadeOut(Motion.reducedFade()) else shrinkVertically(Motion.spatial()) + fadeOut(Motion.effects()),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoicePill("Foto", onPhoto, Modifier.weight(1f))
            ChoicePill("Galleria", onGallery, Modifier.weight(1f))
            ChoicePill("File", onFile, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ChoicePill(label: String, onClick: () -> Unit, modifier: Modifier) {
    val action = LocalAction.current
    Box(
        modifier.heightIn(min = 48.dp)
            .pressable(RoundedCornerShape(24.dp), MaterialTheme.colorScheme.primary, onClick = onClick)
            .background(action.soft, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = action.onSoft) }
}

// ---- pieces ----

@Composable
private fun Header(
    route: AiRoute,
    status: RouteStatus,
    running: Boolean,
    onNew: () -> Unit,
    onCloseSetup: () -> Unit,
    setupOpen: Boolean,
    wide: Boolean,
    waiting: Int = 0,
) {
    val cs = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    // G-12: the shared page header (same style, inset and height as Pilastri, Spese, Futuro, Impostazioni); the route is its
    // subtitle, "Da confermare · N" a state line under it, the help and "Nuova" (or the setup's back) its trailing slot.
    Column(Modifier.padding(start = PageSpec.gutter(wide), end = PageSpec.gutter(wide), top = PageSpec.Top, bottom = 8.dp)) { // 8 dp: the Page spacing the other roots leave under their header (first content on one y)
        PageHeader("Agente", if (status.ready) route.label else "Non collegato") { // K2: the subtitle line carries the state even before a first link
            if (setupOpen) HeaderGlyph(Glyph.BACK, "Indietro", onCloseSetup)
            else if (status.ready) HeaderPill("Nuova", Glyph.PLUS, onNew, enabled = !running)
            // U-15: one "?" per state. The first-run choice below has its own next to its heading (board 5c).
            if (status.ready) HelpButton("Agente", AiHelp.AGENT)
        }
        // "Da confermare · N" only when N > 0 (Council 3 §2): rule rows waiting in the thread below. A state, not a control.
        AnimatedVisibility(waiting > 0,
            enter = if (reduce) fadeIn(Motion.reducedFade()) else expandVertically(Motion.spatial()) + fadeIn(Motion.effects()),
            exit = if (reduce) fadeOut(Motion.reducedFade()) else shrinkVertically(Motion.spatial()) + fadeOut(Motion.effects())) {
            Text("Da confermare · $waiting", style = MaterialTheme.typography.labelMedium, color = cs.onTertiaryContainer,
                modifier = Modifier.padding(bottom = 4.dp).background(cs.tertiaryContainer, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 3.dp))
        }
    }
}

/** The provider's name for the one-line relink ("Accedi di nuovo · Claude"). */
private val AiRoute.providerName: String
    get() = when (this) {
        AiRoute.CLAUDE_ACCOUNT, AiRoute.CLAUDE_API, AiRoute.CLAUDE_ROUTINE -> "Claude"
        AiRoute.CHATGPT_ACCOUNT -> "ChatGPT"
        AiRoute.OPENAI_API -> "OpenAI"
    }

/** Never linked: the choice in place of the chat. Two providers, at most a secondary "Chiave API"; no composer, no suggestions. */
@Composable
private fun ProviderChoice(wide: Boolean, chatGptBusy: Boolean, note: String?, onClaude: () -> Unit, onChatGpt: () -> Unit, onApiKey: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = PageSpec.Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) { // 16 dp gutter, 8 dp gaps (CR-62)
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Collega un assistente", style = MaterialTheme.typography.titleMedium, color = cs.onSurface, modifier = Modifier.semantics { heading() })
            HelpButton("Collega un assistente", AiHelp.CONNECTIONS)
        }
        ProviderOption("Claude", onClaude)
        // The wait is never a trap (K19): the row stays tappable while the browser sign-in is pending, and a tap starts it again.
        ProviderOption(if (chatGptBusy) "ChatGPT · accesso in corso · Riprova" else "ChatGPT", onChatGpt)
        ProviderOption("Chiave API", onApiKey) // L-5/U-21: the same row component as Claude and ChatGPT
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error) }
    }
}

@Composable
private fun ProviderOption(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val pal = LocalPal.current
    val shape = CardShape
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .contentRow(pal, shape)
            .pressable(shape, cs.primary, onClick = onClick, enabled = enabled)
            .semantics { contentDescription = label }
            .padding(horizontal = CardInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = if (enabled) cs.onSurface else cs.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("›", style = MaterialTheme.typography.titleLarge, color = cs.primary)
    }
}

/** A round 48 dp header control with only a glyph; the label is its accessible name. */
@Composable
private fun HeaderGlyph(glyph: Glyph, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(48.dp)
            .pressable(CircleShape, cs.primary, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).background(cs.surfaceContainerLowest, CircleShape), contentAlignment = Alignment.Center) { GlyphIcon(glyph, cs.primary, 20.dp) }
    }
}

@Composable
private fun HeaderPill(label: String, glyph: Glyph, onClick: () -> Unit, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val fg = if (enabled) cs.primary else cs.onSurface.copy(alpha = 0.38f)
    Row(
        Modifier.heightIn(min = 48.dp)
            .pressable(RoundedCornerShape(24.dp), cs.primary, onClick = onClick, enabled = enabled)
            .semantics { contentDescription = label }
            .padding(vertical = 2.dp)
            .background(cs.surfaceContainerLowest, RoundedCornerShape(22.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        GlyphIcon(glyph, fg, 18.dp)
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@Composable
private fun ChatList(
    state: AssistantState,
    status: RouteStatus,
    route: AiRoute,
    onApply: (ChangeEntry) -> Unit,
    onUndo: (ChangeEntry) -> Unit,
    onDiscard: (ChangeEntry) -> Unit,
    onAutoUndo: (AutoCategoryEntry) -> Unit,
    onRuleConfirm: (RuleEntry) -> Unit,
    onRuleUndo: (RuleEntry) -> Unit,
    onRuleRefuse: (RuleEntry) -> Unit,
    onAction: (InfoAction) -> Unit,
    onExample: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val reduce = LocalReduceMotion.current
    LaunchedEffect(state.entries.size) {
        if (state.entries.isNotEmpty()) {
            if (reduce) listState.scrollToItem(state.entries.lastIndex) else listState.animateScrollToItem(state.entries.lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxHeight().widthIn(max = 760.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // The quiet lines of automatic runs do not replace the examples of an empty conversation.
        if (state.entries.all { it is AutoCategoryEntry }) {
            item(key = "empty") { EmptyState(status, route, onAction, onExample, Modifier.animateItem(placementSpec = Motion.travel(androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow, visibilityThreshold = androidx.compose.ui.unit.IntOffset(1, 1))))) }
        }
        items(state.entries, key = { it.key }) { entry ->
            val itemModifier = Modifier.animateItem(fadeInSpec = tween(180), placementSpec = Motion.spatialOffset(), fadeOutSpec = tween(120))
            when (entry) {
                is UserEntry -> UserBubble(entry, itemModifier)
                is AssistantEntry -> AssistantCard(entry, itemModifier)
                is InfoEntry -> InfoCard(entry, onAction, itemModifier)
                is ChangeEntry -> ChangeCard(entry, onApply = { onApply(entry) }, onUndo = { onUndo(entry) }, onDiscard = { onDiscard(entry) }, modifier = itemModifier)
                is AutoCategoryEntry -> AutoCategoryLine(entry, onUndo = { onAutoUndo(entry) }, modifier = itemModifier)
                is RuleEntry -> RuleRow(entry, onConfirm = { onRuleConfirm(entry) }, onRefuse = { onRuleRefuse(entry) }, onUndo = { onRuleUndo(entry) }, modifier = itemModifier)
            }
        }
    }
}

@Composable
private fun EmptyState(status: RouteStatus, route: AiRoute, onAction: (InfoAction) -> Unit, onExample: (String) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!status.ready) return@Column
        SectionLabel("PROVA A CHIEDERE")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Quanto ho nei pilastri e da quando?",
                "Quali rate scadono questo mese?",
                "Categorizza le spese da rivedere",
                "Ho versato 150 € al pilastro 4 oggi",
            ).forEach { example -> AiButton(example, emphasis = false, onClick = { onExample(example) }) }
        }
    }
}

@Composable
private fun UserBubble(entry: UserEntry, modifier: Modifier) {
    val action = LocalAction.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        entry.context?.let { Text("da $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp, bottom = 2.dp)) }
        Box(
            Modifier.widthIn(max = 560.dp)
                .background(action.soft, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.text, style = MaterialTheme.typography.bodyMedium, color = action.onSoft)
                if (entry.attachments.isNotEmpty()) Text(entry.attachments.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = action.onSoft.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun AssistantCard(entry: AssistantEntry, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth()
            .background(cs.surfaceContainerLow, RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entry.via?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = cs.primary) }
        Text(markdownLite(entry.text, cs.surfaceContainerHighest), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
        entry.reads?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
        if (entry.tainted) Text("modifiche da confermare fino a «Nuova»", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        // AG-REPLY: the last row of the reply card, after the reply's own state lines.
        if (entry.text != NO_REPLY_TEXT && entry.text.isNotBlank()) ReplyMetaRow(markdownLite(entry.text, Color.Unspecified).text, entry.at)
    }
}

@Composable
internal fun InfoCard(entry: InfoEntry, onAction: (InfoAction) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantic.current
    // Text edge 63 dp inside the card (the composer text edge): the text starts where the composer's text starts; an action's label starts on the same edge.
    AiCard(modifier) {
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (entry.error) GlyphDisc(Glyph.ALERT, sem.warnContainer, sem.onWarnContainer) else GlyphDisc(Glyph.CHECK, sem.gainContainer, sem.onGainContainer)
            Text(entry.text, style = MaterialTheme.typography.bodySmall, color = cs.onSurface, modifier = Modifier.weight(1f).padding(top = 8.dp, end = 4.dp))
        }
        if (entry.actions.isNotEmpty()) {
            // The pill starts 25 dp before its label (its own 14 dp padding and the card padding of the pill): the label starts on the text's start edge.
            FlowRow(Modifier.padding(start = 33.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entry.actions.forEachIndexed { i, a ->
                    AiButton(
                        when (a) {
                            InfoAction.RELINK -> "Accedi di nuovo"
                            InfoAction.FIX -> entry.cause?.button ?: "Riprova"
                            InfoAction.MANAGE_USAGE -> "Gestisci utilizzo"
                            InfoAction.SETUP -> "Assistenti"
                        },
                        onClick = { onAction(a) },
                        emphasis = i == 0,
                    )
                }
            }
        }
    }
}

/** One quiet line, no card: "12 spese categorizzate · Annulla"; after the undo "… · annullato". */
@Composable
private fun AutoCategoryLine(entry: AutoCategoryEntry, onUndo: () -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val label = entry.label ?: ("${entry.count} " + (if (entry.count == 1) "spesa categorizzata" else "spese categorizzate"))
    Row(modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label + (if (entry.undone) " · annullato" else "") + (entry.note?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        if (!entry.undone) AiButton("Annulla", onClick = onUndo, emphasis = false, enabled = !entry.busy)
    }
}

/** The categories a change creates, each with a "nuova" chip; existing ones are not listed (the diff names them). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewCategories(names: List<String>, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val action = LocalAction.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        names.forEach { name ->
            Row(
                Modifier.background(cs.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "$name, categoria nuova" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(name, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
                Text(
                    "nuova", style = MaterialTheme.typography.labelSmall, color = action.onSoft,
                    modifier = Modifier.background(action.soft, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** "56 categorie da applicare" for a batch, "3 modifiche applicate" for a merged request, the change's own summary for a single one. */
private fun changeTitle(change: AiChange, status: ChangeStatus): String {
    val categories = change.summary.startsWith(CATEGORY_BATCH_SUMMARY)
    val n = if (categories) change.count else change.memberIds.size
    if (!categories && n <= 1) return change.summary
    val noun = if (categories) (if (n == 1) "categoria" else "categorie") else (if (n == 1) "modifica" else "modifiche")
    val done = if (categories) (if (n == 1) "spesa categorizzata" else "spese categorizzate") else (if (n == 1) "modifica applicata" else "modifiche applicate")
    return when (status) {
        ChangeStatus.PENDING -> "$n $noun da applicare"
        ChangeStatus.APPLIED -> "$n $done"
        ChangeStatus.UNDONE -> "$n $noun annullate"
        ChangeStatus.DISCARDED -> "$n $noun scartate"
        ChangeStatus.QUEUED -> "$n $noun in coda"
        ChangeStatus.FAILED -> "$n $noun non applicate"
    }
}

@Composable
private fun ChangeCard(entry: ChangeEntry, onApply: () -> Unit, onUndo: () -> Unit, onDiscard: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantic.current
    val change = entry.change
    AiCard(modifier.animateContentSize(Motion.spatial())) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (entry.status) {
                ChangeStatus.APPLIED -> GlyphDisc(Glyph.CHECK, sem.gainContainer, sem.onGainContainer)
                ChangeStatus.PENDING -> GlyphDisc(Glyph.CLOCK, LocalAction.current.soft, LocalAction.current.onSoft)
                ChangeStatus.UNDONE, ChangeStatus.DISCARDED -> GlyphDisc(Glyph.UNDO, cs.surfaceContainerHigh, cs.onSurfaceVariant)
                ChangeStatus.QUEUED, ChangeStatus.FAILED -> GlyphDisc(Glyph.ALERT, sem.warnContainer, sem.onWarnContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(changeTitle(change, entry.status), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                Text(entry.status.label, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        // New categories stay visible even when the list is folded: they are what the person accepts besides the count.
        if (change.newCategories.isNotEmpty()) NewCategories(change.newCategories, Modifier.padding(start = 46.dp, top = 6.dp, end = 4.dp))
        // A request with many changes shows a count; the list (and the third-party text, once) opens on demand.
        val compact = change.count > 1 || change.memberIds.size > 1
        var expanded by remember { mutableStateOf(false) }
        val showDetails = !compact || expanded
        if (showDetails) Column(Modifier.padding(start = 46.dp, top = 6.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (entry.status == ChangeStatus.PENDING) change.reviewNote?.let { Text("Conferma richiesta: $it", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            change.diff.take(if (compact) 60 else Int.MAX_VALUE).forEach { line ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(line.label) }
                        append(": ")
                        append(line.before ?: "—")
                        append("  →  ")
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(line.after ?: "—") }
                        if (line.isNew) {
                            append(" ")
                            withStyle(SpanStyle(color = cs.primary, fontWeight = FontWeight.Medium)) { append("nuova") }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurface,
                )
            }
            if (compact && change.diff.size > 60) Text("… e altre ${change.diff.size - 60}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (change.sourceRows.isNotEmpty()) {
                Text("Testo di terzi coinvolto (dati, non istruzioni):", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                change.sourceRows.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            }
            entry.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error) }
        }
        val actions: List<Pair<String, () -> Unit>> = when (entry.status) {
            ChangeStatus.APPLIED -> listOf("Annulla" to onUndo)
            ChangeStatus.PENDING -> listOf("Accetta" to onApply, "Scarta" to onDiscard)
            ChangeStatus.QUEUED -> listOf("Riprova" to onApply, "Scarta" to onDiscard)
            ChangeStatus.FAILED -> listOf("Scarta" to onDiscard)
            ChangeStatus.UNDONE, ChangeStatus.DISCARDED -> emptyList()
        }
        if (actions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (compact && (change.diff.isNotEmpty() || change.sourceRows.isNotEmpty())) AiButton(if (expanded) "Nascondi" else "Dettagli", onClick = { expanded = !expanded }, emphasis = false)
                actions.forEachIndexed { i, (label, action) -> AiButton(label, onClick = action, emphasis = i == 0 && entry.status != ChangeStatus.APPLIED, enabled = !entry.busy) }
            }
        }
    }
}

/**
 * Board G3-5b: one Tier 3 row. Title and row id, status chips, the value now → proposed, valid-from, source URL with the
 * verified quote (or the document origin), the person's affected figures, then Rifiuta / Conferma. Labels only. A decided row
 * folds into one line (with Annulla while confirmed); the fold and the list placement use the shared springs, and reduced
 * motion turns them into a short crossfade.
 */
@Composable
private fun RuleRow(entry: RuleEntry, onConfirm: () -> Unit, onRefuse: () -> Unit, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantic.current
    val action = LocalAction.current
    val reduce = LocalReduceMotion.current
    val view = entry.view
    val open = entry.status == ChangeStatus.PENDING || entry.status == ChangeStatus.FAILED
    val small = MaterialTheme.typography.labelSmall
    val strike = SpanStyle(textDecoration = TextDecoration.LineThrough, color = cs.onSurfaceVariant)
    Column(
        modifier.fillMaxWidth()
            .animateContentSize(Motion.spatial())
            .background(cs.surfaceContainerLow, RoundedCornerShape(24.dp))
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!open) {
                if (entry.status == ChangeStatus.APPLIED) GlyphDisc(Glyph.CHECK, sem.gainContainer, sem.onGainContainer)
                else GlyphDisc(Glyph.UNDO, cs.surfaceContainerHigh, cs.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(view.label ?: view.rowId, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
                if (view.label != null) Text(view.rowId, style = small, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                if (!open) Text(
                    when (entry.status) { ChangeStatus.APPLIED -> "Confermata"; ChangeStatus.UNDONE -> "Annullata"; else -> "Rifiutata" } + " · ${view.before} → ${view.after}",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.status == ChangeStatus.APPLIED) AiButton("Annulla", onClick = onUndo, emphasis = false, enabled = !entry.busy)
        }
        if (!open) entry.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error) }
        AnimatedVisibility(
            visible = open,
            enter = if (reduce) fadeIn(Motion.reducedFade()) else expandVertically(Motion.spatial()) + fadeIn(tween(180)),
            exit = if (reduce) fadeOut(Motion.reducedFade()) else shrinkVertically(Motion.spatial()) + fadeOut(tween(120)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (view.verified) RuleChip("verificato", action.soft, action.onSoft, Glyph.CHECK)
                    ruleFieldToVerify(view)?.let { RuleChip(it, sem.warnContainer, sem.onWarnContainer, Glyph.ALERT) }
                    if (!view.confirmable) RuleChip("non confermabile", sem.warnContainer, sem.onWarnContainer, null)
                    view.origin?.let { RuleChip(if (it == "dichiarato") it else "documento", cs.surfaceContainerHigh, cs.onSurfaceVariant, null) }
                }
                Row(
                    Modifier.fillMaxWidth().background(cs.surfaceContainerLowest, RoundedCornerShape(16.dp))
                        .border(1.dp, cs.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Text("Ora nell'app", style = small, color = cs.onSurfaceVariant)
                        Text(buildAnnotatedString { withStyle(strike) { append(view.before) } }, style = MaterialTheme.typography.bodyMedium)
                    }
                    GlyphIcon(Glyph.ARROW, cs.primary, 20.dp)
                    Column(Modifier.weight(1f, fill = false)) {
                        Text("Proposto", style = small, color = cs.onSurfaceVariant)
                        Text(view.after, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.onSurface)
                    }
                }
                view.validFrom?.let { from ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Valida dal", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Text(from, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    }
                }
                val url = view.sourceUrl
                if (url != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Fonte", style = small, color = cs.onSurfaceVariant)
                        Text(url, style = small, color = cs.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        view.quote?.let { Text("«$it»", style = MaterialTheme.typography.bodySmall, color = cs.onSurface) }
                    }
                } else view.origin?.let { o ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Origine", style = small, color = cs.onSurfaceVariant)
                        Text(o, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
                    }
                }
                Column {
                    Text("Cosa cambia", style = small, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
                    if (view.outputs.isEmpty()) Text(view.cards.joinToString(" · ").ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    view.outputs.forEach { o ->
                        Column(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalArrangement = Arrangement.Center) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant.copy(alpha = 0.5f)))
                            Text("${o.card} · ${o.label}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(buildAnnotatedString { withStyle(strike) { append(o.before) } }, style = MaterialTheme.typography.bodyMedium)
                                GlyphIcon(Glyph.ARROW, cs.primary, 16.dp)
                                Text(o.after, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = cs.onSurface)
                            }
                        }
                    }
                }
                entry.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiButton("Rifiuta", onClick = onRefuse, modifier = Modifier.weight(1f), emphasis = false, enabled = !entry.busy)
                    if (entry.status == ChangeStatus.PENDING) {
                        AiButton("Conferma", onClick = onConfirm, modifier = Modifier.weight(1f), emphasis = true, enabled = view.confirmable && !entry.busy)
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleChip(text: String, bg: Color, fg: Color, glyph: Glyph?) {
    Row(
        Modifier.height(22.dp).background(bg, RoundedCornerShape(11.dp)).padding(start = if (glyph != null) 7.dp else 9.dp, end = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (glyph != null) GlyphIcon(glyph, fg, 12.dp)
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

@Composable
private fun PageBanner(account: WebAccount, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(cs.surfaceContainerHigh).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Verifica di ${account.label}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        AiButton("Torna alla chat", onClick = onBack)
    }
}

@Composable
private fun ContextChip(origin: String, onRemove: () -> Unit) {
    val action = LocalAction.current
    Row(
        Modifier.heightIn(min = 48.dp)
            .pressable(RoundedCornerShape(24.dp), MaterialTheme.colorScheme.primary, onClick = onRemove)
            .semantics { contentDescription = "Contesto: $origin. Tocca per toglierlo." }
            .padding(vertical = 6.dp)
            .background(action.soft, RoundedCornerShape(18.dp))
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Da: $origin", style = MaterialTheme.typography.labelMedium, color = action.onSoft, maxLines = 1)
        GlyphIcon(Glyph.CLOSE, action.onSoft, 16.dp)
    }
}

/** The C9 composer pill: deep purple container, sparkle disc, text and context line, dictation and send discs. */
internal const val COMPOSER_HINT = "Scrivi un messaggio"

@Composable
internal fun Composer(
    text: String,
    onText: (String) -> Unit,
    subtitle: String,
    enabled: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onDictate: () -> Unit,
    attachOpen: Boolean,
    onAttach: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    // The plus turns into a cross: a spring, so it follows a quick second tap.
    val angle by animateFloatAsState(if (attachOpen) 45f else 0f, Motion.spatial(), label = "attach-angle")
    val dark = cs.background.luminance() < 0.5f
    val container = if (dark) cs.primaryContainer else lerp(cs.primary, cs.onPrimaryContainer, 0.45f)
    val ink = if (dark) cs.onPrimaryContainer else cs.onPrimary
    val accent = if (dark) cs.primary else cs.primaryContainer
    val onAccent = if (dark) cs.onPrimary else cs.onPrimaryContainer
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).background(container, RoundedCornerShape(29.dp)).padding(start = 4.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The leading disc is the attach control (it replaces the decorative sparkle, which kept no width to spare on the cover).
        Box(
            Modifier.size(48.dp)
                .pressable(CircleShape, onAccent, onClick = onAttach, enabled = enabled)
                .semantics { contentDescription = if (attachOpen) "Chiudi la scelta degli allegati" else "Allega foto o file" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(42.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.graphicsLayer { rotationZ = angle }) { GlyphIcon(Glyph.PLUS, onAccent, 20.dp) }
            }
        }
        Column(Modifier.weight(1f).padding(start = 7.dp, top = 2.dp, bottom = 2.dp)) {
            BasicTextField(
                value = text,
                onValueChange = onText,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = ink, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(ink),
                maxLines = 6,
                // 48 dp tall by itself (two lines of 20 sp fit): the touch target needs no inflation, so it cannot overlap the context line below,
                // and a short question wraps inside the same height.
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Domanda per l'agente" },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text(COMPOSER_HINT, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = ink, maxLines = 1, softWrap = false)
                        inner()
                    }
                },
            )
            Text(subtitle, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = ink.copy(alpha = 0.78f), maxLines = 1)
        }
        ComposerDisc(Glyph.MIC, "Detta", ink.copy(alpha = 0.16f), ink, enabled, onDictate)
        ComposerDisc(Glyph.SEND, "Invia", if (canSend) accent else ink.copy(alpha = 0.16f), if (canSend) onAccent else ink.copy(alpha = 0.5f), canSend, onSend)
    }
}

@Composable
private fun ComposerDisc(glyph: Glyph, label: String, bg: Color, fg: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp)
            .pressable(CircleShape, fg, onClick = onClick, enabled = enabled)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(42.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) { GlyphIcon(glyph, fg, 18.dp) }
    }
}

// ---- glyphs: stroked 24×24 paths (the C9 concept's icons), no icon library ----

internal enum class Glyph(val path: String) {
    SPARKLE("M12 3l1.8 4.7L18.5 9.5l-4.7 1.8L12 16l-1.8-4.7L5.5 9.5l4.7-1.8z"),
    MIC("M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0zM5 11a7 7 0 0 0 14 0M12 18v3"),
    SEND("M12 19V6M6 11l6-6 6 6"),
    CHECK("M5 12l5 5L20 7"),
    CLOCK("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 2"),
    UNDO("M9 14L4 9l5-5M4 9h10a6 6 0 0 1 0 12h-3"),
    ALERT("M12 9v4M12 16.5v.01M12 3l9.5 17h-19z"),
    GLOBE("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18"),
    PLUS("M12 5v14M5 12h14"),
    GEAR("M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2"),
    CLOSE("M6 6l12 12M18 6L6 18"),
    ARROW("M4 12h15M13 6l6 6-6 6"),
    BACK("M20 12H5M11 6l-6 6 6 6"),
    COPY("M9 9h11v11H9zM5 15V4h11"),
}

@Composable
internal fun GlyphIcon(glyph: Glyph, color: Color, size: androidx.compose.ui.unit.Dp) {
    val path = remember(glyph) { PathParser().parsePathString(glyph.path).toPath() }
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, pivot = Offset.Zero) {
            drawPath(path, color, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun GlyphDisc(glyph: Glyph, bg: Color, fg: Color) {
    Box(Modifier.size(36.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) { GlyphIcon(glyph, fg, 18.dp) }
}

/** Bold, inline code and links of the model's markdown as styled text; everything else stays as written. */
internal fun markdownLite(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    val token = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")
    var cursor = 0
    token.findAll(text).forEach { m ->
        append(text.substring(cursor, m.range.first))
        when {
            m.groupValues[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groupValues[1]) }
            m.groupValues[2].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(m.groupValues[2]) }
            else -> { append(m.groupValues[3]); append(" (" + m.groupValues[4] + ")") }
        }
        cursor = m.range.last + 1
    }
    append(text.substring(cursor))
}

/** The provider choice replaces the chat only when never linked and not in the capture seam's synthetic linked chat (routes "agente-reply" / "agente-nodrive"). */
internal fun showsProviderChoice(link: AiLinkState, captureLinked: Boolean): Boolean = link == AiLinkState.NOT_LINKED && !captureLinked

/** The time of a reply: "HH:mm" (24 h) when it arrived today, "<d mmm> · HH:mm" on another day, null (shows nothing) when no time was stored. */
internal fun replyTimeLabel(at: Long?, now: Long = System.currentTimeMillis(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? {
    if (at == null) return null
    val t = java.time.Instant.ofEpochMilli(at).atZone(zone)
    val hm = "%02d:%02d".format(t.hour, t.minute)
    return if (t.toLocalDate() == java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) hm else xyz.grtlabs.pilastri.ui.Fmt.day(t.toLocalDate()) + " \u00B7 " + hm
}

internal object ReplyMetaTag { const val TIME = "ai_reply_time"; const val COPY = "ai_reply_copy" }

/**
 * AG-REPLY, the one row under every assistant reply: the time it arrived (start edge on the reply text's) and one copy button (48 dp).
 * Tap copies [text] (plain, as shown in the bubble); the glyph turns into a tick for 1.5 s, announced, with the confirm haptic. The row
 * has a fixed height, so nothing moves before or after the tap. The write goes through [ClipWrite] unless a test provides [LocalReplyCopy].
 */
@Composable
internal fun ReplyMetaRow(text: String, at: Long?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hx = xyz.grtlabs.pilastri.ui.rememberHx()
    val cs = MaterialTheme.colorScheme
    val copy = LocalReplyCopy.current ?: { t: String -> ClipWrite.copyPlain(context, "Risposta dell'assistente", t) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val time = remember(at) { replyTimeLabel(at) }
    Row(modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (time != null) Text(time, Modifier.testTag(ReplyMetaTag.TIME), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Box(
            Modifier.offset(x = (48.dp - 20.dp) / 2).testTag(ReplyMetaTag.COPY).size(48.dp)
                .pressable(CircleShape, cs.primary, onClick = { copy(text); copied = true; hx.confirm() })
                .semantics { contentDescription = "Copia la risposta"; stateDescription = if (copied) "Copiata" else ""; liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.animation.Crossfade(copied, animationSpec = xyz.grtlabs.pilastri.ui.Motion.effects(), label = "replyCopy") { done ->
                GlyphIcon(if (done) Glyph.CHECK else Glyph.COPY, if (done) cs.primary else cs.onSurfaceVariant, 20.dp)
            }
        }
    }
}

/** Test seam: replaces the clipboard write of [ReplyMetaRow]. */
internal val LocalReplyCopy = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

/** The question that did not go through: sent again, unchanged, when the button's act succeeds (AG-ERROR). */
internal class PendingSend(val question: String, val origin: String?, val attached: AttachedSet?)

/**
 * AG-ERROR: an undelivered question. The question stays in the thread as the user's message and the composer stays empty; ONE error row
 * (cause line + one button) stands where the answer would be, replacing any earlier one. [why] is the technical reason, for logs only.
 */
internal fun AssistantState.onUndelivered(p: PendingSend, cause: SendCause, label: String, why: String) {
    runCatching { android.util.Log.d("Pilastri.Agente", "undelivered cause=$cause why=$why") }
    entries.removeAll { it is InfoEntry && it.cause != null }
    pending = p
    pendingCause = cause
    entries.add(InfoEntry(cause.line(label), actions = listOf(InfoAction.FIX), cause = cause))
}

/** The button's act is done: the error row leaves (the normal waiting state takes over) and the same question is handed back once. */
internal fun AssistantState.takePending(): PendingSend? {
    val p = pending ?: return null
    pending = null
    pendingCause = null
    entries.removeAll { it is InfoEntry && it.cause != null }
    return p
}

/**
 * One pass, and one automatic retry before the row appears, only where a retry can help: no network, page not recognised, no delivery.
 * Nothing sent (not [canRead]): the page is reloaded first. A confirmed send is never sent again: the retry only reads the answer.
 */
internal suspend fun deliverWithRetry(
    ask: suspend (readOnly: Boolean) -> WebAsk,
    canRead: () -> Boolean,
    reload: () -> Unit,
    pause: suspend () -> Unit = { kotlinx.coroutines.delay(3_000) },
    readOnly: Boolean = false,
): WebAsk {
    val first = ask(readOnly)
    val cause = first.cause ?: return first
    val confirmed = canRead()
    val retry = (first is WebAsk.NotDriven || first is WebAsk.Failed) &&
        (cause == SendCause.NO_NETWORK || cause == SendCause.NOT_SENT || (cause == SendCause.NO_ANSWER && !confirmed))
    if (!retry) return first
    if (!confirmed) reload()
    pause()
    return ask(canRead())
}
