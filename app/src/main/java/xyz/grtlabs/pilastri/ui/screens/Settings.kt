package xyz.grtlabs.pilastri.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import xyz.grtlabs.pilastri.ai.AiConnectionsSection
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.sync.ConnectorSpec
import xyz.grtlabs.pilastri.sync.Connectors
import xyz.grtlabs.pilastri.sync.ProviderSyncPage
import xyz.grtlabs.pilastri.sync.auto.AutoSyncKit
import xyz.grtlabs.pilastri.sync.auto.CredentialVault
import xyz.grtlabs.pilastri.sync.auto.ProviderSyncState
import xyz.grtlabs.pilastri.sync.auto.UserAction
import xyz.grtlabs.pilastri.sync.gmail.GmailConnector
import xyz.grtlabs.pilastri.sync.paypal.PaypalConnector
import xyz.grtlabs.pilastri.sync.tr.TrConnector
import xyz.grtlabs.pilastri.ui.*
import xyz.grtlabs.pilastri.ui.setup.LocalBankFlow

/** The date of the last successful backup export: a UI preference only, never part of the archive. */
object BackupPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)
    fun markExported(c: Context) = prefs(c).edit().putString("lastBackup", LocalDate.now().toString()).apply()
    fun lastExport(c: Context): LocalDate? = prefs(c).getString("lastBackup", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

@Composable
fun SettingsScreen(app: App) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val last = remember { BackupPrefs.lastExport(ctx) }
    Page(horizontal = PageSpec.gutter(app.twoPane), top = PageSpec.Top) {
        // G-12: a top-level page like the other four: no Back in the header (the dock or the island switches page; system Back still works), so the title keeps its left edge.
        BackHeader("Impostazioni", "dati sul dispositivo" + sampleSuffix(a), null) {
            HelpButton("Dati e licenze", "I dati restano in questo telefono, nella memoria privata dell'app. L'archivio e i backup non sono cifrati. Sessioni, cookie e chiavi non entrano mai nell'archivio né nei backup.\n\nRoboto Flex: SIL Open Font License. Selettore ispirato a Xtraordinary (MIT).")
        }
        val choice = AppearanceStore.get(ctx)
        TitledCard("Aspetto", verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Segmented(Appearance.entries.map { it.label }, choice.ordinal, { AppearanceStore.set(ctx, Appearance.entries[it]) })
        }
        RowsCard {
            // U-09: only what is known; an unknown position is simply left out (no "n/d" next to providers that have data), n/d only when nothing is known.
            val providerCount = a.connections.map { it.provider }.distinct().size
            ListRow("Conti", listOfNotNull(a.positionKinds().takeIf { it.isNotEmpty() }?.let { k -> xyz.grtlabs.pilastri.ui.setup.PositionCatalog.order.filter { it in k }.joinToString(", ") { xyz.grtlabs.pilastri.ui.setup.PositionCatalog.label(it) } },
                providerCount.takeIf { it > 0 }?.let { if (it == 1) "1 fornitore" else "$it fornitori" }).joinToString(" · ").ifEmpty { "n/d" }, { app.go(Route.Connections) }, leading = { IconDisc(Ic.link, pal.lilacBg, pal.lilacInk, 32.dp) })
            ListRow("Dati e backup", last?.let { "ultimo backup ${Fmt.dayYear(it)}" } ?: "nessun backup esportato da qui", { app.go(Route.Backup) }, leading = { IconDisc(Ic.shield, pal.lilacBg, pal.lilacInk, 32.dp) })
            ListRow("Categorie e budget", "${a.planning.categories.count { it.kind == CategoryKind.SPEND }} categorie di spesa", { app.go(Route.Categories) }, leading = { IconDisc(Ic.tag, pal.lilacBg, pal.lilacInk, 32.dp) })
            ListRow("Obiettivi dei pilastri", "Liquidità, Emergenze, Progetti", { app.go(Route.Targets) }, leading = { IconDisc(Ic.target, pal.lilacBg, pal.lilacInk, 32.dp) })
        }
        // N-05: "Fonti e regole" is one row that opens the history page, as on Previdenza (board 4b); no standing section label.
        RowsCard { ListRow("Fonti e regole", null, { app.go(Route.Rules) }) }
        if (xyz.grtlabs.pilastri.BuildConfig.DEBUG && SampleHook.build != null) { // U-19: debug build type only, never on fast/release.
            SectionHeader("Debug")
            TitledCard("Dati di esempio", verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (a.isSample) "caricati · solo sintetici" else if (a.isEmpty) "archivio vuoto" else "non disponibili: ci sono dati veri", style = Type.small, color = pal.ink2)
                if (a.isSample) PillButton("Rimuovi i dati di esempio", { app.clearSample() }, kind = Btn.OUTLINE)
                else if (a.isEmpty) PillButton("Carica i dati di esempio", { app.loadSample() }, kind = Btn.OUTLINE)
            }
        }
    }
}

