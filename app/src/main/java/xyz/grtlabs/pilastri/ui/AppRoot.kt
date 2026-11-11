package xyz.grtlabs.pilastri.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.sync.ConnectorResult
import xyz.grtlabs.pilastri.sync.gmail.GmailConnector
import xyz.grtlabs.pilastri.sync.gmail.GmailResult
import xyz.grtlabs.pilastri.sync.auto.AutoSyncKit
import xyz.grtlabs.pilastri.sync.isybank.IsybankConnector
import xyz.grtlabs.pilastri.sync.isybank.IsybankWebActivity
import xyz.grtlabs.pilastri.sync.paypal.PaypalConnector
import xyz.grtlabs.pilastri.sync.paypal.PaypalWebActivity
import xyz.grtlabs.pilastri.sync.tr.TrConnector
import xyz.grtlabs.pilastri.ui.screens.*

private fun readLimited(context: android.content.Context, uri: android.net.Uri, limit: Int): ByteArray =
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (true) { val n = stream.read(buf); if (n < 0) break; require(out.size() + n <= limit) { "File troppo grande." }; out.write(buf, 0, n) }
        out.toByteArray()
    } ?: error("open failed")

private fun displayName(context: android.content.Context, uri: android.net.Uri): String =
    runCatching { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
        ?: uri.lastPathSegment ?: "file"

/** The redone app shell: storage, result launchers, adaptive layout, peers and the page stacks. */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var savedNav by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var savedMonth by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val app = remember {
        App(context.applicationContext, LocalArchive(context.applicationContext), scope).also { a ->
            decodeNav(savedNav)?.let { a.nav = it }
            runCatching { java.time.YearMonth.parse(savedMonth) }.getOrNull()?.let { a.spendMonth = it }
        }
    }
    SideEffect { savedNav = app.nav.encode(); savedMonth = app.spendMonth.toString() }
    app.hx = rememberHx()
    // Speed: the destinations' read models are computed here, off the main thread, once per archive version, so a switch composes from ready data.
    val modelArchive = app.archive
    LaunchedEffect(modelArchive) {
        val a = modelArchive ?: return@LaunchedEffect
        val t = app.today
        app.homeModel = withContext(Dispatchers.Default) { xyz.grtlabs.pilastri.ui.screens.HomeModel(a, t) }
    }
    LaunchedEffect(Unit) {
        app.load()
        // After an app update, a rule the person changed and the shipped pack resolve once (later checkedOn wins, tie to the
        // shipped pack); saved only when that produced a history line.
        runCatching {
            xyz.grtlabs.pilastri.domain.rules.ShippedRules.install(context.applicationContext.assets)
            val shipped = withContext(Dispatchers.Default) { xyz.grtlabs.pilastri.domain.rules.ShippedRules.current() }
            val cur = app.archive
            val now = java.time.Instant.now()
            if (shipped != null && cur != null && withContext(Dispatchers.Default) { xyz.grtlabs.pilastri.domain.rules.RulesHistory.resolveUpdate(cur, shipped, now) } !== cur)
                app.commitNow(quiet = true) { xyz.grtlabs.pilastri.domain.rules.RulesHistory.resolveUpdate(it, shipped, now) }
        }
        // The automatic sync saves through this App from now on, and runs once now; MainActivity.onStart runs it on every return.
        ArchiveSyncHost.bind(app)
        ArchiveSyncHost.foreground(context)
    }
    DisposableEffect(app) { onDispose { ArchiveSyncHost.unbind(app) } }

    /* Backup, restore, the 2026 JSON */
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = app.archive
        if (uri != null && snapshot != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { val bytes = ArchiveCodec.encode(snapshot); context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("open failed") }
                BackupPrefs.markExported(context); app.hx?.confirm(); app.say("Backup esportato. Il file non è cifrato.")
            } catch (_: Exception) { app.say("Esportazione non riuscita. Verifica la destinazione.") }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try { app.restoreCandidate = withContext(Dispatchers.IO) { ArchiveCodec.decode(readLimited(context, uri, 5_000_000)) } }
            catch (_: Exception) { app.say("File non valido o non supportato. Nessun dato è stato modificato.") }
        }
    }
    val prefill = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try { app.prefillCandidate = withContext(Dispatchers.IO) { val b = readLimited(context, uri, 1_000_000); PendingPrefill(Prefill.parse(b), PrefillFacts.parse(b)) } }
            catch (_: Exception) { app.say("JSON 2026 non valido o non supportato. Nessun dato è stato modificato.") }
        }
    }

    /*
     * Connectors: the provider pages. A page that saved through the automatic sync returns RESULT_APPLIED (one message with what
     * arrived); a file (picked, or a download captured on the page) goes through the connector's parser to the preview.
     */
    val tr = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> app.onConnector(ImportProvider.TRADE_REPUBLIC, TrConnector.parseResult(r.resultCode, r.data)) }
    val isyWeb = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == IsybankWebActivity.RESULT_APPLIED) app.reportSynced(ImportProvider.ISYBANK)
        else app.onConnector(ImportProvider.ISYBANK, IsybankConnector.parseResult(r.resultCode, r.data))
    }
    val ppWeb = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == PaypalWebActivity.RESULT_APPLIED) app.reportSynced(ImportProvider.PAYPAL)
        else app.onConnector(ImportProvider.PAYPAL, PaypalConnector.parseResult(r.resultCode, r.data))
    }
    fun fileLauncherResult(provider: ImportProvider, uri: android.net.Uri?) {
        if (uri == null) return
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val bytes = readLimited(context, uri, 20_000_000); val name = displayName(context, uri)
                    if (provider == ImportProvider.ISYBANK) IsybankConnector.parseFile(bytes, name) else PaypalConnector.parseFile(bytes, name)
                }
            } catch (e: Exception) { ConnectorResult.Failed(e.message ?: "File non leggibile.") }
            app.onConnector(provider, result)
        }
    }
    val isyFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { fileLauncherResult(ImportProvider.ISYBANK, it) }
    val ppFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { fileLauncherResult(ImportProvider.PAYPAL, it) }

    /* Gmail: scan; consent goes through Google's own screen, then the scan runs again. */
    lateinit var runGmail: () -> Unit
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && GmailConnector.onConsentResult(context, r.data)) runGmail() else { app.gmailBusy = false; app.say("Accesso a Gmail non concesso.") }
    }
    runGmail = {
        app.gmailBusy = true
        scope.launch {
            val a = app.archive
            val old = a?.installmentSync?.firstOrNull { it.source == InstallmentSource.GMAIL }
            val (since, until) = gmailWindow(old, app.today)
            // Unknown senders go to the AI only through an API-key route the user consented to (redacted by the connector).
            val ai = xyz.grtlabs.pilastri.ai.AiForConnectors.choice(context)
            when (val r = try { GmailConnector.scan(context, since, ai?.transport, ai?.model, until, known = a?.installments ?: emptyList()) } catch (e: Exception) { GmailResult.Failed(e.message ?: "Ricerca in Gmail non riuscita.") }) {
                is GmailResult.Found -> {
                    val at = app.nowStamp()
                    val ok = app.commitNow { cur ->
                        val merged = cur.withInstallmentPlans(r.plans, at).archive
                        val previous = cur.installmentSync.firstOrNull { it.source == InstallmentSource.GMAIL }
                        r.payments.fold(merged) { acc, seen -> acc.withInstallmentPaid(seen, at) }.withInstallmentSync(mergeGmailSync(previous, r.sync))
                    }
                    app.gmailBusy = false
                    if (ok) {
                        app.hx?.confirm()
                        val pending = app.archive?.installments?.count { it.review == PlanReview.PENDING } ?: 0
                        val more = r.sync.coverageFrom?.isAfter(app.today.minusDays(365)) == true
                        app.say("Gmail: ${r.scanned} email lette, ${r.plans.size} piani a rate trovati." + if (more) " Cerca di nuovo per leggere le email più vecchie." else "", if (pending > 0) "Conferma" else null) { app.openIn(Tab.SPESE, Route.PlanReview) }
                    }
                }
                is GmailResult.NeedsConsent -> consent.launch(IntentSenderRequest.Builder(r.consent).build())
                is GmailResult.NotConfigured -> { app.gmailBusy = false; app.notice = "Gmail non ancora configurato" to r.message }
                is GmailResult.Failed -> { app.gmailBusy = false; app.notice = "Gmail: ricerca non riuscita" to r.message }
            }
        }
    }

    /* Dictation into the composers, only where the system offers a recognizer. */
    var dictationTarget by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && text != null) dictationTarget?.invoke(text)
        dictationTarget = null
    }

    SideEffect {
        app.launchers = Launchers(
            exportBackup = { export.launch("pilastri-backup-${app.today}.json") },
            restoreBackup = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            importPrefill = { prefill.launch(arrayOf("application/json", "text/plain")) },
            signIn = { p ->
                AutoSyncKit.interactiveIntent(context, p)?.let { intent ->
                    when (p) {
                        ImportProvider.TRADE_REPUBLIC -> tr.launch(intent)
                        ImportProvider.ISYBANK -> isyWeb.launch(intent)
                        ImportProvider.PAYPAL -> if (PaypalConnector.available) ppWeb.launch(intent)
                    }
                }
            },
            isybankFile = { isyFile.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/csv", "text/comma-separated-values", "application/octet-stream")) },
            paypalFile = { if (PaypalConnector.available) ppFile.launch(arrayOf("text/csv", "text/comma-separated-values", "text/tab-separated-values", "text/plain", "application/zip", "application/octet-stream")) },
            gmailScan = { if (GmailConnector.available && !app.gmailBusy) runGmail() },
            dictate = { target ->
                dictationTarget = target
                speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT"))
            },
        )
    }

    val reduce = LocalReduceMotion.current
    LocalReduceMotionValue.value = reduce
    val pal = LocalPal.current
    BackHandler(enabled = app.nav.depth.isEmpty() && app.nav.tab != Tab.PILASTRI && !app.agentOpen) { app.nav = app.nav.select(Tab.PILASTRI) }

    // Setup (slice 5b) owns the first screen of an empty archive and pushes the app in when it is done.
    xyz.grtlabs.pilastri.ui.setup.SetupFrame(app) {
    BoxWithConstraints(Modifier.fillMaxSize().background(pal.bg).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        val density = LocalDensity.current
        val fold = rememberFoldingFeature()
        // The panes split on a vertical fold's bounds whether it is flat or half-opened (the open Fold reports a flat one).
        val vertical = fold?.takeIf { it.orientation == FoldingFeature.Orientation.VERTICAL }
        val layout = rememberAppLayout(maxWidth.value, maxHeight.value, vertical?.let { with(density) { it.bounds.left.toDp().value } }, vertical?.let { with(density) { it.bounds.width().toDp().value } },
            fold?.orientation == FoldingFeature.Orientation.HORIZONTAL && fold.isSeparating)
        SideEffect { app.onPanes(layout is AppLayout.TwoPane) }
        // The frame (island, Impostazioni, the Agente panel) sits outside the animated container: sections fade through
        // under it, and on two panes only the right pane's stack moves.
        val geometry = remember { IslandGeometry() }
        // G-09: folding or unfolding maps the one logical place before anything of the new shape is composed (Nav.kt
        // folded/unfolded), and the new shape's top page arrives with the page spring instead of being already there.
        val two = layout is AppLayout.TwoPane
        val track = remember { FoldTrack() }
        if (track.lastTwo != null && track.lastTwo != two) {
            val a = app.archive
            val month = if (a == null) YearMonth.from(app.today) else app.home(a).let { m -> defaultShownMonth(app.nav.month, m.timeline, m.flow, app.today) }
            app.nav = if (two) app.nav.unfolded() else app.nav.folded(month.toString())
            track.arrival = Arrival(app.nav.tab, toPanes = two, agent = app.agentOpen)
            geometry.agent = null
        }
        track.lastTwo = two
        val arrival = track.arrival
        LaunchedEffect(arrival) { if (arrival != null) { withFrameNanos { }; withFrameNanos { }; track.arrival = null } }
        val holder = rememberSaveableStateHolder()
        val imeVisible = xyz.grtlabs.pilastri.ui.keyboardShownOrComing()
        // One scroll for the Pilastri root in both shapes (the cover's root page and the open Fold's left pane): G-09.
        val homeScroll = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) { androidx.compose.foundation.ScrollState(0) }
        val tabContent: @Composable (Tab) -> Unit = { t ->
            CompositionLocalProvider(LocalRootScroll provides homeScroll) {
                holder.SaveableStateProvider("tab:$t:$two") { TabBody(app, t, layout, enterTop = arrival?.tab == t && (two || app.nav.stacks.getValue(t).isNotEmpty())) }
            }
        }

        when (layout) {
            AppLayout.Compact -> BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom)).onGloballyPositioned { geometry.overlay = it }) {
                // Pages run under the navigation bar: their bottom room is the bar plus the dock above it, so the dock
                // never covers the last row with any navigation mode.
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                // The dock slides away with the keyboard (a fade under reduced motion); the Agente page's room follows it.
                val dockShown by animateFloatAsState(if (imeVisible) 0f else 1f, if (reduce) Motion.reducedFade() else Motion.spatial(), label = "dockShown")
                // G-10: on the cover Agente is a normal peer page: the dock's pill on it, the peer fade-through, Back like the
                // other peers (to the section it was opened over), its thread and draft kept in the assistant's state. The
                // panel morph belongs to the open Fold only, so it rests at 0 here and unfolding with Agente open grows the panel.
                LaunchedEffect(Unit) { app.agentMorph.snapTo(0f) }
                LaunchedEffect(app.agentOpen) { if (!app.agentOpen) app.agentClosed() }
                BackHandler(enabled = app.agentOpen) { app.closeAgent() }
                val arrive = remember { Animatable(if (arrival?.agent == true && !arrival.toPanes) 0f else 1f) }
                LaunchedEffect(Unit) { if (arrive.value < 1f) arrive.animateTo(1f, if (reduce) Motion.reducedFade() else Motion.spatial()) }
                CompositionLocalProvider(LocalDockInset provides navBottom + IslandSpec.CoverScrollInset) {
                    val peer: Tab? = if (app.agentOpen) null else app.nav.tab
                    AnimatedContent(peer, Modifier.fillMaxSize().graphicsLayer {
                        alpha = arrive.value.coerceIn(0f, 1f)
                        if (!reduce) { val k = 0.985f + 0.015f * arrive.value; scaleX = k; scaleY = k }
                    }, transitionSpec = peerTransition(reduce, ::peerOrder), label = "peer") { t ->
                        if (t != null) tabContent(t)
                        else app.archive?.let { archive ->
                            xyz.grtlabs.pilastri.ai.AssistantPage(archive, app.aiCommit, Modifier.fillMaxSize(), app.assistant,
                                insets = PaddingValues(bottom = (navBottom + IslandSpec.CoverPageInset) * dockShown.coerceIn(0f, 1f)))
                        }
                    }
                }
                CoverDock(app, geometry, visible = !imeVisible, shown = { dockShown },
                    Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)).padding(bottom = IslandSpec.DockBottom))
            }
            AppLayout.RailSingle -> BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
                .onGloballyPositioned { geometry.overlay = it }) {
                CompositionLocalProvider(LocalDockInset provides IslandSpec.FoldScrollInset) {
                    AnimatedContent(app.nav.tab, Modifier.fillMaxSize().padding(end = IslandSpec.ColumnWidth), transitionSpec = peerTransition(reduce, ::peerOrder), label = "tab") { t -> tabContent(t) }
                }
                AgentHost(app, geometry, Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), PaddingValues(end = IslandSpec.ColumnWidth))
                FoldIsland(app, showBack = false, geometry, Modifier.align(Alignment.CenterEnd).padding(end = IslandSpec.Margin))
                SettingsButton(app, Modifier.align(Alignment.BottomEnd).padding(end = IslandSpec.GearEnd, bottom = 8.dp))
            }
            is AppLayout.TwoPane -> BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
                .onGloballyPositioned { geometry.overlay = it }) {
                AnimatedContent(app.nav.tab, Modifier.fillMaxSize(), transitionSpec = peerTransition(reduce, ::peerOrder), label = "tab") { t -> tabContent(t) }
                // The crease: a hairline where the panes meet (no gap and no reserved column; nothing else crosses it).
                if (layout.gap.value < 1f) Box(Modifier.offset(x = layout.left - 0.5.dp).width(1.dp).fillMaxHeight().background(pal.cardLine))
                AgentTintLayer(app, Modifier.width(layout.left).fillMaxHeight())
                val paneX = with(density) { (layout.left + layout.gap).toPx() }
                AgentHost(app, geometry, Rect(paneX, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), PaddingValues(end = IslandSpec.ColumnWidth))
                FoldIsland(app, showBack = app.nav.islandBackShown(app.agentOpen), geometry, Modifier.align(Alignment.CenterEnd).padding(end = IslandSpec.Margin))
                SettingsButton(app, Modifier.align(Alignment.BottomEnd).padding(end = IslandSpec.GearEnd, bottom = 8.dp))
            }
        }
        ToastHost(app, Modifier.align(Alignment.BottomCenter).padding(bottom = when (layout) { AppLayout.Compact -> 96.dp; else -> 16.dp }).navigationBarsPadding())
    }
    }

    app.preview?.let { ImportSheet(app, it) { app.preview = null } }
    app.restoreCandidate?.let { RestoreDialog(app, it) }
    app.prefillCandidate?.let { PrefillDialog(app, it) }
    app.notice?.let { (t, b) -> InfoDialog(t, b) { app.notice = null } }
}

