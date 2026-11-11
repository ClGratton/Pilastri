package xyz.grtlabs.pilastri.sync.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.ImportProvider

class SessionCookiePersistenceTest {
    /** [header]/[apexHeader]: what getCookie returns for www and the bare domain; [info]: getCookieInfo for www (null: unsupported). */
    private class FakeJar(val info: List<String>?, val header: String? = null, val apexHeader: String? = null) : CookieJar {
        val set = mutableListOf<Pair<String, String>>()
        var flushed = 0
        override fun cookieInfo(url: String) = info
        override fun cookieHeader(url: String) = if (url.startsWith("https://www.")) header else apexHeader
        override fun setCookie(url: String, cookie: String) { set += url to cookie }
        override fun flush() { flushed++ }
    }
    private class CountingCipher(val inner: CredentialCipher = SoftwareCipher()) : CredentialCipher {
        var encrypts = 0
        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray { encrypts++; return inner.encrypt(plain, aad) }
        override fun decrypt(blob: ByteArray, aad: ByteArray) = inner.decrypt(blob, aad)
    }
    private val quiet = { _: String -> }
    private val age = SessionCookieConfig.MAX_AGE_SECONDS
    private val www = "https://www.paypal.com/"

    private fun snapshots(cipher: CredentialCipher = SoftwareCipher(), store: SecretStore = MemorySecretStore()) = SessionSnapshots(store, cipher)

    @Test fun aSessionCookieKeepsItsAttributesAndGainsMaxAge() {
        val line = "id_token=v1; Domain=.paypal.com; Path=/; Secure; HttpOnly; SameSite=None"
        val jar = FakeJar(listOf(line, "ts=b; Path=/; Max-Age=600"))
        assertEquals(1, SessionCookiePersistence.persist(ProviderSites.PAYPAL, jar, quiet))
        assertEquals(www to "$line; Max-Age=$age", jar.set.single())
        assertEquals(1, jar.flushed)
    }

    @Test fun cookiesWithAnExpiryAreNeverTouched() {
        val jar = FakeJar(listOf(
            "datadome=a; Domain=.paypal.com; Path=/; Secure; Max-Age=600",
            "ts=b; Domain=.paypal.com; Path=/; Expires=Wed, 01 Jan 2031 00:00:00 GMT; Secure",
            "nsid=c; Path=/; Secure; HttpOnly",
        ))
        assertEquals(1, SessionCookiePersistence.persist(ProviderSites.PAYPAL, jar, quiet))
        assertEquals(listOf("nsid=c; Path=/; Secure; HttpOnly; Max-Age=$age"), jar.set.map { it.second })
    }

    @Test fun withoutCookieAttributesNoMaxAgeIsWrittenButTheSnapshotIs() {
        val snaps = snapshots()
        val jar = FakeJar(null, header = "TLTSID=a; nsid=b; x-pp-s=c", apexHeader = "TLTSID=a; x-pp-s=c")
        val lines = mutableListOf<String>()
        assertEquals(0, SessionCookiePersistence.persist(ProviderSites.PAYPAL, jar, lines::add, snapshots = snaps))
        assertTrue(jar.set.isEmpty() && jar.flushed == 0)
        assertEquals(
            listOf(
                "TLTSID=a; Path=/; Secure; Domain=.paypal.com",
                "nsid=b; Path=/; Secure; HttpOnly",
                "x-pp-s=c; Path=/; Secure; Domain=.paypal.com; HttpOnly",
            ),
            snaps.load(ImportProvider.PAYPAL),
        )
    }

    @Test fun whenNoCookieShowsAnExpiryTheSnapshotPathDecides() {
        val snaps = snapshots()
        val jar = FakeJar(listOf("a=1; Path=/; Secure", "b=2; Path=/; Secure"), header = "a=1; b=2", apexHeader = "")
        assertEquals(0, SessionCookiePersistence.persist(ProviderSites.PAYPAL, jar, quiet, snapshots = snaps))
        assertTrue(jar.set.isEmpty())
        assertEquals(listOf("a=1; Path=/; Secure", "b=2; Path=/; Secure"), snaps.load(ImportProvider.PAYPAL))
    }

    @Test fun restoreReAddsExactlyTheMissingNamesAndLeavesPresentOnes() {
        val snaps = snapshots()
        snaps.save(ImportProvider.PAYPAL, listOf("TLTSID=a; Path=/; Secure; Domain=.paypal.com", "nsid=b; Path=/; Secure; HttpOnly", "tcs=t; Path=/; Secure"))
        // nsid survived (and holds a newer value); TLTSID is present on the apex only; tcs was dropped by the restart.
        val jar = FakeJar(null, header = "nsid=NEWER; TLTSID=a", apexHeader = "TLTSID=a")
        val lines = mutableListOf<String>()
        assertEquals(1, SessionCookiePersistence.restore(ProviderSites.PAYPAL, jar, snaps, lines::add))
        assertEquals(listOf(www to "tcs=t; Path=/; Secure"), jar.set)
        assertEquals(1, jar.flushed)
        assertTrue(lines.single().contains("restored 1 of 3"))
    }

    @Test fun restoreWithNothingMissingWritesNothing() {
        val snaps = snapshots()
        snaps.save(ImportProvider.PAYPAL, listOf("a=1; Path=/; Secure"))
        val jar = FakeJar(null, header = "a=1")
        assertEquals(0, SessionCookiePersistence.restore(ProviderSites.PAYPAL, jar, snaps, quiet))
        assertTrue(jar.set.isEmpty() && jar.flushed == 0)
        assertEquals(0, SessionCookiePersistence.restore(ProviderSites.PAYPAL, jar, null, quiet))
    }

