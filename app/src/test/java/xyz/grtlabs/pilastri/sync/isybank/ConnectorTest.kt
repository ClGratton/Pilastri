package xyz.grtlabs.pilastri.sync.isybank

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

class ConnectorTest {
    private val batch = ImportBatch(ImportProvider.ISYBANK, "isybank", Instant.parse("2026-03-29T10:00:00Z"), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 28), emptyList())

    @Test fun resultCodesMapToConnectorResults() {
        val token = ImportHandoff.put(batch)
        assertEquals(ConnectorResult.Imported(batch), IsybankConnector.resultFor(android.app.Activity.RESULT_OK, token, null))
        assertTrue("a token is single use", IsybankConnector.resultFor(android.app.Activity.RESULT_OK, token, null) is ConnectorResult.Failed)
        assertTrue(IsybankConnector.resultFor(android.app.Activity.RESULT_OK, null, null) is ConnectorResult.Failed)
        assertEquals(ConnectorResult.Failed("Prova"), IsybankConnector.resultFor(IsybankConnector.RESULT_FAILED, null, "Prova"))
        assertTrue(IsybankConnector.resultFor(IsybankConnector.RESULT_FAILED, null, null) is ConnectorResult.Failed)
        assertEquals(ConnectorResult.SessionExpired, IsybankConnector.resultFor(IsybankConnector.RESULT_SESSION_EXPIRED, null, null))
        assertEquals(ConnectorResult.Cancelled, IsybankConnector.resultFor(android.app.Activity.RESULT_CANCELED, null, null))
    }

    @Test fun filePickerPathReturnsImportedOrAnItalianFailure() {
        val csv = "Data;Descrizione;Importo\n27/03/2026;Bar;-1,50\n".toByteArray()
        val imported = IsybankConnector.parseFile(csv, "movimenti.csv") as ConnectorResult.Imported
        assertEquals(-150L, imported.batch.movements.single().signedCents)
        val failed = IsybankConnector.parseFile("niente".toByteArray(), "x.csv") as ConnectorResult.Failed
        assertTrue(failed.message, failed.message.contains("isybank"))
    }

    @Test fun onlyBankHostsReceiveCookiesAndDownloads() {
        assertTrue(IsybankConnector.isBankHost("www.isybank.com"))
        assertTrue(IsybankConnector.isBankHost("ISYBANK.com."))
        assertTrue(IsybankConnector.isBankHost("login.intesasanpaolo.com"))
        assertFalse(IsybankConnector.isBankHost("isybank.com.evil.example"))
        assertFalse(IsybankConnector.isBankHost("evilisybank.com"))
        assertFalse(IsybankConnector.isBankHost("example.com"))
        assertFalse(IsybankConnector.isBankHost(null))
    }

    @Test fun loginUrlIsHttpsOnTheIsybankHost() {
        assertTrue(IsybankConnector.LOGIN_URL.startsWith("https://www.isybank.com/"))
    }
}
