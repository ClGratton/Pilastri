package xyz.grtlabs.pilastri.ui.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.grtlabs.pilastri.ui.LocalPal
import xyz.grtlabs.pilastri.ui.Text
import xyz.grtlabs.pilastri.ui.Type
import xyz.grtlabs.pilastri.ui.pressable

/** Notification access for Pilastri: is it granted, and the system screen where the person grants it. */
object MailNotificationAccess {
    fun isGranted(ctx: Context): Boolean =
        runCatching { Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty().contains(ctx.packageName) }.getOrDefault(false)

    fun settingsIntent(ctx: Context): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .also { runCatching { it.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(ctx, EbNotificationListener::class.java).flattenToString()) } }
}

/** The numbered steps of the paste page. Text only here, so the order and wording are testable. */
object EbLinkHelp {
    val STEPS = listOf(
        "Apri l'e-mail di Enable Banking",
        "Tieni premuto il pulsante o il link",
        "Scegli «Copia link»",
        "Torna qui e tocca Incolla",
    )
}

/**
 * The numbered steps with their drawings: the one copy of the instructions (the paste page shows it, before or after the 45 s wait).
 * Wide (600 dp and more, the open Fold) lays the four steps side by side so the field below stays in view; narrow keeps one row per step.
 */
@Composable
private fun EbLinkSteps() {
    val pal = LocalPal.current
    if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EbLinkHelp.STEPS.forEachIndexed { i, label ->
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "Passo ${i + 1}: $label" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StepArt(i, Modifier.size(width = 72.dp, height = 54.dp))
                    Text("${i + 1}", style = Type.small, color = pal.ink3)
                    Text(label, style = Type.rowTitle, color = pal.ink)
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EbLinkHelp.STEPS.forEachIndexed { i, label ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Passo ${i + 1}: $label" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StepArt(i, Modifier.size(width = 72.dp, height = 54.dp))
                Column(Modifier.weight(1f)) {
                    Text("${i + 1}", style = Type.small, color = pal.ink3)
                    Text(label, style = Type.rowTitle, color = pal.ink)
                }
            }
        }
    }
}

/**
 * The paste page, one page for both entries (the wait screen's "Incolla il link" before the timeout, and the timeout itself): the
 * instructions, then a real text field with an "Incolla" button inside it, then one outcome line with reserved height (showing it
 * moves nothing). Nothing is read from the clipboard until the person presses Incolla; the text is shown in the field first.
 */
@Composable
internal fun EbPastePage(
    text: String, outcome: ActionOutcome, onText: (String) -> Unit, onPaste: () -> Unit, onSubmit: () -> Unit, onRetry: () -> Unit, onBack: () -> Unit, onLater: () -> Unit,
    working: Boolean = false,
) {
    // G-16: after "Link valido" the flow signs in by itself; the working line runs and the outcome line names the step.
    StepScaffold(EbScreen.LINK.number, EbScreen.TOTAL, onBack, null, "Incolla il link", null, working,
        primary = DockAction(if (working) ebWorkingLabel(EbScreen.LINK) else "Continua", enabled = false, working = working, onClick = {}), secondary = DockAction("Dopo", onClick = onLater)) { pad ->
        ScrollBody(pad) {
            EbLinkSteps()
            Spacer(Modifier.height(16.dp))
            PasteField(text, onText, onPaste, onSubmit)
            PasteOutcomeSlot(if (working) ActionOutcome.Idle else outcome, EbTag.OUTCOME)
            SetupPill("Riprova automatico", PillStyle.Tonal, onRetry)
        }
    }
}

/** An outcome as text: the result of an action in its role (CR-86). [OutcomeRow] and the paste page both draw it. */
@Composable
internal fun OutcomeLine(outcome: ActionOutcome, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    when (outcome) {
        is ActionOutcome.Done -> Text("✓ " + outcome.text, style = Type.rowTitle, color = pal.good, modifier = modifier)
        is ActionOutcome.Working -> Text(outcome.text, style = Type.body, color = pal.ink2, modifier = modifier)
        is ActionOutcome.Failed -> Text(outcome.reason, style = Type.body, color = pal.ink2, modifier = modifier)
        ActionOutcome.Idle -> {}
    }
}

