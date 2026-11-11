package xyz.grtlabs.pilastri.ui

import android.app.Activity
import xyz.grtlabs.pilastri.ui.setup.EbScreen as E
import xyz.grtlabs.pilastri.ui.setup.EbFlowModel as M
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import java.time.YearMonth
import xyz.grtlabs.pilastri.data.isSample
import xyz.grtlabs.pilastri.data.isEmpty
import xyz.grtlabs.pilastri.data.dataMonths
import xyz.grtlabs.pilastri.data.monthTimeline

/**
 * Capture navigation for `tools/capture-all.mjs`: `am start -n xyz.grtlabs.pilastri/.MainActivity --activity-single-top
 * --es capture_id <n> --es capture_route <route> [--es capture_tab <tab>] [--es capture_month <yyyy-MM|prev>]` sets the
 * navigation state directly (no taps, no restart) and, once the screen has stopped drawing, logs one line:
 * `PILASTRI_CAPTURE settled <id> <route>`; `capture_list` logs one `dest <id> <name>|<tab>|<route>` per destination of [destinations] and `listed <id> <n> unmapped=...` (or `settled-timeout`, or `ignored <id> <reason>`).
 *
 * Honoured ONLY while the archive is the synthetic sample data ("Dati di esempio", [isSample]); on real data the intent is
 * ignored, so a phone holding real data can never be driven or dumped by it.
 *
 * capture_route: `root` | `agent` | a comma list of [Route.key]s pushed in order (`pillar:1`, `pension`, `contributions`,
 * `settings,connections`, ...); `month` stands for the Mese page of capture_month (default: the current month).
 */
object CaptureHook {
    /** Capture seam for the Enable Banking setup pages (EB-1..EB-7 and the failure rows): MainActivity renders [EbSetupFlow] with this state over the app; null otherwise. */
    val ebInitial = androidx.compose.runtime.mutableStateOf<xyz.grtlabs.pilastri.ui.setup.EbFlowModel?>(null)

    /** Synthetic states, one per board screen and per failure row; names are the capture-file suffixes. */
    fun ebStates(): List<Pair<String, xyz.grtlabs.pilastri.ui.setup.EbFlowModel>> {
        val now = System.currentTimeMillis(); val bank = "Banca di prova"
        fun at(vararg s: xyz.grtlabs.pilastri.ui.setup.EbScreen, b: String? = null) = s.map { xyz.grtlabs.pilastri.ui.setup.EbStep(it, if (it.number >= 5) b else null) }

        val base = M()
        val out = mutableListOf<Pair<String, xyz.grtlabs.pilastri.ui.setup.EbFlowModel>>()
        out += "eb-1" to base
        out += "eb-2" to M(stack = at(E.EMAIL, E.LINK), sentAt = now)
        out += "eb-2-late" to M(stack = at(E.EMAIL, E.LINK), sentAt = now - 50_000)
        out += "eb-2-paste" to M(stack = at(E.EMAIL, E.LINK), sentAt = now - 10_000, pasteOpen = true)
        out += "eb-3" to M(stack = at(E.EMAIL, E.LINK, E.REGISTER), working = true, checks = setOf(xyz.grtlabs.pilastri.ui.setup.EbCheck.SIGNED_IN, xyz.grtlabs.pilastri.ui.setup.EbCheck.KEY))
        out += "eb-4" to M(stack = at(E.EMAIL, E.LINK, E.REGISTER, E.BANKS), chosen = listOf("isybank"))
        out += "eb-5" to M(stack = at(E.EMAIL, E.LINK, E.REGISTER, E.BANKS, E.APPROVE, b = bank), queue = listOf(bank), asked = listOf(bank))
        out += "eb-6" to M(stack = at(E.EMAIL, E.LINK, E.REGISTER, E.BANKS, E.APPROVE, E.CONFIRM, b = bank), queue = listOf(bank), asked = listOf(bank))
        out += "eb-7" to M(stack = at(E.EMAIL, E.LINK, E.REGISTER, E.BANKS, E.APPROVE, E.CONFIRM, E.LINKED, b = bank), asked = listOf(bank), linked = mapOf(bank to 2))
        for (k in xyz.grtlabs.pilastri.ui.setup.EbFailKind.values()) {
            val scr = when (k) {
                xyz.grtlabs.pilastri.ui.setup.EbFailKind.UNREACHABLE, xyz.grtlabs.pilastri.ui.setup.EbFailKind.RATE_LIMIT -> listOf(E.EMAIL)
                xyz.grtlabs.pilastri.ui.setup.EbFailKind.LINK_INVALID -> listOf(E.EMAIL, E.LINK)
                xyz.grtlabs.pilastri.ui.setup.EbFailKind.NOT_LINKED, xyz.grtlabs.pilastri.ui.setup.EbFailKind.NO_ACCOUNTS, xyz.grtlabs.pilastri.ui.setup.EbFailKind.SCA_DENIED, xyz.grtlabs.pilastri.ui.setup.EbFailKind.PAGE_UNREACHABLE ->
                    listOf(E.EMAIL, E.LINK, E.REGISTER, E.BANKS, E.APPROVE)
                xyz.grtlabs.pilastri.ui.setup.EbFailKind.NO_RETURN -> listOf(E.EMAIL, E.LINK, E.REGISTER, E.BANKS, E.APPROVE, E.CONFIRM)
                else -> listOf(E.EMAIL, E.LINK, E.REGISTER)
            }
            out += "eb-fail-${k.name.lowercase().replace('_', '-')}" to M(stack = at(*scr.toTypedArray(), b = bank), queue = listOf(bank), asked = listOf(bank),
                failure = xyz.grtlabs.pilastri.ui.setup.EbFailure(k, bank, if (k == xyz.grtlabs.pilastri.ui.setup.EbFailKind.REFUSED) "motivo di prova" else null),
                retryAt = if (k == xyz.grtlabs.pilastri.ui.setup.EbFailKind.RATE_LIMIT) now + 120_000 else null)
        }
        return out
    }

