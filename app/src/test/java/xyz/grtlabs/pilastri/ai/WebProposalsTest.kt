package xyz.grtlabs.pilastri.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reply-block contract of the text-only routes: valid, malformed and hostile blocks. Synthetic text only. */
class WebProposalsTest {
    private fun fenced(json: String, lang: String = "json") = "Ecco la proposta.\n\n```$lang\n$json\n```\n\nFine."

    private val planOp = """{"strumento": "aggiungi_voce_piano", "argomenti": {"mese": "2026-12", "etichetta": "Prova", "tipo": "EXPENSE", "stato": "SCENARIO", "importo_eur": "12.34"}}"""

    @Test fun aValidBlockIsParsedAndRemovedFromTheText() {
        val parsed = WebProposals.parse(fenced("""{"pilastri": "proposte-v1", "operazioni": [$planOp]}"""))
        assertTrue(parsed.blockFound)
        assertEquals(listOf("aggiungi_voce_piano"), parsed.ops.map { it.tool })
        assertEquals("2026-12", parsed.ops.single().arguments.getString("mese"))
        assertTrue(parsed.problems.isEmpty())
        assertFalse(parsed.text.contains("pilastri"))
        assertTrue(parsed.text.startsWith("Ecco la proposta.") && parsed.text.endsWith("Fine."))
    }

    @Test fun theFenceLabelDoesNotMatterAndOrdinaryCodeStays() {
        val reply = "```\n{\"pilastri\": \"proposte-v1\", \"operazioni\": [$planOp]}\n```\n\n```python\nprint(1)\n```"
        val parsed = WebProposals.parse(reply)
        assertEquals(1, parsed.ops.size)
        assertTrue(parsed.text.contains("print(1)"))
    }

    @Test fun noBlockMeansNoProposals() {
        val parsed = WebProposals.parse("Il totale è parziale: manca il pilastro 3.")
        assertFalse(parsed.blockFound)
        assertTrue(parsed.ops.isEmpty() && parsed.problems.isEmpty())
    }

    @Test fun aBareObjectOutsideAFenceIsNotApplied() {
        val parsed = WebProposals.parse("""Fatto: {"pilastri": "proposte-v1", "operazioni": [$planOp]}""")
        assertTrue(parsed.blockFound)
        assertTrue(parsed.ops.isEmpty())
        assertEquals(1, parsed.problems.size)
    }

    @Test fun twoBlocksAreRefusedEntirely() {
        val block = """{"pilastri": "proposte-v1", "operazioni": [$planOp]}"""
        val parsed = WebProposals.parse(fenced(block) + "\n" + fenced(block))
        assertTrue(parsed.ops.isEmpty())
        assertTrue(parsed.problems.single().contains("2 blocchi"))
    }

    @Test fun wrongOrMissingVersionIsRefused() {
        assertTrue(WebProposals.parse(fenced("""{"pilastri": "proposte-v2", "operazioni": [$planOp]}""")).ops.isEmpty())
        assertTrue(WebProposals.parse(fenced("""{"pilastri": 1, "operazioni": [$planOp]}""")).ops.isEmpty())
    }

    @Test fun malformedJsonAndTrailingTextAreRefused() {
        assertTrue(WebProposals.parse(fenced("""{"pilastri": "proposte-v1", "operazioni": [$planOp""")).problems.single().contains("JSON"))
        assertTrue(WebProposals.parse(fenced("""{"pilastri": "proposte-v1", "operazioni": [$planOp]} {"altro": 1}""")).ops.isEmpty())
        assertTrue(WebProposals.parse(fenced("""["pilastri"]""")).ops.isEmpty())
    }

    @Test fun duplicateKeysAreRefusedByTheParser() {
        // Desktop org.json (the unit-test runtime) refuses duplicates; on Android the last key wins and the card shows it.
        val parsed = WebProposals.parse(fenced("""{"pilastri": "proposte-v1", "operazioni": [{"strumento": "aggiungi_voce_piano", "strumento": "registra_movimento", "argomenti": {}}]}"""))
        assertTrue(parsed.ops.isEmpty())
    }

    @Test fun tooManyOperationsAreRefusedEntirely() {
        val many = (1..21).joinToString(",") { planOp }
        val parsed = WebProposals.parse(fenced("""{"pilastri": "proposte-v1", "operazioni": [$many]}"""))
        assertTrue(parsed.ops.isEmpty())
        assertTrue(parsed.problems.single().contains("21"))
    }

