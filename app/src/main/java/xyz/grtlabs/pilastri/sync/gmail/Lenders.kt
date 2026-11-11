package xyz.grtlabs.pilastri.sync.gmail

/**
 * A sender the deterministic parsers know. Sender domains are what Gmail's `from:` matches. Provenance, honestly:
 * - PayPal, Klarna: the product behavior (first part at checkout, then monthly; reminder emails; refunds credited to the plan) is stated on
 *   PayPal's and Klarna's own Italian help pages and a Klarna merchant FAQ read 2026-10-31; the exact wording of their mails is NOT published
 *   there, so the phrase lists below are generic Italian receipt vocabulary, not copies of templates.
 * - Cofidis: named as the lender on Amazon.it ("Cofidis S.p.A." in the order summary, per Money.it); Younited: customer address
 *   clienti@younited-credit.it appears in its Italian contract documents.
 * - Scalapay, HeyLight, Compass, Findomestic: domains are the companies' own names; their mail wording is a guess.
 */
internal class Lender(
    val key: String,
    val label: String,
    val domains: List<String>,
    /** A sender that mails about everything (PayPal) only counts when the text matches this. */
    val relevance: Regex? = null,
    /** The part dated "oggi" in a confirmation is collected at checkout (PayPal and Klarna say so; for Scalapay this is assumed). */
    val checkoutPaid: Boolean = false,
    val known: Boolean = true,
)

internal object Lenders {
    private val ic = RegexOption.IGNORE_CASE

    val PAYPAL = Lender(
        "paypal", "PayPal Paga in 3 rate", listOf("paypal.it", "paypal.com"),
        relevance = Regex("""paga in 3|paga in tre|pay in 3|paga a rate|pay monthly|\brat[ae]\b""", ic), checkoutPaid = true,
    )
    val KLARNA = Lender("klarna", "Klarna", listOf("klarna.com"), checkoutPaid = true)
    val SCALAPAY = Lender("scalapay", "Scalapay", listOf("scalapay.com"), checkoutPaid = true)
    val HEYLIGHT = Lender("heylight", "HeyLight", listOf("heylight.com", "heylight.it"))
    val COMPASS = Lender("compass", "Compass", listOf("compass.it"))
    val FINDOMESTIC = Lender("findomestic", "Findomestic", listOf("findomestic.it"))
    val COFIDIS = Lender("cofidis", "Cofidis", listOf("cofidis.it", "cofidis.com"))
    val YOUNITED = Lender("younited", "Younited", listOf("younited-credit.com", "younited-credit.it", "younited.com"))

    val all: List<Lender> = listOf(PAYPAL, KLARNA, SCALAPAY, HEYLIGHT, COMPASS, FINDOMESTIC, COFIDIS, YOUNITED)

    /** Domains used in the Gmail `from:` filter. PayPal is queried separately (it needs a text filter). */
    val dedicatedDomains: List<String> = all.filter { it !== PAYPAL }.flatMap { it.domains }

    private val prettyNames = mapOf(
        "nexi" to "Nexi", "americanexpress" to "American Express", "intesasanpaolo" to "Intesa Sanpaolo", "unicredit" to "UniCredit",
        "poste" to "Poste Italiane", "postepay" to "Postepay", "amazon" to "Amazon", "satispay" to "Satispay", "bnl" to "BNL",
    )
    private val genericSubdomains = setOf(
        "mail", "email", "e", "em", "news", "newsletter", "notify", "notifications", "noreply", "no-reply", "info", "service", "services",
        "support", "mailer", "send", "bounce", "comunicazioni", "www", "my", "clienti", "customer", "app", "mg", "mailing",
    )

    /** The lowercase domain of the address in a From header, or null. */
    fun senderDomain(from: String): String? {
        val bracketed = Regex("""<[^<>\s@]+@([^<>\s]+)>""").find(from)?.groupValues?.get(1)
        val domain = bracketed ?: Regex("""@([A-Za-z0-9.\-]+)""").find(from)?.groupValues?.get(1)
        return domain?.lowercase()?.trim('.', ' ')?.takeIf { it.isNotEmpty() }
    }

    private fun matches(domain: String, listed: String) = domain == listed || domain.endsWith(".$listed")

    fun forSender(from: String): Lender? {
        val domain = senderDomain(from) ?: return null
        return all.firstOrNull { l -> l.domains.any { matches(domain, it) } }
    }

    /** A lender for a sender no parser knows (a card issuer, a shop's own financing, an unknown lender): named after its domain. */
    fun generic(from: String): Lender {
        val domain = senderDomain(from) ?: return Lender("altro", "Altro", emptyList(), known = false)
        var labels = domain.split('.').filter { it.isNotEmpty() }
        while (labels.size > 2 && labels.first() in genericSubdomains) labels = labels.drop(1)
        val core = if (labels.size >= 2) labels[labels.size - 2] else labels.first()
        // The key carries the whole registrable domain, so a lookalike (klarna.co) never shares a plan id with a known lender (klarna).
        val key = labels.takeLast(2).joinToString("").lowercase().filter { it.isLetterOrDigit() }.take(30).ifEmpty { "altro" }
        val label = prettyNames[core.lowercase()] ?: core.lowercase().replaceFirstChar { it.uppercase() }
        return Lender(key, label, listOf(domain), known = false)
    }
}
