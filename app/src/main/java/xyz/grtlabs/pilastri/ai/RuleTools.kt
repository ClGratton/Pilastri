package xyz.grtlabs.pilastri.ai

import org.json.JSONArray
import org.json.JSONObject
import xyz.grtlabs.pilastri.data.Origin
import xyz.grtlabs.pilastri.domain.rules.EditKind
import xyz.grtlabs.pilastri.domain.rules.EffectiveRules
import xyz.grtlabs.pilastri.domain.rules.Evidence
import xyz.grtlabs.pilastri.domain.rules.PersonInputs
import xyz.grtlabs.pilastri.domain.rules.PreparedEdit
import xyz.grtlabs.pilastri.domain.rules.RowKey
import xyz.grtlabs.pilastri.domain.rules.RuleConflictException
import xyz.grtlabs.pilastri.domain.rules.RuleEdits
import xyz.grtlabs.pilastri.domain.rules.RulesHistory
import xyz.grtlabs.pilastri.domain.rules.ShippedPack
import xyz.grtlabs.pilastri.domain.rules.ShippedRules
import java.time.LocalDate
import java.time.ZoneId

/**
 * The assistant's tools on the rule pack (Council 4 c). They only PROPOSE: a LAW value or table change, a step change and a
 * personal value are Tier 3 rows ([AiPolicy.tier3Kinds]) that the user confirms one by one in the Agente panel; identical
 * values from a newer source only refresh the dates (Tier 1); a SCENARIO value is Tier 1 and read only by Futuro.
 * The app fetches nothing: the source text comes from the assistant and the quote must be a verbatim substring of it.
 */
internal object RuleTools {
    private const val AUDIT_APPLY = "assistente · regole"
    private const val AUDIT_UNDO = "assistente · regole · annullo"
    private val zone: ZoneId = ZoneId.of("Europe/Rome")

    val reads: List<AiTool> by lazy { listOf(readRulesTool()) }
    val writes: List<AiTool> by lazy { listOf(lawValueTool(), lawStepsTool(), personalTool(), scenarioTool()) }

    private fun shipped(): ShippedPack = ShippedRules.current() ?: bad("Le regole non sono disponibili su questo dispositivo.")

    private fun today(ctx: AiToolContext): LocalDate = ctx.clock().atZone(zone).toLocalDate()

    private val evidenceProps: Array<Pair<String, JSONObject>> = arrayOf(
        "valida_dal" to prop("string", "Data da cui vale, AAAA-MM-GG (per un periodo d'imposta: il 1° gennaio)."),
        "fonte_url" to prop("string", "URL https della fonte. Confermabile solo da Gazzetta Ufficiale, Normattiva, Senato, Camera, Agenzia delle Entrate, INPS, COVIP, MEF/Finanze; altre fonti restano proposte non confermabili."),
        "citazione" to prop("string", "Citazione letterale, al massimo 200 caratteri, presa da testo_fonte."),
        "testo_fonte" to prop("string", "Il testo della fonte da cui è presa la citazione (al massimo 20.000 caratteri), copiato senza modifiche."),
        "verificato_il" to prop("string", "Data in cui hai letto la fonte, AAAA-MM-GG."),
        "etichetta" to prop("string", "Nome breve della riga per la scheda (al massimo 60 caratteri), facoltativo."),
    )
    private val evidenceRequired = listOf("valida_dal", "fonte_url", "citazione", "testo_fonte", "verificato_il")

    // ---- leggi_regole ----

