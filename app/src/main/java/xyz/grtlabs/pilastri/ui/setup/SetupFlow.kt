package xyz.grtlabs.pilastri.ui.setup

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.window.layout.FoldingFeature
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.grtlabs.pilastri.ai.AiLinkState
import xyz.grtlabs.pilastri.ai.AiSettings
import xyz.grtlabs.pilastri.ai.AiWebActivity
import xyz.grtlabs.pilastri.ai.ChatGptAccount
import xyz.grtlabs.pilastri.ai.ChatGptSignIn
import xyz.grtlabs.pilastri.ai.WebAccount
import xyz.grtlabs.pilastri.ai.WebAccounts
import xyz.grtlabs.pilastri.ai.docs.AndroidImageTools
import xyz.grtlabs.pilastri.ai.docs.AndroidPdfPages
import xyz.grtlabs.pilastri.ai.docs.AttachmentTray
import xyz.grtlabs.pilastri.ai.docs.DocumentPipeline
import xyz.grtlabs.pilastri.ai.docs.PdfText
import xyz.grtlabs.pilastri.data.ConnectorKind
import xyz.grtlabs.pilastri.data.Gestione
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.sync.ConnectorSpec
import xyz.grtlabs.pilastri.sync.auto.AutoSyncKit
import xyz.grtlabs.pilastri.sync.eb.EbAspsp
import xyz.grtlabs.pilastri.ui.*
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.Switch
import xyz.grtlabs.pilastri.sync.auto.CredentialVault
import xyz.grtlabs.pilastri.ui.screens.HelpButton

/*
 * Slice 5b: the setup steps (boards G3-8a/8b/8c). Each step is a page of one StackHost: a horizontal push, Back the exact
 * pop, predictive back from the shared stack. Only Benvenuto explains; elsewhere labels, states and at most a "?".
 * On the open Fold the left pane is a stationary summary of what setup knows; only the right pane's stack moves.
 */

/** True on two panes: the left summary carries the step count, so the step header does not repeat it. */
private val LocalSetupWide = staticCompositionLocalOf { false }

private val ModelSaver = Saver<SetupModel, String>(save = { SetupCodec.encode(it) }, restore = { SetupCodec.decode(it) })

internal object SType {
    val title = Type.setupTitle
    val lead = Type.setupLead
    val rowTitle = Type.setupRow
    val rowName = Type.setupName
    val rowStrong = Type.setupNameStrong
    val caption = Type.body
    val small = Type.section
    val label = Type.small
    val value = Type.setupValue
    val chip = Type.setupChip
    val chipOn = Type.setupChipOn
    val chipSmall = Type.setupChipSmall
    val chipSmallOn = Type.setupChipSmallOn
    val counter = Type.setupCounter
    val overline = Type.setupOverline
    val primary = Type.setupRow
    val secondary = Type.setupName
    val tag = Type.smallStrong
    val section = Type.button
    val gestione = Type.section
}

internal val CardRadius = RoundedCornerShape(24.dp)

/**
 * The whole setup. [preview] (debug entry on an existing archive) writes nothing and starts no connector that would save.
 * [onDone] saves and leaves; it returns false when the save failed (the step stays, with a "Non salvato" state).
 */
