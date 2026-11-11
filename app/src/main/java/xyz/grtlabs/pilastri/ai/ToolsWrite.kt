package xyz.grtlabs.pilastri.ai

import org.json.JSONObject
import xyz.grtlabs.pilastri.data.Account
import xyz.grtlabs.pilastri.data.AccountType
import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.ExternalDecision
import xyz.grtlabs.pilastri.data.ExternalMovement
import xyz.grtlabs.pilastri.data.ExternalStatus
import xyz.grtlabs.pilastri.data.PlanItem
import xyz.grtlabs.pilastri.data.PlanKind
import xyz.grtlabs.pilastri.data.PlanStatus
import xyz.grtlabs.pilastri.data.PensionFund
import xyz.grtlabs.pilastri.data.Sourced
import xyz.grtlabs.pilastri.data.CategorySource
import xyz.grtlabs.pilastri.data.CategoryTaxonomy
import xyz.grtlabs.pilastri.data.installmentPaymentIds
import xyz.grtlabs.pilastri.data.categorize
import xyz.grtlabs.pilastri.data.categoryName
import xyz.grtlabs.pilastri.data.withAgentCategories
import xyz.grtlabs.pilastri.data.withNewCategory
import xyz.grtlabs.pilastri.data.withoutAgentCategories
import xyz.grtlabs.pilastri.data.withExternalDecision
import xyz.grtlabs.pilastri.data.withExternalReopened
import xyz.grtlabs.pilastri.data.withoutAccount
import xyz.grtlabs.pilastri.data.withoutInstrument
import xyz.grtlabs.pilastri.domain.AccountTransfer
import xyz.grtlabs.pilastri.domain.AccountValuation
import xyz.grtlabs.pilastri.domain.Confidence
import xyz.grtlabs.pilastri.domain.EventType
import xyz.grtlabs.pilastri.domain.Instrument
import xyz.grtlabs.pilastri.domain.LedgerEvent
import xyz.grtlabs.pilastri.domain.Money
import xyz.grtlabs.pilastri.domain.Trade
import xyz.grtlabs.pilastri.domain.TradeSide
import xyz.grtlabs.pilastri.domain.Valuation
import java.time.LocalDate
import java.time.YearMonth

/**
 * Write tools. Each one validates its arguments against the archive snapshot, builds an [AiChange] with an exact
 * inverse, and runs the change once against the snapshot as a dry run. Nothing is written here: the agent applies
 * the change through the host's `commit`, or leaves it pending for a tap, according to [AiPolicy].
 */
internal object WriteTools {
    private const val AUDIT_APPLY = "assistente"
    private const val AUDIT_UNDO = "assistente · annullo"
    private const val GONE = "è già modificato o rimosso: l'annullamento è stato rifiutato."

    val all: List<AiTool> by lazy {
        listOf(
            addEventTool(), addPillarValuationTool(), addAccountValuationTool(), addAccountTool(), addPlanItemTool(),
            classifyImportTool(), ignoreImportTool(), reopenImportTool(), setCategoryTool(),
        )
    }

    private val eventTypes = listOf(
        EventType.PERSONAL_CONTRIBUTION, EventType.EMPLOYER_CONTRIBUTION, EventType.WITHDRAWAL, EventType.INCOME, EventType.FEE,
    )

    private fun eventLabel(type: EventType) = when (type) {
        EventType.PERSONAL_CONTRIBUTION -> "versamento personale"
        EventType.EMPLOYER_CONTRIBUTION -> "contributo del datore"
        EventType.WITHDRAWAL -> "prelievo"
        EventType.INCOME -> "entrata"
        EventType.FEE -> "commissione"
        else -> type.name.lowercase()
    }

    private fun pillarArg(args: JSONObject): Int = args.argInt("pilastro")?.takeIf { it in 1..4 } ?: bad("Indica il pilastro come numero da 1 a 4.")

    private fun dateArg(args: JSONObject, key: String = "data"): LocalDate {
        val date = args.argDate(key) ?: bad("Manca la data ($key, AAAA-MM-GG).")
        if (date.isAfter(LocalDate.now())) bad("La data non può essere futura.")
        return date
    }

    private fun cleanName(raw: String?, what: String, max: Int): String {
        val text = raw?.trim()?.replace(Regex("\\s+"), " ") ?: bad("Manca $what.")
        if (text.isEmpty() || text.length > max) bad("$what deve avere da 1 a $max caratteri.")
        return text
    }

    private fun proposal(ctx: AiToolContext, change: AiChange, note: String): AiToolResult {
        change.applyTo(ctx.archive) // dry run on the snapshot: throws an Italian message if the change cannot work
        return AiToolResult.Proposal(change, note)
    }

    // ---- registra_movimento ----