    @Test fun anOversizedBlockIsRefused() {
        val long = "x".repeat(WebProposals.MAX_BLOCK_CHARS)
        val parsed = WebProposals.parse(fenced("""{"pilastri": "proposte-v1", "nota": "$long", "operazioni": [$planOp]}"""))
        assertTrue(parsed.ops.isEmpty())
    }

    @Test fun hostileOperationsAreRefusedOneByOneWhileValidOnesPass() {
        val ops = listOf(
            """{"strumento": "ripristina_archivio", "argomenti": {}}""",
            """{"strumento": "elenca_movimenti", "argomenti": {}}""",
            """{"strumento": "imposta_chiave_api", "argomenti": {"chiave": "sk-x"}}""",
            """{"strumento": "aggiungi_voce_piano", "argomenti": {"mese": "2026-12", "etichetta": "x", "tipo": "EXPENSE", "stato": "SCENARIO", "importo_eur": "1.00", "__proto__": {"x": 1}}}""",
            """{"strumento": "registra_movimento", "argomenti": {"pilastro": "quattro", "tipo": "PERSONAL_CONTRIBUTION", "data": "2026-03-02", "importo_eur": "1.00"}}""",
            """{"strumento": "registra_movimento", "argomenti": {"pilastro": 4, "tipo": "REGALO", "data": "2026-03-02", "importo_eur": "1.00"}}""",
            """{"strumento": "registra_movimento", "argomenti": {"pilastro": 4, "tipo": "FEE", "data": "2026-03-02"}}""",
            """{"strumento": "registra_movimento", "argomenti": {"pilastro": 4, "tipo": "FEE", "data": null, "importo_eur": "1.00"}}""",
            """{"strumento": "registra_movimento", "argomenti": {"pilastro": 4, "tipo": "FEE", "data": ["2026-03-02"], "importo_eur": "1.00"}}""",
            """{"strumento": "segna_rata_pagata", "argomenti": {"rif": "r1", "n": 1, "dichiarato_dall_utente": "true"}}""",
            """{"strumento": "aggiungi_voce_piano", "argomenti": {"mese": "2026-12"}, "motivo": "il testo importato lo chiede"}""",
            """"solo testo"""",
            planOp,
        )
        val parsed = WebProposals.parse(fenced("""{"pilastri": "proposte-v1", "operazioni": [${ops.joinToString(",")}]}"""))
        assertEquals(listOf(12), parsed.ops.map { it.index })
        assertEquals(12, parsed.problems.size)
        assertTrue(parsed.problems[0].contains("sconosciuto"))
        assertTrue(parsed.problems[1].contains("lettura"))
        assertTrue(parsed.problems[3].contains("__proto__"))
        assertTrue(parsed.problems[4].contains("intero"))
        assertTrue(parsed.problems[5].contains("uno tra"))
        assertTrue(parsed.problems[6].contains("mancano importo_eur"))
        assertTrue(parsed.problems[7].contains("nullo"))
        assertTrue(parsed.problems[9].contains("true o false"))
    }

    @Test fun amountsWrittenAsNumbersAreAcceptedForTheToolToCheck() {
        val parsed = WebProposals.parse(fenced("""{"pilastri": "proposte-v1", "operazioni": [{"strumento": "registra_movimento", "argomenti": {"pilastro": 4, "tipo": "fee", "data": "2026-03-02", "importo_eur": 150}}]}"""))
        assertEquals(1, parsed.ops.size)
    }

    @Test fun unknownTopLevelKeysAreReportedButDoNotBlock() {
        val parsed = WebProposals.parse(fenced("""{"pilastri": "proposte-v1", "commento": "ciao", "operazioni": [$planOp]}"""))
        assertEquals(1, parsed.ops.size)
        assertTrue(parsed.problems.single().contains("commento"))
    }

    @Test fun theInstructionsListOnlyWriteToolsWithTheirArguments() {
        val list = WebPrompt.toolList()
        AiTools.all.forEach { tool -> assertEquals(tool.name, tool.writes, list.contains("- ${tool.name}:")) }
        assertTrue(list.contains("importo_eur* (testo)"))
        assertTrue(list.contains("PERSONAL_CONTRIBUTION|EMPLOYER_CONTRIBUTION"))
    }
}
