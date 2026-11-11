package xyz.grtlabs.pilastri.ai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import xyz.grtlabs.pilastri.ui.LocalAction
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.Motion
import xyz.grtlabs.pilastri.ui.pressable
import xyz.grtlabs.pilastri.ui.rememberHx

/**
 * The "Assistenti" block of the Collegamenti screen: Claude and ChatGPT through the user's own account, the two API keys and
 * the Claude routine, each with its honest state (non collegato / collegato / sessione scaduta), Collega · Scollega, the
 * key entry, the per-route consent and which route the Agente tab uses. Contract fixed 2026-10-31: the screen owner hosts
 * it; [onRouteChanged] is called when the user picks the route the Agente uses.
 *
 * Tier 3 by construction: none of this is reachable by the assistant (no tool touches keys, sessions or privacy toggles).
 */
@Composable
fun AiConnectionsSection(modifier: Modifier = Modifier, onRouteChanged: (AiRoute) -> Unit = {}, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val settings = remember { AiSettings.create(context) }
    val vault = remember { AiVault.createOrNull(context) }
    val hx = rememberHx()
    var route by remember { mutableStateOf(settings.route) }
    var expanded by remember { mutableStateOf<AiRoute?>(null) }
    // Bumped on resume and after a sign-in so cookie-based states are read again.
    var refresh by remember { mutableIntStateOf(0) }
    var redaction by remember { mutableStateOf(settings.redactionOn) }
    var attachRedaction by remember { mutableStateOf(settings.attachmentRedaction) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val changed = { refresh++; onChanged() }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("ASSISTENTI", Modifier.weight(1f))
            HelpButton("Assistenti", AiHelp.CONNECTIONS)
        }
        AiRoute.entries.forEach { candidate ->
            val status = remember(candidate, refresh) { routeStatus(context, candidate, settings, vault) }
            RouteCard(
                route = candidate,
                status = status,
                inUse = route == candidate,
                open = expanded == candidate,
                onToggle = { expanded = if (expanded == candidate) null else candidate },
                onUse = { route = candidate; settings.route = candidate; hx.tick(); onRouteChanged(candidate); onChanged() },
            ) {
                when (candidate.kind) {
                    AiRoute.Kind.WEB -> WebDetails(candidate.web!!, settings, changed)
                    AiRoute.Kind.PLAN -> PlanDetails(refresh, changed)
                    AiRoute.Kind.API -> ApiDetails(candidate.api!!, settings, vault, changed)
                    AiRoute.Kind.ROUTINE -> RoutineDetails(settings, vault, changed)
                }
                ConsentRow(candidate, settings) {
                    changed()
                    // Consent was the last step: the card closes so the next thing in view is the chat.
                    if (routeStatus(context, candidate, settings, vault).ready) expanded = null
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Maschera IBAN, carte e codici prima dell'invio", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = redaction, onCheckedChange = { redaction = it; settings.redactionOn = it; hx.toggle(it); onChanged() })
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Oscura dati personali negli allegati", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = attachRedaction, onCheckedChange = { attachRedaction = it; settings.attachmentRedaction = it; hx.toggle(it); onChanged() })
        }
        // The Gmail fallback's own opt-in, per provider: offered only once that provider's API key is saved.
        val keyed = remember(refresh) { if (vault == null) emptyList() else AiProvider.entries.filter { vault.has(it) } }
        keyed.forEach { provider -> key(provider) { MailOptInRow(provider, settings, changed) } }
    }
}

/** "Rate da email sconosciute": separate from the chat consent, off by default (see [AiForConnectors]). */
@Composable
private fun MailOptInRow(provider: AiProvider, settings: AiSettings, changed: () -> Unit) {
    val hx = rememberHx()
    var on by remember(provider) { mutableStateOf(settings.mailOptedIn(provider)) }
    val toggle = { on = !on; settings.setMailOptIn(provider, on); hx.toggle(on); changed() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .pressable(RoundedCornerShape(16.dp), MaterialTheme.colorScheme.primary, role = Role.Switch, onClick = toggle)
            .semantics { stateDescription = if (on) "attivo" else "spento" }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val consent = provider.mailConsentText()
        val title = consent.substringBefore(": ")
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                consent.substringAfter(": ").replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

/** State line of a route: the label the user reads, and whether the route can send now. */
internal data class RouteStatus(val label: String, val detail: String, val ready: Boolean, val linkState: AiLinkState)

internal fun routeStatus(context: android.content.Context, route: AiRoute, settings: AiSettings, vault: AiVault?): RouteStatus {
    val consent = settings.optedIn(route)
    fun withConsent(base: RouteStatus) = if (base.ready && !consent) base.copy(detail = "Manca il consenso all'invio dei dati.", ready = false) else base
    return when (route.kind) {
        AiRoute.Kind.PLAN -> {
            val store = ChatGptAccount.store(context)
            val creds = store?.load()
            when {
                store == null -> RouteStatus("non disponibile", "Il deposito sicuro del telefono non funziona.", false, AiLinkState.NOT_LINKED)
                creds == null -> RouteStatus("non collegato", "Continua con ChatGPT.", false, AiLinkState.NOT_LINKED)
                !creds.hasTokens -> RouteStatus("sessione scaduta", "Accedi di nuovo con ChatGPT.", false, AiLinkState.EXPIRED)
                !creds.planEnabled -> RouteStatus("piano non autorizzato", "Riautorizza l'uso del piano.", false, AiLinkState.EXPIRED)
                else -> withConsent(RouteStatus("collegato", "Piano ChatGPT · ${store.model}", true, AiLinkState.LINKED))
            }
        }
        AiRoute.Kind.WEB -> {
            val account = route.web!!
            when (val s = WebAccounts.state(context, account, settings)) {
                AiLinkState.LINKED -> withConsent(RouteStatus(s.label, "Account ${account.label} nell'app.", true, s))
                AiLinkState.EXPIRED -> RouteStatus(s.label, "Accedi di nuovo a ${account.label}.", false, s)
                AiLinkState.NOT_LINKED -> RouteStatus(s.label, "Accedi con il tuo account ${account.label}.", false, s)
            }
        }
        AiRoute.Kind.API -> {
            val provider = route.api!!
            when {
                vault == null -> RouteStatus("non disponibile", "Il deposito sicuro del telefono non funziona.", false, AiLinkState.NOT_LINKED)
                vault.has(provider) -> withConsent(RouteStatus("collegato", "Chiave salvata, cifrata sul telefono · modello ${settings.model(provider)}.", true, AiLinkState.LINKED))
                else -> RouteStatus("non collegato", "Nessuna chiave salvata.", false, AiLinkState.NOT_LINKED)
            }
        }
        AiRoute.Kind.ROUTINE -> {
            val config = vault?.let { RoutineConfig.load(it) }
            when {
                vault == null -> RouteStatus("non disponibile", "Il deposito sicuro del telefono non funziona.", false, AiLinkState.NOT_LINKED)
                config == null -> RouteStatus("non collegato", "Serve la routine con il suo token.", false, AiLinkState.NOT_LINKED)
                settings.routineRejectedAt != null -> RouteStatus("token non valido", "Claude ha rifiutato il token: generane uno nuovo.", false, AiLinkState.EXPIRED)
                settings.routineFolder == null -> RouteStatus("manca la cartella", "Scegli la cartella dove la routine scrive le risposte.", false, AiLinkState.NOT_LINKED)
                else -> withConsent(RouteStatus("collegato", "Routine ${config.routineId.take(14)}… · cartella «${settings.routineFolderName ?: "?"}».", true, AiLinkState.LINKED))
            }
        }
    }
}

@Composable
private fun RouteCard(
    route: AiRoute,
    status: RouteStatus,
    inUse: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onUse: () -> Unit,
    details: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    AiCard(Modifier.animateContentSize(Motion.spatial())) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .pressable(RoundedCornerShape(20.dp), cs.primary, onClick = onToggle)
                .semantics { stateDescription = (if (open) "aperto" else "chiuso") + ", " + status.label }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusDot(status.linkState, status.ready)
            Column(Modifier.weight(1f)) {
                Text(route.label, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                Text("${status.label} · ${status.detail}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (inUse) xyz.grtlabs.pilastri.ui.Tag("in uso", bg = LocalAction.current.soft, fg = LocalAction.current.onSoft) else AiButton("Usa", onClick = onUse, emphasis = false)
        }
        AnimatedVisibility(
            visible = open,
            enter = if (reduce) fadeIn() else expandVertically(Motion.spatial()) + fadeIn(),
            exit = if (reduce) fadeOut() else shrinkVertically(Motion.spatial()) + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                details()
            }
        }
    }
}

@Composable
private fun WebDetails(account: WebAccount, settings: AiSettings, changed: () -> Unit) {
    val context = LocalContext.current
    val hx = rememberHx()
    var note by remember { mutableStateOf<String?>(null) }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) { settings.markLinked(account, nowIso()); note = "Collegato."; hx.confirm() } else note = "Accesso non completato."
        changed()
    }
    val state = WebAccounts.state(context, account, settings)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AiButton(if (state == AiLinkState.LINKED) "Accedi di nuovo" else "Collega", onClick = { login.launch(AiWebActivity.loginIntent(context, account)) })
        if (state != AiLinkState.NOT_LINKED || settings.linkedAt(account) != null) {
            AiButton("Scollega", emphasis = false, onClick = {
                val clean = WebAccounts.unlink(account, settings)
                note = if (clean) "Scollegato: sessione e cookie di ${account.label} rimossi." else "Scollegato, ma un cookie di sessione è ancora presente: riprova."
                if (clean) hx.confirm() else hx.reject()
                changed()
            })
        }
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}

/**
 * ChatGPT through OpenAI's "Sign in with ChatGPT": «Continua con ChatGPT» opens the system browser (a Custom Tab); the user
 * signs in and authorizes plan use there. Scollega revokes the session. «Gestisci utilizzo» opens ChatGPT's usage settings.
 */
@Composable
private fun PlanDetails(refresh: Int, changed: () -> Unit) {
    val context = LocalContext.current
    val hx = rememberHx()
    val scope = rememberCoroutineScope()
    val store = remember { ChatGptAccount.store(context) }
    val creds = remember(refresh, ChatGptSignIn.revision) { store?.load() }
    var model by remember(refresh) { mutableStateOf(store?.model ?: ChatGptStore.DEFAULT_MODEL) }
    val outcome = ChatGptSignIn.lastOutcome
    LaunchedEffect(ChatGptSignIn.revision) { if (ChatGptSignIn.revision > 0) changed() }
    LaunchedEffect(Unit) { ChatGptSignIn.restore(context) }
    val linked = creds?.hasTokens == true && creds.planEnabled
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AiButton(
            when {
                ChatGptSignIn.running -> "Accesso in corso…"
                creds == null -> "Continua con ChatGPT"
                else -> "Accedi di nuovo"
            },
            enabled = store != null && !ChatGptSignIn.running,
            onClick = { hx.tap(); ChatGptSignIn.start(context) },
        )
        if (ChatGptSignIn.running) AiButton("Riprova", onClick = { hx.tap(); ChatGptSignIn.restart(context) })
        if (creds?.hasTokens == true) AiButton("Scollega", emphasis = false, onClick = {
            scope.launch {
                val revoked = ChatGptAccount.signOut(context.applicationContext)
                ChatGptSignIn.note = if (revoked) "Scollegato." else "Scollegato sul telefono; revoca su OpenAI non confermata."
                if (revoked) hx.confirm() else hx.reject()
                changed()
            }
        })
        if (linked) AiButton("Gestisci utilizzo", emphasis = false, onClick = { ChatGptAccount.openUsage(context) })
    }
    if (linked) {
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; store?.model = it },
            label = { Text("Modello") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(creds?.email?.let { "Account $it" } ?: "Account ChatGPT") },
        )
    }
    val line = ChatGptSignIn.note ?: when (outcome) {
        is ChatGptSignIn.Outcome.Linked -> if (outcome.planEnabled) "Collegato." else "Collegato senza il permesso d'uso del piano."
        is ChatGptSignIn.Outcome.Declined -> "Uso del piano non autorizzato."
        is ChatGptSignIn.Outcome.Refused, is ChatGptSignIn.Outcome.NoReturn, is ChatGptSignIn.Outcome.Failed -> ChatGptSignIn.failureText(outcome)?.plus(".")
        null -> null
    }
    line?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    // OpenAI's UI guideline: confirm plan use once, after the first sign-in with plan use enabled.
    if (outcome is ChatGptSignIn.Outcome.Linked && outcome.planEnabled && outcome.firstTime && store?.welcomed == false) {
        AlertDialog(
            onDismissRequest = { store.welcomed = true; ChatGptSignIn.lastOutcome = outcome.copy(firstTime = false) },
            title = { Text("Stai usando il tuo piano ChatGPT") },
            text = { Text("Le richieste dell'agente con ChatGPT usano il tuo piano. L'utilizzo si gestisce nelle impostazioni di ChatGPT.") },
            confirmButton = {
                TextButton(onClick = { store.welcomed = true; ChatGptSignIn.lastOutcome = outcome.copy(firstTime = false); hx.confirm() }) { Text("Ho capito") }
            },
        )
    }
}