    private fun addEventTool() = AiTool(
        "registra_movimento",
        "Prepara la registrazione di un movimento dichiarato dall'utente: versamento personale, contributo del datore " +
            "(solo pilastro 4), prelievo, entrata o commissione. Importo in euro con al massimo due decimali, senza segno. " +
            "Usalo solo se l'utente lo chiede o lo conferma esplicitamente. L'app chiede conferma all'utente prima di scrivere.",
        schema(
            listOf("pilastro", "tipo", "data", "importo_eur"),
            "pilastro" to prop("integer", "Pilastro 1-4."),
            "tipo" to prop("string", "Tipo di movimento.", eventTypes.map { it.name }),
            "data" to prop("string", "Data del movimento AAAA-MM-GG, non futura."),
            "importo_eur" to prop("string", "Importo in euro, es. 150.00. Positivo."),
            "conto_id" to prop("string", "Conto dello stesso pilastro (da elenca_conti), facoltativo."),
        ), writes = true,
    ) { ctx, args ->
        val pillar = pillarArg(args)
        val typeName = args.argText("tipo") ?: bad("Manca il tipo.")
        val type = eventTypes.firstOrNull { it.name == typeName.uppercase() } ?: bad("Tipo non valido: usa ${eventTypes.joinToString { it.name }}.")
        if (type == EventType.EMPLOYER_CONTRIBUTION && pillar != 4) bad("Il contributo del datore appartiene solo al pilastro 4.")
        val date = dateArg(args)
        val cents = parseEuroToCents(args.opt("importo_eur"), "importo_eur")
        if (cents <= 0) bad("L'importo deve essere maggiore di zero.")
        val account = args.argText("conto_id")?.let { id ->
            ctx.archive.accounts.firstOrNull { it.id == id } ?: bad("Conto non trovato: usa un id restituito da elenca_conti.")
        }
        if (account != null && account.pillar != pillar) bad("Il conto appartiene al pilastro ${account.pillar}, non al $pillar.")
        val key = idempotencyKey(ctx.toolCallId, "registra_movimento", args)
        val event = LedgerEvent("ai:$key", date, pillar, type, Money(cents), Confidence.USER_REPORTED, ctx.source(), accountId = account?.id)
        val summary = "Aggiungi ${eventLabel(type)} di ${italian(cents)} del $date al pilastro $pillar (${PILLAR_NAMES[pillar - 1]})" +
            (account?.let { " sul conto «${it.name}»" } ?: "")
        val clock = ctx.clock
        val change = AiChange(key, AiChangeKind.ADD_EVENT, summary,
            apply = { a ->
                val existing = a.events.firstOrNull { it.id == event.id }
                when {
                    existing == event -> a
                    existing != null -> throw AiAlreadyModifiedException("Esiste già un movimento con questo identificativo ma con contenuto diverso.")
                    else -> {
                        event.accountId?.let { id ->
                            val target = a.accounts.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Il conto non è più disponibile.")
                            require(target.pillar == event.pillar) { "Il conto deve appartenere allo stesso pilastro del movimento." }
                        }
                        require(!event.date.isAfter(LocalDate.now())) { "La data non può essere futura." }
                        a.copy(events = a.events + event).audited(clock, AUDIT_APPLY, summary)
                    }
                }
            },
            undo = { a ->
                if (a.events.count { it == event } != 1) throw AiAlreadyModifiedException("Il movimento $GONE")
                a.copy(events = a.events.filterNot { it == event }).audited(clock, AUDIT_UNDO, "Annullato: $summary")
            },
            diff = listOf(AiDiffLine("Movimenti del pilastro $pillar", null, "+ ${eventLabel(type)} ${italian(cents)} · $date")),
        )
        proposal(ctx, change, "${eventLabel(type)} di ${plain(cents)} EUR del $date, pilastro $pillar")
    }

    // ---- registra_valutazione_pilastro ----

    private fun addPillarValuationTool() = AiTool(
        "registra_valutazione_pilastro",
        "Prepara la registrazione di una valutazione datata del valore di un pilastro dichiarata dall'utente. " +
            "Non sovrascrive una valutazione già presente nello stesso giorno. L'app chiede conferma all'utente prima di scrivere.",
        schema(
            listOf("pilastro", "data", "importo_eur"),
            "pilastro" to prop("integer", "Pilastro 1-4."),
            "data" to prop("string", "Data della valutazione AAAA-MM-GG, non futura."),
            "importo_eur" to prop("string", "Valore in euro, es. 10250.40."),
        ), writes = true,
    ) { ctx, args ->
        val pillar = pillarArg(args)
        val date = dateArg(args)
        val cents = parseEuroToCents(args.opt("importo_eur"), "importo_eur")
        ctx.archive.valuations.firstOrNull { it.pillar == pillar && it.date == date }?.let {
            bad("Esiste già una valutazione del pilastro $pillar al $date (${italian(it.amount.cents)}): l'assistente non la sovrascrive.")
        }
        val key = idempotencyKey(ctx.toolCallId, "registra_valutazione_pilastro", args)
        val valuation = Valuation(date, pillar, Money(cents), ctx.source(), Confidence.USER_REPORTED)
        val summary = "Aggiungi valutazione del pilastro $pillar (${PILLAR_NAMES[pillar - 1]}) al $date: ${italian(cents)}"
        val clock = ctx.clock
        val latest = ctx.archive.valuations.filter { it.pillar == pillar }.maxByOrNull { it.date }
        val change = AiChange(key, AiChangeKind.ADD_VALUATION, summary,
            apply = { a ->
                val existing = a.valuations.firstOrNull { it.pillar == valuation.pillar && it.date == valuation.date }
                when {
                    existing == valuation -> a
                    existing != null -> throw IllegalArgumentException("Esiste già un valore per quel giorno e pilastro.")
                    else -> {
                        require(!valuation.date.isAfter(LocalDate.now())) { "La data non può essere futura." }
                        a.copy(valuations = a.valuations + valuation).audited(clock, AUDIT_APPLY, summary)
                    }
                }
            },
            undo = { a ->
                if (a.valuations.count { it == valuation } != 1) throw AiAlreadyModifiedException("La valutazione $GONE")
                a.copy(valuations = a.valuations.filterNot { it == valuation }).audited(clock, AUDIT_UNDO, "Annullato: $summary")
            },
            diff = listOf(AiDiffLine("Valutazioni del pilastro $pillar",
                latest?.let { "ultima: ${italian(it.amount.cents)} al ${it.date}" } ?: "nessuna valutazione",
                "+ ${italian(cents)} al $date")),
        )
        proposal(ctx, change, "valutazione del pilastro $pillar al $date di ${plain(cents)} EUR")
    }

