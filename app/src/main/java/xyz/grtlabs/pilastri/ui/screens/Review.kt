package xyz.grtlabs.pilastri.ui.screens

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import xyz.grtlabs.pilastri.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.time.temporal.ChronoUnit
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.domain.EventType
import xyz.grtlabs.pilastri.ui.*

private fun decisionLabel(d: ExternalDecision): String = when (d) {
    is ExternalDecision.AsEvent -> when (d.type) {
        EventType.PERSONAL_CONTRIBUTION -> "Versamento tuo"; EventType.EMPLOYER_CONTRIBUTION -> "Contributo datore"; EventType.WITHDRAWAL -> "È una spesa"
        EventType.INCOME -> "Provento"; EventType.FEE -> "Costo o imposta"; else -> "Movimento"
    }
    is ExternalDecision.AsTrade -> "Operazione titoli"
    is ExternalDecision.AsTransfer -> "È un trasferimento"
    ExternalDecision.Ignore -> "Salta"
}

private fun effectOf(d: ExternalDecision?): String = when (d) {
    is ExternalDecision.AsEvent -> when (d.type) {
        EventType.PERSONAL_CONTRIBUTION -> "Diventano versamenti tuoi nel pilastro del conto."
        EventType.WITHDRAWAL -> "Diventano uscite registrate dal conto; la categoria resta quella scelta."
        EventType.INCOME -> "Diventano proventi del conto (non versamenti)."
        EventType.FEE -> "Diventano costi o imposte del conto."
        else -> "Diventano movimenti registrati."
    }
    is ExternalDecision.AsTrade -> "Diventano operazioni su titoli."
    else -> "Nessun effetto sui totali."
}

/**
 * The review queue (Council 1 A5, Council 2 M4). Groups by suggested effect with count and total; "Applica a tutti"
 * only where the group allows it. Possible transfers and duplicates are always decided one by one.
 */
@Composable
fun ReviewScreen(app: App) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val groups = remember(a) { a.reviewQueue() }
    val pending = groups.sumOf { it.count }
    Column(Modifier.fillMaxSize()) {
        BackHeader("Da rivedere", if (pending == 0) "tutto rivisto" else "$pending movimenti", { app.back() }, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            HelpButton("Da rivedere", "I movimenti importati non cambiano i totali finché non li applichi. Scorri a destra per applicare la proposta, a sinistra per ignorare; \"Annulla\" la riapre. \"Applica a tutti\" c'è solo dove il gruppo lo permette: possibili trasferimenti e duplicati si decidono uno per uno.")
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + LocalDockInset.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Lazy-UX #14: the empty state leads back.
            if (groups.isEmpty()) item("empty") { EmptyNote("Niente da rivedere", action = "Torna indietro", onAction = { app.back() }) }
            groups.forEach { g ->
                val gKey = g.label + ":" + g.movements.first().id
                item(gKey) {
                    Row(Modifier.fillMaxWidth().animateItem(placementSpec = Motion.spatialOffset()).padding(top = 8.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.label, style = Type.smallStrong, color = pal.brand)
                            Text("${g.count} · ${Fmt.eurCents(g.totalCents)}" + if (!g.bulk) " · uno per uno" else "", style = Type.small, color = pal.ink2)
                        }
                        // Lazy-UX #2: applies at once with one Undo, no dialog.
                        if (g.bulk && g.decision != null && g.count > 1) PillButton("Applica a tutti", {
                            val d = g.decision; val ids = g.movements.map { it.id }
                            app.commitWithUndo("${ids.size} movimenti applicati · ${effectOf(d).removeSuffix(".").lowercase()}.", { cur -> reopenAll(cur, ids) }) { cur -> ids.fold(cur) { acc, id -> acc.withExternalDecision(id, d) } }
                        }, kind = Btn.PRIMARY)
                    }
                }
                items(g.movements, key = { "m:" + it.id }) { m ->
                    val apply = suggestedApply(a, m, g)
                    SwipeDecide(apply?.let { decisionLabel(it.second) }, apply?.let { (id, d) -> { app.commitWithUndo("Applicato.", { it.withExternalReopened(id) }) { it.withExternalDecision(id, d) } } },
                        "Salta", { app.commitWithUndo("Saltato.", { it.withExternalReopened(m.id) }) { it.withExternalDecision(m.id, ExternalDecision.Ignore) } },
                        Modifier.animateItem(placementSpec = Motion.spatialOffset())) {
                        MovementCard(app, a, m, g, Modifier)
                    }
                }
            }
        }
    }
}

/** Reopens what one "Applica a tutti" applied; a row reopened meanwhile is left as it is. */
private fun reopenAll(cur: Archive, ids: List<String>): Archive =
    ids.fold(cur) { acc, id -> if (acc.externalMovements.firstOrNull { it.id == id }?.status == ExternalStatus.PENDING) acc else acc.withExternalReopened(id) }