/**
 * Which Gmail window to read: one year back on the first scan; while an earlier scan stopped at its message cap
 * (coverage starts later than a year ago) the older window up to that start; otherwise new mail since a week before
 * the last coverage end.
 */
private fun gmailWindow(old: InstallmentSync?, today: java.time.LocalDate): Pair<java.time.LocalDate, java.time.LocalDate?> {
    val start = today.minusDays(365)
    val from = old?.coverageFrom; val to = old?.coverageTo
    return when {
        from == null || to == null -> start to null
        from.isAfter(start) -> start to from
        else -> to.minusDays(7) to null
    }
}

/** Joins a new scan's coverage with the stored one when the two windows touch; otherwise the new one stands alone. */
private fun mergeGmailSync(old: InstallmentSync?, new: InstallmentSync): InstallmentSync {
    val of = old?.coverageFrom; val ot = old?.coverageTo; val nf = new.coverageFrom; val nt = new.coverageTo
    if (of == null || ot == null || nf == null || nt == null) return new
    val touch = !nt.isBefore(of.minusDays(1)) && !nf.isAfter(ot.plusDays(1))
    return if (touch) new.copy(coverageFrom = minOf(of, nf), coverageTo = maxOf(ot, nt)) else new
}

/** Remembers the last window shape and the arrival that follows a fold or an unfold (G-09). */
private class FoldTrack {
    var lastTwo: Boolean? = null
    var arrival by mutableStateOf<Arrival?>(null)
}