    // ---- registra_valutazione_conto ----

    private fun addAccountValuationTool() = AiTool(
        "registra_valutazione_conto",
        "Prepara la registrazione di una valutazione datata del valore di un singolo conto dichiarata dall'utente. " +
            "Non sovrascrive una valutazione già presente nello stesso giorno. L'app chiede conferma all'utente prima di scrivere.",
        schema(
            listOf("conto_id", "data", "importo_eur"),
            "conto_id" to prop("string", "Id del conto (da elenca_conti)."),
            "data" to prop("string", "Data della valutazione AAAA-MM-GG, non futura."),
            "importo_eur" to prop("string", "Valore in euro, es. 2500.00."),
        ), writes = true,
    ) { ctx, args ->
        val account = args.argText("conto_id")?.let { id -> ctx.archive.accounts.firstOrNull { it.id == id } }
            ?: bad("Conto non trovato: usa un id restituito da elenca_conti.")
        val date = dateArg(args)
        val cents = parseEuroToCents(args.opt("importo_eur"), "importo_eur")
        ctx.archive.accountValuations.firstOrNull { it.accountId == account.id && it.date == date }?.let {
            bad("Esiste già una valutazione di questo conto al $date (${italian(it.amount.cents)}): l'assistente non la sovrascrive.")
        }
        val key = idempotencyKey(ctx.toolCallId, "registra_valutazione_conto", args)
        val valuation = AccountValuation(account.id, date, Money(cents), ctx.source(), Confidence.USER_REPORTED)
        val summary = "Aggiungi valutazione del conto «${account.name}» al $date: ${italian(cents)}"
        val clock = ctx.clock
        val latest = ctx.archive.accountValuations.filter { it.accountId == account.id }.maxByOrNull { it.date }
        val change = AiChange(key, AiChangeKind.ADD_VALUATION, summary,
            apply = { a ->
                val existing = a.accountValuations.firstOrNull { it.accountId == valuation.accountId && it.date == valuation.date }
                when {
                    existing == valuation -> a
                    existing != null -> throw IllegalArgumentException("Esiste già un valore del conto per quel giorno.")
                    else -> {
                        require(a.accounts.any { it.id == valuation.accountId }) { "Il conto non è più disponibile." }
                        require(!valuation.date.isAfter(LocalDate.now())) { "La data non può essere futura." }
                        a.copy(accountValuations = a.accountValuations + valuation).audited(clock, AUDIT_APPLY, summary)
                    }
                }
            },
            undo = { a ->
                if (a.accountValuations.count { it == valuation } != 1) throw AiAlreadyModifiedException("La valutazione del conto $GONE")
                a.copy(accountValuations = a.accountValuations.filterNot { it == valuation }).audited(clock, AUDIT_UNDO, "Annullato: $summary")
            },
            diff = listOf(AiDiffLine("Valutazioni del conto «${account.name}»",
                latest?.let { "ultima: ${italian(it.amount.cents)} al ${it.date}" } ?: "nessuna valutazione",
                "+ ${italian(cents)} al $date")),
        )
        proposal(ctx, change, "valutazione del conto ${account.id} al $date di ${plain(cents)} EUR")
    }

    // ---- crea_conto ----