/** The decision a right swipe applies (the row's own primary button): null when the row has no default (it keeps its choices). */
private fun suggestedApply(a: Archive, m: ExternalMovement, g: ReviewGroup): Pair<String, ExternalDecision>? {
    val d = g.decision ?: return null
    if (d is ExternalDecision.AsTransfer) {
        val other = g.movements.firstOrNull { it.id != m.id } ?: a.externalMovements.firstOrNull { it.id == d.counterpartId && it.id != m.id }
        return if (m.signedCents < 0 && other != null) m.id to ExternalDecision.AsTransfer(other.id) else null
    }
    return m.id to d
}

@Composable
private fun MovementCard(app: App, a: Archive, m: ExternalMovement, g: ReviewGroup, modifier: Modifier) {
    val pal = LocalPal.current
    val account = a.accounts.firstOrNull { it.id == m.accountId }
    val (cat, catSource) = remember(a, m) { if (m.categoryId != null) m.categoryId to m.categorySource else a.planning.categorize(m) }
    CardColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(m.description.ifBlank { "Movimento senza descrizione" }, style = Type.rowTitle, color = pal.ink, maxLines = 2)
                Text("${Fmt.day(m.date)} · ${account?.name ?: m.provider.label} · ${pillarName(account?.pillar ?: 1)}", style = Type.small, color = pal.ink2)
            }
            Text(Fmt.signedCents(m.signedCents), style = Type.amount, color = if (m.signedCents > 0) pal.goodInk else pal.ink)
        }
        val decision = g.decision
        // Lazy-UX #4: the three likely categories inline; "altre" opens the full sheet.
        if (decision != null && decision !is ExternalDecision.AsTransfer && g.label != "Possibile duplicato" && m.signedCents < 0)
            CategoryChips(app, a, m, cat) { id -> app.commit("Categoria scelta.") { it.withMovementCategory(m.id, id) } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            when {
                decision is ExternalDecision.AsTransfer -> {
                    val other = g.movements.firstOrNull { it.id != m.id } ?: a.externalMovements.firstOrNull { it.id == decision.counterpartId && it.id != m.id }
                    Text(other?.let { "con ${a.accounts.firstOrNull { x -> x.id == it.accountId }?.name ?: it.provider.label} · ${Fmt.day(it.date)}" } ?: "", style = Type.small, color = pal.ink2, modifier = Modifier.weight(1f))
                    if (m.signedCents < 0 && other != null) PillButton("È un trasferimento", { app.commit("Trasferimento registrato.") { it.withExternalDecision(m.id, ExternalDecision.AsTransfer(other.id)) } }, kind = Btn.PRIMARY)
                }
                g.label == "Possibile duplicato" -> {
                    Text("simile a un movimento già registrato", style = Type.small, color = pal.ink2, modifier = Modifier.weight(1f))
                    if (decision != null) PillButton(decisionLabel(decision), { app.commit("Applicato.") { it.withExternalDecision(m.id, decision) } }, kind = Btn.TONAL)
                }
                decision != null -> {
                    Text(if (m.signedCents < 0) a.categoryName(cat) + (catSource?.let { " · " + sourceLabel(it) } ?: "") else "", style = Type.small, color = pal.ink2, maxLines = 1, modifier = Modifier.weight(1f))
                    PillButton(decisionLabel(decision), { app.commitWithUndo("Applicato.", { it.withExternalReopened(m.id) }) { it.withExternalDecision(m.id, decision) } }, kind = Btn.PRIMARY)
                }
                else -> {
                    // No default (a deposit, an unknown kind): the user says what it is.
                    val options = buildList {
                        if (m.signedCents > 0) { add(ExternalDecision.AsEvent(EventType.INCOME)); add(ExternalDecision.AsEvent(EventType.PERSONAL_CONTRIBUTION)) }
                        else { add(ExternalDecision.AsEvent(EventType.WITHDRAWAL)); add(ExternalDecision.AsEvent(EventType.FEE)) }
                        if (account?.pillar == 4 && m.signedCents > 0) add(ExternalDecision.AsEvent(EventType.EMPLOYER_CONTRIBUTION))
                    }
                    ActionRow(Modifier.weight(1f)) {
                        options.forEach { o -> PillButton(decisionLabel(o), { app.commit("Applicato.") { it.withExternalDecision(m.id, o) } }, kind = Btn.TONAL) }
                    }
                }
            }
            PillButton("Salta", { app.commitWithUndo("Saltato.", { it.withExternalReopened(m.id) }) { it.withExternalDecision(m.id, ExternalDecision.Ignore) } }, kind = Btn.OUTLINE)
        }
    }
}

