package xyz.grtlabs.pilastri.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Q5: on return to the wait page the clip DESCRIPTION decides before any content read (so an unrelated clip raises no "pasted from clipboard" notice). */
class EbClipDescriptionTest {
    private class Fake(var info: ClipInfo?, val body: String) : ClipboardSource {
        var textReads = 0
        override fun describe() = info
        override fun text(): CharSequence? { textReads++; return body }
    }
    private val sentAt = 1_000_000L
    private val link = "https://enablebanking.com/auth_redirect?code=abc&state=xyz"

    @Test fun anUnclassifiedFreshTextClipIsTakenAndReadOnce() {
        val c = Fake(ClipInfo(true, sentAt + 5), link)
        PasteRules.freshLink(c, sentAt)   // validity of the link text itself is covered by EbPasteTest
        assertEquals(1, c.textReads)
    }

    @Test fun aClipTheSystemClassifiedAsNotAUrlIsNeverRead() {
        val c = Fake(ClipInfo(true, sentAt + 5, urlScore = 0.1f), link)
        assertNull(PasteRules.freshLink(c, sentAt)); assertEquals(0, c.textReads)
    }

    @Test fun oldOrNonTextOrNoRequestClipsAreNeverRead() {
        for (info in listOf(ClipInfo(true, sentAt - 1), ClipInfo(false, sentAt + 5), ClipInfo(true, sentAt))) {
            val c = Fake(info, link); assertNull(PasteRules.freshLink(c, sentAt)); assertEquals(0, c.textReads)
        }
        val c = Fake(ClipInfo(true, sentAt + 5), link); assertNull(PasteRules.freshLink(c, null)); assertEquals(0, c.textReads)
    }
}