    private fun addAccountTool() = AiTool(
        "crea_conto",
        "Crea un conto nel registro (nome, pilastro ed eventuale tipo di fondo pensione). Non crea saldi né movimenti. " +
            "Non crea un conto con lo stesso nome nello stesso pilastro.",
        schema(
            listOf("nome", "pilastro"),
            "nome" to prop("string", "Nome del conto, fino a 200 caratteri."),
            "pilastro" to prop("integer", "Pilastro 1-4."),
            "tipo" to prop("string", "Solo pilastro 4: PENSION_FUND per un fondo pensione; altrimenti omettere.", listOf("GENERAL", "PENSION_FUND")),
            "fondo_id" to prop("string", "Solo con PENSION_FUND: id di un fondo già descritto; se omesso, il fondo prende il nome del conto."),
        ), writes = true,
    ) { ctx, args ->
        val name = cleanName(args.argText("nome"), "Il nome", 200)
        val pillar = pillarArg(args)
        val type = args.argText("tipo")?.let { raw -> AccountType.entries.firstOrNull { it.name == raw.uppercase() } ?: bad("Tipo di conto non valido: $raw.") }
        if (type == AccountType.PENSION_FUND && pillar != 4) bad("Un fondo pensione appartiene al pilastro 4.")
        val fundIdArg = args.argText("fondo_id")?.also { id ->
            if (type != AccountType.PENSION_FUND) bad("fondo_id vale solo con PENSION_FUND.")
            if (ctx.archive.funds.none { it.id == id }) bad("Fondo non trovato: $id.")
        }
        if (ctx.archive.accounts.any { it.pillar == pillar && it.name.equals(name, ignoreCase = true) }) {
            bad("Esiste già un conto con questo nome nel pilastro $pillar.")
        }
        val key = idempotencyKey(ctx.toolCallId, "crea_conto", args)
        // A new fund is described only by its name (declared); every other field stays n/d until a document states it.
        val newFund = if (type == AccountType.PENSION_FUND && fundIdArg == null) PensionFund("fund:ai:$key", Sourced.declared(name)) else null
        val account = Account("ai:$key", name, pillar, ctx.source(), type, fundId = fundIdArg ?: newFund?.id)
        val summary = "Crea il conto «$name» nel pilastro $pillar (${PILLAR_NAMES[pillar - 1]})" +
            (if (type == AccountType.PENSION_FUND) " come fondo pensione" else "")
        val clock = ctx.clock
        val change = AiChange(key, AiChangeKind.ADD_ACCOUNT, summary,
            apply = { a ->
                val existing = a.accounts.firstOrNull { it.id == account.id }
                when {
                    existing == account -> a
                    existing != null -> throw AiAlreadyModifiedException("Esiste già un conto con questo identificativo ma con contenuto diverso.")
                    newFund != null && a.funds.any { it.id == newFund.id } -> throw AiAlreadyModifiedException("Esiste già un fondo con questo identificativo.")
                    else -> a.copy(accounts = a.accounts + account, funds = a.funds + listOfNotNull(newFund)).audited(clock, AUDIT_APPLY, summary)
                }
            },
            undo = { a ->
                if (a.accounts.count { it == account } != 1) throw AiAlreadyModifiedException("Il conto $GONE")
                val removed = try { a.withoutAccount(account.id) } catch (_: IllegalArgumentException) {
                    throw AiAlreadyModifiedException("Il conto è già modificato: ora è usato da altre registrazioni e non lo rimuovo.")
                }
                // The fund this change described goes with it, unless something else now refers to it.
                val fundGone = if (newFund == null || removed.accounts.any { it.fundId == newFund.id }) removed
                    else removed.copy(funds = removed.funds.filterNot { it == newFund })
                fundGone.audited(clock, AUDIT_UNDO, "Annullato: $summary")
            },
            diff = listOf(AiDiffLine("Registro conti", null, "+ «$name» · pilastro $pillar")),
        )
        proposal(ctx, change, "conto «$name» nel pilastro $pillar con id ${account.id}")
    }

    // ---- aggiungi_voce_piano ----

    private fun addPlanItemTool() = AiTool(
        "aggiungi_voce_piano",
        "Aggiunge una voce di piano (entrata o uscita di un mese, effettiva o di scenario). Lo stato va indicato: " +
            "ACTUAL per ciò che è accaduto, SCENARIO per un'ipotesi. Mesi dal 2026 al 2085; una voce effettiva non può essere futura.",
        schema(
            listOf("mese", "etichetta", "tipo", "stato", "importo_eur"),
            "mese" to prop("string", "Mese AAAA-MM."),
            "etichetta" to prop("string", "Descrizione breve, fino a 200 caratteri."),
            "tipo" to prop("string", "Entrata o uscita.", listOf("INCOME", "EXPENSE")),
            "stato" to prop("string", "Effettiva o di scenario.", listOf("ACTUAL", "SCENARIO")),
            "importo_eur" to prop("string", "Importo in euro, es. 1200.00. Non negativo."),
        ), writes = true,
    ) { ctx, args ->
        val month = YearMonth.parse(args.argText("mese") ?: bad("Manca il mese (AAAA-MM)."))
        if (month.year !in 2026..2085) bad("Il mese deve essere tra il 2026 e il 2085.")
        val label = cleanName(args.argText("etichetta"), "L'etichetta", 200)
        val kind = args.argText("tipo")?.let { raw -> PlanKind.entries.firstOrNull { it.name == raw.uppercase() } } ?: bad("Tipo non valido: usa INCOME o EXPENSE.")
        val status = args.argText("stato")?.let { raw -> PlanStatus.entries.firstOrNull { it.name == raw.uppercase() } } ?: bad("Stato non valido: usa ACTUAL o SCENARIO.")
        if (status == PlanStatus.ACTUAL && month.isAfter(YearMonth.now())) bad("Una voce effettiva non può riguardare un mese futuro: usa SCENARIO.")
        val cents = parseEuroToCents(args.opt("importo_eur"), "importo_eur")
        val key = idempotencyKey(ctx.toolCallId, "aggiungi_voce_piano", args)
        val item = PlanItem("ai:$key", month, label, kind, status, Money(cents), ctx.source())
        val kindLabel = if (kind == PlanKind.INCOME) "entrata" else "uscita"
        val statusLabel = if (status == PlanStatus.ACTUAL) "effettiva" else "di scenario"
        val summary = "Aggiungi al piano $kindLabel $statusLabel «$label» di ${italian(cents)} per $month"
        val clock = ctx.clock
        val change = AiChange(key, AiChangeKind.ADD_PLAN_ITEM, summary,
            apply = { a ->
                val existing = a.plan.firstOrNull { it.id == item.id }
                when {
                    existing == item -> a
                    existing != null -> throw AiAlreadyModifiedException("Esiste già una voce con questo identificativo ma con contenuto diverso.")
                    else -> a.copy(plan = a.plan + item).audited(clock, AUDIT_APPLY, summary)
                }
            },
            undo = { a ->
                if (a.plan.count { it == item } != 1) throw AiAlreadyModifiedException("La voce di piano $GONE")
                a.copy(plan = a.plan.filterNot { it == item }).audited(clock, AUDIT_UNDO, "Annullato: $summary")
            },
            diff = listOf(AiDiffLine("Piano $month", null, "+ $kindLabel $statusLabel «$label» ${italian(cents)}")),
        )
        proposal(ctx, change, "voce di piano $kindLabel $statusLabel di ${plain(cents)} EUR per $month")
    }