    private fun readRulesTool() = AiTool(
        "leggi_regole",
        "Legge le righe del pacchetto di regole in vigore (valori, validità, stato, fonte, citazione) e i parametri personali previsti. " +
            "Con id restituisce anche i passi di una regola. Serve prima di proporre una modifica.",
        schema(emptyList(),
            "id" to prop("string", "Id esatto di una riga."),
            "prefisso" to prop("string", "Prefisso degli id (es. irpef., pc.deduction.)."),
            *pagingProps,
        ), writes = false,
    ) { ctx, args ->
        val eff = EffectiveRules.build(shipped(), ctx.archive.rulesData)
        val id = args.argText("id")
        val prefix = args.argText("prefisso")
        val limit = (args.argInt("limite") ?: 20).coerceIn(1, 50)
        val offset = (args.argInt("offset") ?: 0).coerceAtLeast(0)
        val keys = eff.pack.rows.map { RowKey(it.id, it.validFrom?.toString()) }
            .filter { k -> (id == null || k.id == id) && (prefix == null || k.id.startsWith(prefix)) }
        val rows = JSONArray()
        keys.drop(offset).take(limit).forEach { k ->
            val r = eff.row(k) ?: return@forEach
            rows.put(JSONObject().put("id", k.id).put("unita", r.optString("unit")).put("forma", r.optString("shape", "SCALAR"))
                .put("valore", r.opt("value") ?: JSONObject.NULL).put("valida_dal", r.opt("validFrom") ?: JSONObject.NULL)
                .put("valida_al", r.opt("validTo") ?: JSONObject.NULL).put("stato", r.optString("status"))
                .put("verificata_il", r.optString("checkedOn")).put("fonte_url", r.opt("sourceUrl") ?: JSONObject.NULL)
                .put("citazione", r.optString("quote")).put("slot_personale", r.opt("personalSlot") ?: JSONObject.NULL)
                .put("modificata_da_te", k in eff.overridden)
                .apply { if (r.has("steps") && !r.isNull("steps")) { if (id != null) put("passi", r.getJSONArray("steps")) else put("passi", r.getJSONArray("steps").length()) } })
        }
        // Slot names and whether a value exists; the person's values themselves are not sent.
        val slots = JSONArray().apply {
            eff.pack.declaredSlots.sorted().forEach { s -> put(JSONObject().put("parametro", s).put("impostato", s in eff.personal).put("scenario", s in eff.scenario)) }
        }
        val json = JSONObject().put("righe", rows).put("totale", keys.size).put("offset", offset).put("parametri_personali", slots)
        AiToolResult.Read(json.toString(), rows.length())
    }

    // ---- proponi_regola ----

    private fun lawValueTool() = AiTool(
        "proponi_regola",
        "Propone un nuovo valore (o una nuova tabella) per una riga di legge, con fonte, citazione letterale e data di validità. " +
            "Non applica nulla: l'utente conferma la riga nel pannello. Una riga nuova o un tipo di passo nuovo non si propongono.",
        schema(listOf("riga_id", "valore") + evidenceRequired,
            "riga_id" to prop("string", "Id della riga (da leggi_regole)."),
            "valore" to prop("string", "Valore nel formato della riga: EUR in euro con il punto (5300.00), RATIO decimale (0.23); per TABELLA o SCAGLIONI un oggetto JSON come in leggi_regole."),
            *evidenceProps,
        ), writes = true,
    ) { ctx, args ->
        val rowId = args.argText("riga_id") ?: bad("Manca riga_id.")
        val value: Any = when (val v = args.opt("valore")) {
            null, JSONObject.NULL -> bad("Manca valore.")
            is JSONObject -> v
            is Number -> java.math.BigDecimal(v.toString()).toPlainString()
            else -> v.toString()
        }
        val ev = evidence(args)
        val edit = RuleEdits.lawValue(shipped(), ctx.archive, rowId, value, ev, label(ctx, args), today(ctx), ctx.toolCallId,
            PersonInputs.from(ctx.archive, ev.validFrom.year))
        proposal(ctx, "proponi_regola", args, edit)
    }

    // ---- proponi_passi_regola ----

    private fun lawStepsTool() = AiTool(
        "proponi_passi_regola",
        "Propone la nuova lista dei passi di una regola (ordine, condizioni when, valori, passi aggiunti dei tipi esistenti " +
            "BRACKETS, PIECEWISE, RATE, LOOKUP, SUM), con fonte e citazione. Non applica nulla: l'utente conferma la riga.",
        schema(listOf("riga_id", "passi") + evidenceRequired,
            "riga_id" to prop("string", "Id della regola (da leggi_regole con id)."),
            "passi" to JSONObject().put("type", "array").put("description", "Tutti i passi, nel formato di leggi_regole.").put("items", JSONObject().put("type", "object")),
            *evidenceProps,
        ), writes = true,
    ) { ctx, args ->
        val rowId = args.argText("riga_id") ?: bad("Manca riga_id.")
        val steps = when (val v = args.opt("passi")) {
            is JSONArray -> v
            is String -> try { JSONArray(v) } catch (_: Exception) { bad("passi: serve un elenco JSON.") }
            else -> bad("Manca passi.")
        }
        val ev = evidence(args)
        val edit = RuleEdits.lawSteps(shipped(), ctx.archive, rowId, steps, ev, label(ctx, args), today(ctx), ctx.toolCallId,
            PersonInputs.from(ctx.archive, ev.validFrom.year))
        proposal(ctx, "proponi_passi_regola", args, edit)
    }

