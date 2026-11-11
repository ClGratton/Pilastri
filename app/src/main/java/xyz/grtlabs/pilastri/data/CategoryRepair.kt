package xyz.grtlabs.pilastri.data

/**
 * Repairs the payment-method "categories" the assistant created before the 2026-11-01 rule ("Pagamenti a rate", "PayPal"…).
 * Pure; idempotent; no schema change: the record needed for the undo lives with the in-app change (as for every assistant
 * change), the audit keeps the history.
 *
 * Agent-created, decided from the data (categories carry no origin field):
 *  - the category is a spending category whose name [PaymentMethodNames.isMethodLike];
 *  - the assistant used it: at least one movement holds it with source AGENT, or an AGENT-origin rule targets it;
 *  - the audit shows the assistant's category tool ran: an "assistente" entry whose summary starts with "Categorizza" (the
 *    summary of `categorizza_movimenti`, the only path that creates categories on the assistant's behalf).
 * What changes:
 *  - movements holding such a category with source AGENT go back to uncategorized (an installment part then reads
 *    "rata · acquisto da classificare" through [installmentPaymentStates]); AGENT-origin rules targeting it are removed;
 *  - installment payments the assistant categorized from their own text (source AGENT, any category) go back to uncategorized:
 *    they take their purchase's category;
 *  - the category itself is removed only when nothing the person set refers to it: no movement left on it (their own choice),
 *    no rule of theirs, provider mapping, budget, plan, sub-category, parent or "essenziale" setting.
 * Never touched: the person's categories, their assignments (OVERRIDE, rules, provider mappings) and every other record.
 * Not run again once the person undid it (audit [AUDIT_UNDO]).
 */
object CategoryRepair {
    const val AUDIT_APPLY = "correzione categorie"
    const val AUDIT_UNDO = "correzione categorie · annullo"
    private const val AGENT_AUDIT_KIND = "assistente"
    private const val AGENT_CATEGORY_SUMMARY = "Categorizza"

    /** What one run changed: enough to undo it exactly. */
    data class Record(
        /** Movement id to its category and source before the repair. */
        val movements: Map<String, Pair<String?, CategorySource?>>,
        val removedCategories: List<Category>,
        val removedRules: List<CategoryRule>,
        /** Movements reset from a payment-method category, and installment parts reset from another assistant category. */
        val fromMethodCategories: Int,
        val fromInstallmentParts: Int,
    ) {
        val count: Int get() = movements.size
    }

    data class Result(val archive: Archive, val record: Record)

    /** The method-like categories the assistant created (criterion above). */
    fun agentMethodCategories(a: Archive): Set<String> {
        if (a.audit.none { it.kind == AGENT_AUDIT_KIND && it.previous.startsWith(AGENT_CATEGORY_SUMMARY) }) return emptySet()
        val used = a.externalMovements.filter { it.categorySource == CategorySource.AGENT }.mapNotNull { it.categoryId }.toSet() +
            a.planning.rules.filter { it.origin == RuleOrigin.AGENT }.map { it.categoryId }
        return a.planning.categories.filter { it.kind == CategoryKind.SPEND && it.id in used && PaymentMethodNames.isMethodLike(it.name) }.map { it.id }.toSet()
    }

    /** The repaired archive and its record, or null when there is nothing to repair (or the person undid it before). */
    fun run(a: Archive, at: String): Result? {
        if (a.audit.any { it.kind == AUDIT_UNDO }) return null
        val bogus = agentMethodCategories(a)
        val parts = a.installmentPaymentIds()
        val previous = LinkedHashMap<String, Pair<String?, CategorySource?>>()
        var fromMethod = 0
        var fromParts = 0
        val moved = a.externalMovements.map { m ->
            if (m.categorySource != CategorySource.AGENT || m.categoryId == null) return@map m
            when {
                m.categoryId in bogus -> fromMethod++
                m.id in parts -> fromParts++
                else -> return@map m
            }
            previous[m.id] = m.categoryId to m.categorySource
            m.copy(categoryId = null, categorySource = null)
        }
        val rulesOut = a.planning.rules.filter { it.origin == RuleOrigin.AGENT && it.categoryId in bogus }
        val userRefs = moved.mapNotNull { it.categoryId }.toSet() +
            a.planning.rules.filter { it !in rulesOut }.map { it.categoryId } + a.planning.providerCategories.map { it.categoryId } +
            a.planning.budgets.map { it.categoryId } + a.planning.categories.mapNotNull { it.parentId } + a.installments.mapNotNull { it.categoryId }
        val removed = a.planning.categories.filter { c ->
            c.id in bogus && c.id !in userRefs && c.parentId == null && c.essential == Essential.UNSET
        }
        if (previous.isEmpty() && removed.isEmpty() && rulesOut.isEmpty()) return null
        val removedIds = removed.map { it.id }.toSet()
        val names = removed.joinToString { it.name }
        val summary = "Categorie che erano mezzi di pagamento: ${previous.size} movimenti tornati senza categoria" +
            (if (removed.isNotEmpty()) ", ${removed.size} categorie rimosse ($names)" else "")
        val next = a.copy(
            externalMovements = moved,
            planning = a.planning.copy(categories = a.planning.categories.filterNot { it.id in removedIds }, rules = a.planning.rules - rulesOut.toSet()),
            audit = a.audit + AuditEntry(at.take(50), AUDIT_APPLY, summary.take(2000)),
        )
        ArchiveCodec.encode(next)
        return Result(next, Record(previous, removed, rulesOut, fromMethod, fromParts))
    }

    /**
     * Reverses [run]: re-adds the removed categories and rules (unless the person has since made one with the same id or name)
     * and gives each movement back its earlier category while it is still uncategorized and that category exists. Throws
     * [IllegalStateException] when nothing can be restored any more.
     */
    fun undo(a: Archive, record: Record, at: String): Archive {
        val categories = a.planning.categories.toMutableList()
        record.removedCategories.forEach { c ->
            if (categories.none { it.id == c.id || it.name.equals(c.name, ignoreCase = true) }) categories += c
        }
        val ids = categories.map { it.id }.toSet()
        val rules = a.planning.rules + record.removedRules.filter { r -> r.categoryId in ids && a.planning.rules.none { it.id == r.id } }
        var restored = 0
        val moved = a.externalMovements.map { m ->
            val old = record.movements[m.id] ?: return@map m
            if (m.categoryId != null || m.categorySource != null || old.first !in ids) return@map m
            restored++
            m.copy(categoryId = old.first, categorySource = old.second)
        }
        check(restored > 0 || record.movements.isEmpty()) { "Le categorie sono già state modificate: l'annullamento è stato rifiutato." }
        val next = a.copy(
            externalMovements = moved,
            planning = a.planning.copy(categories = categories, rules = rules),
            audit = a.audit + AuditEntry(at.take(50), AUDIT_UNDO, "Annullato: correzione di ${record.count} movimenti"),
        )
        ArchiveCodec.encode(next)
        return next
    }
}