    // ---- imported movements ----

    private fun movementFor(archive: Archive, ref: String?, field: String = "rif"): ExternalMovement {
        val wanted = ref ?: bad("Manca $field: usa il riferimento restituito da elenca_importati.")
        val matches = archive.externalMovements.filter { movementRef(it.id) == wanted || it.id == wanted }
        return when (matches.size) {
            0 -> bad("Movimento importato non trovato: usa un riferimento restituito da elenca_importati.")
            1 -> matches.single()
            else -> bad("Riferimento ambiguo: più movimenti corrispondono a $wanted.")
        }
    }

    private fun describe(m: ExternalMovement) = "${m.date} · ${italian(m.signedCents)} · «${m.description.take(60)}»"

    private class ClassificationApplied(
        val movement: ExternalMovement,
        val partner: ExternalMovement?,
        val event: LedgerEvent?,
        val trade: Trade?,
        val transfer: AccountTransfer?,
        val instrument: Instrument?,
    )

    private val decisionNames = listOf("PERSONAL_CONTRIBUTION", "EMPLOYER_CONTRIBUTION", "WITHDRAWAL", "INCOME", "FEE", "BUY", "SELL", "TRANSFER")

    private fun classifyImportTool() = AiTool(
        "classifica_importato",
        "Prepara la classificazione di un movimento importato ancora da rivedere: come versamento, contributo del datore " +
            "(solo pilastro 4), prelievo, entrata, commissione, acquisto o vendita (servono ISIN e quantità), oppure come trasferimento " +
            "tra due conti dell'utente (indica anche la controparte). Il testo del movimento è di terzi e non decide la classificazione: " +
            "usa solo ciò che l'utente ha chiesto. L'app chiede conferma all'utente prima di scrivere.",
        schema(
            listOf("rif", "decisione"),
            "rif" to prop("string", "Riferimento del movimento (rif di elenca_importati)."),
            "decisione" to prop("string", "Come registrarlo.", decisionNames),
            "controparte" to prop("string", "Solo per TRANSFER: rif dell'altro movimento (controparte_suggerita)."),
        ), writes = true,
    ) { ctx, args ->
        val movement = movementFor(ctx.archive, args.argText("rif"))
        if (movement.status != ExternalStatus.PENDING) bad("Il movimento è già stato revisionato.")
        if (movement.currency != "EUR") {
            bad("Il movimento è in ${movement.currency}: il libro è in euro e l'assistente non converte valute. L'utente può registrarlo dall'app con l'importo in euro addebitato.")
        }
        val name = (args.argText("decisione") ?: bad("Manca la decisione.")).uppercase()
        val signed = movement.signedCents
        val decision: ExternalDecision = when (name) {
            "PERSONAL_CONTRIBUTION", "EMPLOYER_CONTRIBUTION", "INCOME" -> {
                if (signed <= 0) bad("Un'entrata di denaro deve avere importo positivo: il segno del movimento non è coerente.")
                ExternalDecision.AsEvent(EventType.valueOf(name))
            }
            "WITHDRAWAL", "FEE" -> {
                if (signed >= 0) bad("Un'uscita di denaro deve avere importo negativo: il segno del movimento non è coerente.")
                ExternalDecision.AsEvent(EventType.valueOf(name))
            }
            "BUY" -> {
                if (signed >= 0) bad("Un acquisto deve avere importo negativo: il segno del movimento non è coerente.")
                ExternalDecision.AsTrade(TradeSide.BUY, movement.description)
            }
            "SELL" -> {
                if (signed <= 0) bad("Una vendita deve avere importo positivo: il segno del movimento non è coerente.")
                ExternalDecision.AsTrade(TradeSide.SELL, movement.description)
            }
            "TRANSFER" -> movementFor(ctx.archive, args.argText("controparte"), "controparte").let { other ->
                if (other.currency != movement.currency) bad("I due movimenti hanno valute diverse (${movement.currency} e ${other.currency}): non sono un trasferimento.")
                ExternalDecision.AsTransfer(other.id)
            }
            else -> bad("Decisione non valida: usa ${decisionNames.joinToString()}.")
        }
        val partner = (decision as? ExternalDecision.AsTransfer)?.let { d -> ctx.archive.externalMovements.single { it.id == d.counterpartId } }
        val key = idempotencyKey(ctx.toolCallId, "classifica_importato", args)
        val label = when (name) {
            "PERSONAL_CONTRIBUTION" -> "versamento personale"; "EMPLOYER_CONTRIBUTION" -> "contributo del datore"
            "WITHDRAWAL" -> "prelievo"; "INCOME" -> "entrata"; "FEE" -> "commissione"
            "BUY" -> "acquisto"; "SELL" -> "vendita"; else -> "trasferimento tra conti"
        }
        val summary = "Classifica il movimento importato ${describe(movement)} come $label"
        val clock = ctx.clock
        val rows = listOfNotNull(ReadTools.importedRow(movement), partner?.let { ReadTools.importedRow(it) })
        val applied = arrayOfNulls<ClassificationApplied>(1)
        val change = AiChange(key, AiChangeKind.CLASSIFY_IMPORT, summary,
            apply = { a ->
                val next = a.withExternalDecision(movement.id, decision, clock().toString())
                val after = next.externalMovements.single { it.id == movement.id }
                val linked = after.linkedRecordId
                applied[0] = ClassificationApplied(
                    after,
                    (decision as? ExternalDecision.AsTransfer)?.let { d -> next.externalMovements.single { it.id == d.counterpartId } },
                    next.events.firstOrNull { it.id == linked },
                    next.trades.firstOrNull { it.id == linked },
                    next.transfers.firstOrNull { it.id == linked },
                    next.instruments.firstOrNull { instrument -> a.instruments.none { it.id == instrument.id } },
                )
                next.audited(clock, AUDIT_APPLY, summary)
            },
            undo = { a ->
                val done = applied[0] ?: throw AiAlreadyModifiedException("La classificazione non risulta applicata da questo assistente.")
                if (a.externalMovements.singleOrNull { it.id == movement.id } != done.movement) throw AiAlreadyModifiedException("Il movimento importato $GONE")
                if (done.partner != null && a.externalMovements.singleOrNull { it.id == done.partner.id } != done.partner) {
                    throw AiAlreadyModifiedException("Il movimento collegato $GONE")
                }
                if (done.event != null && a.events.count { it == done.event } != 1) throw AiAlreadyModifiedException("Il movimento registrato $GONE")
                if (done.trade != null && a.trades.count { it == done.trade } != 1) throw AiAlreadyModifiedException("L'operazione registrata $GONE")
                if (done.transfer != null && a.transfers.count { it == done.transfer } != 1) throw AiAlreadyModifiedException("Il trasferimento registrato $GONE")
                val reopened = a.withExternalReopened(movement.id, clock().toString())
                val cleaned = done.instrument
                    ?.takeIf { instrument -> reopened.instruments.count { it == instrument } == 1 && reopened.trades.none { it.instrumentId == instrument.id } }
                    ?.let { reopened.withoutInstrument(it.id) } ?: reopened
                cleaned.audited(clock, AUDIT_UNDO, "Annullato: $summary")
            },
            diff = buildList {
                add(AiDiffLine("Movimento importato ${movementRef(movement.id)}", "da rivedere", "registrato come $label"))
                when (decision) {
                    is ExternalDecision.AsEvent -> add(AiDiffLine("Movimenti", null, "+ $label ${italian(Math.abs(signed))} · ${movement.date}"))
                    is ExternalDecision.AsTrade -> add(AiDiffLine("Operazioni", null, "+ $label ${italian(Math.abs(signed))} · ${movement.date}"))
                    is ExternalDecision.AsTransfer -> add(AiDiffLine("Trasferimenti", null, "+ ${italian(Math.abs(signed))} · ${movement.date}"))
                    else -> Unit
                }
            },
            sourceRows = rows,
        )
        proposal(ctx, change, "classificazione del movimento ${movementRef(movement.id)} come $label")
    }