    const val TAG = "PILASTRI_CAPTURE"
    private const val QUIET_MS = 300L
    private const val TIMEOUT_MS = 6000L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastDraw = 0L
    private var watched: View? = null

    /** The navigation state a capture request asks for; the Agente panel is opened by the caller when [agent] is set. */
    data class Target(val nav: NavState, val month: YearMonth, val agent: Boolean, val agentKind: String? = null, val single: Boolean = false)

    /** Pure: the state for (route, tab, month); [now] is the current month, [month] may be "prev". */
    fun target(route: String, tab: String?, month: String?, now: YearMonth): Target {
        val ym = when (month) { null, "" -> null; "prev" -> now.minusMonths(1); else -> YearMonth.parse(month) }
        var n = NavState().select(tabOf(tab?.uppercase()))
        if (ym != null) n = n.withMonth(ym.toString())
        if (route != "root" && route != "agent" && route != SPESE_SINGLE && !route.startsWith("agente-")) route.split(',').forEach { k ->
            val r = if (k == "month") Route.Month((ym ?: now).toString()) else routeOf(k)
            if (r != null) n = n.push(r)
        }
        return Target(n, ym ?: now, route == "agent" || route.startsWith("agente-"), route.removePrefix("agente-").takeIf { route.startsWith("agente-") }, route == SPESE_SINGLE)
    }

    /** Capture-forced state route (no person reaches it this way): Spese with one window of coverage, a person's first month. */
    const val SPESE_SINGLE = "spese-single"

    /** One destination of the capture list: [name] is the file-name stem, [tab] the section, [route] a [Route.key] list or "root"/"agent". */
    data class Dest(val name: String, val tab: String, val route: String)

    /**
     * Every page the navigation model can show, derived from [Route] itself (its nested classes), so a new Route is captured with
     * no tool edit. Objects are taken as they are; classes with arguments expand from the sample archive, at most [cap] values
     * each (latest months). A Route class with arguments nobody maps is returned in [unmapped] and the run fails loudly.
     */
    fun destinations(a: xyz.grtlabs.pilastri.data.Archive, today: java.time.LocalDate, cap: Int): Pair<List<Dest>, List<String>> {
        val out = mutableListOf<Dest>(); val unmapped = mutableListOf<String>()
        fun kebab(s: String) = s.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        for (c in Route::class.java.classes.sortedBy { it.simpleName }) when (val n = c.simpleName) {
            "Root" -> Tab.entries.forEach { out += Dest("top-" + it.name.lowercase(), it.name, "root") }
            "PaneRoot" -> {}   // the open Fold's right-pane root: Root(tab) at that width
            "Pillar" -> (1..4).forEach { out += Dest("pillar-$it", "PILASTRI", "pillar:$it") }
            "Account" -> a.accounts.take(cap).forEach { out += Dest("account-" + kebab(it.id), "PILASTRI", "account:" + it.id) }
            "Plan" -> a.installments.take(cap).forEach { out += Dest("plan-" + kebab(it.id), "SPESE", "plan:" + it.id) }
            "Category" -> out += Dest("category-none", "SPESE", "category:null")
            // latest month first, so a per-kind pass (perKind 1) keeps the current month
            "Month" -> monthTimeline(a.dataMonths(today), today).takeLast(cap).reversed().forEach { out += Dest("month-$it", "PILASTRI", "month:$it") }
            "Settings" -> out += Dest("top-impostazioni", "PILASTRI", "settings")
            else -> {
                val inst = runCatching { c.getField("INSTANCE").get(null) as? Route }.getOrNull()
                // the state a user reaches the page in: the section that opens it, and Settings under its Settings children
                if (inst != null) out += Dest(kebab(n), OWNER_TAB[n] ?: "PILASTRI", (if (n in VIA_SETTINGS) "settings," else "") + inst.key) else unmapped += n
            }
        }
        out += Dest("top-agente", "PILASTRI", "agent")
        // capture-forced states (no person reaches them): a linked assistant with one synthetic reply, and the "page does not respond" line
        out += Dest("agente-reply", "PILASTRI", "agente-reply")
        out += Dest("agente-nodrive", "PILASTRI", "agente-nodrive")
        // capture-forced: Spese as if coverage started inside the last window (SpendSpan.count == 1, no neighbour either side); the sample archive itself is untouched
        out += Dest("spese-single", "SPESE", SPESE_SINGLE)
        return out to unmapped
    }

