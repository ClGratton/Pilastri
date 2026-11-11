package xyz.grtlabs.pilastri

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import xyz.grtlabs.pilastri.ui.*

/**
 * Debug-only lab for the motion system of the redone UI: StackHost push/pop/predictive back/swap, the two-pane
 * rule and press feedback, on neutral content. Not part of the shipped app; started with
 * `adb shell am start -n xyz.grtlabs.pilastri/.MotionLabActivity`.
 */
class MotionLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        window.isNavigationBarContrastEnforced = false
        setContent { PilastriTheme { CompositionLocalProvider(LocalReduceMotion provides observeReduceMotion()) { Lab() } } }
    }
}

private val names = listOf("Liquidità", "Emergenze", "Progetti", "Lungo termine")

@Composable
private fun Lab() {
    var nav by remember { mutableStateOf(NavState()) }
    val fold = rememberFoldingFeature()
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        val d = LocalDensity.current
        val vertical = fold?.takeIf { it.orientation == FoldingFeature.Orientation.VERTICAL }
        val layout = appLayout(maxWidth.value, maxHeight.value, vertical?.let { with(d) { it.bounds.left.toDp().value } }, vertical?.let { with(d) { it.bounds.width().toDp().value } },
            fold?.orientation == FoldingFeature.Orientation.HORIZONTAL)
        val open: (Route) -> Unit = { nav = nav.open(it) }
        if (layout is AppLayout.TwoPane) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(layout.left).fillMaxHeight()) { RootPage(nav.openPillar(), open) }
                Spacer(Modifier.width(layout.gap))
                StackHost(nav.rightEntries(), onBack = { nav = nav.pop() }, keyOf = { it.key }, modifier = Modifier.width(layout.right).fillMaxHeight(),
                    swapDirection = { a, b -> if ((b as? Route.Pillar)?.pillar ?: 0 >= ((a as? Route.Pillar)?.pillar ?: 0)) 1 else -1 }) { route ->
                    when (route) {
                        is Route.PaneRoot -> PageFrame("Quanto hai messo", "Radice del pannello destro: mai vuota.") {}
                        else -> LabPage(route, { nav = nav.push(it) }, { nav = nav.pop() })
                    }
                }
            }
        } else {
            StackHost(nav.phoneEntries(), onBack = { nav = nav.pop() }, keyOf = { it.key }, modifier = Modifier.fillMaxSize()) { route ->
                when (route) {
                    is Route.Root -> RootPage(nav.openPillar(), open)
                    else -> LabPage(route, { nav = nav.push(it) }, { nav = nav.pop() })
                }
            }
        }
    }
}

@Composable
private fun RootPage(selected: Int?, open: (Route) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Motion lab", style = MaterialTheme.typography.headlineSmall)
        Text("Tocca un pilastro; trascina dal bordo per tornare.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        names.forEachIndexed { i, name ->
            val on = selected == i + 1
            val fill by animateFill(cs.primaryContainer, on)
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .pressable(RoundedCornerShape(24.dp), cs.primary, onClick = { open(Route.Pillar(i + 1)) })
                .background(cs.surfaceContainerLow).background(fill).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("0${i + 1}", color = cs.primary, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(14.dp))
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${(i + 1) * 1234} €", style = TileNumber)
            }
        }
    }
}

@Composable
private fun LabPage(route: Route, push: (Route) -> Unit, pop: () -> Unit) {
    val title = when (route) { is Route.Pillar -> names[route.pillar - 1]; Route.Pension -> "Previdenza"; else -> route.key }
    PageFrame(title, "Pagina ${route.key}. Indietro la fa scorrere a destra e rivela quella sotto.", onBack = pop) {
        val cs = MaterialTheme.colorScheme
        Box(Modifier.fillMaxWidth().height(56.dp).pressable(RoundedCornerShape(28.dp), cs.primary, onClick = { push(Route.Pension) }).background(cs.primaryContainer),
            contentAlignment = Alignment.Center) { Text("Apri previdenza", color = cs.onPrimaryContainer, style = MaterialTheme.typography.labelLarge) }
        repeat(12) { Text("Riga di contenuto ${it + 1}", style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun PageFrame(title: String, subtitle: String, onBack: (() -> Unit)? = null, body: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onBack != null) Box(Modifier.size(48.dp).pressable(RoundedCornerShape(24.dp), cs.primary, onClick = onBack).background(cs.surfaceContainer), contentAlignment = Alignment.Center) {
            Text("←", style = MaterialTheme.typography.titleMedium)
        }
        Text(title, style = HeroNumber.copy(fontSize = MaterialTheme.typography.headlineMedium.fontSize))
        Text(subtitle, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        body()
    }
}
