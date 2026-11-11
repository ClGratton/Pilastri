package xyz.grtlabs.pilastri.ai.docs

import xyz.grtlabs.pilastri.ai.AiPolicy

/**
 * Removes personal identifiers from the text of an attached document, on the device, when the user turned on
 * "Oscura dati personali negli allegati" (off by default, user decision 2026-11-01). Typed placeholders replace the values:
 * [CF], [IBAN], [CARTA], [NUMERO] (through [AiPolicy.redact]), [MATRICOLA], [NOME], [EMAIL] and [TELEFONO].
 *
 * Names are found from labelled fields ("Cognome e nome", "Intestatario"…) and from [knownNames] (the profile), then every
 * occurrence of each name word is masked, so a name in a header or a signature goes too. Over-masking is accepted; a miss
 * is not: when in doubt the value is replaced.
 */
object DocumentRedactor {
    data class Result(val text: String, val counts: Map<String, Int>) {
        val total: Int get() = counts.values.sum()
    }

    private const val NAME_WORD = """\p{Lu}[\p{L}'’\-]+"""
    private const val NAME_RUN = """$NAME_WORD(?:[ \t]+$NAME_WORD){0,3}"""
    private const val GAP = """[ \t]*[:：.]?[ \t]*(?:\r?\n[ \t]*)?"""

    /** "Cognome e nome", "Cognome/Nome": a value may follow without a colon. */
    private val compoundNameLabel = Regex("""((?i:cognome\s+e\s+nome|cognome\s*/\s*nome|cognome\s+o\s+denominazione|cognome\s+nome))($GAP)($NAME_RUN)""")

    /** A single-word label needs a colon or the end of its line before the value (ordinary prose is left alone). */
    private val singleNameLabel = Regex("""((?i:cognome|nome|nominativo|intestatario|beneficiario|titolare|percettore)\b)([ \t]*[:：][ \t]*(?:\r?\n[ \t]*)?|[ \t]*\r?\n[ \t]*)($NAME_RUN)""")

    private val matricolaLabel = Regex(
        """((?i:matricola|matr\.|n\.?\s*matr(?:icola)?\.?|cod(?:ice)?\.?\s*dipendente|cod\.?\s*matricola))($GAP)((?<![A-Za-z0-9])(?=[A-Za-z0-9/\-]*\d)[A-Za-z0-9][A-Za-z0-9/\-]{2,})""",
    )
    private val fiscalCodeLabel = Regex(
        """((?i:codice\s+fiscale|cod\.?\s*fisc\.?|c\.\s?f\.))($GAP)((?=[A-Za-z0-9]*\d)[A-Za-z0-9]{11,16})(?![A-Za-z0-9])""",
    )

    /** Fiscal code with the omocodia letters (L M N P Q R S T U V stand for digits), in any case. */
    private val omocodiaFiscalCode = Regex(
        """(?<![A-Za-z0-9])[A-Za-z]{6}[0-9LMNPQRSTUVlmnpqrstuv]{2}[A-EHLMPR-Tahlmpr-t][0-9LMNPQRSTUVlmnpqrstuv]{2}[A-Za-z][0-9LMNPQRSTUVlmnpqrstuv]{3}[A-Za-z](?![A-Za-z0-9])""",
    )

