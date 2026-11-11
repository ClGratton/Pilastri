package xyz.grtlabs.pilastri.ai

import xyz.grtlabs.pilastri.data.Archive
import xyz.grtlabs.pilastri.data.CategoryRepair
import xyz.grtlabs.pilastri.data.CategoryTaxonomy
import xyz.grtlabs.pilastri.data.PaymentMethodNames
import java.time.Instant

/**
 * The assistant's side of the 2026-11-01 category rule: a category says what the money was spent on, never how it was paid,
 * and an installment part is never categorized from its own text. Used by `categorizza_movimenti`, the automatic run and the
 * plan category tool.
 */
internal object CategoryGuard {
    const val RATE_REFUSAL = "Sono rate di un acquisto: prendono la categoria dell'acquisto e non si categorizzano dal loro testo."

    fun isMethodLike(name: String): Boolean = PaymentMethodNames.isMethodLike(name)

    fun methodRefusal(names: Collection<String>): String =
        names.take(5).joinToString { "«$it»" } + (if (names.size == 1) " è un mezzo di pagamento" else " sono mezzi di pagamento") +
            ", non una categoria. Una categoria dice per cosa sono stati spesi i soldi (es. Alimentari, Trasporti, Casa)."

    /**
     * The id of the category [name] stands for in [archive]: an id given as is, the same name or a simple synonym of a spending
     * category ([CategoryTaxonomy.match]), or the same name of any kind; never a method-like category. Null: create one.
     */
    fun resolve(archive: Archive, name: String): String? {
        val all = archive.planning.categories.filterNot { isMethodLike(it.name) }
        all.firstOrNull { it.id == name }?.let { return it.id }
        CategoryTaxonomy.match(name, all)?.let { return it.id }
        val create = CategoryTaxonomy.nameToCreate(name)
        return all.firstOrNull { it.name.equals(name, ignoreCase = true) || it.name.equals(create, ignoreCase = true) }?.id
    }
}

/**
 * The repair of [CategoryRepair] as an assistant change: one quiet line in the Agente thread with one "Annulla", like the
 * automatic categorization. [apply] recomputes on the latest archive; [undo] reverses exactly what that apply did.
 */
internal object CategoryRepairChange {
    /** Null when the snapshot has nothing to repair. */
    fun prepare(snapshot: Archive, clock: () -> Instant = { Instant.now() }): AiChange? {
        val dry = CategoryRepair.run(snapshot, clock().toString()) ?: return null
        var record: CategoryRepair.Record? = null
        val names = dry.record.removedCategories.map { it.name }
        val n = dry.record.count
        val spese = if (n == 1) "1 spesa da riclassificare" else "$n spese da riclassificare"
        val summary = when (names.size) {
            0 -> "$spese: erano in categorie non valide"
            1 -> "«${names.single()}» non è una categoria: $spese"
            else -> "${names.joinToString { "«$it»" }} non sono categorie: $spese"
        }
        return AiChange(
            id = "category-repair-" + clock().toEpochMilli(),
            kind = AiChangeKind.SET_IMPORT_CATEGORY,
            summary = summary,
            apply = { a -> CategoryRepair.run(a, clock().toString())?.let { record = it.record; it.archive } ?: a },
            undo = { a ->
                val done = record ?: throw AiAlreadyModifiedException("La correzione non risulta applicata.")
                try { CategoryRepair.undo(a, done, clock().toString()) } catch (e: IllegalStateException) { throw AiAlreadyModifiedException(e.message ?: "Già modificato.") }
            },
            count = dry.record.count,
        )
    }
}