@Composable
internal fun SetupFlow(
    app: App, preview: Boolean, onRootChanged: (Boolean) -> Unit, onRestore: (() -> Unit)?, onDone: suspend (SetupModel) -> Boolean,
    banks: BankMode? = null, onClose: () -> Unit = {},
) {
    val context = LocalContext.current
    val access: BankAccess = remember { EbBankAccess(context) }
    // [banks] != null: a bank-only run ("Aggiungi banca" / "Rinnova") over the app. Benvenuto stays an unseen root.
    // Resume across a kill or an update: only the full setup (not a preview, not a bank-only run) keeps its place in the store.
    val store = remember { if (!preview && banks == null) SetupProgressStore.of(context) else null }
    var model by rememberSaveable(stateSaver = ModelSaver) { mutableStateOf(banks?.let { SetupRules.bankFlow(it.renew) } ?: store?.loadSetup() ?: SetupModel()) }
    var finished by remember { mutableStateOf(false) }
    LaunchedEffect(model) { if (store != null && !finished) store.saveSetup(model) }
    var aspsps by remember { mutableStateOf<List<EbAspsp>>(emptyList()) }; val consents = remember { ConsentSessions() }; DisposableEffect(Unit) { onDispose { consents.releaseAll("flow_end") } }   // one authorization per bank; the page view lives here (ConsentSessions)
    var working by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hx = rememberHx()
    val atRoot = model.entries.size == (if (banks != null) 2 else 1)
    SideEffect { onRootChanged(atRoot) }
    var closing by remember { mutableStateOf(false) }

    fun set(next: SetupModel) {
        if (next.failedBank != null && next.failedBank != model.failedBank) hx.reject()
        if (banks != null && SetupRules.bankRunEnded(next)) {
            // Every path out of the consents reaches Profilo: in a bank-only run that is the end, never shown. It closes through
            // its own flag: `working` is already true inside the consent effect, so finish() would refuse.
            model = next.copy(entries = next.entries.dropLast(1))
            if (!closing) {
                closing = true
                scope.launch { try { onDone(model) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { onClose() } }
            }
            return
        }
        model = next
    }
    fun back() = if (banks != null && atRoot) onClose() else set(SetupRules.back(model))
    fun toProfileIfDone(m: SetupModel): SetupModel =
        if (m.top == SetupEntry.Step(SetupStep.CONSENSI) && SetupRules.consentsDone(m)) SetupRules.push(m, SetupStep.PROFILO) else m

    // Scegli banca: is there a verified application on this device? Then the bank list.
    LaunchedEffect(model.step, model.access) {
        if (model.step == SetupStep.SCEGLI_BANCA && model.access == AccessState.UNKNOWN) {
            val a = BankSteps.registered(access)
            model = model.copy(access = a)
        }
    }
    LaunchedEffect(model.step, model.access, model.list) {
        if (model.step == SetupStep.SCEGLI_BANCA && model.access == AccessState.REGISTERED && model.list == ListState.IDLE) {
            model = model.copy(list = ListState.LOADING)
            val banks = BankSteps.loadBanks(access)
            aspsps = banks.orEmpty()
            model = BankSteps.applyList(model, banks)
        }
    }
    // Consents, one bank at a time: each bank's page is pushed when its authorization address is ready.
    val current = SetupRules.currentBank(model)
    LaunchedEffect(model.top, current, current?.let { model.links[it] }, model.failedBank) {
        val bank = current ?: return@LaunchedEffect
        if (model.top != SetupEntry.Step(SetupStep.CONSENSI) || model.failedBank != null || model.links[bank] != BankLink.IN_ATTESA) return@LaunchedEffect
        working = true
        try {
            if (aspsps.isEmpty()) aspsps = BankSteps.loadBanks(access).orEmpty()
            val outcome = BankSteps.begin(aspsps.firstOrNull { it.name == bank }, access, replace = banks?.renew == bank, sessions = consents, cause = "effect")
            // D1: the bank's address opens in the person's browser; the bank waits as "in corso" until the return (BankReturn) or Riprova.
            if (outcome is BeginOutcome.Page && !BankBrowser.open(context, outcome.url)) { consents.finish(bank, "no_browser"); set(toProfileIfDone(SetupRules.consentFailed(model, bank))) }
            else set(toProfileIfDone(BankSteps.applyBegin(model, bank, outcome)))
        } finally { working = false }
    }
    // D3: the return (MainActivity -> BankReturnHandler) already completed the link; taken here by the bank that waits, also after a process death.
    val inbox = remember { BankReturnInbox.of(context) }
    val returned by inbox.pending.collectAsState()
    LaunchedEffect(returned, model.links) {
        val r = returned ?: return@LaunchedEffect
        // IN_CORSO, or NON_RIUSCITA from a restore that ran after the process died while the person was at the bank (the return already linked it).
        if (!BankSteps.takesReturn(model.links[r.bank], r.ok)) return@LaunchedEffect
        inbox.consume(r); consents.finish(r.bank, "outcome")
        if (r.ok) hx.confirm()
        set(toProfileIfDone(BankSteps.applyComplete(model, r.bank, r.ok)))
    }
    fun finish() {
        if (working) return
        working = true; saveFailed = false
        scope.launch {
            val ok = try { onDone(model) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
            working = false
            if (ok) { finished = true; store?.clear() }
            if (!ok) { saveFailed = true; hx.reject() }
        }
    }

    val pal = LocalPal.current
    BoxWithConstraints(Modifier.fillMaxSize().background(pal.bg)) {
        val density = LocalDensity.current
        val fold = rememberFoldingFeature()
        val vertical = fold?.takeIf { it.orientation == FoldingFeature.Orientation.VERTICAL }
        val layout = rememberAppLayout(maxWidth.value, maxHeight.value, vertical?.let { with(density) { it.bounds.left.toDp().value } },
            vertical?.let { with(density) { it.bounds.width().toDp().value } },
            fold?.orientation == FoldingFeature.Orientation.HORIZONTAL && fold.isSeparating)
        val stack: @Composable (Modifier) -> Unit = { mod ->
            StackHost(if (banks != null) model.entries.drop(1) else model.entries, onBack = { back() }, keyOf = { it.key }, modifier = mod) { entry ->
                when (entry) {
                    is SetupEntry.Step -> StepPage(entry.step, model, aspsps, working, saveFailed, preview, app,
                        set = { set(it) }, back = { back() }, onRestore = onRestore, finish = { finish() }, bankRun = banks != null, access = access, store = store)
                    SetupEntry.MailAccess -> MailAccessPage(SetupRules.pageNumber(SetupStep.SCEGLI_BANCA, model), SetupRules.BOARD_TOTAL, onBack = { back() }, onDone = { model = SetupRules.mailDone(model) })
                    SetupEntry.SignUp -> EbSetupFlow(access, model.positions, onBack = { back() }, onFinished = { linked, pending -> set(SetupRules.ebDone(SetupRules.closePage(model), linked, pending)) })
                }
            }
        }
        val railSingle: @Composable () -> Unit = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { stack(Modifier.widthIn(max = 480.dp).fillMaxHeight()) } }
        when (layout) {
            AppLayout.Compact -> stack(Modifier.fillMaxSize())
            AppLayout.RailSingle -> railSingle()
            // A bank-only run has no summary to show: it uses the single column on a wide screen too.
            is AppLayout.TwoPane -> if (banks != null) railSingle() else Row(Modifier.fillMaxSize()) {
                // The left pane never moves or re-renders with the steps; it only follows the state.
                Box(Modifier.width(layout.left).fillMaxHeight()) { SummaryPane(model) }
                Box(Modifier.width(layout.gap.coerceAtLeast(1.dp)).fillMaxHeight().background(pal.cardLine))
                CompositionLocalProvider(LocalSetupWide provides true) {
                    Box(Modifier.width(layout.right - (if (layout.gap < 1.dp) 1.dp else 0.dp)).fillMaxHeight()) { stack(Modifier.fillMaxSize()) }
                }
            }
        }
    }
}

/* ---------- steps ---------- */

@Composable
private fun StepPage(
    step: SetupStep, model: SetupModel, aspsps: List<EbAspsp>, working: Boolean, saveFailed: Boolean, preview: Boolean, app: App,
    set: (SetupModel) -> Unit, back: () -> Unit, onRestore: (() -> Unit)?, finish: () -> Unit, bankRun: Boolean = false, access: BankAccess, store: SetupProgressStore? = null,
) {
    // D7: a full setup counts the nine board pages ("n di 9", nine segments) whatever is skipped; a bank-only run counts its own steps.
    val (index, total) = if (bankRun) SetupRules.bankSteps(model).let { s -> (s.indexOf(step).coerceAtLeast(0) + 1) to s.size }
    else SetupRules.pageNumber(step, model) to SetupRules.BOARD_TOTAL
    val onBack = if (step == SetupStep.BENVENUTO) null else back
    when (step) {
        SetupStep.BENVENUTO -> StepScaffold(index, total, onBack, null, "Benvenuto", null, false,
            primary = DockAction("Inizia") { set(SetupRules.push(model, SetupStep.POSIZIONE)) },
            secondary = onRestore?.takeIf { !preview }?.let { DockAction("Ripristina", description = "Ripristina un backup", onClick = it) },
        ) { pad -> ScrollBody(pad) { Welcome() } }

        SetupStep.POSIZIONE -> {
            val reader = rememberDocReader(model, set)
            StepScaffold(index, total, onBack, null, "Posizione",
                "Cosa serve la posizione" to "La posizione decide quali regole, documenti e collegamenti valgono per te. Puoi sceglierne più di una. " +
                    "Se salti, resta n/d: nessun valore viene inventato.", reader.busy,
                primary = DockAction("Continua", enabled = SetupRules.canContinuePositions(model), description = "Continua, scegli prima una posizione".takeIf { !SetupRules.canContinuePositions(model) }) { set(SetupRules.positionsDone(model)) },
                secondary = DockAction("Dopo") { set(SetupRules.skipPositions(model)) },
            ) { pad -> ScrollBody(pad) { Positions(model, set, reader) } }
        }

        // Wave 16: Scegli banca is the native Enable Banking flow (EB-1 … EB-7); its EB-4 is this setup's bank picker.
        SetupStep.SCEGLI_BANCA -> EbSetupFlow(access, model.positions, onBack = back, onFinished = { linked, pending -> set(SetupRules.ebDone(model, linked, pending)) }, store = store)
        SetupStep.CONSENSI -> ConsentStep(index, total, onBack, model, working, set)

        // "Conferma tutto" only when every row was copied from a document read here; over declared rows it stays "Continua".
        SetupStep.PROFILO -> StepScaffold(index, total, onBack, null, "Profilo proposto", null, false,
            primary = DockAction(if (SetupRules.confirmAll(model)) "Conferma tutto" else "Continua") { set(SetupRules.push(model, SetupStep.ALTRI_CONTI)) },
        ) { pad -> ScrollBody(pad) { Profile(model) } }

        SetupStep.ALTRI_CONTI -> StepScaffold(index, total, onBack, null, "Altri conti", null, working,
            primary = DockAction(if (working) "Salvataggio" else if (saveFailed) "Riprova" else "Fatto", enabled = !working, working = working) { finish() },
            status = if (saveFailed) "Non salvato" else null,
        ) { pad -> ScrollBody(pad) { OtherAccounts(model, preview, app, set) } }
    }
}

@Composable
private fun Welcome() {
    val pal = LocalPal.current
    Text("Pilastri mostra dove sono i tuoi soldi.", style = SType.lead, color = pal.ink2)
    Text("L'agente propone, tu confermi.", style = SType.lead, color = pal.ink2)
    Spacer(Modifier.height(22.dp))
    val pillars = listOf(
        Triple("P1", "Liquidità", "Il denaro di tutti i giorni"), Triple("P2", "Emergenze", "La riserva per gli imprevisti"),
        Triple("P3", "Progetti", "Soldi messi da parte per uno scopo"), Triple("P4", "Lungo termine", "Investimenti e previdenza"),
    )
    SetupCard {
        pillars.forEachIndexed { i, (code, name, caption) ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(pal.chip), contentAlignment = Alignment.Center) {
                    Text(code, style = Type.setupChipSmallOn, color = pal.onChip)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(name, style = SType.rowTitle, color = pal.ink)
                    Text(caption, style = SType.caption, color = pal.ink2)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Positions(model: SetupModel, set: (SetupModel) -> Unit, reader: DocReaderState) {
    val hx = rememberHx()
    val reduce = LocalReduceMotion.current
    val withRows = model.positions.filter { PositionCatalog.gestioni(it).isNotEmpty() }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PositionCatalog.order.forEach { kind ->
            ChoiceChip(PositionCatalog.label(kind), kind in model.positions, onClick = { hx.tick(); set(SetupRules.togglePosition(model, kind)) })
            // 8a-05: the chip a document selected carries its source ("dal cedolino set 2026"); tapping the chip makes it the person's own.
            val source = SetupRules.docOf(model, kind)?.label
            val lastSource = remember(kind) { arrayOf("") }
            if (source != null) lastSource[0] = source
            AnimatedVisibility(source != null, modifier = Modifier.align(Alignment.CenterVertically),
                enter = if (reduce) fadeIn(Motion.reducedFade()) else expandHorizontally(Motion.spatial()) + fadeIn(Motion.effects()),
                exit = if (reduce) fadeOut(Motion.reducedFade()) else shrinkHorizontally(Motion.spatial()) + fadeOut(Motion.effects())) {
                StatusChip("dal ${lastSource[0]}", RowTone.SOURCE)
            }
            val options = PositionCatalog.gestioni(kind)
            if (options.isNotEmpty()) AnimatedVisibility(kind in model.positions,
                enter = if (reduce) fadeIn(Motion.reducedFade()) else expandVertically(Motion.spatial()) + fadeIn(Motion.effects()),
                exit = if (reduce) fadeOut(Motion.reducedFade()) else shrinkVertically(Motion.spatial()) + fadeOut(Motion.effects())) {
                GestioneRow(if (withRows.size > 1) "Gestione · ${PositionCatalog.label(kind)}" else "Gestione", options, model.gestioni[kind]) { g ->
                    hx.tick(); if (kind in model.positions) set(SetupRules.setGestione(model, kind, g))
                }
            }
        }
    }
    DocumentReader(reader)
}

/**
 * The document reader of Posizione (8a-04): the tray reads a photo or a PDF on the phone, in memory only, and the originals are
 * dropped as soon as [SetupDocReader] has taken what it states. A failure is one line and changes nothing.
 */
@Stable
private class DocReaderState(scope: CoroutineScope) {
    var line by mutableStateOf<String?>(null)
    var choosing by mutableStateOf(false)
    val tray = AttachmentTray(scope, { ctx -> PdfText.init(ctx); DocumentPipeline(AndroidImageTools(ctx), AndroidPdfPages(ctx)) }) { _, why -> line = why }
    val busy: Boolean get() = tray.busy
    var photo: () -> Unit = {}
    var gallery: () -> Unit = {}
    var file: () -> Unit = {}
}

@Composable
private fun rememberDocReader(model: SetupModel, set: (SetupModel) -> Unit): DocReaderState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hx = rememberHx()
    val state = remember { DocReaderState(scope) }
    val latestModel by rememberUpdatedState(model)
    val latestSet by rememberUpdatedState(set)
    var shotPath by rememberSaveable { mutableStateOf<String?>(null) }
    remember(context) {
        // Leftovers of an interrupted capture (older than ten minutes) are removed, as in the Agente.
        File(context.cacheDir, "attach").listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 600_000L) it.delete() }
    }
    // The same pickers as the Agente attachments (same FileProvider authority).
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AttachmentTray.MAX_ITEMS)) { uris ->
        if (uris.isNotEmpty()) state.line = null
        uris.forEach { state.tray.addUri(context, it) }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) state.line = null
        uris.forEach { state.tray.addUri(context, it) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = shotPath?.let(::File)
        shotPath = null
        if (file != null) {
            if (ok) {
                state.line = null
                state.tray.addUri(context, FileProvider.getUriForFile(context, "${context.packageName}.attach", file), "image/jpeg", deleteAfter = file)
            } else file.delete()
        }
    }
    SideEffect {
        state.photo = {
            try {
                val dir = File(context.cacheDir, "attach").apply { mkdirs() }
                val file = File.createTempFile("shot-", ".jpg", dir)
                shotPath = file.absolutePath
                camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.attach", file))
            } catch (_: ActivityNotFoundException) {
                shotPath?.let { File(it).delete() }
                shotPath = null
                state.line = "Su questo telefono non c'è un'app fotocamera."
            } catch (_: java.io.IOException) {
                state.line = "Non riesco a preparare la foto."
            }
        }
        state.gallery = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        state.file = { pickFiles.launch(arrayOf("application/pdf", "image/*")) }
    }
    LaunchedEffect(state) {
        val handled = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        snapshotFlow { state.tray.documents() to state.tray.busy }.collect { (docs, busy) ->
            val fresh = docs.filter { handled.add(it) }
            if (fresh.isNotEmpty()) {
                var next = latestModel
                var failure: String? = null
                for (d in fresh) when (val r = SetupDocReader.read(d.rawText, d.label, d.sha256)) {
                    is SetupDocReader.Result.Read -> next = SetupRules.applyDocument(next, r)
                    is SetupDocReader.Result.Unreadable -> failure = r.line
                }
                if (next != latestModel) { state.line = null; hx.confirm(); latestSet(next) }
                else if (failure != null) { state.line = failure; hx.reject() }
            }
            // The originals and their text are dropped as soon as every chip finished (read or failed).
            if (!busy && state.tray.hasAny) state.tray.clear()
        }
    }
    DisposableEffect(state) { onDispose { state.tray.clear() } }
    return state
}