    /** Section a page is opened from (Pension: Futuro's Deduzione card; Scenarios: Futuro; Plan/Category: Spese); default Pilastri. */
    private val OWNER_TAB = mapOf("Pension" to "FUTURO", "Scenarios" to "FUTURO")
    /** Pages opened from Impostazioni: captured with Settings underneath, as the user reaches them. */
    private val VIA_SETTINGS = setOf("Connections", "Backup", "Targets", "Categories", "Rules")

    /** The extras of one in-app run (`capture_all`): see [runAll]. */
    private val ALL_EXTRAS = listOf("capture_all", "capture_prefix", "capture_perkind", "capture_only", "capture_vptag", "capture_mid", "capture_shots", "capture_cap",
        "capture_frames", "capture_motion", "capture_from", "capture_dir", "capture_resume", "capture_states", "capture_actions", "capture_max", "capture_skiptext", "capture_nav", "capture_eb")

    /**
     * One in-app motion capture (`capture_motion`: entries `out|route|tab|month|vp|kind|len` joined by ';'). The page state is (route, tab, month);
     * [vp] is the strip's viewport index in dump order. `drag`: `<out>-rest` + `<out>-moving`, finger from 85 % to 35 % of the viewport width at its
     * centre (tools/viewport-point.mjs `auto`), the travel times [len] (0.4 = cap-k `mvs_`). `hold`: `<out>-hold-rest|short|long`, finger at 20 % of the
     * viewport moved right by 25 % / 60 % of the current page pitch + 8 dp (tools/hold-point.mjs). The finger is real input (MotionEvents dispatched to the
     * window, DOWN + four MOVEs, dump while held, UP), the same path a touch takes; no adb round trip.
     */
    data class Motion(val out: String, val route: String, val tab: String, val month: String?, val vp: Int, val kind: String, val len: Double)

    fun parseMotion(s: String?): List<Motion> = s.orEmpty().split(';').mapNotNull { e ->
        val p = e.trim().split('|')
        if (p.size < 7 || p[0].isBlank()) null else Motion(p[0], p[1], p[2], p[3].ifBlank { null }, p[4].toIntOrNull() ?: 0, p[5], p[6].toDoubleOrNull() ?: 1.0)
    }

