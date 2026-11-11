package xyz.grtlabs.pilastri.sync.gmail

import java.time.LocalDate

internal enum class FactKind { PLAN, PAID, REMINDER, CANCELLED }

/** One part as a mail states it. Null stays null: an amount or date the mail does not give is not guessed. */
internal data class FactPart(
    val seq: Int?, val due: LocalDate?, val cents: Long?,
    val paid: Boolean = false, val paidOn: LocalDate? = null, val late: Boolean = false,
)

/** What one mail says about an installment plan. Built from extracted values only; no mail text is kept except the short [summary]. */
internal data class MailFact(
    val msgId: String,
    val date: LocalDate,
    val lender: Lender,
    val lenderLabel: String,
    val kind: FactKind,
    val orderKey: String?,
    val merchant: String?,
    val totalCents: Long?,
    val count: Int?,
    val purchaseDate: LocalDate?,
    val parts: List<FactPart>,
    /** A partial refund: noted as evidence, no part changes state. */
    val partial: Boolean = false,
    val byAgent: Boolean = false,
    val summary: String,
)

internal sealed interface Parsed {
    data class Facts(val facts: List<MailFact>) : Parsed
    /** Not an installment mail (or not one this parser can read from a known sender). */
    data object Skip : Parsed
    /** From an unknown sender, mentions installments, but the deterministic parser cannot read it: a candidate for the optional AI fallback. */
    data object Unknown : Parsed
}

/**
 * Deterministic reading of Italian installment mails. One engine, per-lender profiles ([Lenders]): the vocabulary is the generic Italian
 * receipt vocabulary ("rata 2 di 3", "3 rate da € 33,33", "promemoria", "hai pagato", "annullato"), tolerant of layout. It is not tied to
 * a lender's exact template because those are not public; anything it cannot read is skipped, or for unknown senders offered to the AI.
 */
internal object InstallmentMailParser {
    private val ic = RegexOption.IGNORE_CASE
    private fun re(p: String) = Regex(p, ic)

    private val hint = re("""\brat[ae]\b|\brateal|\ba rate\b|paga in (?:3|tre)|pay in 3|installment|30 giorni|30 days|finanziament|pagamento differito|paga dopo""")

    // ---- classification -------------------------------------------------------------------------------------------------------------
    private val cancelSubject = re("""annull|cancell|storn|recess|rimbors(?:o|ato) (?:totale|completo|parziale|dell|del tuo)|\bresi?\b|rimborsat""")
    private val cancelBody = re("""(?:è stat[oa]|sono stat[ei]|abbiamo|risulta|e' stat[oa]) (?:annullat|cancellat|stornat|rimborsat|chius)|ordine (?:è )?annullato|piano (?:è )?annullato|ti abbiamo rimborsat|rimborso (?:totale|completo|parziale)|(?:order|plan) (?:was |has been )?(?:cancel|refund)""")
    private val reminderSubject = re("""promemoria|prossim[ao]|sta per scadere|in scadenza|scadenza|reminder|upcoming|ricord|da pagare|rata in arrivo|pagamento in arrivo""")
    private val lateRe = re("""in ritardo|scadut[oa]|insolut|mancat[oa] (?:pagamento|addebito)|non (?:è )?riuscit|non è andato a buon fine|rifiutat|past due|overdue""")
    private val paidSubject = re("""hai pagato|pagamento (?:ricevuto|effettuato|riuscito|confermato|completato|andato a buon fine)|rata pagata|grazie per il (?:tuo )?pagamento|ricevuta (?:di|del) pagamento|conferma (?:del )?pagamento|payment (?:received|successful|confirmed)|addebito (?:effettuato|riuscito|confermato)""")
    private val confirmSubject = re("""conferma|il tuo (?:ordine|acquisto|piano)|riepilogo|grazie per (?:il tuo )?(?:ordine|acquisto)|approvat|piano di pagamento|paga in (?:3|tre)|in \d+ rate|nuovo piano|order confirmation|hai scelto""")
    private val paidBody = re("""hai pagato|abbiamo ricevuto (?:con successo )?(?:il tuo |il |lo )?(?:pagamento|bonifico|addebito)|pagamento (?:della rata )?(?:è stato )?(?:ricevuto|effettuato|riuscito|completato|andato a buon fine)|rata (?:n\.? ?\d+ )?(?:è stata )?(?:pagata|addebitata|incassata)|abbiamo addebitato|(?:è stat[oa]|e' stat[oa]) addebitat|payment (?:was )?(?:received|successful)""")
    private val reminderBody = re("""promemoria|prossima rata|sta per scadere|in scadenza|verr[àa] addebitat|sar[àa] addebitat|addebiteremo|ti ricordiamo|ricordiamo che|da pagare entro|pagare entro|upcoming payment|will be charged|scade (?:il|domani|oggi)""")

