package xyz.grtlabs.pilastri.data

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Counts-only dry run of [withPaypalInstallmentPayments] on a real archive. Skipped unless PILASTRI_DRYRUN_FILE names an archive file.
 * Prints `INSTDRY ...` lines holding counts only (plans, parts, paid parts, linked rows): never names, amounts, dates or ids. The file is only read.
 *
 *   PILASTRI_DRYRUN_FILE=/path/to/portfolio-v1.json tools/gw.sh :app:testDebugUnitTest --tests "*RealArchiveInstallmentDryRunTest*" --rerun
 */
class RealArchiveInstallmentDryRunTest {
    @Test fun countsBeforeAndAfterTheInstallmentMatch() {
        val path = System.getenv("PILASTRI_DRYRUN_FILE")
        assumeTrue("PILASTRI_DRYRUN_FILE not set", !path.isNullOrBlank())
        val before = ArchiveCodec.decode(File(path!!).readBytes())
        val after = before.withPaypalInstallmentPayments("2026-11-01T00:00:00Z")
        val lines = ArrayList<String>()
        lines += "INSTDRY plans ${before.installments.size} paypalMovements ${before.externalMovements.count { it.provider == ImportProvider.PAYPAL }}"
        lines += "INSTDRY paidBefore ${before.installments.sumOf { it.paidCount() }} paidAfter ${after.installments.sumOf { it.paidCount() }}"
        lines += "INSTDRY linkedMovementsAfter ${after.installments.sumOf { p -> p.installments.count { it.movementId != null } }}"
        before.installments.sortedBy { it.id }.forEachIndexed { i, p ->
            val a = after.installments.first { it.id == p.id }
            lines += "INSTDRY plan$i count=${p.count} partsBefore=${p.installments.size} paidBefore=${p.paidCount()} partsAfter=${a.installments.size} paidAfter=${a.paidCount()} remainingUnknownAfter=${a.remainingUnknown}"
        }
        lines.forEach(::println)
        runCatching { File("build/inst-dryrun-counts.txt").writeText(lines.joinToString("\n", postfix = "\n")) }
    }
}