    // ---- ignora_importato / riapri_importato ----

    private fun ignoreImportTool() = AiTool(
        "ignora_importato",
        "Segna come ignorato un movimento importato ancora da rivedere (non crea alcun movimento nel libro). Si può annullare.",
        schema(listOf("rif"), "rif" to prop("string", "Riferimento del movimento (rif di elenca_importati).")), writes = true,
    ) { ctx, args ->
        val movement = movementFor(ctx.archive, args.argText("rif"))
        if (movement.status != ExternalStatus.PENDING) bad("Il movimento è già stato revisionato.")
        val key = idempotencyKey(ctx.toolCallId, "ignora_importato", args)
        val summary = "Ignora il movimento importato ${describe(movement)}"
        val clock = ctx.clock
        val after = arrayOfNulls<ExternalMovement>(1)
        val change = AiChange(key, AiChangeKind.IGNORE_IMPORT, summary,
            apply = { a ->
                val next = a.withExternalDecision(movement.id, ExternalDecision.Ignore, clock().toString())
                after[0] = next.externalMovements.single { it.id == movement.id }
                next.audited(clock, AUDIT_APPLY, summary)
            },
            undo = { a ->
                val expected = after[0] ?: throw AiAlreadyModifiedException("L'importazione non risulta ignorata da questo assistente.")
                if (a.externalMovements.singleOrNull { it.id == movement.id } != expected) throw AiAlreadyModifiedException("Il movimento importato $GONE")
                a.withExternalReopened(movement.id, clock().toString()).audited(clock, AUDIT_UNDO, "Annullato: $summary")
            },
            diff = listOf(AiDiffLine("Movimento importato ${movementRef(movement.id)}", "da rivedere", "ignorato")),
            sourceRows = listOf(ReadTools.importedRow(movement)),
        )
        proposal(ctx, change, "movimento ${movementRef(movement.id)} ignorato")
    }