/** What arrives after a fold ([toPanes] false) or an unfold: [tab]'s top page with a push, Agente with its own entrance. */
private data class Arrival(val tab: Tab, val toPanes: Boolean, val agent: Boolean)

/** A touch anywhere in a pane, observed before the content and never consumed (G-09: which pane is "the place"). */
private fun Modifier.onPaneTouch(key: Any, touched: () -> Unit) = pointerInput(key) {
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); touched() }
}

/**
 * One tab: on one pane its stack; on two panes its root on the left (outside the animated stack) and the stack on the right.
 * [enterTop]: just folded or unfolded, the top page arrives as a push (G-09).
 */
@Composable
private fun TabBody(app: App, tab: Tab, layout: AppLayout, enterTop: Boolean = false) {
    val pal = LocalPal.current
    val stack = app.nav.stacks.getValue(tab)
    val dir: (Route, Route) -> Int = { a, b -> val x = routeOrder(a); val y = routeOrder(b); if (y >= x) 1 else -1 }
    if (layout is AppLayout.TwoPane) {
        Row(Modifier.fillMaxSize()) {
            // The left pane: the section's root, outside the animated stack; its scroll clears the Impostazioni button.
            Box(Modifier.width(layout.left).fillMaxHeight().paneLeftTag().onPaneTouch(tab) { app.nav = app.nav.touchPane(tab, right = false) }) {
                CompositionLocalProvider(LocalDockInset provides IslandSpec.FoldScrollInset) { LeftPane(app, tab) }
            }
            Spacer(Modifier.width(layout.gap))
            // The right pane: A ends at the island column (80 dp); B ends 68 dp from the edge and its cards' peek runs under the island (IslandSpec).
            // N-04: while the Agente panel covers this pane its page leaves the accessibility tree (one page header per pane);
            // the left pane stays in it (AgentTintLayer has no semantics of its own: a covering semantics node would hide the pane).
            Box(Modifier.width(layout.right).fillMaxHeight().then(if (app.agentOpen) Modifier.clearAndSetSemantics { testTag = UiTag.PANE_RIGHT } else Modifier.paneRightTag())
                .padding(end = IslandSpec.ColumnWidth).onPaneTouch(tab) { app.nav = app.nav.touchPane(tab, right = true) }) {
                CompositionLocalProvider(LocalInPane provides true, LocalBackInFrame provides true, LocalDockInset provides 0.dp) {
                    StackHost(listOf(Route.PaneRoot(tab)) + stack, onBack = { app.nav = app.nav.pop() }, keyOf = { it.key }, modifier = Modifier.fillMaxSize(),
                        backEnabled = !app.agentOpen, swapDirection = dir, enterTop = enterTop) { route ->
                        RouteContent(app, route)
                    }
                }
            }
        }
    } else {
        StackHost(listOf(Route.Root(tab)) + stack, onBack = { app.nav = app.nav.pop() }, keyOf = { it.key }, modifier = Modifier.fillMaxSize(), backEnabled = !app.agentOpen, swapDirection = dir, enterTop = enterTop) { route ->
            RouteContent(app, route)
        }
    }
}