/* ---------- Collegamenti ---------- */

private data class ProviderState(val label: String, val warn: Boolean)

@Composable
fun ConnectionsScreen(app: App) {
    val a = app.archive ?: return
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    // Null when the Keystore is unusable: then "Ricorda l'accesso" is not offered.
    val vault = remember { AutoSyncKit.vault(ctx) }
    // Connectors from the registry: only what the person's positions use, and only implemented ones (Council 4 e).
    val offered = remember(a) { Connectors.offered(a.positionKinds()) }
    fun offers(spec: ConnectorSpec) = offered.any { it.id == spec.id }
    key(refresh) {
        Page {
            BackHeader("Conti", null, { app.back() }) { HelpButton("Conti", ConnectHelp) }
            SectionHeader("Posizione e fondi")
            PositionsCard(app, a)
            FundsCard(app, a)
            if (offers(Connectors.ENABLE_BANKING)) {
                val bankFlow = LocalBankFlow.current
                SectionHeader("Banche · Enable Banking", trailing = bankFlow?.let { flow ->
                    { PillButton("Collega un conto", { flow.open(null) }, kind = Btn.GHOST, icon = Ic.plus, height = 36.dp) }
                })
                BanksCard(app, a)
            }
            val others = (offers(Connectors.TRADE_REPUBLIC)) || (offers(Connectors.PAYPAL) && PaypalConnector.available) || (offers(Connectors.GMAIL) && GmailConnector.available)
            val trOn = offers(Connectors.TRADE_REPUBLIC)
            val ppOn = offers(Connectors.PAYPAL) && PaypalConnector.available
            if (others && !trOn && !ppOn) SectionHeader("Altri conti")
            // Board 6: Trade Republic and PayPal are rows of ONE "Altri conti" card.
            if (trOn || ppOn) TitledCard("Altri conti", verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (trOn) SyncedProviderCard(app, ImportProvider.TRADE_REPUBLIC, Ic.futuro, vault = vault, pickFile = null, embedded = true) { refresh++ }
                if (trOn && ppOn) Hairline()
                if (ppOn) SyncedProviderCard(app, ImportProvider.PAYPAL, Ic.card, vault, pickFile = { app.launchers.paypalFile() }, embedded = true) { refresh++ }
            }
            if (offers(Connectors.GMAIL) && GmailConnector.available) {
                val linked = remember { GmailConnector.isLinked(ctx) }
                val sync = a.installmentSync.firstOrNull { it.source == InstallmentSource.GMAIL }
                ProviderCard("Gmail", Ic.mail, ProviderState(if (linked) "collegato" else "non collegato", false),
                    sync?.let { s -> s.coverageTo?.let { "email fino al ${Fmt.day(it)}" } ?: s.lastScanAt?.let { runCatching { "cercato ${Fmt.relative(Instant.parse(it).localDate(), app.today)}" }.getOrNull() } },
                    secondary = if (linked) { m -> PillButton("Scollega", { GmailConnector.disconnect(ctx); refresh++; app.say("Gmail scollegato.") }, m, kind = Btn.OUTLINE) } else null,
                    confirm = { m -> PillButton(if (app.gmailBusy) "Ricerca in corso…" else if (linked) "Cerca rate" else "Collega e cerca", { app.launchers.gmailScan() }, m, kind = Btn.PRIMARY, enabled = !app.gmailBusy, icon = Ic.search) })
            }
            if (offers(Connectors.ASSISTANT)) {
                SectionHeader("Assistenti")
                AiConnectionsSection(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ProviderCard(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, state: ProviderState, sync: String?, secondary: (@Composable (Modifier) -> Unit)? = null, confirm: @Composable (Modifier) -> Unit) {
    val pal = LocalPal.current
    // Board 6: the provider is a title + one state line (no icon disc); the header is the shared card header.
    CardColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardHeader(name, trailing = { Tag(state.label) })
        if (sync != null && sync.isNotBlank()) Text(sync, style = Type.small, color = pal.ink2)
        RoleActionRow("provider", spacing = 8.dp, secondary = secondary, confirm = confirm)
    }
}

/**
 * One provider of the automatic sync (AGENTS.md: connectors work on their own; sync is automatic and invisible). One plain
 * state line: how recent the data is, a thin bar while it syncs, or the one thing the person must do. "Collega" until the
 * provider is linked; after that the sync runs by itself and "Aggiorna" is only a small secondary action, next to the file
 * fallback and "Scollega". Not a redesign: the card keeps the layout of the other providers.
 */
@Composable
private fun SyncedProviderCard(app: App, p: ImportProvider, icon: ImageVector, vault: CredentialVault?, pickFile: (() -> Unit)?, embedded: Boolean = false, onDisconnected: () -> Unit) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val reduce = LocalReduceMotion.current
    val state by remember(p) { AutoSyncKit.state(ctx, p) }.collectAsState()
    val linked = remember(p, state) { AutoSyncKit.coordinator(ctx).isLinked(p) }
    // isybank stays a page used by hand until its export request is recorded: no automatic sync and nothing to remember yet.
    val automatic = remember(p) { p == ImportProvider.TRADE_REPUBLIC || ProviderSyncPage.isAutomatic(p) }
    val archived = app.archive?.connections?.filter { it.provider == p }?.mapNotNull { it.coverageTo }?.maxOrNull()
    val session = remember(p, state) {
        when (p) {
            ImportProvider.TRADE_REPUBLIC -> TrConnector.hasSession(ctx)
            ImportProvider.PAYPAL -> PaypalConnector.hasSession(ctx)
            ImportProvider.ISYBANK -> archived != null
        }
    }
    fun freshness(f: ProviderSyncState.Fresh?): String? = f?.coverageTo?.let { "dati fino al ${Fmt.day(it)}" }
        ?: f?.let { "aggiornato ${Fmt.relative(it.syncedAt.localDate(), app.today)}" }
        ?: archived?.let { "dati fino al ${Fmt.day(it)}" }
    val s = state
    val (line, warn) = when (s) {
        ProviderSyncState.Idle -> freshness(null) to false
        is ProviderSyncState.Fresh -> freshness(s) to false
        is ProviderSyncState.Syncing -> freshness(s.last) to false
        is ProviderSyncState.NeedsUser -> s.line to true
        is ProviderSyncState.Failed -> s.message to true
    }
    val body: @Composable ColumnScope.() -> Unit = {
        AnimatedContent(line to warn, transitionSpec = fadeThrough(reduce), label = "sync-line") { (text, attention) ->
            if (text != null) Row(verticalAlignment = Alignment.CenterVertically) {
                // CR-60: the warning colour is only for `over`; a step the person must take is the ink colour with its icon.
                if (attention) { Icon(Ic.warning, null, Modifier.size(14.dp), tint = pal.ink); Spacer(Modifier.width(4.dp)) }
                Text(text, style = Type.small, color = if (attention) pal.ink else pal.ink2)
            }
        }
        AnimatedVisibility(s is ProviderSyncState.Syncing, enter = fadeIn(Motion.effects()), exit = fadeOut(Motion.effects()), label = "sync-bar") {
            SyncBar()
        }
        if (vault != null && automatic && p != ImportProvider.TRADE_REPUBLIC) RememberRow(p, vault, s)
        // Trade Republic signs in with phone and PIN: its switch is about the PIN, not the "Ricorda l'accesso" of the others.
        if (vault != null && p == ImportProvider.TRADE_REPUBLIC) TrPinRow(vault)
        val forward: String? = when {
            s is ProviderSyncState.NeedsUser && s.needsPage -> actionLabel(s.action)
            !linked -> "Collega"
            else -> null
        }
        RoleActionRow("provider", spacing = 8.dp,
            secondary = { m ->
                if (linked && automatic) PillButton("Aggiorna", { app.refresh(p) }, m, kind = Btn.GHOST, icon = Ic.refresh, enabled = s !is ProviderSyncState.Syncing, height = 36.dp)
                if (pickFile != null) PillButton("Carica file", pickFile, m, kind = Btn.GHOST, icon = Ic.upload, height = 36.dp)
                if (linked || session) PillButton("Scollega", {
                    AutoSyncKit.disconnect(ctx, p); onDisconnected(); app.say("Accesso ${p.label} rimosso da questo telefono.")
                }, m, kind = Btn.GHOST, height = 36.dp)
            },
            confirm = forward?.let { label -> { m -> PillButton(label, { app.signIn(p) }, m, kind = Btn.PRIMARY) } })
    }
    // Embedded in the "Altri conti" card (board 6): a row title and the same body; alone: its own card with the shared header.
    if (embedded) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(p.label, style = Type.rowTitle, color = pal.ink); body() }
    else CardColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { CardHeader(p.label); body() }
}

private fun actionLabel(a: UserAction) = when (a) {
    UserAction.SIGN_IN_AGAIN -> "Accedi"
    UserAction.APPROVE_2FA -> "Conferma"
    UserAction.CHALLENGE -> "Verifica"
}

/** "Ricorda l'accesso": off deletes the saved sign-in at once. The second line is its state, not an explanation. */
@Composable
private fun RememberRow(p: ImportProvider, vault: CredentialVault, state: ProviderSyncState) {
    val pal = LocalPal.current
    val hx = rememberHx()
    var on by remember(p) { mutableStateOf(vault.isRemembered(p)) }
    val saved = remember(p, on, state) { vault.has(p) }
    val rejected = remember(p, on, state) { vault.isRejected(p) }
    val note = when {
        !on -> null
        rejected -> "rifiutato da ${p.label}: accedi di nuovo"
        saved -> "salvato e cifrato sul telefono"
        else -> "si salva al prossimo accesso"
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .pressable(RoundedCornerShape(16.dp), pal.pressTint, role = Role.Switch, onClick = { on = !on; vault.setRemember(p, on); hx.toggle(on) })
            .semantics { stateDescription = if (on) "attivo" else "spento" }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Ricorda l'accesso", style = Type.body, color = pal.ink)
            if (note != null) Text(note, style = Type.small, color = pal.ink2)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

/**
 * "Ricorda il PIN" (Trade Republic, on by default): off deletes the stored PIN at once and the automatic reading stops until it is off again.
 * The second line is the cost of the switch and shows in both states. Shared with setup › Altri conti.
 */
@Composable
internal fun TrPinRow(vault: CredentialVault) {
    val pal = LocalPal.current
    val hx = rememberHx()
    var declined by remember { mutableStateOf(vault.isTrPinDeclined()) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .pressable(RoundedCornerShape(16.dp), pal.pressTint, role = Role.Switch, onClick = { declined = !declined; vault.setTrPinDeclined(declined); hx.toggle(!declined) })
            .semantics { stateDescription = if (declined) "spento" else "attivo" }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(TrPinLabel, style = Type.body, color = pal.ink)
            Text(trPinLine(declined), style = Type.small, color = pal.ink2)
        }
        Switch(checked = !declined, onCheckedChange = null)
    }
}

/** Everything about connecting accounts, behind the "?" of "Collega conti". */
val ConnectHelp: String get() {
    val isyAuto = ProviderSyncPage.isAutomatic(ImportProvider.ISYBANK)
    return "Tutti i collegamenti sono in sola lettura.\n\n" +
        "Trade Republic e PayPal" + (if (isyAuto) ", isybank" else "") + ": accedi una volta sul sito del fornitore, dentro l'app. La sessione resta nel telefono e Pilastri legge i dati da solo, subito e ogni volta che apri l'app (al massimo ogni 15 minuti). I movimenti nuovi arrivano in \"Da rivedere\". Qui vedi fino a che data arrivano i dati, oppure cosa serve da te.\n\n" +
        "Ricorda l'accesso (PayPal" + (if (isyAuto) " e isybank" else "") + "): Pilastri salva nome utente e password, cifrati con il Keystore del telefono, e li inserisce da sé quando la sessione scade. Non entrano mai nell'archivio, nei backup o nell'assistente. Le conferme (notifica nell'app del fornitore, codice SMS) restano tue. Se il fornitore rifiuta l'accesso salvato, Pilastri non riprova da solo. Senza Ricorda l'accesso, Pilastri non vede password, PIN o codici.\n\n" +
        (if (isyAuto) "" else "isybank: non ha un'API per i clienti e la lettura automatica non è ancora pronta. Accedi e scarica l'Excel dei movimenti dal sito di isybank dentro l'app, o scegli un file già scaricato.\n\n") +
        "Carica file: un export scaricato a mano (Excel di isybank, attività di PayPal), se serve.\n\n" +
        "Gmail: legge le ricevute degli acquisti a rate (PayPal in 3 rate, Klarna, Scalapay…); le email non vengono salvate. Oggetto e un estratto delle email di mittenti sconosciuti vanno all'assistente solo se attivi \"Rate da email sconosciute\" in Assistenti. Google chiede di nuovo il consenso ogni 7 giorni finché l'app è in prova.\n\n" +
        "Scollega rimuove da questo telefono la sessione, i cookie e l'accesso salvato di quel fornitore."
}

/* ---------- Backup ---------- */

@Composable
fun BackupScreen(app: App) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val last = remember(app.toast) { BackupPrefs.lastExport(ctx) }
    Page {
        BackHeader("Dati e backup", last?.let { "ultimo backup ${Fmt.dayYear(it)}" } ?: "nessun backup esportato da qui", { app.back() })
        TitledCard("Esporta", verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${a.accounts.size} conti · ${a.events.size} registrazioni · ${a.externalMovements.size} movimenti · non cifrato", style = Type.small, color = pal.ink2)
            PillButton("Esporta backup", { app.launchers.exportBackup() }, kind = Btn.PRIMARY, icon = Ic.upload)
        }
        TitledCard("Ripristina", verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("sostituisce i dati attuali, dopo un'anteprima", style = Type.small, color = pal.ink2)
            PillButton("Scegli un backup", { app.launchers.restoreBackup() }, kind = Btn.TONAL)
        }
        TitledCard("Indici ISTAT", verticalArrangement = Arrangement.spacedBy(8.dp), trailing = {
            Tag(a.indices.maxOfOrNull { it.month }?.let { "fino a ${Fmt.monthShort(it)} ${it.year}" } ?: "nessuno")
            HelpButton("Indici ISTAT", "NIC generale con tabacchi, base 2025=100: copia verificata della tavola ISTAT di settembre 2026 (gennaio–settembre). Servono per gli euro di gennaio 2026; i mesi successivi restano in attesa dell'indice, non stimati. Serie diverse non si mescolano.")
        }) {
            val missing = xyz.grtlabs.pilastri.data.Istat.snapshot().count { p -> a.indices.none { it.month == p.month } }
            val foreign = a.indices.any { it.source != xyz.grtlabs.pilastri.data.Istat.SERIES }
            if (foreign) Text("Gli indici presenti usano un'altra serie: non si mescolano.", style = Type.small, color = pal.overInk)
            else if (missing > 0) PillButton("Aggiungi $missing mesi", { app.commit("Indici ISTAT aggiunti.") { it.withIstatSnapshot().first } }, kind = Btn.TONAL)
        }
        TitledCard("JSON dei dati 2026", verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("aggiunge senza sostituire, dopo un'anteprima", style = Type.small, color = pal.ink2)
            PillButton("Importa JSON", { app.launchers.importPrefill() }, kind = Btn.OUTLINE)
        }
    }
}

/* ---------- Categorie e budget ---------- */

@Composable
fun CategoriesScreen(app: App) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val month = YearMonth.from(app.today)
    var editing by remember { mutableStateOf<Category?>(null) }
    var newName by remember { mutableStateOf("") }
    val cats = a.planning.categories.filter { it.kind == CategoryKind.SPEND }
    Page {
        BackHeader("Categorie e budget", "budget mensile da ${Fmt.month(month)}", { app.back() }) {
            HelpButton("Categorie e budget", "Il budget di una categoria vale da questo mese in poi. Le categorie essenziali servono a stimare quanti mesi di spese copre la riserva di Emergenze.")
        }
        RowsCard {
            if (cats.isEmpty()) Text("Nessuna categoria.", style = Type.body, color = pal.ink2, modifier = Modifier.padding(12.dp))
            // Lazy-UX #13: the budget is edited on the row; the row opens the sheet for the full edit.
            cats.forEach { c -> BudgetRow(app, c, a.budgetFor(c.id, month), month) { editing = c } }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(newName, { newName = it }, "Nuova categoria", Modifier.weight(1f))
            PillButton("Aggiungi", { val n = newName.trim(); if (n.isNotEmpty()) app.commit("Categoria aggiunta.") { it.withNewCategory(n).first }; newName = "" }, kind = Btn.TONAL, enabled = newName.isNotBlank())
        }
    }
    editing?.let { c ->
        var essential by remember(c.id) { mutableIntStateOf(when (c.essential) { Essential.ESSENTIAL -> 0; Essential.DISCRETIONARY -> 1; Essential.UNSET -> 2 }) }
        val current = a.budgetFor(c.id, month)
        var text by remember(c.id) { mutableStateOf(current?.let { java.math.BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString().replace('.', ',') } ?: "") }
        val cents = parseEuroInput(text)
        Sheet({ editing = null }) {
            Text(c.name, style = Type.screenTitle, color = pal.ink)
            TextInput(text, { text = it }, "Budget mensile in euro (vuoto: nessun budget)", number = true, center = true, big = true)
            (readEuroInput(text) as? EuroInput.Invalid)?.takeIf { text.isNotBlank() }?.let { Text(it.message, style = Type.small, color = pal.overInk) }
            Segmented(listOf("Essenziale", "Non essenziale", "Non indicato"), essential, { essential = it })
            // No "Annulla": the sheet closes by swipe, scrim or Back (the redundant ghost button was removed).
            RoleActionRow("budget", spacing = 8.dp, end = true, confirm = { m -> PillButton("Salva", {
                    val e = listOf(Essential.ESSENTIAL, Essential.DISCRETIONARY, Essential.UNSET)[essential]
                    val budget = if (text.isBlank()) null else cents
                    if (text.isBlank() || cents != null) {
                        app.commit("Categoria aggiornata.") { it.withCategoryEssential(c.id, e).withBudget(c.id, budget, month) }
                        editing = null
                    }
                }, m, kind = Btn.PRIMARY, enabled = text.isBlank() || cents != null) })
        }
    }
}

/** A category with its monthly budget as an editable chip: typing saves on Done or when the field loses focus; empty = no budget. */
@Composable
private fun BudgetRow(app: App, c: Category, budget: Long?, month: YearMonth, onEdit: () -> Unit) {
    val pal = LocalPal.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var text by remember(c.id, budget) { mutableStateOf(budget?.let { java.math.BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString().replace('.', ',') } ?: "") }
    val invalid = text.isNotBlank() && parseEuroInput(text) == null
    fun save() {
        val t = text.trim()
        val cents = if (t.isEmpty()) null else parseEuroInput(t) ?: return
        if (cents != budget) app.commit("Budget aggiornato.") { it.withBudget(c.id, cents, month) }
    }
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).pressable(RoundedCornerShape(20.dp), pal.pressTint, onClick = onEdit).padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.name, style = Type.rowTitle, color = pal.ink)
            Text(when (c.essential) { Essential.ESSENTIAL -> "essenziale"; Essential.DISCRETIONARY -> "non essenziale"; Essential.UNSET -> "essenziale? non indicato" }, style = Type.small, color = pal.ink2)
        }
        androidx.compose.foundation.text.BasicTextField(
            text, { text = it },
            Modifier.widthIn(min = 112.dp).heightIn(min = 48.dp)
                .then(Modifier.background(pal.inset, shape).border(if (invalid) 2.dp else 1.dp, if (invalid) pal.over else pal.btnLine, shape))
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .onFocusChanged { if (!it.isFocused) save() }
                .semantics { contentDescription = "Budget mensile di ${c.name}" + if (invalid) ", importo non valido" else "" },
            textStyle = Type.amount.copy(color = pal.ink, textAlign = androidx.compose.ui.text.style.TextAlign.End),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }),
            decorationBox = { inner -> Box(contentAlignment = Alignment.CenterEnd) { if (text.isEmpty()) Text("n/d\u00A0€", style = Type.amount, color = pal.ink3); inner() } },
        )
        if (text.isNotEmpty()) { Spacer(Modifier.width(4.dp)); Text("€", style = Type.amount, color = pal.ink2) }
    }
}