/** How a [SetupPill] reads: tonal = the stronger group (bring the link), outlined = the lighter one (recover the sent e-mail). */
internal enum class PillStyle { Tonal, Outlined }

private fun pillIcon(name: String, vararg paths: String): androidx.compose.ui.graphics.vector.ImageVector =
    androidx.compose.ui.graphics.vector.ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toNodes(), stroke = androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round)
        }
    }.build()

/** The board's envelope and clipboard drawings (decorative: the label names the action). */
internal object PillIcons {
    val mail by lazy { pillIcon("mail", "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M3 7l9 6 9-6") }
    val clipboard by lazy { pillIcon("clipboard", "M7 4h10a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M9 4h6v3H9z") }
}

/**
 * The one pill of the setup pages (same concept, same code): a tonal or outlined rounded action with an optional leading icon, in a
 * given height. Pairs give it `Modifier.weight(1f)` so both columns are equal. Press feedback on pointer down; disabled keeps its
 * place, dims with a short tween and says so to TalkBack.
 */
@Composable
internal fun SetupPill(
    label: String, style: PillStyle, onClick: () -> Unit, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 48.dp,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, enabled: Boolean = true, description: String? = null, tag: String? = null,
) {
    val pal = LocalPal.current
    val shape = RoundedCornerShape(height / 2)
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (enabled) 1f else 0.45f, xyz.grtlabs.pilastri.ui.Motion.effects(), label = "pill")
    Row(modifier.heightIn(min = height).pressable(shape, pal.pressTint, onClick = onClick, enabled = enabled)
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .semantics(mergeDescendants = true) {
            contentDescription = description ?: label
            if (!enabled) stateDescription = "non ancora disponibile"
        }
        .graphicsLayer { this.alpha = alpha }
        .then(if (style == PillStyle.Tonal) Modifier.background(pal.tonal, shape) else Modifier.border(1.dp, pal.cardLine, shape))
        .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { androidx.compose.material3.Icon(icon, null, Modifier.size(20.dp), tint = pal.onTonal); Spacer(Modifier.width(8.dp)) }
        Text(label, style = Type.button, color = if (style == PillStyle.Tonal) pal.onTonal else pal.ink, maxLines = 1)
    }
}

/** One simple drawing per step: a phone outline with a mail row, the highlighted link, the long-press menu, the app again. */
@Composable
private fun StepArt(step: Int, modifier: Modifier) {
    val pal = LocalPal.current
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val phone = Size(w * 0.46f, h * 0.96f)
        val left = (w - phone.width) / 2f
        drawRoundRect(pal.card, Offset(left, h * 0.02f), phone, CornerRadius(10f))
        drawRoundRect(pal.ink2, Offset(left, h * 0.02f), phone, CornerRadius(10f), Stroke(3f))
        val x = left + 8f; val rowW = phone.width - 16f
        fun row(y: Float, color: Color, width: Float = rowW) = drawRoundRect(color, Offset(x, y), Size(width, 6f), CornerRadius(3f))
        when (step) {
            0 -> { row(h * 0.18f, pal.brand); row(h * 0.34f, pal.track); row(h * 0.50f, pal.track); row(h * 0.66f, pal.track) }
            1 -> { row(h * 0.2f, pal.track, rowW * 0.7f); row(h * 0.36f, pal.track); drawRoundRect(pal.accentSoft, Offset(x, h * 0.52f), Size(rowW, 14f), CornerRadius(7f)); drawCircle(pal.brand, 7f, Offset(x + rowW * 0.7f, h * 0.58f)) }
            2 -> { row(h * 0.2f, pal.track, rowW * 0.7f); drawRoundRect(pal.card, Offset(x, h * 0.36f), Size(rowW, h * 0.42f), CornerRadius(8f)); drawRoundRect(pal.btnLine, Offset(x, h * 0.36f), Size(rowW, h * 0.42f), CornerRadius(8f), Stroke(2f)); row(h * 0.44f, pal.ink3, rowW * 0.6f); row(h * 0.58f, pal.brand, rowW * 0.7f); row(h * 0.70f, pal.ink3, rowW * 0.5f) }
            else -> { drawRoundRect(pal.primary, Offset(x, h * 0.2f), Size(rowW, 10f), CornerRadius(5f)); row(h * 0.4f, pal.track); row(h * 0.55f, pal.track, rowW * 0.6f); drawCircle(pal.good, 9f, Offset(left + phone.width / 2f, h * 0.82f)) }
        }
    }
}

