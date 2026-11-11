package xyz.grtlabs.pilastri.data

import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals

class UpdateSnapshotTest {
    private class Mem(var value: String? = null) : SnapshotMarker {
        override fun get() = value
        override fun set(value: String) { this.value = value }
    }

    private fun dir(): File = java.nio.file.Files.createTempDirectory("snap").toFile().also { it.deleteOnExit() }
    private fun snaps(d: File) = File(d, "snapshots").listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }

    private fun setup(d: File): List<File> {
        val base = File(d, "portfolio-v1.json").apply { writeBytes(byteArrayOf(1, 2, 3, 0, 127, -5)) }
        val bak = File(d, "portfolio-v1.json.bak").apply { writeBytes("bak".toByteArray()) }
        return listOf(base, bak, File(d, "portfolio-v1.json.new"))
    }

    @Test fun updateSnapshotsOnceBeforeFirstWriteWithIdenticalBytes() {
        val d = dir(); val files = setup(d); val marker = Mem()
        val before = files.filter { it.exists() }.associate { it.name to it.readBytes() }
        UpdateSnapshot(d, files, "100-1", marker).ensure()
        // the "first write" happens after ensure(); the snapshot already holds the old bytes
        files[0].writeBytes(byteArrayOf(9))
        val s = snaps(d)
        assertEquals(1, s.size)
        assertEquals(before.keys, s[0].listFiles()!!.map { it.name }.toSet())
        before.forEach { (n, b) -> assertArrayEquals(b, File(s[0], n).readBytes()) }
        assertTrue(s[0].name.endsWith("-u100-1"))
        assertEquals("100-1", marker.value)
    }

    @Test fun sameUpdateDoesNotSnapshotAgain() {
        val d = dir(); val files = setup(d); val marker = Mem()
        UpdateSnapshot(d, files, "100-1", marker).ensure()
        UpdateSnapshot(d, files, "100-1", marker).ensure() // second launch
        assertEquals(1, snaps(d).size)
    }

    @Test fun failedCopyLeavesNoMarkerAndRetries() {
        val d = dir(); val files = setup(d); val marker = Mem(); val logs = mutableListOf<String>()
        val before = files[0].readBytes()
        File(d, "snapshots").writeText("not a directory") // makes the copy impossible
        UpdateSnapshot(d, files, "100-1", marker, { logs += it }).ensure()
        assertNull(marker.value)
        assertEquals(1, logs.size)
        assertArrayEquals(before, files[0].readBytes())
        File(d, "snapshots").delete()
        UpdateSnapshot(d, files, "100-1", marker).ensure() // next launch
        assertEquals("100-1", marker.value)
        assertEquals(1, snaps(d).size)
    }

    @Test fun pruningKeepsFive() {
        val d = dir(); val files = setup(d); val marker = Mem(); var t = 1_700_000_000_000L
        for (i in 1..8) { t += 2_000_000; UpdateSnapshot(d, files, "u$i", marker, now = { t }).ensure() }
        val s = snaps(d)
        assertEquals(5, s.size)
        assertTrue(s.last().name.endsWith("-uu8") && s.first().name.endsWith("-uu4"))
    }

    @Test fun freshInstallDoesNotSnapshotButWritesMarker() {
        val d = dir(); val marker = Mem()
        UpdateSnapshot(d, listOf(File(d, "portfolio-v1.json")), "100-1", marker).ensure()
        assertEquals(0, snaps(d).size)
        assertEquals("100-1", marker.value)
    }

    @Test fun snapshotsFolderIsNeverPartOfTheCopiedFiles() {
        val d = dir(); val files = setup(d); val marker = Mem()
        UpdateSnapshot(d, files, "1", marker).ensure()
        UpdateSnapshot(d, files, "2", marker).ensure()
        // the second snapshot holds only archive files, not the first snapshot folder
        snaps(d).forEach { s -> assertTrue(s.listFiles()!!.none { it.isDirectory || it.name == "snapshots" }) }
        assertTrue(files.none { it.name == "snapshots" })
    }
}