/** "Leggi da un documento": one 48 dp action under the chips; it opens Foto / Galleria / File, then reads. */
@Composable
private fun DocumentReader(reader: DocReaderState) {
    val pal = LocalPal.current
    val hx = rememberHx()
    val reduce = LocalReduceMotion.current
    val shape = RoundedCornerShape(24.dp)
    val line = reader.line
    val lastLine = remember { arrayOf("") }
    if (line != null) lastLine[0] = line
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .pressable(shape, pal.pressTint, onClick = if (reader.busy) null else { { hx.tick(); reader.choosing = !reader.choosing } }, role = Role.Button)
                .background(pal.card).border(1.dp, pal.btnLine, shape).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            Text(if (reader.busy) "Leggo il documento" else "Leggi da un documento", style = SType.chipOn, color = pal.brand, textAlign = TextAlign.Center, maxLines = 2)
        }
        AnimatedVisibility(reader.choosing && !reader.busy,
            enter = if (reduce) fadeIn(Motion.reducedFade()) else expandVertically(Motion.spatial()) + fadeIn(Motion.effects()),
            exit = if (reduce) fadeOut(Motion.reducedFade()) else shrinkVertically(Motion.spatial()) + fadeOut(Motion.effects())) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf<Pair<String, () -> Unit>>("Foto" to reader.photo, "Galleria" to reader.gallery, "File" to reader.file).forEach { (label, go) ->
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp)
                            .pressable(shape, pal.pressTint, onClick = { hx.tick(); reader.choosing = false; go() }, role = Role.Button)
                            .background(pal.chip, shape),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = SType.chipSmallOn, color = pal.onChip) }
                }
            }
        }
        AnimatedVisibility(line != null,
            enter = if (reduce) fadeIn(Motion.reducedFade()) else expandVertically(Motion.spatial()) + fadeIn(Motion.effects()),
            exit = if (reduce) fadeOut(Motion.reducedFade()) else shrinkVertically(Motion.spatial()) + fadeOut(Motion.effects())) {
            // CR-60: not over-budget, so ink + icon, never the warning colour.
            Row(Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.alertCircle, null, Modifier.size(14.dp), tint = pal.ink)
                Spacer(Modifier.width(6.dp))
                Text(lastLine[0], style = SType.caption, color = pal.ink)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GestioneRow(title: String, options: List<Gestione>, chosen: Gestione?, onChoose: (Gestione) -> Unit) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxWidth().clip(CardRadius).background(pal.lilacBg.copy(alpha = 0.45f)).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
        Box(Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.CenterStart) { Text(title, style = SType.gestione, color = pal.brand) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { g -> ChoiceChip(PositionCatalog.gestioneLabel(g), chosen == g, small = true, single = true, onClick = { onChoose(g) }) }
        }
    }
}