/** The peers in dock order: Pilastri, Spese, Futuro, then Agente (no tab) on the cover. */
internal fun peerOrder(t: Tab?): Int = t?.ordinal ?: Tab.entries.size

/**
 * The one transition between peers (top-level destinations), on the cover and on the open Fold: the old page leaves a
 * tenth of the width towards where the dock pill goes from, the new one arrives from the other side with a fade after it
 * (index-directional slide on the page offset spring; alpha on short tweens). Reduced motion: the short crossfade.
 */
internal fun <S> peerTransition(reduce: Boolean, order: (S) -> Int): AnimatedContentTransitionScope<S>.() -> ContentTransform = {
    if (reduce) reducedSwap()
    else {
        val dir = if (order(targetState) >= order(initialState)) 1 else -1
        (fadeIn(tween(210, 90)) + slideInHorizontally(Motion.pageOffset()) { dir * it / 10 }) togetherWith
            (fadeOut(tween(90)) + slideOutHorizontally(Motion.pageOffset()) { -dir * it / 10 })
    }
}

/** True inside the right pane of two panes, where pages sit on the white pane and their cards on the lilac inset. */
val LocalInPane = staticCompositionLocalOf { false }

/** The Pilastri root's scroll, shared by the cover's root page and the open Fold's left pane (G-09: folding keeps the place). */
val LocalRootScroll = staticCompositionLocalOf<androidx.compose.foundation.ScrollState?> { null }

