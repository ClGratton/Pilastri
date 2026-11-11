package xyz.grtlabs.pilastri.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** Synthetic data only. */
class PlanningTest {
    private val bank = Account("bank", "Conto sintetico", 1, "fixture")
    private val pension = Account("fund", "Fondo sintetico", 4, "fixture", AccountType.PENSION_FUND)
    private val food = Category("food", "Spesa", CategoryKind.SPEND, Essential.ESSENTIAL)
    private val out = Category("out", "Ristoranti", CategoryKind.SPEND, Essential.DISCRETIONARY)
    private val day = LocalDate.of(2026, 9, 27)

    private fun withMovements(vararg movements: ImportedMovement): Archive =
        Archive(accounts = listOf(bank, pension)).withImportBatch(
            ImportBatch(ImportProvider.ISYBANK, "Conto sintetico", Instant.parse("2026-09-30T10:00:00Z"), day.minusDays(20), day, movements.toList()), bank.id).archive

    @Test fun fullPlanningAndPensionRoundTrip() {
        val archive = withMovements(ImportedMovement("m1", day, -2_350, "EUR", "PAGAMENTO POS SUPERMERCATO ROSSI 27/09", ExternalKind.CARD_PAYMENT, category = "Alimentari"))
            .copy(
                planning = Planning(
                    categories = listOf(food, out),
                    rules = listOf(CategoryRule("r1", "trattoria", out.id, RuleOrigin.USER)),
                    providerCategories = listOf(ProviderCategory(ImportProvider.ISYBANK, "Alimentari", food.id)),
                    budgets = listOf(Budget(food.id, 35_000, YearMonth.of(2026, 10))),
                    goals = listOf(Goal("g1", "Auto", 600_000, LocalDate.of(2028, 12, 1), bank.id, 200_000)),
                    targets = listOf(PillarTarget(1, TargetKind.BUFFER_CENTS, cents = 250_000, userSet = true), PillarTarget(2, TargetKind.MONTHS, months = BigDecimal("6"), userSet = false)),
                    incomeRules = listOf(IncomeRule("s1", "Stipendio", bank.id, "datore sintetico", 23, 28, 150_000, 220_000, "2026-09-30T10:00:00Z")),
                ),
                pension = PensionData(
                    statements = listOf(PensionStatement("p1", pension.id, LocalDate.of(2026, 9, 30), 203_000, 161_800, 41_200, null, null, null, null, null, null, "Estratto sintetico", true, null)),
                    profiles = listOf(FundProfile(pension.id, FundRegime.PUBLIC, null, null, null, "fixture")),
                    deductionYears = listOf(DeductionYear(2026, 530_000, "COVIP infografica 2026", LocalDate.of(2026, 1, 15))),
                    inps = listOf(InpsEstimate(LocalDate.of(2026, 10, 1), 140_000, null, "Scenario dal simulatore INPS", "INPS")),
                ),
                holdings = listOf(Holding(bank.id, "IE00B4L5Y983", "ETF sintetico", BigDecimal("12.5"), 9_820, day, "fixture")),
            )
        assertEquals(archive, ArchiveCodec.decode(ArchiveCodec.encode(archive)))
        assertEquals("The employer amount stays null when the statement does not state it", null, archive.pension.statements.single().tfrCents)
    }

    @Test fun categoryPrecedenceUserOverrideRuleProviderAgent() {
        val base = withMovements(
            ImportedMovement("a", day, -2_350, "EUR", "POS SUPERMERCATO ROSSI", ExternalKind.CARD_PAYMENT, category = "Alimentari"),
            ImportedMovement("b", day, -4_200, "EUR", "POS TRATTORIA DA MARIO", ExternalKind.CARD_PAYMENT, category = "Alimentari"),
            ImportedMovement("c", day, -900, "EUR", "POS BAR CENTRALE", ExternalKind.CARD_PAYMENT),
        )
        val planning = Planning(
            categories = listOf(food, out),
            rules = listOf(CategoryRule("user", "trattoria", out.id, RuleOrigin.USER), CategoryRule("agent", "bar centrale", out.id, RuleOrigin.AGENT)),
            providerCategories = listOf(ProviderCategory(ImportProvider.ISYBANK, "alimentari", food.id)),
        )
        val byId = base.externalMovements.associateBy { it.id.substringAfter(':') }
        assertEquals(food.id to CategorySource.PROVIDER, planning.categorize(byId.getValue("a")))
        assertEquals("A user rule beats the provider's label", out.id to CategorySource.RULE, planning.categorize(byId.getValue("b")))
        assertEquals(out.id to CategorySource.AGENT, planning.categorize(byId.getValue("c")))
        val overridden = byId.getValue("b").copy(categoryId = food.id, categorySource = CategorySource.OVERRIDE)
        assertEquals("The user's own choice always wins", food.id to CategorySource.OVERRIDE, planning.categorize(overridden))
    }

    @Test fun salaryRuleNeedsConfirmationWindowAndPayer() {
        val archive = withMovements(ImportedMovement("sal", day, 184_200, "EUR", "BONIFICO DATORE SINTETICO STIPENDIO", ExternalKind.DEPOSIT))
        val m = archive.externalMovements.single()
        val rule = IncomeRule("s", "Stipendio", bank.id, "datore sintetico", 23, 28, 150_000, 220_000, "2026-09-30T10:00:00Z")
        assertTrue(rule.matches(m))
        assertFalse("Unconfirmed rules never classify salary", rule.copy(confirmedAt = null).matches(m))
        assertFalse(rule.copy(dayFrom = 1, dayTo = 5).matches(m))
        assertFalse(rule.copy(minCents = 190_000, maxCents = 220_000).matches(m))
    }

    @Test fun referencesAreChecked() {
        val archive = Archive(accounts = listOf(bank), planning = Planning(budgets = listOf(Budget("missing", 1, YearMonth.of(2026, 10)))))
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.encode(archive) }
        val goalOnUnknownAccount = Archive(planning = Planning(goals = listOf(Goal("g", "Viaggio", 100, null, "ghost"))))
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.encode(goalOnUnknownAccount) }
    }

    @Test fun merchantKeyIgnoresCardNoiseAndDigits() {
        assertEquals(merchantKey("PAGAMENTO POS SUPERMERCATO ROSSI 27/09 ORE 18:22"), merchantKey("POS SUPERMERCATO ROSSI 03/10 ORE 09:01"))
    }
}