/* ---------- Installment plan detail ---------- */

@Composable
fun PlanScreen(app: App, planId: String) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val plan = a.installments.firstOrNull { it.id == planId }
    var picking by remember { mutableStateOf(false) }
    if (plan == null) { Page { BackHeader("Piano a rate", null, { app.back() }); EmptyNote("Piano non più disponibile") }; return }
    val count = plan.count ?: plan.installments.size
    Page {
        BackHeader(plan.title(), "${plan.lender} · ${sourceName(plan.source)}${sampleSuffix(a)}", { app.back() }) {
            Tag(when (plan.review) { PlanReview.PENDING -> "da confermare"; PlanReview.CONFIRMED -> "confermato"; PlanReview.IGNORED -> "ignorato" })
        }
        AccentCard { Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RESTANO", style = Type.caps, color = pal.accentLabel, modifier = Modifier.weight(1f))
                if (plan.review == PlanReview.PENDING) Tag("da confermare")
            }
            Text(atLeastText(plan.remainingCents, plan.remainingUnknown, Fmt::eurCents), style = Type.figure, color = pal.onAccent)
            Text(plan.progressText() + (plan.totalCents?.let { " · totale ${Fmt.eurCents(it)}" } ?: " · totale non indicato") +
                (if (plan.remainingUnknown == 1) " · 1 importo non indicato" else if (plan.remainingUnknown > 1) " · ${plan.remainingUnknown} importi non indicati" else ""), style = Type.small, color = pal.accentSoft)
            VSpace(10.dp)
            Meter(if (plan.count != null && count > 0) plan.paidCount().toFloat() / count else null, track = pal.accentTrack, fill = pal.accentFill)
        } }
        when (plan.review) {
            PlanReview.PENDING -> RoleActionRow("plan", spacing = 8.dp, equalWidth = true,
                secondary = { m -> PillButton("Non è mio", { app.commit("Piano ignorato.") { it.withPlanReview(plan.id, PlanReview.IGNORED) } }, m, kind = Btn.OUTLINE) },
                confirm = { m -> PillButton("Conferma", { app.commit("Piano confermato.") { it.withPlanReview(plan.id, PlanReview.CONFIRMED) } }, m, kind = Btn.PRIMARY, icon = Ic.check) })
            PlanReview.CONFIRMED -> ActionRow(spacing = 8.dp, equalWidth = true) { PillButton("Salta il piano", { app.commit("Piano ignorato.") { it.withPlanReview(plan.id, PlanReview.IGNORED) } }, kind = Btn.OUTLINE) }
            PlanReview.IGNORED -> ActionRow(spacing = 8.dp, equalWidth = true) { PillButton("Ripristina", { app.commit("Piano da confermare.") { it.withPlanReview(plan.id, PlanReview.PENDING) } }, kind = Btn.TONAL) }
        }
        RowsCard {
            ListRow("Categoria", a.categoryName(plan.categoryId), { picking = true },
                leading = { IconDisc(Ic.tag, pal.lilacBg, pal.lilacInk, 30.dp) })
        }
        SectionHeader("Rate", trailing = { HelpButton("Rate", "Un piano a rate è un impegno, non un movimento: la spesa conta quando ogni rata viene pagata dal conto. \"In ritardo\" compare solo se lo dice il fornitore; una data passata senza ricevuta resta da pagare.") })
        RowsCard {
            (1..count).forEach { seq ->
                val part = plan.installments.firstOrNull { it.seq == seq }
                val (state, color) = when {
                    part == null -> "non ancora vista" to pal.ink2
                    part.state == InstallmentState.PAID -> ("pagata" + (part.paidOn?.let { " il ${Fmt.day(it)}" } ?: "")) to pal.goodInk
                    part.state == InstallmentState.LATE -> "in ritardo (lo dice il fornitore)" to pal.overInk
                    part.state == InstallmentState.CANCELLED -> "annullata" to pal.ink2
                    part.dueDate == null -> "da pagare · data non indicata" to pal.ink
                    else -> "scade ${Fmt.relative(part.dueDate, app.today)}" + (if (part.dueDate.isBefore(app.today)) " · ricevuta non vista" else "") to pal.ink
                }
                ListRow("Rata $seq di $count", state, null, value = part?.cents?.let { Fmt.eurCents(it) } ?: "importo n/d", valueColor = color,
                    leading = { IconDisc(if (part?.state == InstallmentState.PAID) Ic.check else Ic.calendar, if (part?.state == InstallmentState.PAID) pal.goodBg else pal.lilacBg, if (part?.state == InstallmentState.PAID) pal.goodInk else pal.lilacInk, 30.dp) })
            }
        }
        if (plan.evidence.isNotEmpty()) {
            SectionHeader("Da dove viene")
            RowsCard {
                plan.evidence.sortedByDescending { it.date }.forEach { e ->
                    ListRow(e.summary, "${Fmt.day(e.date)} · ${evidenceSource(e.ref)}", null, leading = { IconDisc(if (e.ref.startsWith("gmail")) Ic.mail else Ic.card, pal.lilacBg, pal.lilacInk, 30.dp) })
                }
            }
        }
        if (plan.extractedByAgent) Tag("letto dall'agente · verifica")
    }
    if (picking) CategorySheet(app, "Categoria del piano", plan.categoryId, { id -> app.commit("Categoria del piano aggiornata.") { it.withPlanCategory(plan.id, id) }; picking = false }, { picking = false })
}