/** Room to leave at the bottom of scrolling pages so the island or the Impostazioni button never hides the last row. */
val LocalDockInset = staticCompositionLocalOf { 0.dp }

private fun routeOrder(r: Route): Int = when (r) { is Route.Pillar -> r.pillar; else -> 0 }

@Composable
private fun LeftPane(app: App, tab: Tab) {
    when (tab) {
        Tab.PILASTRI -> HomeScreen(app, wide = true)
        Tab.SPESE -> SpendingLeft(app)
        Tab.FUTURO -> FutureScreen(app, wide = true)
    }
}

@Composable
fun RouteContent(app: App, route: Route) {
    val archive = app.archive
    if (archive == null) { LoadingOrRecovery(app); return }
    when (route) {
        is Route.Root -> when (route.tab) {
            Tab.PILASTRI -> HomeScreen(app, wide = false)
            Tab.SPESE -> SpendingScreen(app)
            Tab.FUTURO -> FutureScreen(app, wide = false)
        }
        is Route.PaneRoot -> when (route.tab) {
            Tab.PILASTRI -> MeseScreen(app, null, root = true)
            Tab.SPESE -> SpendingPane(app)
            Tab.FUTURO -> ScenariosPane(app)
        }
        is Route.Pillar -> PillarScreen(app, route.pillar)
        is Route.Month -> MeseScreen(app, java.time.YearMonth.parse(route.month), root = false)
        is Route.Category -> CategoryScreen(app, route.id)
        Route.Review -> ReviewScreen(app)
        Route.Payday -> PaydayScreen(app)
        is Route.Plan -> PlanScreen(app, route.id)
        Route.PlanReview -> PlanReviewScreen(app)
        Route.Settings -> SettingsScreen(app)
        Route.Connections -> ConnectionsScreen(app)
        Route.Backup -> BackupScreen(app)
        Route.Categories -> CategoriesScreen(app)
        Route.Targets -> TargetsScreen(app)
        Route.Scenarios -> ScenarioEditorScreen(app)
        Route.Contributions -> ContributionsScreen(app)
        Route.Rules -> RulesPage(app)
        Route.Pension -> PensionScreen(app)
        is Route.Account -> AccountScreen(app, route.id)
    }
}

