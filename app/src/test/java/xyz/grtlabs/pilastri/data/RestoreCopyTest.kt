package xyz.grtlabs.pilastri.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Polish 2026-11-10 (owner B): a replace-restore keeps a copy of the archive it replaces. Synthetic data only. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class RestoreCopyTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun copies() = ctx.filesDir.listFiles { f -> f.name.startsWith(LocalArchive.REPLACED_PREFIX) }.orEmpty()

    @Test fun restoreKeepsACopyOfTheReplacedArchive() {
        val storage = LocalArchive(ctx)
        storage.save(Archive(accounts = listOf(Account("acc-1", "Conto sintetico", 1, "fixture"))))
        val before = File(ctx.filesDir, "portfolio-v1.json").readBytes()
        storage.keepCopyBeforeReplace(1_000L)
        storage.save(Archive())
        val kept = copies().single { it.name == "${LocalArchive.REPLACED_PREFIX}1000-portfolio-v1.json" }
        assertTrue(kept.readBytes().contentEquals(before))
    }

    @Test fun replacedCopiesAreBounded() {
        val storage = LocalArchive(ctx)
        storage.save(Archive())
        for (t in 1L..6L) storage.keepCopyBeforeReplace(t * 1_000L)
        val sets = copies().map { it.name.removePrefix(LocalArchive.REPLACED_PREFIX).substringBefore('-') }.toSet()
        assertEquals(setOf("4000", "5000", "6000"), sets)
    }
}