    private val email = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}""")

    /** Italian mobile and landline numbers, with an optional +39 / 0039 prefix. Not amounts (they carry a comma). */
    private val phone = Regex(
        """(?<![\w.,/\-])(?:(?:\+|00)39[ .\-]?)?(?:3\d{2}[ .\-]?\d{3}[ .\-]?\d{3,4}|0\d{1,3}[ ./\-]?\d{5,8})(?![\w,])""",
    )
    private val intlPhone = Regex("""(?<![\w.,/\-])\+(?!39)\d{1,3}[ .\-]?\d{2,4}(?:[ .\-]?\d{2,4}){2,4}(?![\w,])""")

    /** Words never taken as part of a name (labels and connectives that follow or precede a name run). */
    private val stop: Set<String> = setOf(
        "codice", "fiscale", "matricola", "data", "nascita", "nato", "nata", "luogo", "sesso", "comune", "provincia", "residenza",
        "domicilio", "indirizzo", "qualifica", "livello", "periodo", "mese", "anno", "iban", "azienda", "ente", "datore", "lavoro",
        "sede", "partita", "iva", "importo", "totale", "descrizione", "nome", "cognome", "denominazione", "retribuzione", "stipendio",
        "cedolino", "busta", "paga", "certificazione", "unica", "reddito", "tipo", "tipologia", "numero", "pagina", "foglio", "banca",
        "contratto", "inquadramento", "imponibile", "netto", "lordo", "trattenute", "competenze", "scheda", "prospetto",
    )

    private val placeholders = listOf("CF", "IBAN", "CARTA", "NUMERO", "MATRICOLA", "NOME", "EMAIL", "TELEFONO")

    fun redact(text: String, knownNames: Collection<String> = emptyList()): Result {
        val names = LinkedHashSet<String>()
        knownNames.forEach { addNameWords(it, names) }

        var out = text.replace(' ', ' ')
        out = compoundNameLabel.replace(out) { m -> labelled(m, "[NOME]", names, isName = true) }
        out = singleNameLabel.replace(out) { m -> labelled(m, "[NOME]", names, isName = true) }
        out = matricolaLabel.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + "[MATRICOLA]" }
        out = fiscalCodeLabel.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + "[CF]" }
        out = email.replace(out, "[EMAIL]")
        // IBANs (any position, with separators), fiscal codes, cards and runs of nine or more digits go first, so a phone
        // pattern can never eat the middle of an IBAN and leave the rest of it behind.
        out = AiPolicy.redact(out)
        out = omocodiaFiscalCode.replace(out, "[CF]")
        out = phone.replace(out, "[TELEFONO]")
        out = intlPhone.replace(out, "[TELEFONO]")
        out = maskNames(out, names)
        return Result(out, count(out))
    }

    /** Replaces the name run of a labelled match, cutting it at the first label or connective word. */
    private fun labelled(m: MatchResult, placeholder: String, names: MutableSet<String>, isName: Boolean): String {
        val label = m.groupValues[1]
        val gap = m.groupValues[2]
        val run = m.groupValues[3]
        val words = Regex(NAME_WORD).findAll(run).toList()
        val kept = words.takeWhile { it.value.lowercase() !in stop }
        if (kept.isEmpty()) return m.value
        if (isName) kept.forEach { addNameWords(it.value, names) }
        val end = kept.last().range.last + 1
        return label + gap + placeholder + run.substring(end)
    }

    private fun addNameWords(raw: String, into: MutableSet<String>) {
        Regex("""[\p{L}'’\-]{3,}""").findAll(raw).forEach { w ->
            val word = w.value.trim('\'', '’', '-')
            if (word.length >= 3 && word.lowercase() !in stop && word.any { it.isLetter() }) into += word
        }
    }

    private fun maskNames(text: String, names: Set<String>): String {
        if (names.isEmpty()) return text
        var out = text
        // Longest first, so "De Luca" words do not leave a stub behind.
        names.sortedByDescending { it.length }.forEach { name ->
            val rx = Regex("""(?<![\p{L}])${Regex.escape(name)}(?![\p{L}])""", setOf(RegexOption.IGNORE_CASE))
            out = rx.replace(out, "[NOME]")
        }
        return Regex("""(?:\[NOME][ \t]+){1,}\[NOME]""").replace(out, "[NOME]")
    }

    private fun count(text: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        placeholders.forEach { p ->
            val n = Regex(Regex.escape("[$p]")).findAll(text).count()
            if (n > 0) out[p] = n
        }
        return out
    }
}