/* Scegli banca */

@Composable
internal fun BankList(chosen: List<String>, aspsps: List<EbAspsp>, marks: Map<String, String>, pad: PaddingValues, onToggle: (String) -> Unit, enabled: Boolean) {
    val pal = LocalPal.current
    val hx = rememberHx()
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(aspsps, query) { SetupRules.filterBanks(aspsps, query) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
        item(key = "search") { SearchField(query, { query = it }, enabled = enabled) }
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp).heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Banche", style = SType.section, color = pal.ink, modifier = Modifier.weight(1f).semantics { heading() })
                AnimatedContent(chosen.size, transitionSpec = fadeThrough(LocalReduceMotion.current), label = "chosen") { n ->
                    if (n > 0) StatusChip(if (n == 1) "1 scelta" else "$n scelte", RowTone.SOURCE) else Spacer(Modifier.height(24.dp))
                }
            }
        }
        if (enabled && shown.isEmpty()) item(key = "none") {
            NoBankCard(SetupRules.noBankLine(query), clearable = query.isNotEmpty()) { hx.tick(); query = "" }
        }
        items(shown, key = { "bank:" + it.name }) { b ->
            val i = shown.indexOf(b)
            val first = i == 0; val last = i == shown.lastIndex
            val shape = RoundedCornerShape(topStart = if (first) 24.dp else 0.dp, topEnd = if (first) 24.dp else 0.dp, bottomStart = if (last) 24.dp else 0.dp, bottomEnd = if (last) 24.dp else 0.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(pal.card)) {
                if (!first) Hairline(Modifier.padding(horizontal = 16.dp))
                BankRow(b, marks[b.name], b.name in chosen) { hx.tick(); onToggle(b.name) }
            }
        }
    }
}

/** An empty search result: the search is named, and "Cancella ricerca" brings the whole list back. */
@Composable
private fun NoBankCard(line: String, clearable: Boolean, onClear: () -> Unit) {
    val pal = LocalPal.current
    SetupCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(line, style = SType.rowStrong, color = pal.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            if (clearable) { Spacer(Modifier.width(8.dp)); ChipAction("Cancella ricerca", "Cancella ricerca", enabled = true, onClick = onClear) }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, enabled: Boolean) {
    val pal = LocalPal.current
    val hx = rememberHx()
    val reduce = LocalReduceMotion.current
    val shape = RoundedCornerShape(26.dp)
    BasicTextField(
        value, onChange, enabled = enabled, singleLine = true, textStyle = SType.chip.copy(color = pal.ink),
        cursorBrush = SolidColor(pal.primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(pal.card).border(1.dp, pal.cardLine, shape)
            .semantics { contentDescription = "Cerca banca" },
        decorationBox = { inner ->
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.search, null, Modifier.size(20.dp), tint = pal.ink2)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                    if (value.isEmpty()) Text("Cerca banca", style = SType.chip, color = pal.ink2)
                    inner()
                }
                // × clears the text and keeps the focus, so the next name can be typed at once.
                AnimatedVisibility(value.isNotEmpty() && enabled,
                    enter = if (reduce) fadeIn(Motion.reducedFade()) else fadeIn(Motion.effects()) + scaleIn(Motion.spatial(), initialScale = 0.6f),
                    exit = if (reduce) fadeOut(Motion.reducedFade()) else fadeOut(Motion.effects()) + scaleOut(Motion.effects(), targetScale = 0.6f)) {
                    Box(Modifier.size(48.dp).pressable(CircleShape, pal.pressTint, onClick = { hx.tick(); onChange("") })
                        .semantics { contentDescription = "Cancella ricerca" }, contentAlignment = Alignment.Center) {
                        Icon(Ic.close, null, Modifier.size(18.dp), tint = pal.ink2)
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
        },
    )
}

@Composable
private fun BankRow(bank: EbAspsp, monogram: String?, chosen: Boolean, onClick: () -> Unit) {
    val pal = LocalPal.current
    val avatar by animateColorAsState(if (chosen) pal.chip else pal.lilacBg.copy(alpha = 0.45f), Motion.effects(), label = "avatar")
    val mark by animateFloatAsState(if (chosen) 1f else 0f, Motion.selector(), label = "mark")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).pressable(RoundedCornerShape(0.dp), pal.pressTint, onClick = onClick, role = Role.Checkbox)
            .semantics(mergeDescendants = true) { toggleableState = ToggleableState(chosen); contentDescription = bank.name + if (chosen) ", scelta" else ", non scelta" }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(avatar), contentAlignment = Alignment.Center) {
            Text(monogram ?: initials(bank.name), style = SType.section, color = pal.onChip)
        }
        Spacer(Modifier.width(14.dp))
        Text(bank.name, style = SType.rowName, color = pal.ink, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (bank.beta) { Spacer(Modifier.width(8.dp)); StatusChip("beta", RowTone.PLAIN) }
        Spacer(Modifier.width(12.dp))
        // N-04/N-05: a rounded-square checkbox (multi-select), never a radio circle.
        Box(Modifier.size(28.dp).drawBehind {
            val corner = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
            val inset = 1.dp.toPx()
            val box = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset)
            drawRoundRect(pal.btnLine, androidx.compose.ui.geometry.Offset(inset, inset), box, corner, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            drawRoundRect(pal.primary.copy(alpha = mark.coerceIn(0f, 1f)), androidx.compose.ui.geometry.Offset(inset, inset), box, corner)
        }, contentAlignment = Alignment.Center) {
            Icon(Ic.check, null, Modifier.size(16.dp).graphicsLayer { scaleX = mark; scaleY = mark; alpha = mark.coerceIn(0f, 1f) }, tint = pal.onPrimary)
        }
    }
}

