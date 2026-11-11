package xyz.grtlabs.pilastri.data

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Counts-only dry run of [CategoryRepair] and [withDefaultSpendCategories] on a real archive. Skipped unless PILASTRI_DRYRUN_FILE
 * names an archive file. Prints `CATDRY ...` lines holding counts and booleans only: never names, payees, amounts, dates or ids.
 * The file is only read; nothing is written back.
 *
 *   PILASTRI_DRYRUN_FILE=/path/to/portfolio-v1.json tools/gw.sh :app:testDebugUnitTest --tests "*RealArchiveCategoryRepairDryRunTest*" --rerun
 */
class RealArchiveCategoryRepairDryRunTest {
    @Test fun countsBeforeAndAfterTheCategoryRepair() {
        val path = System.getenv("PILASTRI_DRYRUN_FILE")
        assumeTrue("PILASTRI_DRYRUN_FILE not set", !path.isNullOrBlank() && File(path!!).isFile)
        val before = ArchiveCodec.decode(File(path!!).readBytes())
        fun Archive.spend() = planning.categories.count { it.kind == CategoryKind.SPEND }
        fun Archive.methodLike() = planning.categories.count { PaymentMethodNames.isMethodLike(it.name) }
        fun Archive.agentAssigned() = externalMovements.count { it.categorySource == CategorySource.AGENT }
        val lines = ArrayList<String>()
        lines += "CATDRY categoriesBefore ${before.planning.categories.size} spendBefore ${before.spend()} methodLikeBefore ${before.methodLike()} " +
            "agentMethodCategories ${CategoryRepair.agentMethodCategories(before).size} agentAssignedBefore ${before.agentAssigned()}"
        val statesBefore = before.installmentPaymentStates()
        lines += "CATDRY installmentPayments ${before.installmentPaymentIds().size} statesBefore inherited=${statesBefore.values.count { it is InstallmentPaymentState.Inherited }} " +
            "toClassify=${statesBefore.values.count { it is InstallmentPaymentState.ToClassify }}"
        val r = CategoryRepair.run(before, "2026-11-01T00:00:00Z")
        if (r == null) lines += "CATDRY repair nothing-to-do"
        else {
            val after = r.archive
            val states = after.installmentPaymentStates()
            lines += "CATDRY categoriesAfter ${after.planning.categories.size} spendAfter ${after.spend()} methodLikeAfter ${after.methodLike()} " +
                "removedCategories ${r.record.removedCategories.size} removedAgentRules ${r.record.removedRules.size}"
            lines += "CATDRY movementsReset ${r.record.count} fromMethodCategories ${r.record.fromMethodCategories} fromInstallmentParts ${r.record.fromInstallmentParts} " +
                "agentAssignedAfter ${after.agentAssigned()} userSetUnchanged ${before.externalMovements.filter { it.categorySource != null && it.categorySource != CategorySource.AGENT } == after.externalMovements.filter { it.categorySource != null && it.categorySource != CategorySource.AGENT }}"
            lines += "CATDRY statesAfter inherited=${states.values.count { it is InstallmentPaymentState.Inherited }} toClassify=${states.values.count { it is InstallmentPaymentState.ToClassify }}"
            lines += "CATDRY idempotent ${CategoryRepair.run(after, "2026-11-01T00:00:01Z") == null} " +
                "undoRestoresMovements ${CategoryRepair.undo(after, r.record, "2026-11-01T00:00:02Z").externalMovements == before.externalMovements}"
            lines += "CATDRY wouldSeedDefaultsAfterRepair ${after.withDefaultSpendCategories("2026-11-01T00:00:03Z") !== after}"
        }
        lines.forEach(::println)
        runCatching { File("build/cat-dryrun-counts.txt").writeText(lines.joinToString("\n", postfix = "\n")) }
    }
}
