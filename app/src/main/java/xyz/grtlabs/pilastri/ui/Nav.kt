package xyz.grtlabs.pilastri.ui

/** The three sections (Council 3, 2026-10-31). Agente is an action that opens its panel, never a section. */
enum class Tab(val label: String) { PILASTRI("Pilastri"), SPESE("Spese"), FUTURO("Futuro") }

/** A saved section name; anything unknown (the old "AGENTE" section included) falls back to Pilastri. */
fun tabOf(name: String?): Tab = Tab.entries.firstOrNull { it.name == name } ?: Tab.PILASTRI

/** Every page the app can show. Roots are the tab's main screen; panes are the never-empty right-pane roots on wide. */
sealed interface Route {
    val key: String
    data class Root(val tab: Tab) : Route { override val key get() = "root:$tab" }
    data class PaneRoot(val tab: Tab) : Route { override val key get() = "pane:$tab" }
    data class Pillar(val pillar: Int) : Route { override val key get() = "pillar:$pillar" }
    data object Pension : Route { override val key get() = "pension" }
    data class Account(val id: String) : Route { override val key get() = "account:$id" }
    data object Contributions : Route { override val key get() = "contributions" }
    /** The rules history page opened from Previdenza ("Fonti e regole"). */
    data object Rules : Route { override val key get() = "rules" }
    data class Category(val id: String?) : Route { override val key get() = "category:$id" }
    data object Review : Route { override val key get() = "review" }
    data object Payday : Route { override val key get() = "payday" }
    data object Settings : Route { override val key get() = "settings" }
    data object Connections : Route { override val key get() = "connections" }
    data object Backup : Route { override val key get() = "backup" }
    /** The month page (concept 9 second level): income flow and month numbers. [month] is ISO "yyyy-MM". */
    data class Month(val month: String) : Route { override val key get() = "month:$month" }
    data class Plan(val id: String) : Route { override val key get() = "plan:$id" }
    /** Installment plans found by the connectors and still to confirm. */
    data object PlanReview : Route { override val key get() = "plan-review" }
    data object Categories : Route { override val key get() = "categories" }
    data object Targets : Route { override val key get() = "targets" }
    data object Scenarios : Route { override val key get() = "scenarios" }
}

/**
 * One logical stack per tab, independent of the window: phone shows it as a full-width stack; on two panes the root sits
 * in the left pane and the rest goes to the right pane over its [Route.PaneRoot]. Folding and unfolding keep the stack.
 */
data class NavState(
    val tab: Tab = Tab.PILASTRI,
    val stacks: Map<Tab, List<Route>> = Tab.entries.associateWith { emptyList() },
    /** The Pilastri month (the month row and Mese, G-11), "yyyy-MM"; null = the latest month with data (1a-20). */
    val month: String? = null,
    /**
     * G-09: tabs whose right-pane root is "the place" on the open Fold, so folding lifts it onto the cover as a page. On by
     * default (DESIGN G-09: whatever the right pane shows becomes the cover page); a touch on the left pane clears it, a touch
     * on the right pane sets it, and unfolding sets it only when the cover was showing that root as a page.
     */
    val paneFocus: Set<Tab> = Tab.entries.toSet(),
) {
    val depth: List<Route> get() = stacks.getValue(tab)

    /** The Pilastri month chosen with the swipe or the calendar; only the settled month is ever stored. */
    fun withMonth(m: String?): NavState = if (m == month) this else copy(month = m)

    /** A touch on the open Fold's left ([right] false) or right pane of [t]: see [paneFocus]. */
    fun touchPane(t: Tab, right: Boolean): NavState =
        if ((t in paneFocus) == right) this else copy(paneFocus = if (right) paneFocus + t else paneFocus - t)

    fun phoneEntries(): List<Route> = listOf(Route.Root(tab)) + depth
    fun rightEntries(): List<Route> = listOf(Route.PaneRoot(tab)) + depth

    fun push(route: Route): NavState = copy(stacks = stacks + (tab to depth + route))

    /** Opening another sibling at the first level replaces it (a swap on two panes, a push from the root on phone). */
    fun open(route: Route): NavState =
        if (depth.isNotEmpty() && sameLevel(depth.last(), route)) copy(stacks = stacks + (tab to depth.dropLast(1) + route)) else push(route)

    fun pop(): NavState = if (depth.isEmpty()) this else copy(stacks = stacks + (tab to depth.dropLast(1)))

    /** Back pops within the tab; at a non-home root it returns to Pilastri; at the home root it exits (null). */
    fun back(): NavState? = when {
        depth.isNotEmpty() -> pop()
        tab != Tab.PILASTRI -> copy(tab = Tab.PILASTRI)
        else -> null
    }

    fun select(next: Tab): NavState = copy(tab = next)

    /** Impostazioni is open somewhere in the current stack (its button draws the filled glyph). */
    val settingsOpen: Boolean get() = Route.Settings in depth

    /** Impostazioni from its corner button: pushed like any detail; when it is already in the stack, back to it. */
    fun openSettings(): NavState {
        val at = depth.indexOf(Route.Settings)
        return if (at >= 0) copy(stacks = stacks + (tab to depth.take(at + 1))) else push(Route.Settings)
    }

    /** Back to the tab's root (the right pane's root on two panes). */
    fun popToRoot(): NavState = copy(stacks = stacks + (tab to emptyList()))

    /** Replaces the whole stack of the current tab with one route, for links that jump across levels. */
    fun reset(route: Route): NavState = copy(stacks = stacks + (tab to listOf(route)))

    /** The pillar whose page is open in the current tab, for the left-pane selection highlight. */
    fun openPillar(): Int? = (depth.firstOrNull() as? Route.Pillar)?.pillar

    private fun sameLevel(a: Route, b: Route) = depth.size == 1 && a::class == b::class
}