internal fun initials(name: String): String {
    val words = name.split(' ', '-', '.').filter { it.isNotBlank() }
    val s = if (words.size >= 2) "${words[0].first()}${words[1].first()}" else name.filter { it.isLetterOrDigit() }.take(2)
    return s.uppercase().ifEmpty { "?" }
}

/* Consents */

@Composable
private fun ConsentStep(index: Int, total: Int, onBack: (() -> Unit)?, model: SetupModel, working: Boolean, set: (SetupModel) -> Unit) {
    val failed = model.failedBank
    val current = SetupRules.currentBank(model)
    val rows = model.chosenBanks.map { it to model.links[it] }
    when {
        failed != null -> {
            val (k, n) = SetupRules.bankIndex(model, failed)
            StepScaffold(index, total, onBack, "$k di $n · $failed", "Non riuscito", "Cosa è successo" to "La banca non ha confermato il collegamento (consenso rifiutato, scaduto o interrotto). Riprova per ripartire dall'approvazione, oppure continua senza.", false,
                primary = DockAction("Riprova") { set(SetupRules.retryConsent(model)) },
                secondary = DockAction("Continua senza") {
                    val next = SetupRules.continueWithout(model)
                    set(if (SetupRules.consentsDone(next)) SetupRules.push(next, SetupStep.PROFILO) else next)
                }) { pad -> ScrollBody(pad) { StatusCard(rows) } }
        }
        current != null -> {
            val (k, n) = SetupRules.bankIndex(model, current)
            StepScaffold(index, total, onBack, "$k di $n · $current", "Approva nell'app $current", null, true,
                // the page was left (Back or Chiudi) with its authorization alive: "Riprova" reopens it, it starts nothing new
                primary = if (model.links[current] == BankLink.IN_CORSO && !working) DockAction("Riprova") { set(model.copy(links = model.links + (current to BankLink.IN_ATTESA))) } else null,
                secondary = DockAction("Salta") {
                    val next = SetupRules.skipConsent(model, current)
                    set(if (SetupRules.consentsDone(next)) SetupRules.push(next, SetupStep.PROFILO) else next)
                }) { pad -> ScrollBody(pad) { StatusCard(rows) } }
        }
        else -> StepScaffold(index, total, onBack, null, "Consensi", null, working,
            primary = DockAction("Continua") { set(SetupRules.push(model, SetupStep.PROFILO)) }) { pad -> ScrollBody(pad) { StatusCard(rows) } }
    }
}

private fun linkTag(link: BankLink?): Pair<String, RowTone> = when (link) {
    BankLink.IN_CORSO -> "in corso" to RowTone.SOURCE
    BankLink.COLLEGATA -> "collegata" to RowTone.GOOD
    BankLink.NON_RIUSCITA -> "non riuscito" to RowTone.WARN
    BankLink.DA_APPROVARE -> "da approvare" to RowTone.PLAIN
    BankLink.IN_ATTESA, null -> "in attesa" to RowTone.PLAIN
}

@Composable
private fun StatusCard(rows: List<Pair<String, BankLink?>>) {
    val pal = LocalPal.current
    SetupCard {
        rows.forEachIndexed { i, (name, link) ->
            if (i > 0) Hairline(Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = SType.rowStrong, color = pal.ink, modifier = Modifier.weight(1f))
                if (link != null || rows.size > 1) {
                    val (tag, tone) = linkTag(link)
                    AnimatedContent(tag to tone, transitionSpec = fadeThrough(LocalReduceMotion.current), label = "link") { (t, tn) -> StatusChip(t, tn) }
                }
            }
        }
    }
}

/* Profilo proposto */

@Composable
private fun Profile(model: SetupModel) {
    val pal = LocalPal.current
    val rows = remember(model) { SetupRules.profileRows(model) }
    SetupCard {
        rows.forEachIndexed { i, r ->
            if (i > 0) Hairline(Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.label, style = SType.label, color = pal.ink2)
                    Text(r.value, style = SType.value, color = if (r.value == SetupRules.ND) pal.ink2 else pal.ink)
                }
                if (r.tag != null) { Spacer(Modifier.width(8.dp)); StatusChip(r.tag, r.tone) }
            }
        }
    }
}

/* Altri conti */

@Composable
private fun OtherAccounts(model: SetupModel, preview: Boolean, app: App, set: (SetupModel) -> Unit) {
    val context = LocalContext.current
    val hx = rememberHx()
    val offered = remember(model.positions) { SetupRules.offered(model) }
    val vault = remember { AutoSyncKit.vault(context) }
    var linked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tick by remember { mutableIntStateOf(0) }
    var launched by remember { mutableStateOf<WebAccount?>(null) }
    val aiLogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val account = launched
        if (r.resultCode == Activity.RESULT_OK && account != null) {
            AiSettings.create(context).markLinked(account, Instant.now().toString()); hx.confirm()
        }
        tick++
    }
    // The provider pages return to the app: read who is linked again whenever the screen comes back.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(ChatGptSignIn.revision) { tick++ }
    LaunchedEffect(tick, offered) {
        linked = withContext(Dispatchers.IO) {
            offered.filter { spec ->
                runCatching {
                    when (spec.kind) {
                        ConnectorKind.ASSISTANT -> AiSettings.create(context).let { s -> WebAccount.entries.any { it.webSignIn && WebAccounts.state(context, it, s) == AiLinkState.LINKED } || ChatGptAccount.state(context) == AiLinkState.LINKED }
                        else -> ImportProvider.byId(spec.id)?.let { AutoSyncKit.coordinator(context).isLinked(it) } == true
                    }
                }.getOrDefault(false)
            }.map { it.id }.toSet()
        }
    }
    SetupCard {
        offered.forEachIndexed { i, spec ->
            if (i > 0) Hairline(Modifier.padding(horizontal = 16.dp))
            // The assistant has no "Collega" hop: its Claude / ChatGPT choices sit on the row and open that provider's sign-in.
            // Claude opens its account page; ChatGPT uses the documented browser sign-in (never chatgpt.com in an in-app page).
            val accounts = if (spec.kind == ConnectorKind.ASSISTANT) WebAccount.entries.filter { it.webSignIn } else emptyList()
            val chatGpt = if (spec.kind == ConnectorKind.ASSISTANT) listOf(WebAccount.CHATGPT.label) else emptyList()
            ConnectorRow(spec, spec.id in linked, spec.id in model.later, enabled = !preview, choices = accounts.map { it.label } + chatGpt,
                onLink = { ImportProvider.byId(spec.id)?.let { app.signIn(it) } },
                onChoose = { i ->
                    val account = accounts.getOrNull(i)
                    if (account != null) { launched = account; aiLogin.launch(AiWebActivity.loginIntent(context, account)) }
                    else if (i == accounts.size && chatGpt.isNotEmpty()) ChatGptSignIn.start(context)
                },
                onLater = { hx.tick(); set(SetupRules.toggleLater(model, spec.id)) })
            if (spec.kind == ConnectorKind.ASSISTANT) ChatGptSignInStatus(spec.id in linked)
            // Trade Republic: the PIN is saved by default; this switch turns it off (and deletes it). Null: the Keystore is unusable.
            if (spec.id == ImportProvider.TRADE_REPUBLIC.id && vault != null) {
                Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) { RememberPinRow(vault) }
            }
        }
    }
    if (offered.isEmpty()) NothingToLink()
}