private fun sourceName(s: InstallmentSource) = when (s) { InstallmentSource.GMAIL -> "da Gmail"; InstallmentSource.PAYPAL -> "da PayPal"; InstallmentSource.MANUAL -> "inserito da te"; InstallmentSource.AGENT -> "dall'agente" }
private fun evidenceSource(ref: String) = when (ref.substringBefore(':')) { "gmail" -> "email"; "paypal" -> "PayPal"; else -> ref.substringBefore(':') }

/** Plans found by the connectors that wait for the user's word. */
@Composable
fun PlanReviewScreen(app: App) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val plans = a.installments.filter { it.review == PlanReview.PENDING }
    Column(Modifier.fillMaxSize()) {
        BackHeader("Rate da confermare", if (plans.isEmpty()) "nessun piano in attesa" else "${plans.size} piani trovati in Gmail o PayPal", { app.back() }, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + LocalDockInset.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plans.isEmpty()) item("empty") { EmptyNote("Tutto confermato", action = "Torna indietro", onAction = { app.back() }) }
            items(plans, key = { it.id }) { p ->
                // Lazy-UX #9: swipe right = Conferma, left = Non è mio, each with Undo; the buttons stay.
                val confirm = { app.commitWithUndo("Piano confermato.", { it.withPlanReview(p.id, PlanReview.PENDING) }) { it.withPlanReview(p.id, PlanReview.CONFIRMED) } }
                val ignore = { app.commitWithUndo("Piano ignorato.", { it.withPlanReview(p.id, PlanReview.PENDING) }) { it.withPlanReview(p.id, PlanReview.IGNORED) } }
                SwipeDecide("Conferma", confirm, "Non è mio", ignore, Modifier.animateItem(placementSpec = Motion.spatialOffset())) {
                    CardColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ListRow(p.title(), "${p.lender} · ${p.count ?: p.installments.size} rate" + (p.totalCents?.let { " · ${Fmt.eurCents(it)}" } ?: " · totale non indicato") + " · ${p.evidence.size} prove",
                            { app.go(Route.Plan(p.id)) }, Modifier.padding(start = 0.dp))
                        RoleActionRow("plan", spacing = 8.dp, end = true,
                            secondary = { m -> PillButton("Non è mio", ignore, m, kind = Btn.OUTLINE) },
                            confirm = { m -> PillButton("Conferma", confirm, m, kind = Btn.PRIMARY) })
                    }
                }
            }
        }
    }
}

/* ---------- Payday ---------- */

@Composable
fun PaydayScreen(app: App) {
    val a = app.archive ?: return
    val pal = LocalPal.current
    val salary = remember(a) { a.briefing(app.today).firstOrNull { it.kind == BriefingKind.SALARY } }
    Page {
        BackHeader("Ripartisci lo stipendio", salary?.let { "${Fmt.eurCents(it.cents ?: 0)} · ${it.date?.let { d -> Fmt.day(d) }}" } ?: "nessuno stipendio negli ultimi 5 giorni", { app.back() })
        if (salary?.cents == null) { EmptyNote("Nessuno stipendio recente"); return@Page }
        val lines = remember(a) { a.paydayProposal(salary.cents, app.today) }
        RowsCard {
            Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp)) { SectionHeader("Proposta", tag = "calcolato", trailing = { HelpButton("Ripartizione", "Ordine dei pilastri: prima le spese del mese, poi la riserva di emergenza e i progetti fino ai loro obiettivi, il resto al lungo termine. È una proposta: i bonifici li fai tu dall'app della banca e Pilastri non registra nulla finché non arrivano i movimenti.") }) }
            lines.forEach { l ->
                ListRow(pillarName(l.pillar), l.why, null, value = Fmt.eurCents(l.cents), leading = { IconDisc(Ic.pillars, pal.lilacBg, pal.lilacInk, 30.dp) })
            }
        }
    }
}

/** Days since a date, for "datato" labels. */
fun ageDays(d: java.time.LocalDate, today: java.time.LocalDate) = ChronoUnit.DAYS.between(d, today)