    // ---- proponi_parametro_personale ----

    private fun personalTool() = AiTool(
        "proponi_parametro_personale",
        "Propone un valore personale per un parametro previsto dalle regole (aliquota del fondo, quota TFR, gestione, domicilio, " +
            "aliquota trattenuta, attestazione del regime), letto da un documento della persona o dichiarato da lei. " +
            "Non sostituisce mai una riga di legge. L'utente conferma la riga nel pannello.",
        schema(listOf("parametro", "valore", "origine"),
            "parametro" to prop("string", "Nome del parametro (parametri_personali di leggi_regole)."),
            "valore" to prop("string", "Valore nel formato del parametro."),
            "origine" to prop("string", "DOCUMENTO se letto da un documento della persona, DICHIARATO se lo dice la persona.", listOf("DOCUMENTO", "DICHIARATO")),
            "ambito" to prop("string", "Id di un fondo o di una posizione, se il valore riguarda solo quello."),
            "documento_id" to prop("string", "Con DOCUMENTO: id del documento."),
            "campo" to prop("string", "Con DOCUMENTO: etichetta del campo nel documento."),
            "hash_documento" to prop("string", "Con DOCUMENTO: SHA-256 del documento."),
            "data_documento" to prop("string", "Con DOCUMENTO: data del documento, AAAA-MM-GG."),
        ), writes = true,
    ) { ctx, args ->
        val slot = args.argText("parametro") ?: bad("Manca parametro.")
        val raw = args.argText("valore") ?: bad("Manca valore.")
        val origin = when (args.argText("origine")?.uppercase()) {
            "DOCUMENTO" -> Origin.DOCUMENTO
            "DICHIARATO" -> Origin.DICHIARATO
            else -> bad("origine: DOCUMENTO o DICHIARATO.")
        }
        listOfNotNull(raw, args.argText("campo"), args.argText("documento_id")).forEach { personalDataCheck(it, "Il valore") }
        val today = today(ctx)
        val edit = RuleEdits.personal(shipped(), ctx.archive, slot, args.argText("ambito"), raw, origin,
            args.argText("documento_id"), args.argText("campo"), args.argText("hash_documento")?.lowercase(), args.argDate("data_documento"),
            today, ctx.clock(), ctx.toolCallId, PersonInputs.from(ctx.archive, today.year))
        proposal(ctx, "proponi_parametro_personale", args, edit)
    }

    // ---- imposta_scenario ----

    private fun scenarioTool() = AiTool(
        "imposta_scenario",
        "Imposta un valore di scenario per Futuro su un parametro previsto dalle regole. Non tocca i conti reali; si annulla dalla scheda.",
        schema(listOf("parametro", "valore"),
            "parametro" to prop("string", "Nome del parametro (parametri_personali di leggi_regole)."),
            "valore" to prop("string", "Valore nel formato del parametro."),
        ), writes = true,
    ) { ctx, args ->
        val slot = args.argText("parametro") ?: bad("Manca parametro.")
        val raw = args.argText("valore") ?: bad("Manca valore.")
        personalDataCheck(raw, "Il valore")
        val edit = RuleEdits.scenario(shipped(), ctx.archive, slot, raw, ctx.clock(), ctx.toolCallId)
        val param = edit.param!!
        val key = idempotencyKey(ctx.toolCallId, "imposta_scenario", args)
        val summary = "Scenario · $slot = ${edit.view.after}"
        val clock = ctx.clock
        val change = AiChange(key, AiChangeKind.SET_SCENARIO_VALUE, summary,
            apply = { a -> RulesHistory.addScenario(a, param).let { if (it === a) a else it.audited(clock, AUDIT_APPLY, summary) } },
            undo = { a ->
                try { RulesHistory.removeScenario(a, param.id) } catch (e: RuleConflictException) { throw AiAlreadyModifiedException(e.message ?: "Scenario già modificato.") }
                    .audited(clock, AUDIT_UNDO, "Annullato: $summary")
            },
            diff = listOf(AiDiffLine("Futuro · $slot", edit.view.before, edit.view.after)),
        )
        change.applyTo(ctx.archive)
        AiToolResult.Proposal(change, "scenario $slot per Futuro")
    }

    // ---- shared ----