/** The ChatGPT sign-in under the assistant row: progress and a retry from the first frame of the wait, or the failure with whose error it is. */
@Composable
private fun ChatGptSignInStatus(linked: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ChatGptSignIn.restore(context) }
    val running = ChatGptSignIn.running
    val outcome = ActionOutcome.ofChatGpt(running, ChatGptSignIn.lastOutcome)
    if (outcome is ActionOutcome.Idle || (linked && !running)) return
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        WorkingLine(running)
        if (running) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutcomeLine(outcome, Modifier.weight(1f))
                SetupPill("Riprova", PillStyle.Tonal, { ChatGptSignIn.restart(context) })
            }
        } else OutcomeRow("Riprova", outcome) { ChatGptSignIn.start(context) }
    }
}

/**
 * U-14: "Ricorda il PIN" is on by default (the PIN is saved, encrypted with the Keystore); off means no automatic reading.
 * Same stored flag as Impostazioni's "declined" switch, shown the right way round.
 */
@Composable
private fun RememberPinRow(vault: CredentialVault) {
    val pal = LocalPal.current
    val hx = rememberHx()
    var declined by remember { mutableStateOf(vault.isTrPinDeclined()) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .pressable(RoundedCornerShape(16.dp), pal.pressTint, role = Role.Switch, onClick = { declined = !declined; vault.setTrPinDeclined(declined); hx.toggle(!declined) })
            .semantics { stateDescription = if (declined) "spento" else "attivo" }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Ricorda il PIN", style = SType.rowTitle, color = pal.ink)
            Text(if (declined) "Senza PIN niente lettura automatica" else "Cifrato nel telefono", style = SType.small, color = pal.ink2)
        }
        Switch(checked = !declined, onCheckedChange = null)
    }
}

/** Every connector the registry offers is already linked or absent: one plain line, not an empty card. */
@Composable
private fun NothingToLink() {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text("Nessun altro conto da collegare", style = SType.lead, color = pal.ink2)
    }
}

/** The 36 dp chip inside a 48 dp touch target ("Collega", "Cancella ricerca"). */
@Composable
internal fun ChipAction(label: String, description: String, enabled: Boolean, tag: String? = null, onClick: () -> Unit) {
    val pal = LocalPal.current
    // U-14: an enabled action is filled (it reads as live); a disabled one is dimmed and says why.
    Box(Modifier.heightIn(min = 48.dp).pressable(RoundedCornerShape(18.dp), pal.pressTint, onClick = onClick, enabled = enabled).then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .semantics { contentDescription = description; if (!enabled) stateDescription = "non disponibile in anteprima" }, contentAlignment = Alignment.Center) {
        Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (enabled) pal.primary else pal.primary.copy(alpha = 0.35f)).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center) {
            Text(label, style = SType.section, color = if (enabled) pal.onPrimary else pal.onPrimary.copy(alpha = 0.8f), maxLines = 1)
        }
    }
}

/**
 * G-17 / EB-2: one action of a group, full width on the card's start and end lines: a title and one caption line that says what it
 * does or when it becomes available, at least 56 dp high (the same height enabled or not, so a caption changing never reflows).
 * Press feedback on pointer down; disabled keeps its place and says so. The one implementation of this row (same concept, same code).
 */
@Composable
internal fun SetupActionRow(title: String, caption: String, enabled: Boolean, description: String, tag: String? = null, onClick: () -> Unit) {
    val pal = LocalPal.current
    // D-08: the row is square; the card around it is the rounded surface (same as BankRow).
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).pressable(RoundedCornerShape(0.dp), pal.pressTint, onClick = onClick, enabled = enabled)
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .semantics(mergeDescendants = true) { contentDescription = description; if (!enabled) stateDescription = "non ancora disponibile" }
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = SType.rowStrong, color = if (enabled) pal.ink else pal.ink2, maxLines = 1)
        Text(caption, style = SType.small, color = pal.ink2, maxLines = 1)
    }
}

/**
 * One connector. By [SetupRules.connectorShape]: linked reads "collegato"; the assistant shows its [choices] right under the
 * title (one tap opens that provider's sign-in); every other connector has "Collega". "Dopo" sits on every unlinked row.
 */
@Composable
private fun ConnectorRow(
    spec: ConnectorSpec, linked: Boolean, later: Boolean, enabled: Boolean, choices: List<String>,
    onLink: () -> Unit, onChoose: (Int) -> Unit, onLater: () -> Unit,
) {
    val pal = LocalPal.current
    val reduce = LocalReduceMotion.current
    val shape = SetupRules.connectorShape(spec, linked)
    val dim by animateFloatAsState(if (later && !linked) 0.6f else 1f, Motion.effects(), label = "later")
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).alpha(dim).semantics(mergeDescendants = true) {}) {
                Text(spec.label, style = SType.rowStrong, color = pal.ink)
                Text(spec.caption, style = SType.small, color = pal.ink2)
            }
            AnimatedContent(shape, transitionSpec = fadeThrough(reduce), label = "linked") { s ->
                if (s == ConnectorShape.LINKED) StatusChip("collegato", RowTone.GOOD)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s == ConnectorShape.LINK) ChipAction("Collega", "Collega ${spec.label}", enabled, onClick = onLink)
                    Box(Modifier.size(width = 64.dp, height = 48.dp).pressable(RoundedCornerShape(24.dp), pal.pressTint, onClick = onLater, role = Role.Checkbox)
                        .background(animateFill(pal.chip, later, "dopo").value, RoundedCornerShape(24.dp))
                        .semantics { contentDescription = "Dopo, ${spec.label}"; toggleableState = ToggleableState(later) }, contentAlignment = Alignment.Center) {
                        Text("Dopo", style = SType.section, color = pal.brand)
                    }
                }
            }
        }
        AnimatedVisibility(shape == ConnectorShape.CHOOSE && choices.isNotEmpty(),
            enter = if (reduce) fadeIn(Motion.reducedFade()) else expandVertically(Motion.spatial()) + fadeIn(Motion.effects()),
            exit = if (reduce) fadeOut(Motion.reducedFade()) else shrinkVertically(Motion.spatial()) + fadeOut(Motion.effects())) {
            Row(Modifier.fillMaxWidth().alpha(dim).padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEachIndexed { i, label ->
                    ChoiceChip(label, false, onClick = { onChoose(i) }, modifier = Modifier.weight(1f), small = true, single = true, enabled = enabled)
                }
            }
        }
    }
}

/* ---------- the open Fold's left pane ---------- */