    @Test fun theSnapshotIsEncryptedAndNeverStoredInTheClear() {
        val cipher = CountingCipher()
        val store = MemorySecretStore()
        val snaps = snapshots(cipher, store)
        val jar = FakeJar(null, header = "nsid=SECRETVALUE123", apexHeader = "")
        SessionCookiePersistence.persist(ProviderSites.PAYPAL, jar, quiet, snapshots = snaps)
        assertEquals(1, cipher.encrypts)
        val raw = store.get("session.PAYPAL")!!
        assertFalse(raw.contains("SECRETVALUE") || raw.contains("nsid"))
        assertTrue(snaps.load(ImportProvider.PAYPAL).single().startsWith("nsid=SECRETVALUE123"))
    }

    @Test fun aSnapshotBoundToAnotherProviderOrKeyReadsAsEmpty() {
        val store = MemorySecretStore()
        snapshots(store = store).save(ImportProvider.PAYPAL, listOf("a=1"))
        assertTrue(SessionSnapshots(store, SoftwareCipher(ByteArray(32) { 9 })).load(ImportProvider.PAYPAL).isEmpty())
        store.put("session.ISYBANK", store.get("session.PAYPAL")!!)
        assertTrue(snapshots(store = store).load(ImportProvider.ISYBANK).isEmpty())
    }

    @Test fun anEmptyJarKeepsTheLastGoodSnapshot() {
        val snaps = snapshots()
        snaps.save(ImportProvider.PAYPAL, listOf("a=1; Path=/; Secure"))
        SessionCookiePersistence.persist(ProviderSites.PAYPAL, FakeJar(null, header = null), quiet, snapshots = snaps)
        assertEquals(listOf("a=1; Path=/; Secure"), snaps.load(ImportProvider.PAYPAL))
    }

    @Test fun forgetDeletesTheSnapshotAndDisconnectCallsIt() {
        val store = MemorySecretStore()
        val snaps = snapshots(store = store)
        snaps.save(ImportProvider.PAYPAL, listOf("a=1"))
        snaps.forget(ImportProvider.PAYPAL)
        assertTrue(store.get("session.PAYPAL") == null && snaps.load(ImportProvider.PAYPAL).isEmpty())
        val src = syncDir().resolve("auto/AutoSyncKit.kt").readText().replace("\r\n", "\n")
        assertTrue(src.contains("SessionSnapshots.createOrNull(context)?.forget(provider)"))
    }

    @Test fun onlyPaypalOptsIn() {
        assertTrue(ProviderSites.PAYPAL.persistSession)
        assertFalse(ProviderSites.ISYBANK.persistSession)
        val jar = FakeJar(listOf("a=1; Path=/"), header = "a=1")
        val snaps = snapshots()
        assertEquals(0, SessionCookiePersistence.persist(ProviderSites.ISYBANK, jar, quiet, snapshots = snaps))
        assertTrue(jar.set.isEmpty() && snaps.load(ImportProvider.ISYBANK).isEmpty())
        snaps.save(ImportProvider.ISYBANK, listOf("a=1"))
        assertEquals(0, SessionCookiePersistence.restore(ProviderSites.ISYBANK, FakeJar(null, header = ""), snaps, quiet))
    }

    @Test fun valuesNeverReachTheLog() {
        val snaps = snapshots()
        val jar = FakeJar(listOf("tok=SECRETVALUE123; Path=/; Secure", "x=ANOTHERSECRET; Path=/; Max-Age=5"), header = "tok=SECRETVALUE123; x=ANOTHERSECRET")
        val lines = mutableListOf<String>()
        SessionCookiePersistence.persist(ProviderSites.PAYPAL, jar, lines::add, snapshots = snaps)
        SessionCookiePersistence.restore(ProviderSites.PAYPAL, FakeJar(null, header = ""), snaps, lines::add)
        SessionCookiePersistence.logRestore("PAYPAL", true, lines::add)
        SessionCookiePersistence.logRestore("PAYPAL", false, lines::add)
        assertTrue(lines.isNotEmpty() && lines.none { it.contains("SECRETVALUE") || it.contains("ANOTHERSECRET") })
        assertTrue(lines.any { it.endsWith("restored session: expired") } && lines.any { it.endsWith("restored session: valid") })
    }

    @Test fun restoreRunsBeforeAnyProviderPageLoads() {
        val src = syncDir().resolve("auto/AutoSyncKit.kt").readText().replace("\r\n", "\n")
        assertTrue(src.contains("restoreSessions(context)\n        val c = coordinator(context)\n        HiddenWebHost.install"))
        assertTrue(src.contains("AutoSyncKit.restoreSessions(context)\n        val fetcher"))
        assertTrue(src.contains("provider.also { restoreSessions(context) }"))
    }

    @Test fun disconnectExpiresRootPathCookiesOnHostAndDomain() {
        val src = syncDir().resolve("paypal/PaypalConnector.kt").readText()
        assertTrue(src.contains("listOf(null, host, \".\$root\")") && src.contains("val paths = listOf(\"/\""))
        assertTrue(src.contains("Max-Age=0"))
    }

    private fun syncDir() = listOf(java.io.File("src/main/java/xyz/grtlabs/pilastri/sync"), java.io.File("app/src/main/java/xyz/grtlabs/pilastri/sync")).first { it.isDirectory }
}