    private fun boxes(x: String, tag: String): List<List<Int>> =
        Regex("<node [^>]*resource-id=\"" + Regex.escape(tag) + "\"[^>]*bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"").findAll(x)
            .map { m -> m.groupValues.drop(1).map(String::toInt) }.filter { it[2] > it[0] && it[3] > it[1] }.toList()

    /** Drag start x, y, end x for viewport [vi] of dump [x] (same arithmetic as viewport-point.mjs and cap-k `mvs_`). */
    fun dragPoints(x: String, tag: String, vi: Int, len: Double): IntArray? {
        val v = boxes(x, tag).getOrNull(vi) ?: return null
        val w = v[2] - v[0]; val y = Math.round((v[1] + v[3]) / 2.0).toInt()
        val x1 = Math.round(v[0] + w * 0.85).toInt(); var x2 = Math.round(v[0] + w * 0.35).toInt()
        if (len < 1.0) x2 = x1 - ((x1 - x2) * len + 1e-9).toInt()
        return intArrayOf(x1, y, x2)
    }

    /** Hold start x, y and page pitch for viewport [vi] (hold-point.mjs: 20 % in, pitch = current page width + 8 dp). */
    fun holdPoint(x: String, tag: String, vi: Int, density: Double): IntArray? {
        val v = boxes(x, tag).getOrNull(vi) ?: return null
        val a = boxes(x, "period-page-cur").firstOrNull { it[0] >= v[0] - 2 && it[2] <= v[2] + 2 && it[1] >= v[1] - 2 && it[3] <= v[3] + 2 } ?: return null
        return intArrayOf(Math.round(v[0] + (v[2] - v[0]) * 0.2).toInt(), Math.round((v[1] + v[3]) / 2.0).toInt(), Math.round(a[2] - a[0] + 8 * density).toInt())
    }

    // ---- states / actions / launch frames (device add-ons state-stability, action-outcome, safe-area; SKILL section 2 K15-K18)
    /** Labels the walker never taps: destructive or external (sign-in, connect, mail and notification grants, delete, send, pay, export, sample load). */
    const val SKIP_DEFAULT = "(?i)accedi|entra|sign|login|collega|connetti|gmail|google|consenti|notific|elimina|cancella|rimuovi|svuota|reset|azzera|invia|send|paga|pay|acquista|compra|esporta|importa|backup|ripristina|esci|logout|carica i dati|condividi|share|scarica|download|chiama|https?:"
    /** Navigation controls (not actions of the page). */
    const val NAV_DEFAULT = "(?i)^(continua|avanti|inizia|comincia|indietro.*|dopo|salta|chiudi|fatto|annulla|back|pilastri|spese|futuro|agente|impostazioni)$"
    const val ADVANCE_DEFAULT = "(?i)^(continua|avanti|inizia|comincia)$"

    /** One node parsed back from a dump (what the walker taps). */
    data class N(val text: String, val id: String, val desc: String, val checkable: Boolean, val checked: Boolean, val clickable: Boolean, val enabled: Boolean, val b: List<Int>) {
        val cx get() = (b[0] + b[2]) / 2; val cy get() = (b[1] + b[3]) / 2; val label get() = text.ifBlank { desc }
    }
    fun parseNodes(x: String): List<N> = Regex("<node [^>]*>").findAll(x).mapNotNull { m ->
        val s = m.value; fun a(k: String) = Regex(" $k=\"([^\"]*)\"").find(s)?.groupValues?.get(1) ?: ""
        val b = Regex("bounds=\"\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]\"").find(s)?.groupValues?.drop(1)?.map(String::toInt) ?: return@mapNotNull null
        if (b[2] <= b[0] || b[3] <= b[1]) null else N(a("text"), a("resource-id"), a("content-desc"), a("checkable") == "true", a("checked") == "true", a("clickable") == "true", a("enabled") != "false", b)
    }.toList()
    private fun attrEsc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun kebabOf(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32)

    /** Context of one walk: where dumps go and what is never tapped. */
    private class Run(val activity: Activity, val dir: java.io.File, val id: String, val skip: Regex, val nav: Regex, val max: Int) {
        var files = 0
        fun dump(attrs: String = "") = runCatching { CaptureDump.xml(activity, attrs) }.getOrElse { "" }
        fun save(name: String, x: String) { if (x.isNotEmpty()) { java.io.File(dir, "$name.xml").writeText(x); files++ } }
    }

    private fun tap(r: Run, x: Int, y: Int, then: () -> Unit) {
        val down = SystemClock.uptimeMillis(); inject(r.activity, MotionEvent.ACTION_DOWN, down, x, y)
        main.postDelayed({ inject(r.activity, MotionEvent.ACTION_UP, down, x, y); settle(r.id, "tap", 3000L, then) }, 60L)
    }

    /** States: each enabled checkable node (toggle, chip, radio; Compose maps selectable to checkable) is tapped once: `<n>-stateK-before|after`
     *  (after carries `capture-tapped`); the previous choice is tapped back so later captures see the original state. */
    private fun states(r: Run, n: String, x0: String, then: () -> Unit) {
        val cands = parseNodes(x0).filter { it.checkable && it.enabled && it.label.isNotBlank() && !r.skip.containsMatchIn(it.label) }.distinctBy { it.label }.take(r.max)
        fun s(k: Int, before: String) {
            if (k >= cands.size) { then(); return }
            val c = cands[k]
            tap(r, c.cx, c.cy) {
                val after = r.dump(" capture-tapped=\"${attrEsc(c.label)}\""); r.save("$n-state$k-before", before); r.save("$n-state$k-after", after)
                val was = parseNodes(before).filter { it.checkable }; val now = parseNodes(after).filter { it.checkable }.associateBy { it.label }
                val off = was.firstOrNull { it.checked && it.label != c.label && now[it.label]?.checked == false }
                val back = off ?: if (now[c.label]?.checked != c.checked) now[c.label] else null
                if (back == null) s(k + 1, after) else tap(r, back.cx, back.cy) { s(k + 1, r.dump()) }
            }
        }
        s(0, x0)
    }

    /** Actions: each enabled, labelled, non-checkable control that is neither navigation nor destructive/external is tapped: `<n>-actionK-before|after`
     *  (after carries `capture-tapped`, and `capture-foreign="true"` when another window took the focus); [reset] restores the page after each tap. */
    private fun actions(r: Run, n: String, x0: String, reset: (() -> Unit) -> Unit, then: () -> Unit) {
        val cands = parseNodes(x0).filter { it.clickable && it.enabled && !it.checkable && it.label.isNotBlank() && !r.skip.containsMatchIn(it.label) && !r.nav.containsMatchIn(it.label) && !it.id.startsWith("dock") }
            .distinctBy { it.label }.take(r.max)
        fun a(k: Int) {
            if (k >= cands.size) { then(); return }
            val c = cands[k]; val before = r.dump()
            tap(r, c.cx, c.cy) {
                val foreign = !r.activity.hasWindowFocus()
                r.save("$n-action$k-before", before); r.save("$n-action$k-after", r.dump(" capture-tapped=\"${attrEsc(c.label)}\"" + if (foreign) " capture-foreign=\"true\"" else ""))
                CaptureDump.dismissPopups(r.activity); reset { a(k + 1) }
            }
        }
        a(0)
    }

    /** Setup walk on an EMPTY archive: each page (`<p>-setup-<title>`), its states and actions, then the advance control (a choice is made first if needed). */
    private fun setupWalk(r: Run, prefix: String, adv: Regex, then: () -> Unit) {
        val seen = mutableSetOf<String>()
        fun title(x: String) = parseNodes(x).firstOrNull { it.id == "setup-title" }?.text ?: ""
        fun page(p: Int) {
            settle(r.id, "setup", 4000L) {
                val x = r.dump(); val t = title(x)
                if (p >= 14 || t.isBlank() || !seen.add(t)) { then(); return@settle }
                val n = "$prefix-setup-" + kebabOf(t); r.save(n, x); Log.i(TAG, "begin ${r.id} ${1000 + p} $n")
                val reset: (() -> Unit) -> Unit = { k -> if (title(r.dump()) != t) (r.activity as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.onBackPressed(); settle(r.id, "reset", 3000L, k) }
                states(r, n, x) { actions(r, n, r.dump(), reset) {
                    val ns = parseNodes(r.dump()); val go = ns.firstOrNull { adv.containsMatchIn(it.text) || adv.containsMatchIn(it.desc) }
                    if (go == null) { then(); return@actions }
                    val pick = if (ns.any { it.checkable && it.checked }) null else ns.firstOrNull { it.checkable && it.enabled && !r.skip.containsMatchIn(it.label) }
                    val advance = { tap(r, go.cx, go.cy) { page(p + 1) } }
                    if (pick != null) tap(r, pick.cx, pick.cy) { advance() } else advance()
                } }
            }
        }
        page(0)
    }

    /**
     * Launch frames (`--es capture_launch <id>` with `am start -S`): N dumps at fixed intervals from process start (`capture_frames` N:ms, default 20:150),
     * held in memory and written only once the archive is known to be the sample or EMPTY (real data: dropped). Then optionally the setup walk
     * (`capture_walk 1`, empty archive only) and the sample load (`capture_sample 1`, empty archive only), then `done <id>`.
     */
    private fun launch(activity: Activity, id: String, intent: Intent) {
        val prefix = intent.getStringExtra("capture_prefix") ?: "cap"; val label = intent.getStringExtra("capture_label") ?: "launch"
        val fr = parseFrames(intent.getStringExtra("capture_frames")) ?: (20 to 150L)
        val walk = intent.getStringExtra("capture_walk") == "1"; val sample = intent.getStringExtra("capture_sample") == "1"
        val dirName = intent.getStringExtra("capture_dir") ?: id; val max = intent.getStringExtra("capture_max")?.toIntOrNull() ?: 6
        val skip = Regex(intent.getStringExtra("capture_skiptext") ?: SKIP_DEFAULT); val nav = Regex(intent.getStringExtra("capture_nav") ?: NAV_DEFAULT)
        val adv = Regex(intent.getStringExtra("capture_advance") ?: ADVANCE_DEFAULT)
        LAUNCH_EXTRAS.forEach { intent.removeExtra(it) }
        val decor = activity.window.decorView
        if (watched !== decor) { decor.viewTreeObserver.addOnDrawListener { lastDraw = SystemClock.uptimeMillis() }; watched = decor }
        val frames = ArrayList<String>(); val f0 = SystemClock.uptimeMillis()
        fun finish(waited: Long) {
            val app = ArchiveSyncHost.bound(); val a = app?.archive
            if (app == null || a == null) { if (waited < 15000) main.postDelayed({ finish(waited + 100) }, 100) else Log.i(TAG, "ignored $id app-not-ready"); return }
            if (!a.isSample && !a.isEmpty) { Log.i(TAG, "ignored $id not-sample-data"); return }
            val dir = java.io.File(activity.getExternalFilesDir(null), "capture/$dirName").apply { mkdirs() }
            val r = Run(activity, dir, id, skip, nav, max)
            frames.forEachIndexed { k, x -> r.save("$prefix-$label-f" + k.toString().padStart(2, '0'), x) }
            fun done() = CaptureDump.afterIo { Log.i(TAG, "done $id ${r.files} files ${SystemClock.uptimeMillis() - f0} ms") }
            fun loadSample() {
                if (!sample || app.archive?.isEmpty != true) { done(); return }
                app.loadSample()
                fun wait(t: Long) { if (app.archive?.isSample == true || t > 15000) settle(id, "sample") { done() } else main.postDelayed({ wait(t + 100) }, 100) }
                wait(0)
            }
            if (walk && a.isEmpty) setupWalk(r, prefix, adv) { loadSample() } else loadSample()
        }
        fun frame(k: Int) {
            if (k >= fr.first) { finish(0); return }
            frames += runCatching { CaptureDump.xml(activity) }.getOrElse { "" }
            main.postAtTime({ frame(k + 1) }, f0 + (k + 1) * fr.second)
        }
        main.post { frame(0) }
    }
    private val LAUNCH_EXTRAS = listOf("capture_launch", "capture_prefix", "capture_label", "capture_frames", "capture_walk", "capture_sample", "capture_dir", "capture_max", "capture_skiptext", "capture_nav", "capture_advance")

    /** States then actions on one captured page; [reset] puts the page back (navigation state) after each tap. */
    private fun walkPage(r: Run, n: String, x: String, statesOn: Boolean, actionsOn: Boolean, reset: (() -> Unit) -> Unit, then: () -> Unit) {
        val acts = { if (actionsOn) actions(r, n, r.dump(), reset, then) else then() }
        if (statesOn) states(r, n, x) { reset { acts() } } else acts()
    }

    private const val MOVE_MS = 40L
    private fun inject(activity: Activity, action: Int, down: Long, x: Int, y: Int) {
        val loc = IntArray(2); activity.window.decorView.getLocationOnScreen(loc)
        val pp = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val pc = MotionEvent.PointerCoords().apply { this.x = (x - loc[0]).toFloat(); this.y = (y - loc[1]).toFloat(); pressure = 1f; size = 1f }
        val ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(pp), arrayOf(pc), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        runCatching { activity.dispatchTouchEvent(ev) }; ev.recycle()
    }

    /** Finger DOWN at (x0, y), four MOVEs to x1, held until the screen settles, [held] (the dump), UP at x1, settle, [then]. */
    private fun press(activity: Activity, id: String, x0: Int, y: Int, x1: Int, held: () -> Unit, then: () -> Unit) {
        val down = SystemClock.uptimeMillis()
        inject(activity, MotionEvent.ACTION_DOWN, down, x0, y)
        fun move(i: Int) {
            if (i > 4) { settle(id, "held", 2500L) { held(); inject(activity, MotionEvent.ACTION_UP, down, x1, y); settle(id, "released", 3000L, then) }; return }
            inject(activity, MotionEvent.ACTION_MOVE, down, x0 + (x1 - x0) * i / 4, y)
            main.postDelayed({ move(i + 1) }, MOVE_MS)
        }
        main.postDelayed({ move(1) }, MOVE_MS)
    }

    /** "N:ms" -> frame count and interval (N 1..60, interval 16..500 ms); null = off. */
    fun parseFrames(s: String?): Pair<Int, Long>? {
        val p = s?.split(':') ?: return null
        val n = p.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 60) ?: return null
        return n to (p.getOrNull(1)?.toLongOrNull() ?: 32L).coerceIn(16L, 500L)
    }

    private fun glob(g: String) = Regex(g.map { when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) } }.joinToString(""))

    /**
     * v2 whole-run capture: `--es capture_all <id> --es capture_prefix <p> [--es capture_perkind N] [--es capture_only g1,g2] [--es capture_vptag period-viewport]
     * [--es capture_mid 1] [--es capture_shots 0|1] [--es capture_cap N]`. The app walks every destination itself (settle, self-dump to `<p>-<name>.xml`,
     * half-size `half/<p>-<name>.png`, and `<p>-<name>-mid.*` for the previous month when the page has a swipe strip), into
     * `getExternalFilesDir("capture")/<id>/`, then logs `PILASTRI_CAPTURE done <id> <n files>`. One `adb pull` brings it back.
     */
    private fun runAll(activity: Activity, id: String, intent: Intent) {
        val prefix = intent.getStringExtra("capture_prefix") ?: "cap"
        val perKind = intent.getStringExtra("capture_perkind")?.toIntOrNull() ?: 0
        val only = (intent.getStringExtra("capture_only") ?: "").split(',').filter { it.isNotBlank() }.map(::glob)
        val vpTag = intent.getStringExtra("capture_vptag") ?: "period-viewport"
        val mid = intent.getStringExtra("capture_mid") == "1"; val shots = intent.getStringExtra("capture_shots") != "0"
        val cap = intent.getStringExtra("capture_cap")?.toIntOrNull() ?: 3
        val frames = parseFrames(intent.getStringExtra("capture_frames"))
        val motions = parseMotion(intent.getStringExtra("capture_motion"))
        val from = intent.getStringExtra("capture_from")?.toIntOrNull() ?: 0
        val dirName = intent.getStringExtra("capture_dir") ?: id
        val resume = intent.getStringExtra("capture_resume") == "1"
        val statesOn = intent.getStringExtra("capture_states") == "1"
        val actionsOn = (intent.getStringExtra("capture_actions") ?: "").split(',').filter { it.isNotBlank() }.map(::glob)
        val ebOn = intent.getStringExtra("capture_eb") == "1"
        val walkMax = intent.getStringExtra("capture_max")?.toIntOrNull() ?: 6
        val skip = Regex(intent.getStringExtra("capture_skiptext") ?: SKIP_DEFAULT); val navRe = Regex(intent.getStringExtra("capture_nav") ?: NAV_DEFAULT)
        ALL_EXTRAS.forEach { intent.removeExtra(it) }
        val decor = activity.window.decorView
        if (watched !== decor) { decor.viewTreeObserver.addOnDrawListener { lastDraw = SystemClock.uptimeMillis() }; watched = decor }
        val t0 = SystemClock.uptimeMillis()
        main.post(object : Runnable {
            override fun run() {
                val app = ArchiveSyncHost.bound(); val archive = app?.archive
                when {
                    app != null && archive != null -> {
                        if (!archive.isSample) { Log.i(TAG, "ignored $id not-sample-data"); return }
                        // one device folder for the whole run (all layouts): the script pulls it ONCE at the end; a resumed walk (after a crash) keeps it
                        val dir = java.io.File(activity.getExternalFilesDir(null), "capture/$dirName"); if (!resume && from == 0) dir.listFiles()?.filter { it.name.startsWith("$prefix-") || it.name == "half" }?.forEach { f ->
                            if (f.name == "half") f.listFiles()?.filter { it.name.startsWith("$prefix-") }?.forEach { it.delete() } else f.delete() }
                        dir.mkdirs()
                        val kinds = mutableMapOf<String, Int>()
                        fun keep(d: Dest): Boolean {
                            if (perKind <= 0) return true
                            val k = d.route.substringBefore(':') + (if (d.route == "root") d.tab else ""); val v = (kinds[k] ?: 0) + 1; kinds[k] = v; return v <= perKind
                        }
                        val sel = destinations(archive, java.time.LocalDate.now(), cap).first.filter(::keep).filter { d ->
                            val n = "$prefix-${d.name}"; only.isEmpty() || only.any { it.matches(n) || it.matches("$n-mid") || it.matches("$n-vp0") }
                        }
                        var files = 0
                        fun grab(name: String, then: (String) -> Unit) {
                            val x = runCatching { CaptureDump.xml(activity) }.getOrElse { Log.i(TAG, "dump-failed $id $name ${it.javaClass.simpleName}: ${it.message}"); "" }
                            if (x.isNotEmpty()) { java.io.File(dir, "$name.xml").writeText(x); files++ }
                            val after = { then(x) }
                            if (shots) CaptureDump.shot(activity, java.io.File(dir, "half/$name.png")) { files++; after() } else after()
                        }
                        // frames mode: right after a navigation / month change, N self-dumps at fixed intervals while the spring settles (no adb round trip), `<name>-f00.xml`...
                        fun frameRun(name: String, then: () -> Unit) {
                            if (frames == null) { then(); return }
                            val f0 = SystemClock.uptimeMillis()
                            fun frame(k: Int) {
                                if (k >= frames.first) { then(); return }
                                val x = runCatching { CaptureDump.xml(activity) }.getOrElse { "" }
                                if (x.isNotEmpty()) { java.io.File(dir, "$name-f" + k.toString().padStart(2, '0') + ".xml").writeText(x); files++ }
                                main.postAtTime({ frame(k + 1) }, f0 + (k + 1) * frames.second)
                            }
                            main.post { frame(0) }
                        }
                        val rw = Run(activity, dir, id, skip, navRe, walkMax)
                        val mot = motions.filter { m -> only.isEmpty() || listOf("", "-rest", "-moving", "-hold-rest").any { s -> only.any { it.matches(m.out + s) } } }
                        val density = activity.resources.displayMetrics.density.toDouble()
                        fun save(name: String, x: String) { if (x.isNotEmpty()) { java.io.File(dir, "$name.xml").writeText(x); files++ } }
                        fun dump() = runCatching { CaptureDump.xml(activity) }.getOrElse { "" }
                        // motion items run after the static walk; their index continues the static one (`begin <id> <index> <name>`), so a resumed walk can start after a crash
                        fun motionStep(j: Int) {
                            if (j >= mot.size) {
                                val finish = { CaptureDump.afterIo { Log.i(TAG, "done $id ${files + rw.files} files ${SystemClock.uptimeMillis() - t0} ms") }; Unit }
                                if (!ebOn) { finish(); return }
                                val ebs = ebStates()
                                fun eb(k: Int) {
                                    if (k >= ebs.size) { ebInitial.value = null; finish(); return }
                                    val n = "$prefix-setup-${ebs[k].first}"; Log.i(TAG, "begin $id ${sel.size + mot.size + k} $n")
                                    ebInitial.value = ebs[k].second
                                    settle(id, "eb") { grab(n) { eb(k + 1) } }
                                }
                                eb(0); return
                            }
                            val m = mot[j]; if (sel.size + j < from) { motionStep(j + 1); return }
                            Log.i(TAG, "begin $id ${sel.size + j} ${m.out}")
                            val t = runCatching { target(m.route, m.tab, m.month, YearMonth.now()) }.getOrNull() ?: run { Log.i(TAG, "skipped $id ${m.out} bad-route"); motionStep(j + 1); return }
                            app.closeAgent(); app.nav = t.nav; app.spendMonth = t.month; app.captureSingleWindow = t.single; if (t.agent) { app.assistant.prepareForCapture(t.agentKind); app.openAgent() }
                            settle(id, m.route) {
                                val rest = dump()
                                if (m.kind == "hold") {
                                    save("${m.out}-hold-rest", rest)
                                    val p = holdPoint(rest, vpTag, m.vp, density) ?: run { Log.i(TAG, "skipped $id ${m.out} no-viewport-${m.vp}"); motionStep(j + 1); return@settle }
                                    val ds = listOf("short" to p[2] * 25 / 100, "long" to p[2] * 60 / 100)
                                    fun h(k: Int) { if (k >= ds.size) { motionStep(j + 1); return }; press(activity, id, p[0], p[1], p[0] + ds[k].second, { save("${m.out}-hold-${ds[k].first}", dump()) }) { h(k + 1) } }
                                    h(0)
                                } else {
                                    save("${m.out}-rest", rest)
                                    val p = dragPoints(rest, vpTag, m.vp, m.len) ?: run { Log.i(TAG, "skipped $id ${m.out} no-viewport-${m.vp}"); motionStep(j + 1); return@settle }
                                    press(activity, id, p[0], p[1], p[2], { save("${m.out}-moving", dump()) }) { motionStep(j + 1) }
                                }
                            }
                        }
                        fun step(i: Int) {
                            if (i >= sel.size) { motionStep(0); return }
                            if (i < from) { step(i + 1); return }
                            val d = sel[i]; val n = "$prefix-${d.name}"
                            Log.i(TAG, "begin $id $i $n")
                            val t = runCatching { target(d.route, d.tab, null, YearMonth.now()) }.getOrNull() ?: run { Log.i(TAG, "skipped $id $n bad-route"); step(i + 1); return }
                            app.closeAgent(); app.nav = t.nav; app.spendMonth = t.month; app.captureSingleWindow = t.single; if (t.agent) { app.assistant.prepareForCapture(t.agentKind); app.openAgent() }
                            frameRun(n) { settle(id, d.route) {
                                grab(n) { x -> walkPage(rw, n, x, statesOn, actionsOn.any { it.matches(n) }, { k -> app.closeAgent(); app.nav = t.nav; app.spendMonth = t.month; app.captureSingleWindow = t.single; if (t.agent) { app.assistant.prepareForCapture(t.agentKind); app.openAgent() }; settle(id, d.route) { k() } }) {
                                    if (mid && x.contains("resource-id=\"$vpTag\"")) {
                                        val tm = target(d.route, d.tab, "prev", YearMonth.now()); app.nav = tm.nav; app.spendMonth = tm.month
                                        frameRun("$n-sw") { settle(id, d.route) { grab("$n-mid") { step(i + 1) } } }
                                    } else step(i + 1)
                                } }
                            } }
                        }
                        step(0)
                    }
                    SystemClock.uptimeMillis() - t0 < 15000 -> main.postDelayed(this, 100)
                    else -> Log.i(TAG, "ignored $id app-not-ready")
                }
            }
        })
    }

    fun handle(activity: Activity, intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra("capture_launch")?.let { launch(activity, it, intent); return }
        intent.getStringExtra("capture_all")?.let { runAll(activity, it, intent); return }
        val listId = intent.getStringExtra("capture_list")
        val route = intent.getStringExtra("capture_route") ?: listId?.let { "list" } ?: return
        val id = listId ?: intent.getStringExtra("capture_id") ?: "0"; val cap = intent.getStringExtra("capture_cap")?.toIntOrNull() ?: 3
        val tab = intent.getStringExtra("capture_tab"); val month = intent.getStringExtra("capture_month")
        listOf("capture_list", "capture_cap", "capture_route", "capture_id", "capture_tab", "capture_month").forEach { intent.removeExtra(it) }
        val decor = activity.window.decorView
        if (watched !== decor) { decor.viewTreeObserver.addOnDrawListener { lastDraw = SystemClock.uptimeMillis() }; watched = decor }
        val t0 = SystemClock.uptimeMillis()
        main.post(object : Runnable {
            override fun run() {
                val app = ArchiveSyncHost.bound(); val archive = app?.archive
                when {
                    app != null && archive != null -> {
                        if (!archive.isSample) { Log.i(TAG, "ignored $id not-sample-data"); return }
                        if (listId != null) {
                            val (ds, un) = destinations(archive, java.time.LocalDate.now(), cap)
                            ds.forEach { Log.i(TAG, "dest $id ${it.name}|${it.tab}|${it.route}") }
                            Log.i(TAG, "listed $id ${ds.size} unmapped=${un.joinToString(",")}"); return
                        }
                        val t = runCatching { target(route, tab, month, YearMonth.now()) }.getOrElse { Log.i(TAG, "ignored $id bad-request"); return }
                        app.closeAgent(); app.nav = t.nav; app.spendMonth = t.month
                        if (t.agent) { app.assistant.prepareForCapture(t.agentKind); app.openAgent() }
                        settle(id, route)
                    }
                    SystemClock.uptimeMillis() - t0 < 15000 -> main.postDelayed(this, 100)
                    else -> Log.i(TAG, "ignored $id app-not-ready")
                }
            }
        })
    }

    /** Settled = no frame drawn for [QUIET_MS] after the request was applied (springs finished, content stable). */
    private fun settle(id: String, route: String, onDone: (() -> Unit)? = null) = settle(id, route, TIMEOUT_MS, onDone)

    private fun settle(id: String, route: String, timeout: Long, onDone: (() -> Unit)?) {
        val t0 = SystemClock.uptimeMillis()
        main.post(object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                when {
                    now - maxOf(lastDraw, t0) >= QUIET_MS -> if (onDone != null) onDone() else Log.i(TAG, "settled $id $route")
                    now - t0 > timeout -> if (onDone != null) { Log.i(TAG, "settled-timeout $id $route"); onDone() } else Log.i(TAG, "settled-timeout $id $route")
                    else -> main.postDelayed(this, 40)
                }
            }
        })
    }
}
