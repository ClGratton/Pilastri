package xyz.grtlabs.pilastri.sync.tr

import java.math.BigDecimal
import java.net.URLEncoder
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrProtocolTest {
    // ---- frames ----------------------------------------------------------------------------------------------------

    @Test fun `parses connected, full, delta, closed and error frames`() {
        assertEquals(TrFrame.Connected, TrProtocol.parseFrame("connected"))
        assertEquals(TrFrame.Full(1, """{"a":1}"""), TrProtocol.parseFrame("""1 A {"a":1}"""))
        assertEquals(TrFrame.Delta(12, "=3\t+x"), TrProtocol.parseFrame("12 D =3\t+x"))
        assertEquals(TrFrame.Closed(4), TrProtocol.parseFrame("4 C"))
        assertEquals(TrFrame.Closed(4), TrProtocol.parseFrame("4 C "))
        assertEquals(TrFrame.Failed(9, """{"errors":[]}"""), TrProtocol.parseFrame("""9 E {"errors":[]}"""))
    }

    @Test fun `payload loses leading python whitespace like pytr lstrip`() {
        assertEquals(TrFrame.Full(3, """{"a":1}"""), TrProtocol.parseFrame("3 A    {\"a\":1}"))
        // a delta whose first token would be empty loses the leading tab, exactly as pytr's lstrip() does
        assertEquals(TrFrame.Delta(3, "=2"), TrProtocol.parseFrame("3 D \t=2"))
    }

    @Test fun `unexpected frames become Unknown and keep only their length`() {
        assertEquals(TrFrame.Unknown(5), TrProtocol.parseFrame("hello"))
        assertEquals(TrFrame.Unknown(9), TrProtocol.parseFrame("x A {\"a\"}"))
        assertEquals(TrFrame.Unknown(1), TrProtocol.parseFrame("5"))
        assertEquals(TrFrame.Unknown(7), TrProtocol.parseFrame("7 Z foo"))
        assertEquals(TrFrame.Unknown(0), TrProtocol.parseFrame(""))
    }

    @Test fun `connect frame matches pytr and the protocol number is configurable`() {
        assertEquals(
            """connect 31 {"locale":"it","platformId":"webtrading","platformVersion":"chrome - 94.0.4606","clientId":"app.traderepublic.com","clientVersion":"5582"}""",
            TrProtocol.connectFrame(TrConfig()),
        )
        assertTrue(TrProtocol.connectFrame(TrConfig(protocol = 34, locale = "de")).startsWith("""connect 34 {"locale":"de","""))
    }

    @Test fun `subscription frames and payloads are exact`() {
        assertEquals("""sub 7 {"type":"timelineTransactions","after":null}""", TrProtocol.subscribeFrame(7, TrProtocol.timelineTransactions(null)))
        assertEquals("""{"type":"timelineActivityLog","after":"abc"}""", TrProtocol.timelineActivityLog("abc"))
        assertEquals("""{"type":"timelineActivityLog","after":"a\"b"}""", TrProtocol.timelineActivityLog("a\"b"))
        assertEquals("""{"type":"timelineDetailV2","id":"u-1"}""", TrProtocol.timelineDetail("u-1"))
        assertEquals("""{"type":"compactPortfolioByType","secAccNo":"S1"}""", TrProtocol.compactPortfolio("S1"))
        assertEquals("""{"type":"cash"}""", TrProtocol.cash())
        assertEquals("""{"type":"instrument","id":"ZZ0000000011"}""", TrProtocol.instrument("ZZ0000000011"))
        assertEquals("""{"type":"ticker","id":"ZZ0000000011.LSX"}""", TrProtocol.ticker("ZZ0000000011", "LSX"))
        assertEquals("unsub 7", TrProtocol.unsubscribeFrame(7))
    }

    @Test fun `every payload builder uses a read-only type`() {
        val all = listOf(
            TrProtocol.timelineTransactions(null), TrProtocol.timelineActivityLog(null), TrProtocol.timelineDetail("x"),
            TrProtocol.compactPortfolio("s"), TrProtocol.cash(), TrProtocol.accountPairs(), TrProtocol.instrument("i"), TrProtocol.ticker("i", "E"),
        )
        all.forEach { assertTrue(it, JSONObject(it).getString("type") in TrProtocol.READ_ONLY_TYPES) }
        assertEquals(all.size, all.map { JSONObject(it).getString("type") }.toSet().size)
    }

    @Test fun `error code is extracted only when it looks like a code`() {
        assertEquals("BAD_SUBSCRIPTION_TYPE", TrProtocol.errorCode("""{"errors":[{"errorCode":"BAD_SUBSCRIPTION_TYPE","errorMessage":"free text"}]}"""))
        assertNull(TrProtocol.errorCode("""{"errors":[{"errorCode":"has spaces and ünïcode"}]}"""))
        assertNull(TrProtocol.errorCode("not json"))
    }

    // ---- delta decoding --------------------------------------------------------------------------------------------

    /** Test-side encoder for the delta grammar. */
    private fun add(text: String) = "+" + URLEncoder.encode(text, "UTF-8")
    private fun delta(vararg tokens: String) = tokens.joinToString("\t")

    @Test fun `delta copies skips and appends`() {
        val prev = """{"a":1,"b":"xy"}"""            // 16 characters
        // copy {"a":1,"b":" (12), skip xy (2), append zzz, copy "} (2)
        val next = TrProtocol.decodeDelta(prev, delta("=12", "-2", add("zzz"), "=2"))
        assertEquals("""{"a":1,"b":"zzz"}""", next)
    }

    @Test fun `delta chains against the previous result`() {
        val first = TrProtocol.decodeDelta("""{"n":1}""", delta("=5", "-1", add("2"), "=1"))
        assertEquals("""{"n":2}""", first)
        val second = TrProtocol.decodeDelta(first, delta("=5", "-1", add("3"), "=1"))
        assertEquals("""{"n":3}""", second)
    }

    @Test fun `delta indexes count code points, not UTF-16 units`() {
        // The emoji is one code point (two UTF-16 units): 10 code points, 11 UTF-16 units. A UTF-16 decoder would keep "x".
        val prev = "{\"n\":\"😀x\"}"
        assertEquals(10, prev.codePointCount(0, prev.length))
        assertEquals(11, prev.length)
        val next = TrProtocol.decodeDelta(prev, delta("=6", "-2", add("😁z"), "=2"))
        assertEquals("{\"n\":\"😁z\"}", next)
    }

    @Test fun `delta copy range spans accented, CJK and astral characters`() {
        val prev = "{\"t\":\"café 日本 🚀!\"}"
        assertEquals(prev, TrProtocol.decodeDelta(prev, "=" + prev.codePointCount(0, prev.length)))
        val head = "{\"t\":\"café 日本 🚀".codePointCount(0, "{\"t\":\"café 日本 🚀".length)
        val next = TrProtocol.decodeDelta(prev, delta("=$head", "-1", add("?"), "=2"))
        assertEquals("{\"t\":\"café 日本 🚀?\"}", next)
    }

    @Test fun `appended text is percent-decoded as UTF-8 and plus is a space`() {
        // %C3%A9 = é, %F0%9F%98%81 = 😁, %2B = +
        assertEquals("é😁", TrProtocol.decodeDelta("", "+%C3%A9%F0%9F%98%81"))
        assertEquals("a b", TrProtocol.decodeDelta("", "+a+b"))
        assertEquals("+1", TrProtocol.decodeDelta("", "+%2B1"))
    }

    @Test fun `appended text is stripped of surrounding whitespace, the pytr quirk`() {
        assertEquals("hello", TrProtocol.decodeDelta("", "+%20hello%20"))
        assertEquals("ab", TrProtocol.decodeDelta("", delta("+a%20", "+%20b")))   // the space the server meant between a and b is lost
        assertEquals("x", TrProtocol.decodeDelta("", "+%C2%A0x%E3%80%80"))        // NBSP and ideographic space are python whitespace too
    }

    @Test fun `delta copy beyond the end is clipped like a python slice`() {
        assertEquals("abc", TrProtocol.decodeDelta("abc", "=99"))
        assertEquals("", TrProtocol.decodeDelta("abc", delta("-5", "=2")))
    }

    @Test fun `malformed deltas raise a protocol error`() {
        assertThrows(TrError.Protocol::class.java) { TrProtocol.decodeDelta("abc", "*2") }
        assertThrows(TrError.Protocol::class.java) { TrProtocol.decodeDelta("abc", "=x") }
        assertThrows(TrError.Protocol::class.java) { TrProtocol.decodeDelta("abc", "=-3") }
        assertThrows(TrError.Protocol::class.java) { TrProtocol.decodeDelta("abc", "=") }
    }

    @Test fun `unquote keeps malformed percent sequences literally`() {
        assertEquals("%zz", TrProtocol.unquotePlus("%zz"))
        assertEquals("100%", TrProtocol.unquotePlus("100%"))
        assertEquals("%4", TrProtocol.unquotePlus("%4"))
        assertEquals("A", TrProtocol.unquotePlus("%41"))
    }

    // ---- time, money, numbers --------------------------------------------------------------------------------------

    @Test fun `timestamp with a colonless offset is read and moved to the Italian day`() {
        assertEquals(LocalDate.of(2025, 10, 23), TrProtocol.parseDate("2025-10-23T08:26:13.657+0000"))
        // 22:30 UTC on 23 Oct 2025 is 00:30 on the 24th in Rome (CEST, UTC+2)
        assertEquals(LocalDate.of(2025, 10, 24), TrProtocol.parseDate("2025-10-23T22:30:00.000+0000"))
        // winter: 23:30 UTC on 31 Dec is 00:30 on 1 Jan in Rome (CET, UTC+1)
        assertEquals(LocalDate.of(2026, 1, 1), TrProtocol.parseDate("2025-12-31T23:30:00.000+0000"))
        // DST ended on 26 Oct 2025 (01:00 UTC): afterwards Rome is UTC+1
        assertEquals(LocalDate.of(2025, 10, 27), TrProtocol.parseDate("2025-10-26T23:30:00.000+0000"))
        assertEquals(LocalDate.of(2025, 10, 26), TrProtocol.parseDate("2025-10-26T22:30:00.000+0000"))
    }

    @Test fun `other timestamp shapes are accepted and garbage is rejected`() {
        assertEquals(LocalDate.of(2025, 6, 3), TrProtocol.parseDate("2025-06-03T15:10:48Z"))
        assertEquals(LocalDate.of(2025, 6, 3), TrProtocol.parseDate("2025-06-03T15:10:48.123456+00:00"))
        assertEquals(LocalDate.of(2025, 6, 3), TrProtocol.parseDate("2025-06-03T15:10:48"))   // no offset: UTC, as pytr
        assertNull(TrProtocol.parseDate("yesterday"))
        assertNull(TrProtocol.parseDate(""))
        assertNull(TrProtocol.parseDate(null))
    }

    @Test fun `cents come from BigDecimal and round half away from zero`() {
        assertEquals(-11100L, TrProtocol.toCents(BigDecimal("-111.00")))
        assertEquals(428L, TrProtocol.toCents(TrProtocol.toBigDecimal(4.28)!!))
        assertEquals(123450L, TrProtocol.toCents(TrProtocol.toBigDecimal(1234.5)!!))
        assertEquals(1L, TrProtocol.toCents(BigDecimal("0.005")))
        assertEquals(-1L, TrProtocol.toCents(BigDecimal("-0.005")))
        assertEquals(0L, TrProtocol.toCents(BigDecimal("0.004")))
        assertEquals(1L, TrProtocol.toCents(BigDecimal("0.0149")))
        assertNull(TrProtocol.toCents(BigDecimal("1E30")))
        assertNull(TrProtocol.toBigDecimal(Double.NaN))
        assertNull(TrProtocol.toBigDecimal("abc"))
        assertEquals(0, BigDecimal("12.5").compareTo(TrProtocol.toBigDecimal("12.5")))
        assertNull(TrProtocol.toBigDecimal(null))
    }

    @Test fun `localized decimals`() {
        fun d(s: String?) = TrProtocol.parseDecimal(s)?.toPlainString()
        assertEquals("0.685102", d("0,685102"))
        assertEquals("10.640298", d("10.640298"))
        assertEquals("1234.56", d("1.234,56 €"))
        assertEquals("1234.56", d("1,234.56"))
        assertEquals("-0.78", d("-0,78 €"))
        assertEquals("-0.78", d("−0,78 €"))
        assertEquals("1000000", d("1.000.000"))
        assertEquals("0.5", d("0,5 × "))
        assertEquals("12", d("12 Azioni"))
        assertEquals("1234.5", d("1 234,5"))
        assertNull(d("Kostenlos"))
        assertNull(d(null))
    }

    // ---- URLs ------------------------------------------------------------------------------------------------------

    @Test fun `only the session and account GETs and the one socket are allowed`() {
        assertTrue(TrUrls.isAllowedGet(TrUrls.SESSION_URL))
        assertTrue(TrUrls.isAllowedGet(TrUrls.ACCOUNT_URL))
        assertFalse(TrUrls.isAllowedGet("https://api.traderepublic.com/api/v1/auth/web/logout"))
        assertFalse(TrUrls.isAllowedGet("https://evil.example/api/v1/auth/web/session"))
        assertFalse(TrUrls.isAllowedGet(TrUrls.SESSION_URL + "?x=1"))
        assertTrue(TrUrls.isAllowedSocket("wss://api.traderepublic.com"))
        assertFalse(TrUrls.isAllowedSocket("wss://evil.example"))
        assertEquals("https://api.traderepublic.com/api/v1/auth/web/session", TrUrls.SESSION_URL)
        assertEquals("https://api.traderepublic.com/api/v2/auth/account", TrUrls.ACCOUNT_URL)
    }

    @Test fun `navigation is limited to https traderepublic pages`() {
        assertTrue(TrUrls.isAllowedNavigation("https://app.traderepublic.com/login"))
        assertTrue(TrUrls.isAllowedNavigation("https://traderepublic.com/it-it"))
        assertFalse(TrUrls.isAllowedNavigation("http://app.traderepublic.com/login"))
        assertFalse(TrUrls.isAllowedNavigation("https://traderepublic.com.evil.example/"))
        assertFalse(TrUrls.isAllowedNavigation("https://eviltraderepublic.com/"))
        assertFalse(TrUrls.isAllowedNavigation("intent://x"))
        assertFalse(TrUrls.isAllowedNavigation(null))
    }

    @Test fun `login detection reads the route, not just the host`() {
        assertEquals(TrUrls.Page.LOGIN, TrUrls.classify("https://app.traderepublic.com/login"))
        assertEquals(TrUrls.Page.LOGIN, TrUrls.classify("https://app.traderepublic.com/it/login?x=1"))
        assertEquals(TrUrls.Page.PRE_LOGIN, TrUrls.classify("https://app.traderepublic.com/signup"))
        assertEquals(TrUrls.Page.ROOT, TrUrls.classify("https://app.traderepublic.com/"))
        assertEquals(TrUrls.Page.ROOT, TrUrls.classify("https://app.traderepublic.com"))
        assertEquals(TrUrls.Page.APP, TrUrls.classify("https://app.traderepublic.com/portfolio"))
        assertEquals(TrUrls.Page.OTHER, TrUrls.classify("https://traderepublic.com/login"))
        assertEquals(TrUrls.Page.OTHER, TrUrls.classify(null))

        assertTrue(TrUrls.looksLoggedIn("https://app.traderepublic.com/portfolio", false))
        assertFalse(TrUrls.looksLoggedIn("https://app.traderepublic.com/login", true))
        assertFalse(TrUrls.looksLoggedIn("https://app.traderepublic.com/", false))
        assertTrue(TrUrls.looksLoggedIn("https://app.traderepublic.com/", true))
    }

    @Test fun `session cookie hint looks at names only`() {
        assertTrue(TrUrls.hasSessionCookie(listOf("aws-waf-token", "tr_session")))
        assertTrue(TrUrls.hasSessionCookie(listOf("TR_REFRESH")))
        assertFalse(TrUrls.hasSessionCookie(listOf("aws-waf-token", "_ga")))
        assertFalse(TrUrls.hasSessionCookie(emptyList()))
    }

    @Test fun `cookie path ancestors cover every scope an API cookie could have`() {
        assertEquals(listOf("/"), TrConnector.ancestorPaths("/"))
        assertEquals(listOf("/"), TrConnector.ancestorPaths(""))
        assertEquals(listOf("/", "/api", "/api/v1", "/api/v1/auth"), TrConnector.ancestorPaths("/api/v1/auth"))
        assertNotNull(TrConnector.ancestorPaths(null))
    }
}