@Composable
private fun ApiDetails(provider: AiProvider, settings: AiSettings, vault: AiVault?, changed: () -> Unit) {
    val hx = rememberHx()
    var keyInput by remember(provider) { mutableStateOf("") }
    var model by remember(provider) { mutableStateOf(settings.model(provider)) }
    var note by remember(provider) { mutableStateOf<String?>(null) }
    val modelValid = model.isNotBlank() && model.trim().matches(Regex("[A-Za-z0-9._:/-]{1,100}"))
    OutlinedTextField(
        value = keyInput,
        onValueChange = { keyInput = it },
        label = { Text("Chiave API ${provider.label} (${provider.keyHint})") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        supportingText = { Text(if (vault?.has(provider) == true) "Chiave salvata" else "Nessuna chiave") },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AiButton("Salva chiave", enabled = vault != null && keyInput.isNotBlank(), onClick = {
            note = try { vault?.set(provider, keyInput); keyInput = ""; hx.confirm(); "Chiave salvata." } catch (e: Exception) { hx.reject(); e.message ?: "Impossibile salvare la chiave." }
            changed()
        })
        if (vault?.has(provider) == true) AiButton("Rimuovi chiave", emphasis = false, onClick = { vault.clear(provider); note = "Chiave rimossa."; changed() })
    }
    OutlinedTextField(
        value = model,
        onValueChange = { model = it; settings.setModel(provider, it) },
        label = { Text("Modello") },
        isError = !modelValid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("Predefinito ${provider.defaultModel}") },
    )
    if (model.trim() != provider.defaultModel) AiButton("Modello predefinito", emphasis = false, onClick = { model = provider.defaultModel; settings.setModel(provider, provider.defaultModel) })
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}