    private fun reopenImportTool() = AiTool(
        "riapri_importato",
        "Riporta da rivedere un movimento importato che era stato ignorato. Non riapre i movimenti già registrati nel libro: " +
            "per quelli l'utente usa l'app. Si può annullare.",
        schema(listOf("rif"), "rif" to prop("string", "Riferimento del movimento (rif di elenca_importati).")), writes = true,
    ) { ctx, args ->
        val movement = movementFor(ctx.archive, args.argText("rif"))
        if (movement.status == ExternalStatus.PENDING) bad("Il movimento è già da rivedere.")
        if (movement.status == ExternalStatus.APPLIED) bad("Il movimento è già registrato nel libro: l'assistente non rimuove registrazioni, l'utente può riaprirlo dall'app.")
        val key = idempotencyKey(ctx.toolCallId, "riapri_importato", args)
        val summary = "Riporta da rivedere il movimento importato ${describe(movement)}"
        val clock = ctx.clock
        val after = arrayOfNulls<ExternalMovement>(1)
        val change = AiChange(key, AiChangeKind.REOPEN_IMPORT, summary,
            apply = { a ->
                val current = a.externalMovements.singleOrNull { it.id == movement.id } ?: throw IllegalArgumentException("Il movimento importato non è più disponibile.")
                require(current.status == ExternalStatus.IGNORED) { "Il movimento non è più ignorato." }
                val next = a.withExternalReopened(movement.id, clock().toString())
                after[0] = next.externalMovements.single { it.id == movement.id }
                next.audited(clock, AUDIT_APPLY, summary)
            },
            undo = { a ->
                val expected = after[0] ?: throw AiAlreadyModifiedException("La riapertura non risulta fatta da questo assistente.")
                if (a.externalMovements.singleOrNull { it.id == movement.id } != expected) throw AiAlreadyModifiedException("Il movimento importato $GONE")
                a.withExternalDecision(movement.id, ExternalDecision.Ignore, clock().toString()).audited(clock, AUDIT_UNDO, "Annullato: $summary")
            },
            diff = listOf(AiDiffLine("Movimento importato ${movementRef(movement.id)}", "ignorato", "da rivedere")),
            sourceRows = listOf(ReadTools.importedRow(movement)),
        )
        proposal(ctx, change, "movimento ${movementRef(movement.id)} riportato da rivedere")
    }

    // ---- categorizza_movimenti ----

    private const val MAX_ASSIGNMENTS_PER_CALL = 500

