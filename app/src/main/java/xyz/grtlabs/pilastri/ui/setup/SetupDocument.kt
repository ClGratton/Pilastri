package xyz.grtlabs.pilastri.ui.setup

import xyz.grtlabs.pilastri.ai.docs.DocumentKinds
import xyz.grtlabs.pilastri.data.Gestione
import xyz.grtlabs.pilastri.data.PositionKind
import xyz.grtlabs.pilastri.data.SeveranceRegime

/*
 * Setup, Posizione › "Leggi da un documento" (boards 8a-04/05): what a cedolino or a CU says about the person's position,
 * read from the text the on-device pipeline already extracted. Pure and conservative: a field is set only on an unambiguous
 * cue, otherwise it stays unset (n/d). Nothing here is a person-, employer- or provider-specific rule: only generic cues
 * of the Italian public/private employment documents. No value of the document is kept except what it says about the
 * position; amounts, names and the document text are never stored.
 */
object SetupDocReader {
    sealed interface Result {
        /** The document pre-selects [positions] and may name the [gestione] and the end-of-service regime. [sha] is the document's SHA-256. */
        data class Read(
            val positions: Set<PositionKind>,
            val gestione: Gestione?,
            val severance: SeveranceRegime?,
            val label: String,
            val sha: String,
        ) : Result

        /** Nothing changes; [line] is the one line shown to the person. */
        data class Unreadable(val line: String) : Result
    }

    const val NOT_RECOGNISED = "Non riconosco il documento: serve un cedolino o una CU."
    const val NO_POSITION = "Dal documento non capisco che posizione hai."

    private val opts = setOf(RegexOption.IGNORE_CASE)
    private val publicCue = Regex(
        """\bnoi\s?pa\b|\bministero\b|\bpubblica\s+amministrazione\b|\bamministrazione\s+(?:pubblica|centrale|statale|scolastica)\b""", opts,
    )
    private val privateCue = Regex("""\bCCNL\b|\bmatricola\s+(?:azienda\s+)?INPS\b""", opts)
    private val tfr = Regex("""\bTFR\b|trattamento\s+di\s+fine\s+rapporto""", opts)
    private val tfs = Regex("""\bTFS\b|trattamento\s+di\s+fine\s+servizio""", opts)
    private val cpdel = Regex("""\bCPDEL\b""", opts)
    private val ctps = Regex("""\bCTPS\b""", opts)
    private val fpld = Regex("""\bFPLD\b""", opts)

    /**
     * [rawText] is the extracted text, [label] the pipeline's name for it ("cedolino ottobre 2026", "CU 2025"; null = derive it
     * from the text), [sha] the SHA-256 of the original. Only a cedolino or a CU is read.
     */
    fun read(rawText: String, label: String?, sha: String): Result {
        val kindLabel = DocumentKinds.label(rawText)
        val isPayslip = kindLabel.startsWith("cedolino")
        val isCu = kindLabel == "CU" || kindLabel.startsWith("CU ")
        if (!isPayslip && !isCu) return Result.Unreadable(NOT_RECOGNISED)

        val pub = publicCue.containsMatchIn(rawText)
        val priv = privateCue.containsMatchIn(rawText)
        // Both kinds of cue, or neither, is ambiguous: the position is not guessed.
        val kind = when {
            pub && !priv -> PositionKind.DIPENDENTE_PUBBLICO
            priv && !pub -> PositionKind.DIPENDENTE_PRIVATO
            else -> return Result.Unreadable(NO_POSITION)
        }

        val hasTfr = tfr.containsMatchIn(rawText)
        val hasTfs = tfs.containsMatchIn(rawText)
        val shown = when {
            hasTfr && !hasTfs -> SeveranceRegime.TFR
            hasTfs && !hasTfr -> SeveranceRegime.TFS
            else -> null
        }
        // TFS belongs to the public regime only: a private position that shows it is contradictory, so the field stays unset.
        val severance = shown.takeUnless { it == SeveranceRegime.TFS && kind == PositionKind.DIPENDENTE_PRIVATO }

        val funds = buildSet {
            if (cpdel.containsMatchIn(rawText)) add(Gestione.CPDEL)
            if (ctps.containsMatchIn(rawText)) add(Gestione.CTPS)
            if (fpld.containsMatchIn(rawText)) add(Gestione.FPLD)
        }
        val gestione = funds.singleOrNull()?.takeIf {
            if (kind == PositionKind.DIPENDENTE_PUBBLICO) it == Gestione.CPDEL || it == Gestione.CTPS else it == Gestione.FPLD
        }

        val name = label?.trim()?.takeIf { it.isNotEmpty() && it != "documento" } ?: kindLabel
        return Result.Read(setOf(kind), gestione, severance, name, sha)
    }
}
