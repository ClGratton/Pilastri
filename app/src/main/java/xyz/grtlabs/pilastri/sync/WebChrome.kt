package xyz.grtlabs.pilastri.sync

import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.launch
import xyz.grtlabs.pilastri.ui.AppearanceStore
import xyz.grtlabs.pilastri.ui.Btn
import xyz.grtlabs.pilastri.ui.DarkPal
import xyz.grtlabs.pilastri.ui.Ic
import xyz.grtlabs.pilastri.ui.IconDisc
import xyz.grtlabs.pilastri.ui.LightPal
import xyz.grtlabs.pilastri.ui.LocalPal
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.LocalSemantic
import xyz.grtlabs.pilastri.ui.Motion
import xyz.grtlabs.pilastri.ui.overTag
import xyz.grtlabs.pilastri.ui.PilastriTheme
import xyz.grtlabs.pilastri.ui.PillButton
import xyz.grtlabs.pilastri.ui.RoundIcon
import xyz.grtlabs.pilastri.ui.Type
import xyz.grtlabs.pilastri.ui.animateFill
import xyz.grtlabs.pilastri.ui.fadeThrough
import xyz.grtlabs.pilastri.ui.observeReduceMotion
import xyz.grtlabs.pilastri.ui.pressable
import xyz.grtlabs.pilastri.ui.rememberHx

/*
 * The one top bar of every provider page (Trade Republic, isybank, PayPal). Quiet chrome over the provider's own page:
 *
 *   [ back ]  Provider name                      [ toggle ] [ ? ]
 *             lock  current.host.of.the.page
 *   [ icon ]  one compact state line (only when there is a state)
 *   ───────── hairline; the load progress runs along it
 *
 * The host line is the security cue: it always shows the host of the page actually loaded, and turns into a warning when
 * that host is not the provider's. Explanations live behind "?" (AGENTS.md, Copy on screens), never on the bar.
 * Compose hosts use [WebChromeScaffold]; View-based activities add [webChromeView] above their WebView and give the insets
 * to [installWebInsets], so each window has exactly one inset owner.
 */

/** One point of the "?" sheet: a short lead and one or two sentences. */
class WebHelpPoint(val icon: ImageVector, val lead: String, val text: String)

/** The state line under the bar. Short, one line where it fits, no trailing period. */
data class WebStatus(val text: String, val tone: Tone) {
    enum class Tone { HINT, BUSY, BLOCKED, DANGER }

    companion object {
        /** What the user is expected to do on this page, e.g. "Accedi e scarica l'Excel dei movimenti". */
        fun hint(text: String) = WebStatus(text, Tone.HINT)
        /** Work in progress, e.g. "Lettura del file". */
        fun busy(text: String) = WebStatus(text, Tone.BUSY)
        /** Something was refused, e.g. a page outside the provider. */
        fun blocked(text: String) = WebStatus(text, Tone.BLOCKED)
    }
}

/** An on/off control in the bar (e.g. "Versione desktop"). The label stays the same; the state is announced separately. */
data class WebToggle(val label: String, val icon: ImageVector, val on: Boolean, val onToggle: () -> Unit)

/**
 * What the bar shows. One per provider page, owned by its activity and written on the main thread.
 *
 * @param isProviderHost the provider's own host allowlist: the host line is a lock only for https pages it accepts.
 * @param foreignWarning shown as a danger line while a page outside the provider is on screen.
 */
@Stable
class WebChrome(
    val provider: String,
    private val isProviderHost: (String?) -> Boolean,
    private val foreignWarning: String,
    val help: List<WebHelpPoint>,
) {
    /** The page's idle line, if any; [status] replaces it while set. */
    var hint: WebStatus? by mutableStateOf(null)
    var status: WebStatus? by mutableStateOf(null)
    /** WebChromeClient progress, 0..100. */
    var progress by mutableIntStateOf(0)
    var host: String? by mutableStateOf(null); private set
    var trusted by mutableStateOf(true); private set
    /** The leading button: a back arrow, or a cross while it cancels running work. */
    var closeLabel by mutableStateOf("Torna a Pilastri")
    var closeCancels by mutableStateOf(false)
    var toggle: WebToggle? by mutableStateOf(null)
    /** Set by [installWebInsets] for View hosts; -1 means "read the Compose window insets". */
    internal var topInsetPx by mutableIntStateOf(-1)

    /** Main-frame page start: a refusal notice belongs to the page that was refused, so a new page clears it. */
    fun pageStarted(url: String?) {
        if (status?.tone == WebStatus.Tone.BLOCKED) status = null
        showUrl(url)
    }

    /** Any main-frame address change, including history changes of single-page apps. */
    fun showUrl(url: String?) {
        val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val h = uri?.host?.lowercase()?.takeIf { it.isNotEmpty() }
        host = h
        trusted = h == null || (uri.scheme == "https" && isProviderHost(h))
    }

    internal val shown: WebStatus?
        get() = if (!trusted) WebStatus(foreignWarning, WebStatus.Tone.DANGER) else status ?: hint
}