/**
 * Setup, once, right before Scegli banca: link Gmail and allow notification access so the Enable Banking sign-in e-mail is read
 * for the person. One line of why (setup copy rules); declining either is fine, the sign-in then shows the instruction screen.
 */
@Composable
internal fun MailAccessPage(index: Int, total: Int, onBack: () -> Unit, onDone: () -> Unit) {
    val pal = LocalPal.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val hx = xyz.grtlabs.pilastri.ui.rememberHx()
    var gmail by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ActionOutcome>(
        if (xyz.grtlabs.pilastri.sync.gmail.GmailConnector.isLinked(context)) ActionOutcome.Done("Gmail collegato") else ActionOutcome.Idle) }
    var notifAsked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var notif by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(ActionOutcome.ofNotifications(MailNotificationAccess.isGranted(context), false)) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        val granted = MailNotificationAccess.isGranted(context)
        logOutcome("Pilastri.Mail", "notification access granted=$granted asked=$notifAsked") // state only, never an account
        notif = ActionOutcome.ofNotifications(granted, notifAsked)
    }
    val consent = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val ok = xyz.grtlabs.pilastri.sync.gmail.GmailConnector.onConsentResult(context, r.data)
        logOutcome("Pilastri.Gmail", "consent resultCode=${r.resultCode} granted=$ok") // status only, never tokens, accounts or e-mails
        gmail = ActionOutcome.ofConsentResult(ok)
    }
    val done = gmail is ActionOutcome.Done || notif is ActionOutcome.Done
    // Same page scaffold as every setup step (back, "n di N", title, bottom bar): it owns the insets.
    StepScaffold(index, total, onBack, null, "Posta", null, false,
        primary = DockAction(if (done) "Continua" else "Dopo") { onDone() },
    ) { pad ->
        ScrollBody(pad) {
            Text("Così l'accesso a Enable Banking si completa da solo: l'app legge l'e-mail del link e non conserva nulla.", style = Type.body, color = pal.ink2)
            Spacer(Modifier.height(12.dp))
            OutcomeRow("Collega Gmail", gmail) {
                gmail = ActionOutcome.Working("Collegamento in corso…")
                scope.launch {
                    val a = xyz.grtlabs.pilastri.sync.gmail.GmailAuth.accessToken(context)
                    logOutcome("Pilastri.Gmail", "authorize outcome=${a::class.simpleName}" + ((a as? xyz.grtlabs.pilastri.sync.gmail.AuthOutcome.NotConfigured)?.let { " (client not set up)" } ?: ""))
                    when (a) {
                        is xyz.grtlabs.pilastri.sync.gmail.AuthOutcome.Consent -> { gmail = ActionOutcome.ofGmail(a); consent.launch(androidx.activity.result.IntentSenderRequest.Builder(a.intent).build()) }
                        is xyz.grtlabs.pilastri.sync.gmail.AuthOutcome.Token -> { xyz.grtlabs.pilastri.sync.gmail.GmailAuth.setLinked(context, true); gmail = ActionOutcome.ofGmail(a) }
                        else -> { hx.reject(); gmail = ActionOutcome.ofGmail(a) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutcomeRow("Consenti notifiche", notif) { notifAsked = true; runCatching { context.startActivity(MailNotificationAccess.settingsIntent(context)) } }
        }
    }
}

/** An action with its result on the row (CR-80): the button while it can still be pressed, and the outcome next to it, never silence. */
@Composable
internal fun OutcomeRow(action: String, outcome: ActionOutcome, onClick: () -> Unit) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (outcome !is ActionOutcome.Done && outcome !is ActionOutcome.Working) SetupPill(if (outcome is ActionOutcome.Failed) "Riprova" else action, PillStyle.Tonal, onClick)
        OutcomeLine(outcome, if (outcome is ActionOutcome.Failed) Modifier.weight(1f) else Modifier)
        (outcome as? ActionOutcome.Failed)?.steps?.let { xyz.grtlabs.pilastri.ui.screens.HelpButton("Configurazione Google", it) }
    }
}