@Composable
private fun RoutineDetails(settings: AiSettings, vault: AiVault?, changed: () -> Unit) {
    val context = LocalContext.current
    val hx = rememberHx()
    var address by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                settings.routineFolder = uri.toString()
                settings.routineFolderName = SafExchangeFolder.displayName(context, uri) ?: "cartella"
                note = "Cartella scelta: «${settings.routineFolderName}»."
                hx.confirm()
            } catch (e: Exception) {
                note = "Non posso usare questa cartella: ${e.message ?: "permesso negato"}."
                hx.reject()
            }
            changed()
        }
    }
    val config = vault?.let { RoutineConfig.load(it) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AiButton("Copia le istruzioni", emphasis = false, onClick = { ClipWrite.copyPlain(context, "Istruzioni per la routine Pilastri", RoutinePrompt.TEXT); note = "Istruzioni copiate: incollale nella routine."; hx.tick() })
        AiButton(if (settings.routineFolder == null) "Scegli la cartella" else "Cambia cartella", emphasis = false, onClick = { folder.launch(null) })
    }
    OutlinedTextField(
        value = address, onValueChange = { address = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text("Indirizzo o id della routine (trig_…)") },
        supportingText = { Text(config?.let { "Collegata: ${it.routineId}" } ?: "Nessuna routine") },
    )
    OutlinedTextField(
        value = token, onValueChange = { token = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text("Token della routine (sk-ant-oat01-…)") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AiButton("Salva", enabled = vault != null && address.isNotBlank() && token.isNotBlank(), onClick = {
            val parsed = RoutineConfig.from(address, token)
            note = if (parsed == null || vault == null) { hx.reject(); "Indirizzo o token non riconosciuti: l'id comincia con trig_, il token con sk-ant-oat01-." }
            else { RoutineConfig.save(vault, parsed); settings.routineRejectedAt = null; address = ""; token = ""; hx.confirm(); "Routine salvata." }
            changed()
        })
        if (config != null) AiButton("Scollega", emphasis = false, onClick = {
            vault?.let { RoutineConfig.clear(it) }
            settings.routineRejectedAt = null
            settings.routineFolder?.let { uri -> try { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { } }
            settings.routineFolder = null
            settings.routineFolderName = null
            note = "Routine scollegata: token e cartella dimenticati. Su claude.ai puoi revocare il token."
            changed()
        })
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}

@Composable
internal fun ConsentRow(route: AiRoute, settings: AiSettings, changed: () -> Unit) {
    var checked by remember(route) { mutableStateOf(settings.optedIn(route)) }
    Row(
        Modifier.fillMaxWidth().pressable(RoundedCornerShape(16.dp), MaterialTheme.colorScheme.primary, role = Role.Checkbox, onClick = {
            checked = !checked; settings.setOptIn(route, checked); changed()
        }).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(route.consentText(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(start = 4.dp))
    }
}

// ---- small shared pieces (also used by the Agente page) ----

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
    )
}

/** C9 card: lowest surface, 24dp corners, a hairline outline. */
@Composable
internal fun AiCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth()
            .background(cs.surfaceContainerLowest, RoundedCornerShape(24.dp))
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        content = content,
    )
}

