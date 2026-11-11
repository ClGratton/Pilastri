package xyz.grtlabs.pilastri.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Counts-only dry run of the v13 → v14 migration on a real archive. Skipped unless the environment variable
 * PILASTRI_DRYRUN_FILE names an archive file (the app's portfolio-v1.json). It reads the bytes, decodes and migrates, encodes v14,
 * decodes again and prints one line per collection, `DRYRUN name before after`, plus the stored version. It never prints values,
 * names, ids, dates or labels, and failures report only an exception class or a collection name. The file is only read.
 *
 * Run (Gradle skips an up-to-date test task, hence --rerun):
 *   PILASTRI_DRYRUN_FILE=/path/to/portfolio-v1.json tools/gw.sh :app:testDebugUnitTest --tests "*RealArchiveDryRunTest*" --rerun
 * Output: the test report's system-out (app/build/test-results/testDebugUnitTest/TEST-*RealArchiveDryRunTest.xml)
 * and app/build/dryrun-counts.txt.
 */
class RealArchiveDryRunTest {
    @Test fun realArchiveMigratesWithEveryCollectionCountPreserved() {
        val path = System.getenv("PILASTRI_DRYRUN_FILE")
        assumeTrue("PILASTRI_DRYRUN_FILE not set", !path.isNullOrBlank())
        val bytes = File(path!!).readBytes()
        val version = PremigrationCopy.storedVersion(bytes)
        fun <T> step(name: String, block: () -> T): T = try { block() } catch (t: Throwable) {
            fail("$name failed: ${t.javaClass.simpleName}"); throw IllegalStateException()
        }
        val payload = step("read payload") { V13.payloadOf(bytes) }
        val migrated = step("decode and migrate") { ArchiveCodec.decode(bytes) }
        val v14 = step("encode v14") { ArchiveCodec.encode(migrated) }
        val again = step("decode v14") { ArchiveCodec.decode(v14) }

        val before = ArchiveCounts.before(payload)
        val after = ArchiveCounts.after(migrated)
        val lines = listOf("storedVersion $version", "writtenVersion ${PremigrationCopy.storedVersion(v14)}") + ArchiveCounts.lines(before, after)
        lines.forEach { println("DRYRUN $it") }
        runCatching { File("build/dryrun-counts.txt").writeText(lines.joinToString("\n", postfix = "\n")) }

        ArchiveCounts.PRESERVED.forEach { name -> assertEquals("count of $name", before[name], after[name]) }
        if (version != null && version < 14) {
            assertEquals("positions created", 0, after["positions"])
            assertEquals("funds = legacy fund accounts", before["legacyFundAccounts"], after["funds"])
        }
        // Equality only; a failure must not print the archive.
        assertTrue("v14 re-decode differs from the migrated archive", again == migrated)
        assertEquals("counts after re-decode", after, ArchiveCounts.after(again))
    }
}