    private fun setCategoryTool() = AiTool(
        "categorizza_movimenti",
        "Assegna una categoria di spesa a uno o più movimenti importati in un'unica operazione (anche centinaia: fino a $MAX_ASSIGNMENTS_PER_CALL per chiamata, " +
            "poi altre chiamate). Per trovarli usa elenca_importati con senza_categoria=true. Usa il nome di una categoria di elenca_categorie; " +
            "un nome equivalente (maiuscole, accenti, sinonimi semplici) viene ricondotto alla categoria esistente; altrimenti viene creata (tipo spesa) quando l'utente accetta. " +
            "Una categoria dice PER COSA sono stati spesi i soldi: un mezzo di pagamento (rate, PayPal, Klarna, carta, bancomat, bonifico, addebito, contanti, F24, giroconto) " +
            "non è mai una categoria e viene rifiutato. Le rate di un acquisto (es. Paga in 3 rate) non si categorizzano: prendono la categoria dell'acquisto. " +
            "L'utente vede UNA scheda con UN pulsante Accetta e può annullare tutto. Le categorie scelte dall'utente non vengono toccate.",
        schema(
            listOf("assegnazioni"),
            "assegnazioni" to JSONObject().put("type", "array").put("description", "Una voce per movimento.").put(
                "items",
                schema(
                    listOf("rif", "categoria"),
                    "rif" to prop("string", "Riferimento del movimento (rif di elenca_importati)."),
                    "categoria" to prop("string", "Nome della categoria, es. Alimentari (massimo 60 caratteri)."),
                ),
            ),
        ), writes = true,
    ) { ctx, args ->
        val list = args.optJSONArray("assegnazioni") ?: bad("Manca assegnazioni: un elenco di {rif, categoria}.")
        if (list.length() == 0) bad("L'elenco assegnazioni è vuoto.")
        if (list.length() > MAX_ASSIGNMENTS_PER_CALL) bad("Al massimo $MAX_ASSIGNMENTS_PER_CALL assegnazioni per chiamata: dividi in più chiamate.")
        val byRef = HashMap<String, ExternalMovement>(ctx.archive.externalMovements.size * 2)
        ctx.archive.externalMovements.forEach { byRef[movementRef(it.id)] = it; byRef[it.id] = it }
        val wanted = LinkedHashMap<String, String>() // movement id -> category name (last one wins)
        var unknown = 0
        val parts = ctx.archive.installmentPaymentIds()
        var rates = 0
        var methodEntries = 0
        val methods = LinkedHashSet<String>()
        for (i in 0 until list.length()) {
            val entry = list.optJSONObject(i) ?: bad("Voce ${i + 1}: atteso un oggetto {rif, categoria}.")
            val name = entry.argText("categoria")?.replace(Regex("\\s+"), " ") ?: bad("Voce ${i + 1}: manca la categoria.")
            if (name.length > 60) bad("Voce ${i + 1}: la categoria può avere al massimo 60 caratteri.")
            val movement = byRef[entry.argText("rif") ?: bad("Voce ${i + 1}: manca il rif.")]
            // A name given as an existing category id is judged by that category's name.
            val named = ctx.archive.planning.categories.firstOrNull { it.id == name }?.name ?: name
            when {
                movement == null -> unknown++
                movement.id in parts -> rates++
                CategoryGuard.isMethodLike(named) -> { methodEntries++; methods += named }
                else -> wanted[movement.id] = name
            }
        }
        if (wanted.isEmpty()) bad(when {
            methods.isNotEmpty() -> CategoryGuard.methodRefusal(methods)
            rates > 0 -> CategoryGuard.RATE_REFUSAL
            else -> "Nessun movimento trovato: usa i rif restituiti da elenca_importati."
        })
        val key = idempotencyKey(ctx.toolCallId, "categorizza_movimenti", args)
        val clock = ctx.clock
        val before = HashMap<String, Pair<String?, CategorySource?>>()
        val after = HashMap<String, String>()
        val created = LinkedHashSet<String>()
        val changed = IntArray(1)
        val summary = "$CATEGORY_BATCH_SUMMARY ${wanted.size} movimenti importati"
        val change = AiChange(key, AiChangeKind.SET_IMPORT_CATEGORY, summary,
            apply = { a ->
                var cur = a
                val ids = HashMap<String, String>()
                fun categoryId(name: String): String = ids.getOrPut(name.lowercase()) {
                    CategoryGuard.resolve(cur, name)
                        ?: cur.withNewCategory(CategoryTaxonomy.nameToCreate(name)).let { (next, id) -> cur = next; created += id; id }
                }
                val index = a.externalMovements.associateBy { it.id }
                before.clear(); after.clear(); created.clear()
                val assign = LinkedHashMap<String, String>()
                for ((movementId, name) in wanted) {
                    val m = index[movementId] ?: continue
                    if (m.categorySource == CategorySource.OVERRIDE) continue
                    val target = categoryId(name)
                    if (m.categoryId == target && m.categorySource == CategorySource.AGENT) continue
                    before[movementId] = m.categoryId to m.categorySource
                    after[movementId] = target
                    assign[movementId] = target
                }
                changed[0] = assign.size
                if (assign.isEmpty()) a else cur.withAgentCategories(assign).audited(clock, AUDIT_APPLY, summary)
            },
            undo = { a ->
                if (before.isEmpty()) throw AiAlreadyModifiedException("Le categorie non risultano assegnate da questo assistente.")
                val index = a.externalMovements.associateBy { it.id }
                if (before.keys.none { id -> index[id]?.let { it.categoryId == after[id] && it.categorySource == CategorySource.AGENT } == true })
                    throw AiAlreadyModifiedException("I movimenti $GONE")
                a.withoutAgentCategories(before.toMap(), after.toMap(), created.toSet()).audited(clock, AUDIT_UNDO, "Annullato: $summary")
            },
        )
        val dry = change.applyTo(ctx.archive) // dry run on the snapshot: fills before/after and the count, throws an Italian message if it cannot work
        if (changed[0] == 0) bad("Nessuna modifica: i movimenti hanno già quella categoria o l'utente l'ha scelta di persona.")
        val index = ctx.archive.externalMovements.associateBy { it.id }
        val diff = before.keys.map { id ->
            val m = index.getValue(id)
            val old = (m.categoryId ?: ctx.archive.planning.categorize(m).first)?.let(ctx.archive::categoryName) ?: "senza categoria"
            val target = after.getValue(id)
            AiDiffLine(describe(m), old, dry.categoryName(target), isNew = target in created)
        }
        val newNames = created.map(dry::categoryName)
        val skipped = listOfNotNull(
            if (unknown > 0) "$unknown rif non trovati e saltati" else null,
            if (rates > 0) "$rates rate saltate: prendono la categoria dell'acquisto" else null,
            if (methodEntries > 0) "$methodEntries assegnazioni rifiutate: ${CategoryGuard.methodRefusal(methods)}" else null,
        )
        val note = "${changed[0]} movimenti categorizzati" + (if (newNames.isNotEmpty()) ", nuove categorie: ${newNames.joinToString()}" else "") +
            (if (skipped.isNotEmpty()) " (${skipped.joinToString("; ")})" else "")
        AiToolResult.Proposal(change.copy(count = changed[0], diff = diff, newCategories = newNames), note)
    }
}