/** C9 action pill: 44dp tall (48dp touch target), 22dp radius; emphasis fills with the primary, otherwise the soft action color. */
@Composable
internal fun AiButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, emphasis: Boolean = true, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val action = LocalAction.current
    val bg = when {
        !enabled -> cs.onSurface.copy(alpha = 0.08f)
        emphasis -> cs.primary
        else -> action.soft
    }
    val fg = when {
        !enabled -> cs.onSurface.copy(alpha = 0.38f)
        emphasis -> cs.onPrimary
        else -> action.onSoft
    }
    Box(
        modifier.heightIn(min = 48.dp)
            .pressable(RoundedCornerShape(22.dp), cs.primary, onClick = onClick, enabled = enabled)
            .semantics { contentDescription = text }
            .padding(vertical = 2.dp)
            .background(bg, RoundedCornerShape(22.dp))
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = fg, textAlign = TextAlign.Center) }
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The "?" next to a heading (AGENTS.md, "Copy on screens"): explanations open on demand, never as paragraphs on the screen.
 * 48dp target, labelled for TalkBack.
 */
@Composable
internal fun HelpButton(title: String, text: String) = xyz.grtlabs.pilastri.ui.screens.HelpButton(title, text) // G-12: the one shared glyph

/** Explanations shown only behind a "?" (setup knowledge, not screen copy). */
internal object AiHelp {
    val CONNECTIONS = """
Claude (account): il tuo account Claude, in una pagina di claude.ai nascosta sotto la chat. La vedi solo per accedere o per una verifica (CAPTCHA) che completi tu. L'app non vede né salva la password: sa solo se il cookie di sessione esiste. Le condizioni d'uso di claude.ai possono vietare l'uso automatizzato della pagina; le vie documentate sono la chiave API e la routine.

ChatGPT (account): l'accesso ufficiale «Sign in with ChatGPT» di OpenAI, nel browser. Le richieste usano il tuo piano ChatGPT (Plus o Pro) tramite le API di OpenAI, con gli stessi strumenti e conferme della chiave API. I token restano cifrati sul telefono.

Chiavi API: la tua chiave Anthropic o OpenAI, cifrata sul telefono. Non entra nell'archivio né nei backup.

Routine Claude: la crei tu su claude.ai, con il solo connettore Google Drive, incollando le istruzioni e attivando l'avvio via API. Pilastri la avvia con il token che generi sul sito (solo verso api.anthropic.com) e legge la risposta nella cartella che scegli. Generare un nuovo token revoca il precedente.

Mascheramento: prima dell'invio IBAN, carte, codici fiscali e numeri lunghi diventano segnaposti. Per l'account Claude e la routine il riepilogo è sempre mascherato.

Ogni invio parte solo dal tuo tocco su «Invia».
""".trim()

    val AGENT = """
Le modifiche semplici (voci di piano, nomi, categorie, ignorare un movimento importato) si applicano subito e si annullano dalla scheda.

Valutazioni, movimenti, conferme di importazioni e più modifiche insieme aspettano il tuo «Applica».

Se nella conversazione entrano testi di terzi (descrizioni bancarie, una risposta dalla pagina di Claude, la routine, una risposta incollata), ogni modifica aspetta la tua conferma fino a «Nuova».

L'agente legge solo l'archivio e ti dice cosa ha letto; i numeri sensibili escono mascherati.
""".trim()
}

/** A dot beside the state word: never the only cue (the label says the state in text). */
@Composable
private fun StatusDot(state: AiLinkState, ready: Boolean) {
    val cs = MaterialTheme.colorScheme
    val color: Color = when {
        ready -> cs.tertiary
        state == AiLinkState.EXPIRED -> cs.error
        else -> cs.outline
    }
    Box(Modifier.size(36.dp).background(color.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
    }
}