    internal fun classify(subject: String, body: String): FactKind? {
        val s = subject.lowercase()
        val b = body.lowercase()
        val reminderCue = reminderSubject.containsMatchIn(s)
        if (cancelSubject.containsMatchIn(s) && !reminderCue) return FactKind.CANCELLED
        if (lateRe.containsMatchIn(s) || (reminderCue && lateRe.containsMatchIn(b))) return FactKind.REMINDER
        if (paidSubject.containsMatchIn(s)) return FactKind.PAID
        if (reminderCue) return FactKind.REMINDER
        if (confirmSubject.containsMatchIn(s)) return FactKind.PLAN
        if (cancelBody.containsMatchIn(b)) return FactKind.CANCELLED
        if (paidBody.containsMatchIn(b)) return FactKind.PAID
        if (countIn(b) != null) return FactKind.PLAN // "in 9 rate da € 52,00" is a plan's shape, even when it also says "sarà addebitata"
        if (reminderBody.containsMatchIn(b)) return FactKind.REMINDER
        return FactKind.PLAN
    }

    // ---- field extraction ------------------------------------------------------------------------------------------------------------
    private val countWords = mapOf(
        "due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5, "sei" to 6, "sette" to 7, "otto" to 8, "nove" to 9, "dieci" to 10,
        "undici" to 11, "dodici" to 12, "diciotto" to 18, "ventiquattro" to 24, "trentasei" to 36,
    )
    private val ordinals = mapOf(
        "prima" to 1, "seconda" to 2, "terza" to 3, "quarta" to 4, "quinta" to 5, "sesta" to 6, "settima" to 7, "ottava" to 8,
        "nona" to 9, "decima" to 10, "undicesima" to 11, "dodicesima" to 12,
    )
    private const val WORD_NUM = """\d{1,3}|due|tre|quattro|cinque|sei|sette|otto|nove|dieci|undici|dodici|diciotto|ventiquattro|trentasei"""

    private fun number(word: String): Int? = word.toIntOrNull() ?: countWords[word.lowercase()]

    private val seqOfCount1 = re("""rata\s*(?:n\.?|nr\.?|n°|numero|#)?\s*(\d{1,3})\s*(?:di|su|/|of)\s*(\d{1,3})""")
    private val seqOfCount2 = re("""(\d{1,3})\s*[ªº°^]?\s*rata\s*(?:di|su|/)\s*(\d{1,3})""")
    private val seqOrdinal = re("""\b(${ordinals.keys.joinToString("|")})\s+rata(?:\s+(?:di|su)\s+(\d{1,3}))?""")
    private val seqOnly1 = re("""rata\s*(?:n\.?|nr\.?|n°|numero|#)?\s*(\d{1,3})(?![\d.,/%])""")
    private val seqOnly2 = re("""(\d{1,3})\s*[ªº°]\s*rata""")

