package xyz.grtlabs.pilastri.data

import java.text.Normalizer

/*
 * Spending categories (2026-11-01 "Pagamenti a rate" fix): a category describes what the money was spent ON. How it was paid
 * (in installments, through PayPal, by card, by transfer, in cash) is never a category. The default set is generic, for any
 * person, and editable in Impostazioni › Categorie.
 */

/** Lower case, accents removed, every run of non-letters/digits a single space. */
internal fun normalizeName(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        .replace(Regex("[^a-z0-9]+"), " ").trim()

/**
 * Recognizes names that only state a payment method or instrument, never what was bought. Whole-token matching on the
 * normalized name: a name is method-like when it holds at least one method token and every other token is either a method
 * token, a method qualifier (credito, debito, prepagata) or a generic filler word (pagamenti, spese, di, a, in, numbers…).
 * So "Pagamenti a rate", "Paga in 3 rate", "PayPal", "Carta di credito", "Addebiti SDD", "F24" are method-like, while
 * "Cartoleria", "Carburante", "Carta e cancelleria", "Rata mutuo", "Tasse F24", "Ricariche telefoniche" are not.
 */
object PaymentMethodNames {
    /** Tokens that name a payment method or instrument (normalized, after [JOINED] phrases are glued). */
    val METHOD_TOKENS: Set<String> = setOf(
        // installments and pay-later plans
        "rata", "rate", "rateale", "rateali", "rateizzazione", "rateizzazioni", "rateizzato", "rateizzata", "rateizzati", "rateizzate",
        "paga", "paylater",
        // wallets and pay-later providers
        "paypal", "klarna", "scalapay", "satispay", "afterpay", "clearpay", "pagolight", "heylight", "applepay", "googlepay", "samsungpay",
        // cards and card networks
        "carta", "carte", "bancomat", "pagobancomat", "pos", "contactless", "postepay", "visa", "mastercard", "maestro", "amex", "nexi",
        // transfers and direct debits
        "bonifico", "bonifici", "sepa", "addebito", "addebiti", "sdd", "rid", "domiciliazione", "domiciliazioni", "mav", "rav", "pagopa", "cbill",
        "giroconto", "giroconti", "trasferimento", "trasferimenti", "ricarica", "ricariche", "assegno", "assegni",
        // cash and the tax payment form used as a method
        "contanti", "contante", "cash", "prelievo", "prelievi", "f24",
    )

    /** Words that qualify a method ("carta di credito") but never make a name method-like on their own ("Debiti"). */
    private val QUALIFIERS = setOf("credito", "debito", "prepagata", "prepagate", "prepagato", "prepagati", "virtuale", "virtuali")

    /** Generic words that say nothing about the purpose. */
    private val FILLER = setOf(
        "a", "ad", "al", "alla", "alle", "con", "da", "dal", "dalla", "di", "del", "della", "dei", "delle", "e", "ed", "in", "per", "su", "tramite", "via",
        "il", "la", "le", "lo", "i", "gli", "un", "una", "d", "l", "o",
        "pagamento", "pagamenti", "pagato", "pagata", "pagati", "pagate", "spese", "spesa", "acquisto", "acquisti", "uscita", "uscite",
        "entrata", "entrate", "online", "diretto", "diretta", "diretti", "dirette", "automatico", "automatica", "automatici", "automatiche",
        "mensile", "mensili", "ricevuto", "ricevuti", "inviato", "inviati", "dopo", "operazione", "operazioni", "movimento", "movimenti",
        "vari", "varie", "altri", "altre",
    )

    /** Multi-word methods glued into one token before matching. */
    private val JOINED = listOf(
        "pay pal" to "paypal", "apple pay" to "applepay", "google pay" to "googlepay", "samsung pay" to "samsungpay",
        "pago bancomat" to "pagobancomat", "poste pay" to "postepay", "pay later" to "paylater", "pago pa" to "pagopa",
        "american express" to "amex",
    )

    fun isMethodLike(name: String): Boolean {
        var text = " " + normalizeName(name) + " "
        JOINED.forEach { (from, to) -> text = text.replace(" $from ", " $to ") }
        val tokens = text.trim().split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        var method = false
        for (t in tokens) {
            when {
                t in METHOD_TOKENS -> method = true
                t in QUALIFIERS || t in FILLER || t.all(Char::isDigit) -> Unit
                else -> return false
            }
        }
        return method
    }
}

/**
 * The generic default taxonomy and how a proposed name is mapped onto the categories a person already has.
 * Mapping order: same normalized name (accents, case, punctuation and a final vowel ignored) → a simple synonym of the same
 * default category → otherwise a new category, created only for a real spending purpose (never a [PaymentMethodNames] name).
 */
object CategoryTaxonomy {
    /** Generic spending categories for any person; no person-, employer- or provider-specific names. */
    val DEFAULT_SPEND: List<String> = listOf(
        "Alimentari", "Ristoranti e bar", "Casa", "Bollette", "Trasporti", "Salute", "Cura personale", "Abbigliamento",
        "Acquisti", "Svago", "Viaggi", "Abbonamenti", "Istruzione", "Regali e donazioni", "Tasse",
    )

    /** Audit kind written once when the defaults are seeded: an archive the person later emptied is not seeded again. */
    const val SEEDED_AUDIT_KIND = "categorie predefinite"

    private val SYNONYMS: Map<String, String> = buildMap {
        fun syn(target: String, vararg names: String) = names.forEach { put(key(it), target) }
        syn("Alimentari", "spesa alimentare", "supermercato", "supermercati", "alimentazione", "cibo", "generi alimentari", "spesa")
        syn("Ristoranti e bar", "ristoranti", "ristorante", "bar", "ristorazione", "bar e ristoranti", "cene fuori", "pranzi fuori", "ristoranti e caffe", "caffe")
        syn("Casa", "abitazione", "affitto", "casa e famiglia", "arredamento", "manutenzione casa")
        syn("Bollette", "utenze", "bollette e utenze", "luce e gas", "energia", "luce", "gas", "acqua", "telefono e internet", "internet")
        syn("Trasporti", "trasporto", "mobilita", "carburante", "benzina", "auto", "auto e trasporti", "mezzi pubblici", "treni", "taxi", "parcheggi", "pedaggi")
        syn("Salute", "sanita", "farmacia", "farmaci", "medico", "visite mediche", "salute e benessere")
        syn("Cura personale", "bellezza", "parrucchiere", "benessere", "igiene personale")
        syn("Abbigliamento", "vestiti", "abbigliamento e scarpe", "moda", "scarpe")
        syn("Acquisti", "shopping", "acquisti online", "elettronica", "articoli per la casa")
        syn("Svago", "tempo libero", "intrattenimento", "divertimento", "hobby", "cultura", "sport", "svago e cultura")
        syn("Viaggi", "vacanze", "viaggi e vacanze", "hotel", "alloggi")
        syn("Abbonamenti", "streaming", "servizi in abbonamento", "abbonamenti digitali")
        syn("Istruzione", "scuola", "formazione", "corsi", "libri e scuola", "universita")
        syn("Regali e donazioni", "regali", "donazioni", "beneficenza")
        syn("Tasse", "imposte", "tributi", "tasse e imposte")
    }

    /** Normalized words, each without a final vowel (Ristorante = Ristoranti, Bolletta = Bollette). */
    fun key(name: String): String = normalizeName(name).split(' ').filter { it.isNotEmpty() }
        .joinToString(" ") { w -> if (w.length > 3 && w.last() in "aeiou") w.dropLast(1) else w }

    /** The default category a name stands for, or null. */
    fun canonical(name: String): String? {
        val k = key(name)
        return DEFAULT_SPEND.firstOrNull { key(it) == k } ?: SYNONYMS[k]
    }

    /** The existing spending category [name] means: same key first, then the same default category through a synonym. */
    fun match(name: String, categories: List<Category>): Category? {
        val spend = categories.filter { it.kind == CategoryKind.SPEND }
        val k = key(name)
        spend.firstOrNull { key(it.name) == k }?.let { return it }
        val target = canonical(name) ?: return null
        return spend.firstOrNull { canonical(it.name) == target }
    }

    /** The name to create when nothing matches: the default category's own name when it is a synonym of one. */
    fun nameToCreate(name: String): String = canonical(name) ?: name.trim().replace(Regex("\\s+"), " ")

    /** The names the model chooses from: the person's spending categories (never method-like ones), or the defaults when none. */
    fun namesFor(archive: Archive): List<String> {
        val own = archive.planning.categories.filter { it.kind == CategoryKind.SPEND && !PaymentMethodNames.isMethodLike(it.name) }.map { it.name }
        return own.ifEmpty { DEFAULT_SPEND }
    }
}

/**
 * Seeds [CategoryTaxonomy.DEFAULT_SPEND] when the archive has no spending category and was never seeded before (the audit
 * marker [CategoryTaxonomy.SEEDED_AUDIT_KIND]). Idempotent; it never renames, duplicates or deletes a category: a default whose
 * name (or id) is already taken by another kind is skipped. Returns the same instance when nothing changes.
 */
fun Archive.withDefaultSpendCategories(at: String): Archive {
    if (planning.categories.any { it.kind == CategoryKind.SPEND }) return this
    if (audit.any { it.kind == CategoryTaxonomy.SEEDED_AUDIT_KIND }) return this
    val added = ArrayList<Category>()
    val taken = planning.categories.map { CategoryTaxonomy.key(it.name) }.toMutableSet()
    val ids = planning.categories.map { it.id }.toMutableSet()
    for (name in CategoryTaxonomy.DEFAULT_SPEND) {
        if (!taken.add(CategoryTaxonomy.key(name))) continue
        val base = "c:" + normalizeName(name).replace(' ', '-').take(40)
        var id = base; var n = 2
        while (id in ids) id = base + "-" + n++
        ids += id
        added += Category(id, name, CategoryKind.SPEND)
    }
    if (added.isEmpty()) return this
    return copy(
        planning = planning.copy(categories = planning.categories + added),
        audit = audit + AuditEntry(at.take(50), CategoryTaxonomy.SEEDED_AUDIT_KIND, "${added.size} categorie di spesa predefinite"),
    ).also { ArchiveCodec.encode(it) }
}