/* ---- window, insets and hosting ------------------------------------------------------------------------------------ */

/**
 * Transparent system bars with icons for the appearance chosen in Impostazioni → Aspetto, no contrast scrim, and the app
 * surface as the window background. Returns that surface colour (ARGB) for a View root.
 */
fun ComponentActivity.applyWebWindow(): Int {
    val dark = AppearanceStore.isDark(this)
    val clear = android.graphics.Color.TRANSPARENT
    val style = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
    enableEdgeToEdge(style, style)
    window.isNavigationBarContrastEnforced = false
    window.isStatusBarContrastEnforced = false
    val surface = (if (dark) DarkPal else LightPal).bg.toArgb()
    window.setBackgroundDrawable(ColorDrawable(surface))
    return surface
}

/**
 * The single inset owner of a View-based provider page: the sides and the bottom (navigation bar or keyboard, whichever is
 * taller) pad [root]; the top goes to the bar, which paints behind the status bar. The WebView receives no insets.
 */
fun installWebInsets(root: View, chrome: WebChrome) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        chrome.topInsetPx = bars.top
        view.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
        WindowInsetsCompat.CONSUMED
    }
}

/** The bar as a View, for activities built with Views. Add it above the WebView with MATCH_PARENT × WRAP_CONTENT. */
fun webChromeView(activity: ComponentActivity, chrome: WebChrome, onClose: () -> Unit): ComposeView = ComposeView(activity).apply {
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    setContent { WebChromeTheme { WebChromeBar(chrome, onClose) } }
}

/** The app theme for a provider activity: the user's appearance and the system's reduced-motion setting. */
@Composable
fun WebChromeTheme(content: @Composable () -> Unit) {
    PilastriTheme { CompositionLocalProvider(LocalReduceMotion provides observeReduceMotion(), content = content) }
}

/**
 * A whole provider page for Compose hosts: the bar on top (it owns the top inset), [content] below it filling the rest, with
 * the sides and the bottom (navigation bar or keyboard, never both) handled once here.
 */
@Composable
fun WebChromeScaffold(chrome: WebChrome, onClose: () -> Unit, showProgress: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxSize().background(pal.bg).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        WebChromeBar(chrome, onClose, showProgress = showProgress)
        Box(
            Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)),
            content = content,
        )
    }
}

/* ---- the bar ------------------------------------------------------------------------------------------------------- */

/** Leading slot = 8dp edge + 48dp button + 12dp gap: the title, the host and the state text all start at this line. */
private val Edge = 8.dp
private val Slot = 48.dp
private val Gap = 12.dp

@Composable
fun WebChromeBar(chrome: WebChrome, onClose: () -> Unit, modifier: Modifier = Modifier, showProgress: Boolean = true) {
    val pal = LocalPal.current
    val reduce = LocalReduceMotion.current
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    val top = if (chrome.topInsetPx >= 0) with(LocalDensity.current) { chrome.topInsetPx.toDp() }
    else WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()

    Column(modifier.fillMaxWidth().background(pal.bg).padding(top = top)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Edge, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(chrome.closeCancels, transitionSpec = fadeThrough(reduce), label = "close") { cancels ->
                RoundIcon(if (cancels) Ic.close else Ic.back, chrome.closeLabel, onClose, bg = pal.card, line = true)
            }
            Spacer(Modifier.width(Gap))
            Column(Modifier.weight(1f)) {
                Text(chrome.provider, style = Type.pageTitle, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() })
                HostLine(chrome.host, chrome.trusted, chrome.provider)
            }
            chrome.toggle?.let { ToggleButton(it); Spacer(Modifier.width(4.dp)) }
            RoundIcon(WebIcons.help, "Come funziona", { helpOpen = true }, tint = pal.ink2)
        }
        StatusLine(chrome.shown)
        LoadLine(if (showProgress) chrome.progress else 100)
    }
    if (helpOpen) HelpSheet(chrome) { helpOpen = false }
}

