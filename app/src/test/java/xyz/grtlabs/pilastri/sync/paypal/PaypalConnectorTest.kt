package xyz.grtlabs.pilastri.sync.paypal

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.ImportBatch
import xyz.grtlabs.pilastri.data.ImportProvider
import xyz.grtlabs.pilastri.sync.ConnectorResult
import xyz.grtlabs.pilastri.sync.ImportHandoff
import xyz.grtlabs.pilastri.sync.paypal.PaypalFixtures.Tx
import xyz.grtlabs.pilastri.sync.paypal.PaypalFixtures.id

/** Synthetic data only. The WebView activity itself needs a device and a signed-in PayPal session and is not exercised here. */
class PaypalConnectorTest {
    private val batch = ImportBatch(ImportProvider.PAYPAL, "PayPal", Instant.parse("2026-10-31T10:00:00Z"), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 30), emptyList())

    @Test fun resultCodesMapToConnectorResults() {
        val token = ImportHandoff.put(batch)
        assertEquals(ConnectorResult.Imported(batch), PaypalConnector.resultFor(android.app.Activity.RESULT_OK, token, null))
        assertTrue("a token is single use", PaypalConnector.resultFor(android.app.Activity.RESULT_OK, token, null) is ConnectorResult.Failed)
        assertTrue(PaypalConnector.resultFor(android.app.Activity.RESULT_OK, null, null) is ConnectorResult.Failed)
        assertEquals(ConnectorResult.Failed("Prova"), PaypalConnector.resultFor(PaypalConnector.RESULT_FAILED, null, "Prova"))
        assertTrue(PaypalConnector.resultFor(PaypalConnector.RESULT_FAILED, null, " ") is ConnectorResult.Failed)
        assertEquals(ConnectorResult.SessionExpired, PaypalConnector.resultFor(PaypalConnector.RESULT_SESSION_EXPIRED, null, null))
        assertEquals(ConnectorResult.Cancelled, PaypalConnector.resultFor(android.app.Activity.RESULT_CANCELED, null, null))
    }

    @Test fun filePickerPathReturnsImportedOrAnItalianFailureAndNeverThrows() {
        val csv = PaypalFixtures.en(Tx("05/10/2026", "Express Checkout Payment", "-1.50", id(1), name = "Bar"))
        val imported = PaypalConnector.parseFile(csv, "Download.csv") as ConnectorResult.Imported
        assertEquals(-150L, imported.batch.movements.single().signedCents)
        assertEquals(ImportProvider.PAYPAL, imported.batch.provider)
        val failed = PaypalConnector.parseFile("niente".toByteArray(), "x.csv") as ConnectorResult.Failed
        assertTrue(failed.message, failed.message.contains("PayPal"))
        assertTrue(PaypalConnector.parseFile(ByteArray(0), "x.csv") is ConnectorResult.Failed)
        assertTrue(PaypalConnector.parseFile("%PDF-1.4".toByteArray(), "x.pdf") is ConnectorResult.Failed)
    }

    @Test fun onlyPaypalHostsReceiveCookiesAndDownloadsAndNavigation() {
        assertTrue(PaypalConnector.isPaypalHost("www.paypal.com"))
        assertTrue(PaypalConnector.isPaypalHost("PAYPAL.com."))
        assertTrue(PaypalConnector.isPaypalHost("history.paypal.com"))
        assertFalse(PaypalConnector.isPaypalHost("paypal.com.evil.example"))
        assertFalse(PaypalConnector.isPaypalHost("evilpaypal.com"))
        assertFalse(PaypalConnector.isPaypalHost("paypal.me.evil.example"))
        assertFalse("static assets are never navigated to", PaypalConnector.isPaypalHost("www.paypalobjects.com"))
        assertFalse(PaypalConnector.isPaypalHost("example.com"))
        assertFalse(PaypalConnector.isPaypalHost(null))
    }

    @Test fun entryUrlIsHttpsOnThePaypalHost() {
        assertTrue(PaypalConnector.ENTRY_URL.startsWith("https://www.paypal.com/"))
        assertTrue(PaypalConnector.isPaypalHost(java.net.URI(PaypalConnector.ENTRY_URL).host))
    }

    @Test fun theFilePathIsOfferedButTheWebCaptureIsStillMarkedUnverified() {
        assertTrue(PaypalConnector.available)
        assertFalse("flip only after a person completed a live export through the WebView", PaypalConnector.WEB_CAPTURE_VERIFIED)
    }
}
