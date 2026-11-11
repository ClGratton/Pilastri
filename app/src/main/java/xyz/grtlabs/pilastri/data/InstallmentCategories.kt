package xyz.grtlabs.pilastri.data

/*
 * Installment payments and categories (2026-11-01 "Pagamenti a rate" fix). A movement that pays a part of a plan says how the
 * purchase is being paid, not what was bought: its payee is often the plan type ("Paga in 3 rate"). Such a part takes the
 * category of the plan's purchase. While that is unknown it stays uncategorized and reads "rata · acquisto da classificare".
 * The assistant never categorizes a part from its own text. Read model only: nothing here writes the archive.
 */

/** What the Spese screens show for one installment payment whose category comes from its plan. */
sealed interface InstallmentPaymentState {
    /** The plan the part belongs to; null when only the payee says it is a plan part (no plan matched yet). */
    val planId: String?

    /** The purchase has a category: the part counts there. */
    data class Inherited(override val planId: String, val categoryId: String, val from: InheritedFrom) : InstallmentPaymentState

    /** The purchase is not classified yet: the part stays uncategorized and shows [label]. */
    data class ToClassify(override val planId: String?) : InstallmentPaymentState {
        val label: String get() = LABEL
    }

    companion object {
        /** The label of a part whose purchase has no category yet. */
        const val LABEL = "rata · acquisto da classificare"
    }
}

/** Where an inherited category comes from, in order of precedence. */
enum class InheritedFrom { PLAN, PURCHASE, MERCHANT_RULE, MERCHANT }

private val planTypePayee = Regex("^(paypal )?(paga (in \\d+( rate| giorni)?|a rate|dopo|piu tardi)|pagamento a rate|pagamenti a rate|pay in \\d+|pay later)$")

/** True for an outflow whose payee is the plan type itself ("Paga in 3 rate", "Pagamento a rate"), not a merchant. */
fun ExternalMovement.hasPlanTypePayee(): Boolean = signedCents < 0 && planTypePayee.matches(normalizeName(description))

/** Movement id to the plan whose part it paid (the link written by the installment matching in Installments.kt). */
private fun Archive.partLinks(): Map<String, InstallmentPlan> {
    val out = HashMap<String, InstallmentPlan>()
    installments.forEach { p -> p.installments.forEach { part -> part.movementId?.let { out.putIfAbsent(it, p) } } }
    return out
}

/** Every installment payment: an outflow linked to a plan part, or one whose payee is a plan type. */
fun Archive.installmentPaymentIds(): Set<String> {
    val links = partLinks()
    return externalMovements.filter { it.signedCents < 0 && (it.id in links || it.hasPlanTypePayee()) }.map { it.id }.toSet()
}

/** A category the person set or confirmed (own choice, own rule, confirmed provider label): never replaced by inheritance. */
private fun Archive.userSetCategory(m: ExternalMovement): Boolean {
    if (m.categorySource == CategorySource.OVERRIDE && m.categoryId != null) return true
    val source = planning.categorize(m).second
    return source == CategorySource.RULE || source == CategorySource.PROVIDER
}

/**
 * The state of each installment payment whose category comes from its plan (movement id to state). Parts with a category the
 * person chose are left out: their own category stands. Inheritance, first that applies:
 *  1. the plan's own category ([InstallmentPlan.categoryId]);
 *  2. the purchase row, when the plan's evidence or ids name a movement that is not itself a part;
 *  3. a rule of the person's that matches the plan's merchant;
 *  4. the merchant's other purchases, when they all have one and the same category.
 */
fun Archive.installmentPaymentStates(): Map<String, InstallmentPaymentState> {
    val links = partLinks()
    val parts = externalMovements.filter { it.signedCents < 0 && (it.id in links || it.hasPlanTypePayee()) && !userSetCategory(it) }
    if (parts.isEmpty()) return emptyMap()
    val partIds = installmentPaymentIds()
    // A payment-method "category" (the bug this fixes) is never inherited.
    val known = planning.categories.filterNot { PaymentMethodNames.isMethodLike(it.name) }.map { it.id }.toSet()
    fun ownCategory(m: ExternalMovement): String? = (m.categoryId ?: planning.categorize(m).first)?.takeIf { it in known }
    val purchases = externalMovements.filter { it.signedCents < 0 && it.id !in partIds }
    val byEvidence = purchases.groupBy { "paypal:" + it.id.substringAfter(':') }
    val byId = purchases.associateBy { it.id }
    val byMerchant = HashMap<String, MutableSet<String>>()
    purchases.forEach { m ->
        val key = m.merchantKey ?: merchantKey(m.description)
        val category = ownCategory(m)
        if (key.isNotEmpty() && category != null) byMerchant.getOrPut(key) { HashSet() } += category
    }
    val cache = HashMap<String, Pair<String, InheritedFrom>?>()
    fun inherited(p: InstallmentPlan): Pair<String, InheritedFrom>? = cache.getOrPut(p.id) {
        p.categoryId?.takeIf { it in known }?.let { return@getOrPut it to InheritedFrom.PLAN }
        val rows = (listOf(p.id) + p.aliases).mapNotNull { byId[it] } + p.evidence.flatMap { byEvidence[it.ref].orEmpty() }
        rows.mapNotNull(::ownCategory).distinct().singleOrNull()?.let { return@getOrPut it to InheritedFrom.PURCHASE }
        val merchant = p.merchant?.let(::merchantKey).orEmpty()
        if (merchant.isEmpty()) return@getOrPut null
        planning.rules.filter { r ->
            r.origin == RuleOrigin.USER && r.accountId == null && r.categoryId in known && merchant.contains(merchantKey(r.pattern)) &&
                (p.totalCents == null || ((r.minCents == null || p.totalCents >= r.minCents) && (r.maxCents == null || p.totalCents <= r.maxCents)))
        }.maxWithOrNull(compareBy({ it.priority }, { it.pattern.length }))?.let { return@getOrPut it.categoryId to InheritedFrom.MERCHANT_RULE }
        byMerchant[merchant]?.singleOrNull()?.let { it to InheritedFrom.MERCHANT }
    }
    return parts.associate { m ->
        val plan = links[m.id]
        val found = plan?.let(::inherited)
        m.id to if (plan != null && found != null) InstallmentPaymentState.Inherited(plan.id, found.first, found.second)
        else InstallmentPaymentState.ToClassify(plan?.id)
    }
}

/** The state of one movement, or null when it is not an installment payment whose category comes from its plan. */
fun Archive.installmentPaymentState(movementId: String): InstallmentPaymentState? = installmentPaymentStates()[movementId]

/**
 * The category Spese counts a spending movement in: an installment payment's inherited category (null while its purchase is
 * unclassified, whatever the assistant once put on it), otherwise the stored choice or the precedence of [categorize].
 * [states] is [installmentPaymentStates], computed once per screen.
 */
fun Archive.spendCategoryOf(m: ExternalMovement, states: Map<String, InstallmentPaymentState>): String? {
    states[m.id]?.let { return (it as? InstallmentPaymentState.Inherited)?.categoryId }
    return m.categoryId ?: planning.categorize(m).first
}