    private fun evidence(args: JSONObject): Evidence {
        val url = args.argText("fonte_url") ?: bad("Manca fonte_url.")
        val quote = args.optString("citazione", "").takeIf { args.has("citazione") && !args.isNull("citazione") } ?: bad("Manca citazione.")
        val text = args.optString("testo_fonte", "").takeIf { args.has("testo_fonte") && !args.isNull("testo_fonte") } ?: bad("Manca testo_fonte.")
        personalDataCheck(text, "Il testo della fonte")
        return Evidence(url, quote, text, args.argDate("valida_dal") ?: bad("Manca valida_dal."), args.argDate("verificato_il") ?: bad("Manca verificato_il."))
    }

    /** Public law text never holds an IBAN, a codice fiscale or a card number: such text is not stored. */
    private fun personalDataCheck(text: String, what: String) {
        if (AiPolicy.redact(text, longDigitRuns = false) != text) bad("$what contiene dati personali (IBAN, codice fiscale o carta): non lo salvo.")
    }

    private fun label(ctx: AiToolContext, args: JSONObject): String? = args.argText("etichetta")?.let { ctx.text(it) }?.take(60)

    private fun proposal(ctx: AiToolContext, tool: String, args: JSONObject, edit: PreparedEdit): AiToolResult {
        val shipped = shipped()
        val key = idempotencyKey(ctx.toolCallId, tool, args)
        val versionId = "pv:$key"
        val clock = ctx.clock
        val kind = when (edit.kind) {
            EditKind.LAW_VALUE -> AiChangeKind.RULE_LAW_CHANGE
            EditKind.LAW_STEPS -> AiChangeKind.RULE_STEP_CHANGE
            EditKind.PERSONAL -> AiChangeKind.RULE_PERSONAL_CHANGE
            EditKind.REFRESH_LAW, EditKind.REFRESH_PERSONAL -> AiChangeKind.RULE_REFRESH
            EditKind.SCENARIO -> error("scenario")
        }
        val summary = when (edit.kind) {
            EditKind.REFRESH_LAW, EditKind.REFRESH_PERSONAL -> "Verifica aggiornata · ${edit.target}"
            EditKind.PERSONAL -> "Parametro · ${edit.view.rowId}"
            else -> "Regola · ${edit.target}"
        }
        val view = edit.view
        val change = AiChange(key, kind, summary,
            apply = { a -> RulesHistory.confirm(a, edit, shipped, clock(), versionId).let { if (it === a) a else it.audited(clock, AUDIT_APPLY, summary) } },
            undo = { a ->
                try { RulesHistory.undo(a, versionId, shipped, clock()) } catch (e: RuleConflictException) { throw AiAlreadyModifiedException(e.message ?: "Regola già modificata.") }
                    .audited(clock, AUDIT_UNDO, "Annullato: $summary")
            },
            diff = listOf(AiDiffLine(view.label ?: view.rowId, view.before, view.after)) + view.outputs.map { AiDiffLine("${it.card} · ${it.label}", it.before, it.after) },
            rule = if (edit.kind.tier3) view else null,
        )
        // Dry run on the snapshot: a change that cannot be written is refused now. A non-primary proposal is never written.
        if (edit.confirmable) change.applyTo(ctx.archive)
        val note = when {
            !edit.confirmable -> "proposta su ${edit.target} da fonte non primaria: l'utente può solo rifiutarla"
            edit.kind.tier3 -> "proposta su ${view.rowId} (${view.before} → ${view.after}), da confermare dall'utente riga per riga"
            else -> "verifica aggiornata per ${edit.target}, valori invariati"
        }
        return AiToolResult.Proposal(change, note)
    }
}

/**
 * Board G3-5a/5b, second chip: the field a Tier 3 row still asks the person to check ("fonte: da verificare"), or null.
 * A LAW row proposed from a source that is not a primary one is written with status UNVERIFIED ([RuleEdits]); such a row is
 * also not confirmable. The view carries no [xyz.grtlabs.pilastri.data.PackStatus] of its own, so this reads the same fact
 * from `layer` and `verified`. A personal value is a declaration or a document, never a pack status: no chip.
 */
internal fun ruleFieldToVerify(view: xyz.grtlabs.pilastri.domain.rules.RuleRowView): String? =
    if (view.layer == xyz.grtlabs.pilastri.data.ChangeLayer.LAW && !view.verified) RuleFieldToVerify + ": da verificare" else null

internal const val RuleFieldToVerify = "fonte"
