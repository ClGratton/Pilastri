package xyz.grtlabs.pilastri.ui.setup

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.PositionKind

/** Synthetic notifications and channels only. */
private fun suspendNull(f: suspend () -> String?): suspend () -> String? = f

class EbLinkChannelsTest {
    private val link = "https://enablebanking.com/auth/magic?token=synthetic9"

    @Test fun readsTheLinkFromAMailNotificationOfEnableBanking() {
        assertEquals(link, EbNotificationLink.extract("com.google.android.gm", null, "Enable Banking", listOf("Accedi: $link.", null)))
        // a mail app unknown to the list is accepted through the e-mail category
        assertEquals(link, EbNotificationLink.extract("org.example.mail", "email", "Sign in", listOf("da no-reply@enablebanking.com", "Apri $link")))
        // lines extra (inbox style) and big text
        assertEquals(link, EbNotificationLink.extract("com.microsoft.office.outlook", null, null, listOf("Enable Banking", "x", link)))
    }

    @Test fun ignoresOtherAppsOtherSendersAndForeignLinks() {
        assertNull(EbNotificationLink.extract("org.example.chat", "msg", "Enable Banking", listOf(link)))        // not a mail app
        assertNull(EbNotificationLink.extract("com.google.android.gm", null, "Mario Rossi", listOf(link)))        // another sender
        assertNull(EbNotificationLink.extract("com.google.android.gm", null, "Enable Banking", listOf("https://evil.example/enablebanking.com/auth")))
        assertNull(EbNotificationLink.extract("com.google.android.gm", null, "Enable Banking", listOf("nessun link", null)))
    }

    @Test fun inboxIgnoresEverythingUnlessTheStepIsWaiting() = runBlocking {
        EbLinkInbox.waiting = false; EbLinkInbox.reset()
        assertFalse(EbLinkInbox.offer(link))
        EbLinkInbox.waiting = true
        assertTrue(EbLinkInbox.offer(link))
        assertEquals(link, EbLinkInbox.next())
        EbLinkInbox.waiting = false
    }

    @Test fun listenerGateIsSilentWithoutAWaitAndCountsNonMailDuringOne() {
        val lines = mutableListOf<String>()
        EbLinkInbox.waiting = false; EbLinkInbox.sink = { lines += it }
        try {
            assertFalse(EbLinkInbox.admit(false)); assertFalse(EbLinkInbox.admit(true))
            assertTrue(lines.isEmpty())
            EbLinkInbox.beginWait()
            assertFalse(EbLinkInbox.admit(false)); assertFalse(EbLinkInbox.admit(false))
            assertTrue(EbLinkInbox.admit(true))
            EbLinkInbox.endWait("timeout"); EbLinkInbox.endWait("cancel")
            assertEquals(listOf("link wait start", "link wait end outcome=timeout nonMailNotifications=2"), lines)
        } finally { EbLinkInbox.waiting = false; EbLinkInbox.sink = { xyz.grtlabs.pilastri.sync.eb.EbLog.line(xyz.grtlabs.pilastri.sync.eb.EbLog.MAIL, it) } }
    }

    @Test fun theFirstChannelWithALinkWinsTheRace() = runBlocking {
        val slow: suspend () -> String? = { delay(300); "https://enablebanking.com/auth/slow" }
        val fast: suspend () -> String? = { delay(20); link }
        val none = suspendNull { null }
        assertEquals(link, EbLinkRace.await(2_000, listOf(slow, none, fast)))
    }

    @Test fun noLinkWithinTheTimeoutLeadsToTheInstructionScreen() = runBlocking {
        assertEquals(45_000L, EbLinkRace.TIMEOUT_MS)
        val never = suspendNull { delay(10_000); null }
        val nothing = suspendNull { null }
        assertNull(EbLinkRace.await(150, listOf(never, nothing))) // null = the page opens EbLinkInstructions
        assertEquals(4, EbLinkHelp.STEPS.size)
        assertTrue(EbLinkHelp.STEPS[2].contains("Copia link"))
    }

    @Test fun theLinkAndTheMessageAreNeverLogged() {
        val root = File("src/main/java/xyz/grtlabs/pilastri").takeIf { it.isDirectory } ?: File("app/src/main/java/xyz/grtlabs/pilastri")
        val names = listOf("ui/setup/EbLinkChannels.kt", "ui/setup/EbLinkHelp.kt", "ui/setup/EbSignInLink.kt", "sync/gmail/EbMagicLink.kt", "ui/setup/SetupWeb.kt")
        val bad = names.flatMap { n -> File(root, n).readLines().withIndex().filter { (_, l) -> Regex("""\b(Log\.[vdiwe]|println|Timber\.|Logger\.)""").containsMatchIn(l) }.map { (i, _) -> "$n:${i + 1}" } }
        assertTrue("No logging in the link path: $bad", bad.isEmpty())
    }
}

class MailAccessStepTest {
    @Test fun mailAccessIsOfferedOnceRightBeforeScegliBanca() {
        val m0 = SetupModel(entries = listOf(SetupEntry.Step(SetupStep.BENVENUTO), SetupEntry.Step(SetupStep.POSIZIONE)))
        val m1 = SetupRules.skipPositions(m0)
        assertEquals(SetupEntry.MailAccess, m1.top)
        assertEquals(SetupStep.SCEGLI_BANCA, m1.step)
        assertEquals(4, SetupRules.progress(m1).first)
        val m2 = SetupRules.mailDone(m1)
        assertEquals(SetupEntry.Step(SetupStep.SCEGLI_BANCA), m2.top)
        // back from Scegli banca and forward again: not offered twice
        val m3 = SetupRules.positionsDone(SetupRules.back(m2).copy(positions = listOf(PositionKind.entries.first())))
        assertEquals(SetupEntry.Step(SetupStep.SCEGLI_BANCA), m3.top)
    }

    @Test fun backFromTheMailPageLandsOnScegliBanca() {
        val m = SetupRules.skipPositions(SetupModel(entries = listOf(SetupEntry.Step(SetupStep.BENVENUTO), SetupEntry.Step(SetupStep.POSIZIONE))))
        assertEquals(SetupEntry.Step(SetupStep.SCEGLI_BANCA), SetupRules.back(m).top)
    }

    @Test fun aBankRunFromSettingsSkipsTheOffer() {
        assertEquals(SetupEntry.Step(SetupStep.SCEGLI_BANCA), SetupModel(entries = listOf(SetupEntry.Step(SetupStep.BENVENUTO), SetupEntry.Step(SetupStep.SCEGLI_BANCA))).top)
    }
}
