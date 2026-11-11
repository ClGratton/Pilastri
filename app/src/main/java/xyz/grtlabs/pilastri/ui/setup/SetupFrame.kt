package xyz.grtlabs.pilastri.ui.setup

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.grtlabs.pilastri.data.isEmpty
import xyz.grtlabs.pilastri.ui.App
import xyz.grtlabs.pilastri.ui.Btn
import xyz.grtlabs.pilastri.ui.LocalPal
import xyz.grtlabs.pilastri.ui.LocalReduceMotion
import xyz.grtlabs.pilastri.ui.Motion
import xyz.grtlabs.pilastri.ui.PillButton
import xyz.grtlabs.pilastri.ui.SampleHook
import xyz.grtlabs.pilastri.ui.Tag
import xyz.grtlabs.pilastri.ui.TitledCard
import xyz.grtlabs.pilastri.ui.Type
import xyz.grtlabs.pilastri.ui.Ic
import xyz.grtlabs.pilastri.ui.card

/*
 * Setup routing (slice 5b). Setup shows by itself only for an empty archive the first time; an archive with data is never
 * forced through it. Setup → app is a push on the page spring (the app arrives from the right, setup recedes and dims).
 * Reopened from Home it is pushed over the app, and Back / Fatto pop it off to the right.
 *
 * Debug only: `adb shell am start -S -n xyz.grtlabs.pilastri/.MainActivity --ez pilastri.setup.preview true` opens setup
 * as a preview on any archive. A preview writes nothing to the archive and starts no connector that would save.
 */

/** Opens setup over the app (the Home entry); null outside [SetupFrame]. */
val LocalSetupEntry = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * Opens a bank-only run over the app (Impostazioni → Banche): `open(null)` adds a bank, `open(bank)` renews that bank's consent.
 * [version] counts the finished runs, so the list of banks reloads when one ends. Null outside [SetupFrame].
 */
class BankFlow(val open: (String?) -> Unit, val version: Int)

val LocalBankFlow = compositionLocalOf<BankFlow?> { null }

const val SetupPreviewExtra = "pilastri.setup.preview"

private enum class Layer { APP, SETUP }

private tailrec fun Context.activity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private fun previewRequested(context: Context): Boolean {
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    return debuggable && context.activity()?.intent?.getBooleanExtra(SetupPreviewExtra, false) == true
}