    /** (part number, number of parts) as a line says them: "rata 2 di 3", "2/3 rata", "seconda rata". */
    internal fun seqCount(s: String): Pair<Int?, Int?> {
        fun ok(seq: Int?, count: Int?): Pair<Int?, Int?>? {
            if (seq != null && seq !in 1..120) return null
            if (count != null && count !in 1..120) return null
            if (seq != null && count != null && seq > count) return null
            return seq to count
        }
        // "rata 5 di 3" contradicts itself: take neither number rather than half of it.
        seqOfCount1.find(s)?.let { m -> return ok(m.groupValues[1].toInt(), m.groupValues[2].toInt()) ?: (null to null) }
        seqOfCount2.find(s)?.let { m -> return ok(m.groupValues[1].toInt(), m.groupValues[2].toInt()) ?: (null to null) }
        seqOrdinal.find(s)?.let { m -> ok(ordinals[m.groupValues[1].lowercase()], m.groupValues[2].toIntOrNull())?.let { return it } }
        seqOnly1.find(s)?.let { m -> ok(m.groupValues[1].toInt(), null)?.let { return it } }
        seqOnly2.find(s)?.let { m -> ok(m.groupValues[1].toInt(), null)?.let { return it } }
        return null to null
    }

    private val countRes = listOf(
        re("""\bin\s+($WORD_NUM)\s+rate"""),
        re("""\b($WORD_NUM)\s+rate\s+(?:mensili|settimanali|quindicinali|da|di|senza|con|a tasso|fisse|costanti|x)"""),
        re("""paga\s+in\s+($WORD_NUM)\b(?!\s*(?:giorni|days|gg))"""),
        re("""pay\s+in\s+(\d{1,3})\b(?!\s*(?:giorni|days|gg))"""),
        re("""numero\s+(?:di\s+)?rate\s*:?\s*(\d{1,3})"""),
        re("""durata\s*:?\s*(\d{1,3})\s*(?:mesi|rate)"""),
        re("""\b($WORD_NUM)\s+rate\b"""),
    )

    /** How many parts the mail says the plan has (2..120). */
    internal fun countIn(s: String): Int? {
        for (r in countRes) for (m in r.findAll(s)) {
            val n = number(m.groupValues[1])
            if (n != null && n in 2..120) return n
        }
        return null
    }

    private val rateDaRe = re("""\b($WORD_NUM)\s+rate\s+(?:mensili\s+|settimanali\s+|costanti\s+|fisse\s+)?(?:da|di|pari a|x)\s*""")
    private val rataDaRe = re("""\brata\s+(?:mensile\s+|singola\s+)?(?:da|di|pari a)\s*|importo\s+(?:della\s+|di ogni\s+)?(?:singola\s+)?rata\s*:?\s*|\b\d{1,3}\s*[x×]\s*""")

    /** "N rate da X": (N, X). Dates must be blanked in [blank]. */
    private fun rateDa(blank: String): Pair<Int?, Long>? {
        for (m in rateDaRe.findAll(blank)) {
            val n = number(m.groupValues[1]) ?: continue
            amountAfter(blank, m.range.last + 1)?.let { return n.takeIf { c -> c in 2..120 } to it }
        }
        for (m in rataDaRe.findAll(blank)) amountAfter(blank, m.range.last + 1)?.let { return null to it }
        return null
    }

    /** The first amount starting within [window] characters after [from], on the same logical stretch of text. */
    private fun amountAfter(blank: String, from: Int, window: Int = 30): Long? {
        if (from >= blank.length) return null
        // Read a little past the window so an amount that starts inside it is not cut in half.
        val end = minOf(blank.length, from + window + 24)
        val hit = ItalianFormats.findAmounts(blank.substring(from, end)).firstOrNull() ?: return null
        return hit.cents.takeIf { hit.range.first <= window }
    }

    private val totalTier1 = re("""totale\s+(?:dell'?\s*)?(?:ordine|acquisto|carrello|da pagare per l'ordine)|importo\s+totale|prezzo\s+totale|importo\s+finanziato|importo\s+(?:dell'?\s*)?(?:acquisto|ordine|finanziamento)|totale\s+complessivo|total\s+(?:amount|order)|(?:acquisto|spesa)\s+di""")
    private val totalTier2 = re("""\btotale\b(?!\s*(?:da pagare\s*)?(?:oggi|rate|rata|interessi|spedizione|dovuto oggi))""")

    private fun totalIn(blank: String): Long? {
        for (r in listOf(totalTier1, totalTier2)) for (m in r.findAll(blank)) amountAfter(blank, m.range.last + 1, window = 16)?.let { return it }
        return null
    }