private fun stepName(s: SetupStep) = when (s) {
    SetupStep.BENVENUTO -> "Benvenuto"; SetupStep.POSIZIONE -> "Posizione"; SetupStep.SCEGLI_BANCA -> "Scegli banca"
    SetupStep.CONSENSI -> "Consensi"; SetupStep.PROFILO -> "Profilo proposto"; SetupStep.ALTRI_CONTI -> "Altri conti"
}

private fun stepValue(s: SetupStep, m: SetupModel, reached: Boolean): String? = when (s) {
    SetupStep.POSIZIONE -> when {
        m.positions.isNotEmpty() -> m.positions.joinToString(", ") { PositionCatalog.label(it) }
        m.positionsSkipped -> SetupRules.ND
        else -> null
    }
    SetupStep.SCEGLI_BANCA -> when {
        m.chosenBanks.isNotEmpty() -> m.chosenBanks.joinToString(", ")
        reached && m.step != SetupStep.SCEGLI_BANCA -> SetupRules.ND
        else -> null
    }
    SetupStep.CONSENSI -> {
        val on = m.chosenBanks.count { m.links[it] == BankLink.COLLEGATA }
        val wait = m.chosenBanks.count { m.links[it] == BankLink.DA_APPROVARE }
        val failed = m.chosenBanks.count { m.links[it] == BankLink.NON_RIUSCITA }
        listOfNotNull(if (on > 0) "$on collegate" else null, if (wait > 0) "$wait da approvare" else null, if (failed > 0) "$failed non riuscite" else null).joinToString(" · ").ifEmpty { null }
    }
    else -> null
}

@Composable
private fun SummaryPane(m: SetupModel) {
    val pal = LocalPal.current
    val reduce = LocalReduceMotion.current
    val steps = SetupRules.steps(m)
    val (_, total) = SetupRules.progress(m)
    val index = steps.indexOf(m.step) + 1
    val places = remember { mutableStateMapOf<SetupStep, Pair<Float, Float>>() }
    val target = places[m.step]
    val y by animateFloatAsState(target?.first ?: 0f, Motion.selector(), label = "pillY")
    val h by animateFloatAsState(target?.second ?: 0f, Motion.selector(), label = "pillH")
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
        .verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 20.dp, top = 52.dp, bottom = 24.dp)) {
        Text("Configurazione", style = SType.title, color = pal.ink, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        Text("${SetupRules.progress(m).first} di $total", style = SType.counter, color = pal.ink2)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().clip(CardRadius).background(pal.card).border(1.dp, pal.cardLine, CardRadius).padding(vertical = 4.dp)) {
            if (target != null) Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).graphicsLayer { translationY = y }
                .height(with(LocalDensity.current) { h.toDp() }).clip(RoundedCornerShape(18.dp)).background(pal.selected))
            Column {
                steps.forEachIndexed { i, s ->
                    val done = i < index - 1
                    val now = i == index - 1
                    val value = stepValue(s, m, done || now)
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).onPlaced { places[s] = it.positionInParent().y to it.size.height.toFloat() }
                        .padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) { stateDescription = if (now) "in corso" else if (done) "fatto" else "da fare" },
                        verticalAlignment = Alignment.CenterVertically) {
                        val disc by animateColorAsState(if (done) pal.primary else if (now) pal.chip else pal.card, Motion.effects(), label = "disc")
                        Box(Modifier.size(28.dp).clip(CircleShape).background(disc).border(1.5.dp, if (done || now) pal.primary else pal.btnLine, CircleShape), contentAlignment = Alignment.Center) {
                            if (done) Icon(Ic.check, null, Modifier.size(15.dp), tint = pal.onPrimary)
                            else Text("${i + 1}", style = SType.tag, color = if (now) pal.onChip else pal.ink2)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stepName(s), style = if (now) SType.rowStrong else SType.rowName, color = pal.ink)
                            if (value != null) Text(value, style = SType.caption, color = pal.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/* ---------- shared pieces ---------- */

/** [working]: the primary is disabled by running work; its label then names the work and a circular loader sits at its right (the page adds no text or line for that work). */
internal class DockAction(val label: String, val enabled: Boolean = true, val description: String? = null, val working: Boolean = false, val onClick: () -> Unit)

/**
 * One step page: header (back, "n di N", segments), the thin working line, an optional overline, the title with an optional
 * "?", the body, and the floating dock. The page owns its insets; the keyboard hides the dock and lifts the body.
 */
@Composable
internal fun StepScaffold(
    index: Int, total: Int, onBack: (() -> Unit)?, overline: String?, title: String, help: Pair<String, String>?, working: Boolean,
    primary: DockAction? = null, secondary: DockAction? = null, status: String? = null, subtitle: String? = null,
    body: @Composable (PaddingValues) -> Unit,
) {
    val pal = LocalPal.current
    val wide = LocalSetupWide.current
    val gutter = PageSpec.gutter(wide)
    val density = LocalDensity.current
    val imeVisible = xyz.grtlabs.pilastri.ui.keyboardShownOrComing()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(pal.bg)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = gutter)) {
            Row(Modifier.fillMaxWidth().height(48.dp).offset(x = (-8).dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) RoundIcon(Ic.back, "Indietro", onBack, tint = pal.ink) else Spacer(Modifier.width(48.dp))
                Spacer(Modifier.weight(1f))
                if (!wide) Text("$index di $total", style = SType.counter, color = pal.ink2, modifier = Modifier.offset(x = 8.dp).padding(end = 8.dp))
            }
            if (!wide) Segments(index, total)
            WorkingLine(working && primary?.working != true, Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            xyz.grtlabs.pilastri.ui.PageHeader(title, subtitle, setupOverline = xyz.grtlabs.pilastri.ui.SetupOverline(overline),
                trailing = help?.let { h -> { HelpButton(h.first, h.second) } })
            Spacer(Modifier.height(16.dp))
            Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))) {
                body(PaddingValues(bottom = if (imeVisible) 16.dp else navBottom + 104.dp))
            }
        }
        Dock(primary, secondary, status, gutter, visible = !imeVisible,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)).padding(bottom = 16.dp))
    }
}

@Composable
internal fun ScrollBody(pad: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), content = content)
}

@Composable
internal fun SetupCard(content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxWidth().clip(CardRadius).background(pal.card).border(1.dp, pal.cardLine, CardRadius).padding(vertical = 4.dp), content = content)
}

@Composable
private fun Segments(index: Int, total: Int) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().height(4.dp).semantics { contentDescription = "Passo $index di $total" }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { i ->
            val c by animateColorAsState(if (i < index) pal.primary else pal.track, Motion.effects(), label = "seg")
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c))
        }
    }
}

/** A thin line while something runs (a read, a save); nothing when idle. Never a claim of progress it does not know. */
@Composable
internal fun WorkingLine(on: Boolean, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val shown by animateFloatAsState(if (on) 1f else 0f, Motion.effects(), label = "working")
    // Reduced motion: no endless sweep; the bar rests as a segment (the line still fades in with the work).
    val x by if (LocalReduceMotion.current) remember { mutableFloatStateOf(0.5f) } else {
        val sweep = rememberInfiniteTransition(label = "sweep")
        sweep.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "x")
    }
    Box(modifier.fillMaxWidth().height(2.dp).graphicsLayer { alpha = shown }.drawBehind {
        if (shown <= 0.001f) return@drawBehind
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(pal.track, cornerRadius = r)
        val w = size.width * 0.3f
        val left = (size.width + w) * x - w
        drawRoundRect(pal.primary, Offset(left.coerceAtLeast(0f), 0f), Size((left + w).coerceAtMost(size.width) - left.coerceAtLeast(0f), size.height), r)
    }.semantics { if (on) stateDescription = "in corso" })
}