@Composable
fun SetupFrame(app: App, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduce = LocalReduceMotion.current
    val preview = remember { previewRequested(context) }
    var decided by remember { mutableStateOf(false) }
    // Bottom to top. The app is composed while it is visible or kept under setup; setup only while it is shown.
    var layers by remember { mutableStateOf(listOf(Layer.APP)) }
    var moving by remember { mutableStateOf<Layer?>(null) }
    var fade by remember { mutableStateOf(false) }
    val progress = remember { Animatable(1f) }
    var generation by remember { mutableIntStateOf(0) }
    var atRoot by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }
    // A bank-only run (Aggiungi banca / Rinnova) is setup pushed over the app with only the bank steps.
    var bankMode by remember { mutableStateOf<BankMode?>(null) }
    var banksVersion by remember { mutableIntStateOf(0) }

    suspend fun push(layer: Layer, crossfade: Boolean = false) {
        layers = layers.filter { it != layer } + layer
        moving = layer; fade = crossfade
        progress.snapTo(0f)
        try { progress.animateTo(1f, if (crossfade) tween(220) else Motion.page) } finally {
            moving = null; fade = false
            // Setup under the app is finished: it leaves the composition. The app under setup stays (its state is kept),
            // except at the first decision: then setup is the first screen and Back at Benvenuto leaves the app as usual.
            if (layer == Layer.APP || crossfade) layers = listOf(layer)
        }
    }

    suspend fun popSetup(velocity: Float = 0f) {
        moving = Layer.SETUP; fade = false
        try { progress.animateTo(0f, Motion.page, initialVelocity = velocity.coerceAtMost(0f)) } finally {
            moving = null; layers = listOf(Layer.APP); progress.snapTo(1f)
            bankMode = null; banksVersion++
        }
    }

    /** Leaves setup: over the app it pops off to the right; as the first screen the app is pushed in. */
    fun leave() {
        scope.launch { if (layers.firstOrNull() == Layer.APP && Layer.SETUP in layers) popSetup() else push(Layer.APP) }
    }

    fun openOverApp() {
        if (Layer.SETUP in layers || moving != null) return
        generation++
        scope.launch { push(Layer.SETUP) }
    }

    fun openBanks(renew: String?) {
        if (Layer.SETUP in layers || moving != null) return
        generation++
        bankMode = BankMode(renew)
        scope.launch { push(Layer.SETUP) }
    }
    val bankFlow = remember(banksVersion) { BankFlow({ renew -> openBanks(renew) }, banksVersion) }

    LaunchedEffect(app.loaded) {
        if (!app.loaded || decided) return@LaunchedEffect
        decided = true
        val a = app.archive
        val firstRun = a != null && a.isEmpty && a.positions.isEmpty() && !withContext(Dispatchers.IO) { SetupStore.done(context) }
        if (preview) { generation++; push(Layer.SETUP, crossfade = true) }
        else if (firstRun) { generation++; layers = listOf(Layer.SETUP) } // the splash still covers the app: setup is the first frame, no crossfade over home
        androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { }
        xyz.grtlabs.pilastri.ui.StartGate.settle()
    }

    // A backup restored from Benvenuto: data arrived, so the app takes over (nothing of setup is written).
    val data = app.archive?.let { !it.isEmpty || it.positions.isNotEmpty() } == true
    LaunchedEffect(data) {
        if (data && !preview && layers == listOf(Layer.SETUP) && moving == null && atRoot) push(Layer.APP)
    }

    // Setup over the app, on its first page: Back follows the finger and pops it off.
    val overApp = layers == listOf(Layer.APP, Layer.SETUP)
    var widthPx by remember { mutableStateOf(1f) }
    val commitHx = xyz.grtlabs.pilastri.ui.rememberCommitHaptics(xyz.grtlabs.pilastri.ui.Motion.DismissFraction) // platform decides the commit: grab and release only
    PredictiveBackHandler(enabled = overApp && atRoot && moving == null) { events ->
        commitHx.start()
        dragging = true; moving = Layer.SETUP
        var start = Float.NaN; var velocity = 0f; var lastX = 0f; var lastT = 0L
        try {
            events.collect { e ->
                val now = System.nanoTime()
                if (start.isNaN()) { start = e.touchX; lastX = e.touchX; lastT = now }
                val dt = (now - lastT) / 1e9f
                if (dt > 0.004f) { velocity = 0.6f * velocity + 0.4f * (kotlin.math.abs(e.touchX - start) - kotlin.math.abs(lastX - start)) / dt; lastX = e.touchX; lastT = now }
                progress.snapTo((1f - kotlin.math.abs(e.touchX - start) / widthPx).coerceIn(0f, 1f))
            }
            dragging = false
            commitHx.release(committed = true) // the snap as the commit becomes visible; no settle, nothing separate arrives
            popSetup(-velocity / widthPx)
        } catch (e: CancellationException) {
            commitHx.cancel()
            dragging = false
            scope.launch { progress.animateTo(1f, Motion.page, initialVelocity = velocity / widthPx); moving = null }
            throw e
        }
    }

    val background = MaterialTheme.colorScheme.background
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        widthPx = width
        val top = layers.last()
        listOf(Layer.APP, Layer.SETUP).forEach { layer ->
            if (layer in layers) key(layer) {
                val isTop = layer == top
                val under = !isTop && moving == top
                Box(
                    Modifier.fillMaxSize().zIndex(if (isTop) 1f else 0f)
                        .graphicsLayer {
                            val p = progress.value
                            alpha = 1f; translationX = 0f; shadowElevation = 0f
                            when {
                                isTop && moving == layer -> if (reduce || fade) alpha = p else {
                                    translationX = (1f - p) * width
                                    shadowElevation = if (p in 0.001f..0.999f) 12.dp.toPx() else 0f
                                    shape = RectangleShape; clip = false
                                }
                                under -> if (!reduce && !fade) translationX = -Motion.ParentShift * width * p
                                !isTop -> alpha = 0f
                            }
                        }
                        .drawWithContent {
                            drawContent()
                            if (under && !reduce && !fade) drawRect(Color.Black, alpha = Motion.ParentDim * progress.value)
                        }
                        .background(background)
                        .then(if (isTop) Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } } else Modifier),
                ) {
                    when (layer) {
                        Layer.APP -> CompositionLocalProvider(LocalSetupEntry provides { openOverApp() }, LocalBankFlow provides bankFlow) { content() }
                        Layer.SETUP -> key(generation) {
                            SetupFlow(app, preview, onRootChanged = { atRoot = it },
                                onRestore = if (layers == listOf(Layer.SETUP)) ({ app.launchers.restoreBackup() }) else null,
                                onDone = { m ->
                                    if (bankMode != null) {
                                        // Bank-only run: only the banks waiting for approval change; nothing else of setup is written.
                                        withContext(Dispatchers.IO) { SetupStore.savePendingBanks(context, SetupRules.mergePending(SetupStore.pendingBanks(context), m)) }
                                        app.hx?.confirm(); leave(); true
                                    } else save(app, context, preview, m).also { ok -> if (ok) { app.hx?.confirm(); leave() } }
                                },
                                banks = bankMode, onClose = { leave() })
                        }
                    }
                }
            }
        }
    }
}

/** "Fatto": positions as declared, the setup marker and the banks waiting for approval. A preview writes nothing. */
private suspend fun save(app: App, context: Context, preview: Boolean, m: SetupModel): Boolean {
    if (preview) return true
    val positions = SetupRules.positionsToWrite(m, app.today) { k -> "pos-${k.name.lowercase()}-${UUID.randomUUID().toString().take(8)}" }
    if (positions.isNotEmpty() && !app.commitNow { SetupRules.applyToArchive(it, positions) }) return false
    withContext(Dispatchers.IO) {
        SetupStore.markDone(context)
        SetupStore.savePendingBanks(context, SetupRules.pendingBanks(m).toSet())
    }
    return true
}

/** Home's entry into setup on an empty archive (replaces the old "Per iniziare" card). Labels only. */
@Composable
fun SetupEntryCard(app: App) {
    val pal = LocalPal.current
    val open = LocalSetupEntry.current
    TitledCard("Configurazione", verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (open != null) PillButton("Configura", open, Modifier.fillMaxWidth(), kind = Btn.PRIMARY, icon = Ic.chevronRight)
        PillButton("Ripristina un backup", { app.launchers.restoreBackup() }, Modifier.fillMaxWidth(), kind = Btn.TONAL)
    }
    if (SampleHook.build != null) TitledCard("Dati di esempio", verticalArrangement = Arrangement.spacedBy(8.dp), trailing = { Tag("solo debug") }) {
        PillButton("Carica i dati di esempio", { app.loadSample() }, kind = Btn.OUTLINE, icon = Ic.box)
    }
}