    private val keyLabel = re(
        """(?:(?:numero|n\.|nr\.|n°|id|codice|riferimento|rif\.|order|ordine|pratica|contratto)\s*(?:d[eil]l?l?['’]?\s*)?(?:ordine|pratica|contratto|piano(?: rate)?|finanziamento|transazione|operazione|acquisto|prestito|rate)""" +
            """|ordine\s*(?:n\.|n°|nr\.|numero|#)|pratica\s*(?:n\.|n°|nr\.|numero|#)|order\s*(?:id|number|no\.?|#)|reference(?: number)?|riferimento)\s*[:#.\-–]?\s*([A-Za-z0-9][A-Za-z0-9\-_/]{3,39})""",
    )

    /** A plausible lender-side order or plan id: at least 5 characters with a digit, normalized to upper case, safe for a plan id. */
    internal fun orderKeyIn(s: String): String? {
        for (m in keyLabel.findAll(s)) {
            val token = m.groupValues[1].trim('-', '_', '/', '.')
            if (token.length >= 5 && token.any { it.isDigit() } && !token.matches(Regex("""(?:19|20)\d{2}"""))) return sanitizeKey(token)
        }
        return null
    }

    internal fun sanitizeKey(raw: String): String? =
        raw.uppercase().replace('/', '-').filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }.take(60).trim('-', '_', '.').takeIf { it.length >= 3 }

    private val merchantLabelled = re("""(?:venditore|negozio|esercente|merchant|rivenditore|store)\s*:\s*([^\n|]{2,80})""")
    private val merchantPhrase = re("""(?:acquisto|ordine|pagamento|acquisti|spesa)\s+(?:presso|da|a|su)\s+([^\n|]{2,80})""")
    private val merchantPresso = Regex("""\bpresso\s+([A-Z][^\n|]{1,60})""")
    private val merchantTail = re("""\s+(?:(?:il|del|in data|per|con|tramite|n\.|n°|ordine|rata|rate|totale|ha|è|sono|ti|ci)(?![\p{L}\d])|dell').*$|\s*[(€].*$|[,;].*$|\.\s.*$|\.$""")
    private val merchantBad = re("""^(?:te|voi|noi|tua|tuo|tue|tuoi|banca|nostro|nostra|questo|questa|rata|rate|paypal|klarna|scalapay|[\d\W]+)\b""")

    internal fun merchantIn(subject: String, body: String, ownLabel: String): String? {
        val candidates = listOf(merchantLabelled.find(body), merchantPhrase.find(subject), merchantPhrase.find(body), merchantPresso.find(body)).filterNotNull()
        for (m in candidates) {
            val cleaned = merchantTail.replace(m.groupValues[1].trim(), "").trim(' ', '-', '–', ':', '.', '"', '\'')
            if (cleaned.length in 2..100 && !merchantBad.containsMatchIn(cleaned) && !cleaned.equals(ownLabel, ignoreCase = true)) return cleaned
        }
        return null
    }

    private val rowSkipHard = re("""\btotal[ei]\b|subtotal|spedizion|sconto|\biva\b|commission|interess|\btaeg\b|\btan\b|importo finanziato""")
    private val rowSkipSoft = re("""\bordine\b|\bacquisto\b|\bconsegna\b|\bspedit""")
    private val rowKeep = re("""\brata\b|\boggi\b|\btoday\b""")
    private fun skipRow(lower: String) = rowSkipHard.containsMatchIn(lower) || (rowSkipSoft.containsMatchIn(lower) && !rowKeep.containsMatchIn(lower))
    // "addebitata" is a payment already made unless the sentence is in the future ("sarà addebitata il ...").
    private val paidWord = re("""pagat|saldat|incassat|\bpaid\b|✓|✔|completat|(?<!sarà )(?<!sara )(?<!verrà )(?<!verra )(?<!saranno )(?<!verranno )addebitat""")

    private data class Row(val seq: Int?, val due: LocalDate?, val cents: Long?, val paid: Boolean)

    /** Lines that pair a date with an amount (a schedule table row), or a numbered part with an amount. */
    private fun scheduleRows(text: String, ref: LocalDate): List<Row> {
        val lines = text.lines()
        val rows = ArrayList<Row>()
        for (line in lines) {
            val lower = line.lowercase()
            if (line.isBlank() || skipRow(lower)) continue
            val dates = ItalianFormats.findDates(line, ref)
            val amounts = ItalianFormats.findAmounts(ItalianFormats.blankDates(line, ref))
            val seq = seqCount(line).first
            when {
                dates.size == 1 && amounts.size == 1 -> rows += Row(seq, dates[0].date, amounts[0].cents, paidWord.containsMatchIn(lower))
                dates.isEmpty() && amounts.size == 1 && seq != null && lower.contains("rata") && seqCount(line).second == null ->
                    rows += Row(seq, null, amounts[0].cents, paidWord.containsMatchIn(lower))
                // "Seconda rata: 12 dic 2026": the part and its date, the amount stated once elsewhere ("3 rate da € 20,00").
                dates.size == 1 && amounts.isEmpty() && seq != null && lower.contains("rata") ->
                    rows += Row(seq, dates[0].date, null, paidWord.containsMatchIn(lower))
            }
        }
        if (rows.isEmpty()) {
            // Layouts that put the date and the amount in neighbouring cells/lines.
            for (i in 0 until lines.size - 1) {
                val a = lines[i]; val b = lines[i + 1]
                if (skipRow(a.lowercase()) || skipRow(b.lowercase())) continue
                val da = ItalianFormats.findDates(a, ref); val db = ItalianFormats.findDates(b, ref)
                val ma = ItalianFormats.findAmounts(ItalianFormats.blankDates(a, ref)); val mb = ItalianFormats.findAmounts(ItalianFormats.blankDates(b, ref))
                if (da.size == 1 && ma.isEmpty() && db.isEmpty() && mb.size == 1) rows += Row(null, da[0].date, mb[0].cents, paidWord.containsMatchIn(b.lowercase()))
                else if (ma.size == 1 && da.isEmpty() && db.size == 1 && mb.isEmpty()) rows += Row(null, db[0].date, ma[0].cents, paidWord.containsMatchIn(a.lowercase()))
            }
        }
        return rows
    }

    private val dueCue = re("""(?:entro(?: il)?|scadenza(?: il| del)?|scade(?: il)?|scadut[ao](?: il)?|addebit\w+ (?:il|in data)|il giorno|in data|data di scadenza|sar[àa] addebitat\w+|verr[àa] addebitat\w+)\s*:?\s*""")

    /** The due date a reminder states: a date right after a cue like "entro il", else the first date that is not simply "oggi". */
    private fun dueDateIn(text: String, ref: LocalDate): LocalDate? {
        val hits = ItalianFormats.findDates(text, ref)
        for (m in dueCue.findAll(text)) hits.firstOrNull { it.range.first >= m.range.last - 1 && it.range.first - m.range.last <= 40 }?.let { return it.date }
        return hits.firstOrNull { it.date != ref }?.date
    }

    private val amountCue = re("""hai pagato|pagamento di|pagato|addebitat\w*|ricevut\w*|importo|rata|pagamento""")

    private fun amountNearCue(blank: String): Long? {
        for (m in amountCue.findAll(blank)) amountAfter(blank, m.range.last + 1, window = 45)?.let { return it }
        return ItalianFormats.findAmounts(blank).firstOrNull()?.cents
    }

    private val paidCue = re("""pagat\w*|addebitat\w*|effettuat\w*|ricevut\w*|eseguit\w*""")
    private val nextCue = re("""prossim[ao]\s+(?:rata|pagamento|addebito|scadenza)""")

    // ---- assembly -------------------------------------------------------------------------------------------------------------------
    private fun summary(vararg pieces: String?): String =
        pieces.filterNotNull().filter { it.isNotBlank() }.joinToString(", ").replace(Regex("\\s+"), " ").take(200)

    private fun euros(cents: Long) = String.format(java.util.Locale.ROOT, "€ %d,%02d", cents / 100, cents % 100)

    private fun labelFor(lender: Lender, lower: String): String =
        if (lender === Lenders.PAYPAL && "paga a rate" in lower && "paga in 3" !in lower && "paga in tre" !in lower) "PayPal Paga a rate" else lender.label

    fun parse(m: MailMessage): Parsed {
        val known = Lenders.forSender(m.from)
        val lender = known ?: Lenders.generic(m.from)
        val subject = ItalianFormats.normalizeSpaces(m.subject)
        val text = m.text
        val lower = (subject + "\n" + text).lowercase()
        if (lender.relevance != null && !lender.relevance.containsMatchIn(lower)) return Parsed.Skip
        // Dedicated lenders (Klarna, Scalapay, ...) mail about payments by definition; PayPal and unknown senders need the installment vocabulary.
        if ((!lender.known || lender === Lenders.PAYPAL) && !hint.containsMatchIn(lower)) return Parsed.Skip

        val kind = classify(subject, text)
        val label = labelFor(lender, lower)
        val ref = m.date
        val whole = subject + "\n" + text
        val blank = ItalianFormats.blankDates(whole, ref)
        val merchant = merchantIn(subject, text, label)
        val key = orderKeyIn(whole)
        val strict = !lender.known // unknown senders must show the unmistakable "rata N di M" / "N rate da X" shape

        fun facts(vararg f: MailFact): Parsed = Parsed.Facts(f.toList())
        fun unreadable(): Parsed = if (strict) Parsed.Unknown else Parsed.Skip

        return when (kind) {
            null -> unreadable()
            FactKind.CANCELLED -> {
                val partial = Regex("parziale", ic).containsMatchIn(lower) && !Regex("totale|completo", ic).containsMatchIn(lower)
                if (strict && key == null && merchant == null) return Parsed.Unknown
                facts(MailFact(m.id, ref, lender, label, FactKind.CANCELLED, key, merchant, null, null, null, emptyList(), partial = partial,
                    summary = summary(label, if (partial) "rimborso parziale" else "ordine/piano annullato o rimborsato", merchant)))
            }
            FactKind.PAID -> {
                val (seq, count) = seqCount(whole)
                if (strict && seq == null) return Parsed.Unknown
                val cents = amountNearCue(blank)
                if (seq == null && cents == null) return unreadable()
                val paidOn = paidCue.findAll(text).firstNotNullOfOrNull { c ->
                    ItalianFormats.findDates(text.substring(c.range.last + 1, minOf(text.length, c.range.last + 40)), ref, relative = false)
                        .firstOrNull()?.date?.takeIf { it <= ref.plusDays(1) }
                } ?: ref
                val out = ArrayList<MailFact>()
                out += MailFact(m.id, ref, lender, label, FactKind.PAID, key, merchant, null, count, null,
                    listOf(FactPart(seq, null, cents, paid = true, paidOn = paidOn)),
                    summary = summary(label, "rata ${seq ?: "?"}${count?.let { " di $it" } ?: ""} pagata", cents?.let(::euros), merchant))
                nextCue.find(text)?.let { cue ->
                    val tail = text.substring(cue.range.last + 1, minOf(text.length, cue.range.last + 140))
                    val due = ItalianFormats.findDates(tail, ref).firstOrNull()?.date
                    val nextCents = ItalianFormats.findAmounts(ItalianFormats.blankDates(tail, ref)).firstOrNull()?.cents
                    if (due != null || nextCents != null)
                        out += MailFact(m.id, ref, lender, label, FactKind.REMINDER, key, merchant, null, count, null,
                            listOf(FactPart(seq?.plus(1), due, nextCents)), summary = summary(label, "prossima rata", nextCents?.let(::euros), due?.toString(), merchant))
                }
                Parsed.Facts(out)
            }
            FactKind.REMINDER -> {
                val (seq, count) = seqCount(whole)
                if (strict && seq == null) return Parsed.Unknown
                val due = dueDateIn(text, ref)
                val cents = amountNearCue(blank)
                if (due == null && cents == null) return unreadable()
                val late = lateRe.containsMatchIn(lower)
                facts(MailFact(m.id, ref, lender, label, FactKind.REMINDER, key, merchant, null, count, null,
                    listOf(FactPart(seq, due, cents, late = late)),
                    summary = summary(label, if (late) "rata scaduta o non riuscita" else "promemoria rata", seq?.let { "n. $it${count?.let { c -> " di $c" } ?: ""}" }, cents?.let(::euros), due?.toString(), merchant)))
            }
            FactKind.PLAN -> parsePlan(m, lender, label, lower, subject, text, blank, merchant, key, strict)
        }
    }

    private fun parsePlan(
        m: MailMessage, lender: Lender, label: String, lower: String, subject: String, text: String, blank: String,
        merchant: String?, key: String?, strict: Boolean,
    ): Parsed {
        val ref = m.date
        val rows = scheduleRows(text, ref).let { all ->
            // A schedule lists the parts of one plan: keep at most the stated count, and drop an explicit-number clash.
            val seqs = all.mapNotNull { it.seq }
            if (seqs.size != seqs.distinct().size) all.map { it.copy(seq = null) } else all
        }
        val perPart = rateDa(blank)
        val stated = countIn(blank) ?: perPart?.first
        val thirtyDay = Regex("""paga in 30 giorni|pay in 30 days|pagamento a 30 giorni|paga dopo|pay later""", ic).containsMatchIn(lower) && stated == null && rows.size <= 1
        val count = stated ?: if (thirtyDay) 1 else rows.size.takeIf { it >= 2 }
        val total = totalIn(blank)
        val partCents = perPart?.second
        if (count == null || (strict && stated == null)) return if (strict) Parsed.Unknown else Parsed.Skip
        if (total == null && partCents == null && rows.none { it.cents != null }) return if (strict) Parsed.Unknown else Parsed.Skip
        val effectiveRows = if (rows.size > count) rows.take(count) else rows

        val numbered = effectiveRows.mapIndexed { i, r -> r.copy(seq = r.seq ?: (i + 1)) }
        val seqSet = numbered.mapNotNull { it.seq }
        val rowsOk = seqSet.size == seqSet.distinct().size && seqSet.all { it in 1..count }
        val ordered = (if (rowsOk) numbered else effectiveRows.mapIndexed { i, r -> r.copy(seq = i + 1) }).sortedBy { it.seq }

        val parts = ArrayList<FactPart>()
        for (r in ordered) {
            val atCheckout = lender.checkoutPaid && r.seq == 1 && r.due == m.date
            parts += FactPart(r.seq, r.due, r.cents ?: partCents, paid = r.paid || atCheckout, paidOn = if (r.paid || atCheckout) (r.due ?: m.date) else null)
        }
        if (parts.isEmpty() && thirtyDay) {
            val due = dueDateIn(text, ref)
            parts += FactPart(1, due, total ?: partCents)
        }
        if (partCents != null && count <= 36) {
            // "N rate da X": every part's amount is stated, even when only some due dates are.
            for (seq in 1..count) if (parts.none { it.seq == seq }) parts += FactPart(seq, null, partCents)
        } else if (parts.none { it.seq == 1 }) {
            Regex("""prima\s+rata[^\n]{0,40}""", ic).find(text)?.let { c ->
                ItalianFormats.findDates(c.value, ref).firstOrNull()?.let { d -> parts += FactPart(1, d.date, partCents ?: ItalianFormats.findAmounts(ItalianFormats.blankDates(c.value, ref)).firstOrNull()?.cents) }
            }
        }
        val dated = Regex("""data\s+(?:dell['’]?\s*)?(?:ordine|acquisto)\s*:?\s*""", ic).find(text)
            ?.let { c -> ItalianFormats.findDates(text.substring(c.range.last + 1, minOf(text.length, c.range.last + 30)), ref, relative = false).firstOrNull()?.date }
        return Parsed.Facts(listOf(MailFact(
            m.id, ref, lender, label, FactKind.PLAN, key, merchant, total, count, dated ?: ref, parts.sortedBy { it.seq },
            summary = summary(label, "piano in $count ${if (count == 1) "pagamento" else "rate"}", merchant, total?.let { "totale ${euros(it)}" }, partCents?.let { "rata ${euros(it)}" }),
        )))
    }
}