@Composable
private fun HostLine(host: String?, trusted: Boolean, provider: String) {
    val pal = LocalPal.current
    AnimatedContent(host to trusted, transitionSpec = fadeThrough(LocalReduceMotion.current), contentAlignment = Alignment.CenterStart, label = "host") { (h, ok) ->
        val tint = if (ok) pal.ink2 else pal.overInk
        val spoken = when {
            h == null -> ""
            ok -> "Sito aperto: $h, connessione protetta"
            else -> "Attenzione: $h non è un sito $provider"
        }
        Row(Modifier.heightIn(min = 18.dp).semantics { contentDescription = spoken }, verticalAlignment = Alignment.CenterVertically) {
            if (h != null) {
                Icon(if (ok) WebIcons.lock else Ic.warning, null, Modifier.size(14.dp), tint = tint)
                Spacer(Modifier.width(4.dp))
                // Start ellipsis keeps the registrable end of a long host visible ("…login.isybank.com").
                Text(h, style = Type.sub, color = tint, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            }
        }
    }
}

/** One compact line under the bar; it opens and closes with the spatial spring and crossfades between messages. */
@Composable
private fun StatusLine(status: WebStatus?) {
    val reduce = LocalReduceMotion.current
    // The last message stays readable while the line closes (a plain holder: no state write during composition).
    val last = remember { arrayOfNulls<WebStatus>(1) }
    if (status != null) last[0] = status
    val size = spring(Motion.PageDamping, Motion.PageStiffness, IntSize(1, 1))
    AnimatedVisibility(
        visible = status != null,
        enter = if (reduce) fadeIn(Motion.effects()) else expandVertically(size, expandFrom = Alignment.Top) + fadeIn(Motion.effects()),
        exit = if (reduce) fadeOut(Motion.effects()) else shrinkVertically(size, shrinkTowards = Alignment.Top) + fadeOut(Motion.effects()),
        label = "status",
    ) {
        AnimatedContent(last[0], transitionSpec = fadeThrough(reduce), label = "status-text") { s -> if (s != null) StatusContent(s) }
    }
}

@Composable
private fun StatusContent(s: WebStatus) {
    val pal = LocalPal.current
    val sem = LocalSemantic.current
    val (bg, ink) = when (s.tone) {
        WebStatus.Tone.HINT -> Color.Unspecified to pal.ink2
        WebStatus.Tone.BUSY -> Color.Unspecified to pal.ink
        WebStatus.Tone.BLOCKED -> pal.warnBg to pal.warnInk
        WebStatus.Tone.DANGER -> sem.lossContainer to sem.onLossContainer
    }
    Row(
        Modifier.fillMaxWidth().then(if (s.tone == WebStatus.Tone.BLOCKED) Modifier.overTag() else Modifier).then(if (bg != Color.Unspecified) Modifier.background(bg) else Modifier).heightIn(min = 40.dp)
            .padding(start = Edge, end = 16.dp, top = 6.dp, bottom = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(Slot), contentAlignment = Alignment.Center) {
            when (s.tone) {
                WebStatus.Tone.BUSY -> CircularProgressIndicator(Modifier.size(16.dp), color = pal.fill, trackColor = pal.track, strokeWidth = 2.dp, strokeCap = StrokeCap.Round)
                WebStatus.Tone.HINT -> Icon(WebIcons.download, null, Modifier.size(18.dp), tint = ink)
                else -> Icon(Ic.warning, null, Modifier.size(18.dp), tint = ink)
            }
        }
        Spacer(Modifier.width(Gap))
        Text(s.text, style = Type.body, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/**
 * The hairline that separates the bar from the page; while the page loads, a 2dp accent line runs along it and follows the
 * WebView's progress with the page spring, completes, then fades. A new load starts again from the left.
 */
@Composable
private fun LoadLine(progress: Int) {
    val pal = LocalPal.current
    val fraction = remember { Animatable(0f) }
    val shown = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        if (progress in 1..99) {
            val target = progress / 100f
            if (shown.targetValue == 0f || target < fraction.value) fraction.snapTo(0f)
            launch { shown.animateTo(1f, Motion.effects()) }
            fraction.animateTo(target, Motion.page)
        } else if (progress >= 100 && shown.targetValue > 0f) {
            fraction.animateTo(1f, Motion.page)
            shown.animateTo(0f, Motion.effects())
        }
    }
    Box(Modifier.fillMaxWidth().height(2.dp).drawBehind {
        val hair = 1.dp.toPx()
        drawRect(pal.cardLine, topLeft = Offset(0f, size.height - hair), size = Size(size.width, hair))
        if (shown.value > 0.001f) drawRect(pal.fill.copy(alpha = shown.value), size = Size(size.width * fraction.value, size.height))
    })
}

@Composable
private fun ToggleButton(t: WebToggle) {
    val pal = LocalPal.current
    val hx = rememberHx()
    val fill by animateFill(pal.selected, t.on, "toggle")
    // On: a filled disc and the dark ink; off: no disc. The fill is a shape cue as well as a colour.
    Box(
        Modifier.size(48.dp).pressable(CircleShape, pal.pressTint, onClick = { hx.toggle(!t.on); t.onToggle() }, role = Role.Switch)
            .background(fill)
            .semantics {
                contentDescription = t.label
                stateDescription = if (t.on) "attiva" else "non attiva"
                toggleableState = ToggleableState(t.on)
            },
        contentAlignment = Alignment.Center,
    ) { Icon(t.icon, null, Modifier.size(20.dp), tint = if (t.on) pal.tabOn else pal.ink2) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpSheet(chrome: WebChrome, onDismiss: () -> Unit) {
    val pal = LocalPal.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = pal.card, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(chrome.provider, style = Type.sub, color = pal.ink2)
            Text("Come funziona", style = Type.pageTitle, color = pal.ink, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                chrome.help.forEach { p ->
                    Row(verticalAlignment = Alignment.Top) {
                        IconDisc(p.icon, pal.chip, pal.onChip, 36.dp)
                        Spacer(Modifier.width(Gap))
                        Column(Modifier.weight(1f).padding(top = 2.dp)) {
                            Text(p.lead, style = Type.bodyStrong, color = pal.ink)
                            Text(p.text, style = Type.body, color = pal.ink2)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            PillButton("Chiudi", { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }, Modifier.align(Alignment.End), kind = Btn.TONAL)
        }
    }
}

/* ---- icons: the kit's 24-unit stroke style ------------------------------------------------------------------------- */

private fun stroke(name: String, vararg paths: String, width: Float = 2f): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    paths.forEach { d ->
        addPath(PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
}.build()

object WebIcons {
    val lock by lazy { stroke("lock", "M7.5 11V8a4.5 4.5 0 0 1 9 0v3", "M6 11h12a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1z", width = 2.2f) }
    val help by lazy { stroke("help", "M9.2 9a2.9 2.9 0 1 1 4.1 2.6c-.8.4-1.3 1.1-1.3 2v.6", "M12 17.6v.01", width = 2.2f) }
    val download by lazy { stroke("download", "M12 4v11m0 0l-4-4m4 4l4-4", "M5 20h14") }
    val desktop by lazy { stroke("desktop", "M4 5h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z", "M9 21h6M12 17v4") }
    val readOnly by lazy { stroke("eye", "M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12z", "M14.8 12a2.8 2.8 0 1 1-5.6 0a2.8 2.8 0 1 1 5.6 0z") }
    val preview by lazy { stroke("preview", "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M8 9h8M8 13h8M8 17h5") }
}

/**
 * Provider pages enter like a pushed page: from the right edge over the app, which shifts left and dims; closing (and Back)
 * reverses it (AGENTS.md motion contract). Android 14+ applies it to predictive back too; older systems keep their default.
 */
fun ComponentActivity.usePageTransitions() {
    if (android.os.Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, xyz.grtlabs.pilastri.R.anim.page_in, xyz.grtlabs.pilastri.R.anim.page_parent_out)
        overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, xyz.grtlabs.pilastri.R.anim.page_parent_in, xyz.grtlabs.pilastri.R.anim.page_out)
    }
}