/** L-6: one StepActions row. The primary has the same width and right edge on every step (confirm right, CR-96); the secondary slot, to its left, has a fixed width. */
private val StepSecondaryWidth = 132.dp
private val StepActionsGap = 12.dp

@Composable
private fun Dock(primary: DockAction?, secondary: DockAction?, status: String?, gutter: Dp, visible: Boolean, modifier: Modifier) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(visible, modifier, enter = if (reduce) fadeIn(Motion.reducedFade()) else fadeIn(Motion.effects()) + expandVertically(Motion.spatial(), expandFrom = Alignment.Bottom),
        exit = if (reduce) fadeOut(Motion.reducedFade()) else fadeOut(Motion.effects()) + shrinkVertically(Motion.spatial(), shrinkTowards = Alignment.Bottom)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
            val primaryWidth = maxWidth - StepSecondaryWidth - StepActionsGap
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                AnimatedVisibility(status != null, enter = appear(LocalReduceMotion.current), exit = vanish(LocalReduceMotion.current)) {
                    Box(Modifier.padding(bottom = 8.dp)) { StatusChip(status ?: "", RowTone.WARN) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StepActionsGap), verticalAlignment = Alignment.CenterVertically) {
                    // The confirming action sits on the right, the escape to its left (CR-96); the primary keeps its width and right edge on every step.
                    if (secondary != null) DockSecondary(secondary, (if (primary == null) Modifier.widthIn(min = 96.dp) else Modifier.width(StepSecondaryWidth)).testTag("action-secondary-dock"))
                    else if (primary != null) Spacer(Modifier.width(StepSecondaryWidth))
                    if (primary != null) DockPrimary(primary, Modifier.width(primaryWidth).testTag("action-confirm-dock"))
                }
            }
        }
    }
}

private val DockShape = RoundedCornerShape(28.dp)

@Composable
private fun DockPrimary(a: DockAction, modifier: Modifier) {
    val pal = LocalPal.current
    val hx = rememberHx()
    Box(modifier.height(56.dp).shadow(8.dp, DockShape, clip = false).clip(DockShape).background(pal.card.copy(alpha = 0.94f)).border(1.dp, pal.cardLine, DockShape).padding(4.dp)) {
        val fill by animateColorAsState(if (a.enabled || a.working) pal.primary else pal.primary.copy(alpha = 0.4f), Motion.effects(), label = "primary")
        Box(Modifier.fillMaxSize().pressable(RoundedCornerShape(24.dp), pal.pressTint, onClick = a.onClick, enabled = a.enabled && !a.working, haptic = { hx.tap() })
            .background(fill).semantics { contentDescription = a.description ?: a.label; if (a.working) stateDescription = "in corso" }, contentAlignment = Alignment.Center) {
            AnimatedContent(a.label to a.working, transitionSpec = { fadeIn(Motion.effects()) togetherWith fadeOut(Motion.effects()) }, label = "primaryLabel") { (t, work) ->
                Row(Modifier.padding(horizontal = if (work) 2.dp else 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(t, style = SType.primary, color = pal.onPrimary, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (work) {
                        Spacer(Modifier.width(4.dp))
                        CircularProgressIndicator(Modifier.size(14.dp).testTag("progress_primary"), strokeWidth = 2.dp, color = pal.onPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DockSecondary(a: DockAction, modifier: Modifier) {
    val pal = LocalPal.current
    val hx = rememberHx()
    Box(modifier.height(56.dp).shadow(8.dp, DockShape, clip = false).clip(DockShape).background(pal.card.copy(alpha = 0.94f)).border(1.dp, pal.cardLine, DockShape)
        .pressable(DockShape, pal.pressTint, onClick = a.onClick, enabled = a.enabled, haptic = { hx.tap() })
        .semantics { contentDescription = a.description ?: a.label }, contentAlignment = Alignment.Center) {
        AnimatedContent(a.label, transitionSpec = { fadeIn(Motion.effects()) togetherWith fadeOut(Motion.effects()) }, label = "secondaryLabel") { t ->
            Text(t, style = SType.secondary, color = pal.brand, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

/**
 * A 48 dp choice with a centred label. Multi-select by default (checkbox semantics); [single] for one-of rows. The check
 * grows in on selection and the fill and outline follow with the effects spring.
 */
@Composable
internal fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false, single: Boolean = false, enabled: Boolean = true) {
    val pal = LocalPal.current
    val reduce = LocalReduceMotion.current
    val shape = RoundedCornerShape(24.dp)
    val bg by animateColorAsState(if (selected) pal.chip else pal.card, Motion.effects(), label = "chipBg")
    val line by animateColorAsState(if (selected) pal.primary else pal.btnLine, Motion.effects(), label = "chipLine")
    val width by animateFloatAsState(if (selected) 1.5f else 1f, Motion.effects(), label = "chipW")
    val checkAlpha by animateFloatAsState(if (selected) 1f else 0f, if (reduce) Motion.reducedFade() else Motion.effects(), label = "chipCheck")
    Row(
        modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else 0.5f).pressable(shape, pal.pressTint, onClick = onClick, role = if (single) Role.RadioButton else Role.Checkbox, enabled = enabled)
            .background(bg).border(width.dp, line, shape)
            .semantics { if (single) this.selected = selected else toggleableState = ToggleableState(selected) }
            .padding(horizontal = 26.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        // Layout stability (CR-83): a state change never changes bounds. The check is drawn in a gutter that is reserved in both
        // states (only its alpha changes), and the label keeps one weight, so selecting never reflows the siblings.
        Box(Modifier.size(0.dp).wrapContentSize(unbounded = true, align = Alignment.CenterEnd)) {
            Icon(Ic.check, null, Modifier.size(16.dp).offset(x = (-4).dp).graphicsLayer { alpha = checkAlpha }, tint = pal.brand)
        }
        Text(label, style = if (small) SType.chipSmallOn else SType.chipOn,
            color = if (selected) pal.onChip else pal.ink, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
internal fun StatusChip(text: String, tone: RowTone) {
    val pal = LocalPal.current
    val (bg, fg) = when (tone) {
        RowTone.SOURCE -> pal.chip to pal.onChip
        RowTone.GOOD -> pal.goodBg to pal.goodInk
        RowTone.WARN -> pal.chip to pal.onChip // CR-60: ink + icon, not the warning colour
        RowTone.PLAIN -> Color.Transparent to pal.ink2
    }
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.heightIn(min = 24.dp).clip(shape).background(bg).then(if (tone == RowTone.PLAIN) Modifier.border(1.dp, pal.btnLine, shape) else Modifier)
        .padding(horizontal = 10.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (tone == RowTone.WARN) { Icon(Ic.alertCircle, null, Modifier.size(12.dp), tint = fg); Spacer(Modifier.width(4.dp)) }
            Text(text, style = SType.tag, color = fg, maxLines = 1)
        }
    }
}