/* ---------- G-09: fold/unfold continuity (one logical place for both window shapes) ---------- */

/** The right pane's root as a page on one pane: Mese of [month] for Pilastri, the pane root itself for the other sections. */
fun paneRootPage(tab: Tab, month: String): Route = if (tab == Tab.PILASTRI) Route.Month(month) else Route.PaneRoot(tab)

private fun isPaneRootPage(tab: Tab, r: Route): Boolean = if (tab == Tab.PILASTRI) r is Route.Month else r == Route.PaneRoot(tab)

/**
 * Folding (two panes → one): what the right pane shows becomes a page pushed over the left root, so Back returns to the
 * left root. A deeper detail is already the stack's top; the right-pane root itself is lifted as a page when it is "the
 * place" ([NavState.paneFocus]), under any detail opened from it. [month] is the Pilastri month Mese shows (the chosen one
 * or the latest with data).
 */
fun NavState.folded(month: String): NavState = copy(
    stacks = stacks.mapValues { (t, d) ->
        if (t !in paneFocus || d.firstOrNull()?.let { isPaneRootPage(t, it) } == true) d else listOf(paneRootPage(t, this.month ?: month)) + d
    },
)

/**
 * Unfolding (one pane → two): a cover stack that starts with the right-pane root as a page returns it to the right pane
 * (Mese keeps its month: the page shows [NavState.month] when one was chosen), the left root stays in place with its selection. [paneFocus] then remembers per tab whether the
 * cover was on that root page, so folding again restores exactly the cover's place.
 */
fun NavState.unfolded(): NavState {
    val lifted = mutableSetOf<Tab>()
    val next = stacks.mapValues { (t, d) ->
        val first = d.firstOrNull()
        if (first != null && isPaneRootPage(t, first)) {
            lifted += t
            d.drop(1)
        } else d
    }
    return copy(stacks = next, paneFocus = lifted)
}

/** The stack as text, so the page the user was on survives a dark-mode switch or the process being recreated. */
fun NavState.encode(): String = org.json.JSONObject().put("tab", tab.name).put("stacks", org.json.JSONObject().apply {
    stacks.forEach { (t, routes) -> put(t.name, org.json.JSONArray(routes.map { it.key })) }
}).put("month", month ?: "").put("focus", org.json.JSONArray(paneFocus.map { it.name })).toString()

fun decodeNav(text: String): NavState? = runCatching {
    val o = org.json.JSONObject(text)
    val s = o.getJSONObject("stacks")
    val month = o.optString("month").takeIf { m -> runCatching { java.time.YearMonth.parse(m) }.isSuccess }
    val focus = o.optJSONArray("focus")?.let { a -> (0 until a.length()).mapNotNull { i -> Tab.entries.firstOrNull { it.name == a.getString(i) } }.toSet() } ?: Tab.entries.toSet()
    NavState(tabOf(o.optString("tab")), Tab.entries.associateWith { t ->
        if (!s.has(t.name)) emptyList() else s.getJSONArray(t.name).let { a -> (0 until a.length()).mapNotNull { routeOf(a.getString(it)) } }
    }, month, focus)
}.getOrNull()

fun routeOf(key: String): Route? {
    val kind = key.substringBefore(':'); val arg = key.substringAfter(':', "")
    return when (kind) {
        "pillar" -> arg.toIntOrNull()?.takeIf { it in 1..4 }?.let { Route.Pillar(it) }
        "pension" -> Route.Pension
        "account" -> Route.Account(arg)
        "contributions" -> Route.Contributions
        "rules" -> Route.Rules
        "category" -> Route.Category(arg.takeIf { it != "null" })
        "review" -> Route.Review
        "payday" -> Route.Payday
        "settings" -> Route.Settings
        "connections" -> Route.Connections
        "backup" -> Route.Backup
        "month" -> runCatching { java.time.YearMonth.parse(arg) }.getOrNull()?.let { Route.Month(arg) }
        "plan" -> Route.Plan(arg)
        "plan-review" -> Route.PlanReview
        "categories" -> Route.Categories
        "targets" -> Route.Targets
        "scenarios" -> Route.Scenarios
        "pane" -> Tab.entries.firstOrNull { it.name == arg }?.let { Route.PaneRoot(it) }
        else -> null
    }
}