private typealias YearMonth = java.time.YearMonth

/* ---------- Toast ---------- */

@Composable
private fun ToastHost(app: App, modifier: Modifier) {
    val pal = LocalPal.current
    val msg = app.toast
    var shown by remember { mutableStateOf<ToastMsg?>(null) }
    val lift = remember { Animatable(0f) }
    val reduce = LocalReduceMotion.current
    LaunchedEffect(msg) {
        if (msg != null) {
            if (shown != null) lift.travelTo(0f, reduce, Motion.spatial())
            shown = msg
            lift.travelTo(1f, reduce, Motion.spatial())
            delay(if (msg.action != null) 6000 else 3500)
            lift.travelTo(0f, reduce, Motion.spatial())
            shown = null
            if (app.toast?.id == msg.id) app.toast = null
        }
    }
    val m = shown ?: return
    Row(
        modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth()
            .graphicsLayer { alpha = lift.value.coerceIn(0f, 1f); if (!reduce) translationY = (1f - lift.value) * 48.dp.toPx() }
            .clip(RoundedCornerShape(22.dp)).background(pal.composer).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(m.text, style = Type.body, color = pal.onAccent, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        if (m.action != null && m.onAction != null) PillButton(m.action, { app.toast = null; m.onAction.invoke() }, kind = Btn.ON_ACCENT)
    }
}

@Composable
private fun LoadingOrRecovery(app: App) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!app.loaded) { Text("Caricamento…", style = Type.body, color = pal.ink2); return@Column }
        Text("Dati non leggibili", style = Type.title, color = pal.ink)
        EmptyNote("I dati locali non si possono leggere", app.loadError ?: "Errore sconosciuto.", action = "Ripristina un backup", onAction = { app.launchers.restoreBackup() })
    }
}