/* ---------- Obiettivi ---------- */

@Composable
fun TargetsScreen(app: App) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    Page {
        BackHeader("Obiettivi dei pilastri", null, { app.back() }) {
            HelpButton("Obiettivi", "Le barre dei pilastri si riempiono solo contro un obiettivo che imposti tu. Il lungo termine non ha obiettivo: resta un contorno.")
        }
        RowsCard {
            (1..3).forEach { p ->
                val t = a.targetFor(p, app.today)
                // UX-12: the target is edited in place on its figure (InlineAmountField falls back to the sheet at large text).
                val amount = t as? TargetView.Amount
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pillarName(p), style = Type.rowTitle, color = pal.ink)
                        Text(when (t) { is TargetView.Amount -> if (t.userSet) "impostato da te" else "predefinito"; is TargetView.Unavailable -> t.reason; TargetView.None -> "nessun obiettivo" },
                            style = Type.small, color = pal.ink2)
                    }
                    InlineAmountField(amount?.cents, "Obiettivo di ${pillarName(p)}",
                        onSave = { cents -> app.commit("Obiettivo salvato.") { it.withPillarTarget(p, cents) } },
                        style = Type.secondaryFigure, shown = amount?.let { Fmt.eur(it.cents) } ?: "—", allowEmpty = amount != null,
                        onClear = if (amount != null) ({ app.commit("Obiettivo rimosso.") { it.withPillarTarget(p, null) } }) else null,
                        sheetHint = "importo da tenere nel pilastro")
                }
            }
            ListRow(pillarName(4), "nessun obiettivo", null, value = "—", chevron = false)
        }
    }
}
