package xyz.grtlabs.pilastri.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest
import java.time.LocalDate
import xyz.grtlabs.pilastri.domain.AccountValuation
import xyz.grtlabs.pilastri.domain.Confidence
import xyz.grtlabs.pilastri.domain.EventType
import xyz.grtlabs.pilastri.domain.LedgerEvent
import xyz.grtlabs.pilastri.domain.Money

class AccountClassificationTest {
    private val date = LocalDate.now().minusDays(1)
    // Synthetic records. "Espero" here is only a name: a name never classifies an account.
    private val legacyNamedAccount = Account("a", "Espero", 4, "manual record")
    private val allianzFund = PensionFund("fund:b", Sourced.declared("Savings"))
    /** The shape a v6–v13 account typed ALLIANZ_INSIEME migrates to. */
    private val allianz = Account("b", "Savings", 4, "manual record", AccountType.PENSION_FUND, fundId = allianzFund.id, legacyType = LegacyAccountType.ALLIANZ_INSIEME)
    private fun value(account: Account, date: LocalDate = this.date, cents: Long = 42_000) =
        AccountValuation(account.id, date, Money(cents), "dated statement", Confidence.DOCUMENT)

    private fun archiveAtVersion(version: Int): ByteArray {
        val root = JSONObject(ArchiveCodec.encode(Archive(accounts = listOf(allianz), funds = listOf(allianzFund))) .toString(Charsets.UTF_8))
        val payload = JSONObject(java.util.Base64.getDecoder().decode(root.getString("payload")).toString(Charsets.UTF_8))
        if (version < 8) { payload.remove("instruments"); payload.remove("trades") }
        if (version < 9) payload.remove("transfers")
        if (version < 10) payload.remove("projections")
        if (version < 11) payload.remove("performanceAttestations")
        if (version < 12) { payload.remove("externalMovements"); payload.remove("connections"); payload.remove("planning"); payload.remove("pension"); payload.remove("holdings"); payload.remove("installments"); payload.remove("installmentSync"); V13.downgrade(payload) }
        if (version < 14) V13.downgrade(payload)
        if (version < 13) { payload.remove("installments"); payload.remove("installmentSync"); V13.downgrade(payload) }
        if (version < 5) payload.remove("accountValuations")
        if (version < 6) {
            val accounts = payload.getJSONArray("accounts")
            for (i in 0 until accounts.length()) accounts.getJSONObject(i).remove("type")
        }
        if (version < 4) {
            val events = payload.getJSONArray("events")
            for (i in 0 until events.length()) events.getJSONObject(i).remove("accountId")
        }
        if (version < 3) payload.remove("prefillFacts")
        if (version == 1) payload.remove("accounts")
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return root.put("version", version).put("sha256", digest)
            .put("payload", java.util.Base64.getEncoder().encodeToString(bytes)).toString().toByteArray(Charsets.UTF_8)
    }

    @Test fun explicitClassificationRoundTripsAndNameNeverAssignsIt() {
        val archive = Archive(accounts = listOf(legacyNamedAccount, allianz), funds = listOf(allianzFund), accountValuations = listOf(value(allianz)))
        val encoded = ArchiveCodec.encode(archive)

        assertEquals(14, JSONObject(encoded.toString(Charsets.UTF_8)).getInt("version"))
        assertEquals(archive, ArchiveCodec.decode(encoded))
        assertNull(ArchiveCodec.decode(ArchiveCodec.encode(Archive(accounts = listOf(legacyNamedAccount)))).accounts.single().type)
    }

    @Test fun v1ThroughV6RemainReadableAndOnlyV6CarriesExplicitFundTypes() {
        for (version in 1..6) {
            val decoded = ArchiveCodec.decode(archiveAtVersion(version))
            val expectedAccounts = when (version) {
                1 -> emptyList()
                6 -> listOf(allianz)
                else -> listOf(Account("b", "Savings", 4, "manual record"))
            }
            assertEquals("version $version", expectedAccounts, decoded.accounts)
            assertEquals("version $version", if (version == 6) listOf(allianzFund) else emptyList<PensionFund>(), decoded.funds)
        }
        val current = JSONObject(ArchiveCodec.encode(Archive()).toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCodec.decode(current.put("version", 15).toString().toByteArray())
        }
    }

    @Test fun pensionFundValueRequiresOneMigratedPillarFourAccountAndUsesItsLatestDate() {
        assertEquals(PensionFundValueUnavailableReason.NO_TYPED_ACCOUNT,
            (Archive(accounts = listOf(legacyNamedAccount)).pensionFundValue(LegacyAccountType.ESPERO) as PensionFundValueResolution.Unavailable).reason)
        assertEquals(PensionFundValueUnavailableReason.NO_DATED_VALUE,
            (Archive(accounts = listOf(allianz)).pensionFundValue(LegacyAccountType.ALLIANZ_INSIEME) as PensionFundValueResolution.Unavailable).reason)

        val older = value(allianz, date.minusDays(10), 30_000)
        val latest = value(allianz, date, 42_000)
        val available = Archive(accounts = listOf(allianz), accountValuations = listOf(older, latest))
            .pensionFundValue(LegacyAccountType.ALLIANZ_INSIEME) as PensionFundValueResolution.Available
        assertEquals(latest, available.valuation)

        val ambiguous = Archive(accounts = listOf(allianz, allianz.copy(id = "another")), accountValuations = listOf(latest))
            .pensionFundValue(LegacyAccountType.ALLIANZ_INSIEME) as PensionFundValueResolution.Unavailable
        assertEquals(PensionFundValueUnavailableReason.MULTIPLE_TYPED_ACCOUNTS, ambiguous.reason)
    }

    @Test fun fundTypeRequiresPillarFourAndAnEditKeepsTheLegacyCodeAndCreatesNoNewOne() {
        assertThrows(IllegalArgumentException::class.java) { Account("invalid", "Fondo", 3, "manual", AccountType.PENSION_FUND) }
        assertThrows(IllegalArgumentException::class.java) { Account("invalid", "Conto", 4, "manual", AccountType.GENERAL, fundId = "f") }

        val existingEvent = LedgerEvent("flow", date, 4, EventType.PERSONAL_CONTRIBUTION,
            Money(1_000), Confidence.USER_REPORTED, "pay slip", accountId = legacyNamedAccount.id)
        val existingValue = value(legacyNamedAccount)
        val unclassified = Archive(events = listOf(existingEvent), accounts = listOf(legacyNamedAccount), accountValuations = listOf(existingValue))

        // Classifying by an explicit edit gives a fund named from the account, never a legacy code.
        val classified = unclassified.updateAccount(legacyNamedAccount.copy(type = AccountType.PENSION_FUND)).withAccountFund(legacyNamedAccount.id)
        val account = classified.accounts.single()
        assertEquals(AccountType.PENSION_FUND, account.type)
        assertNull(account.legacyType)
        assertEquals(Sourced.declared("Espero"), classified.funds.single().name)
        assertEquals(account.fundId, classified.funds.single().id)
        assertEquals(FundAttachment(account.fundId, Sourced.nd()), classified.attachmentOf(classified.events.single()))
        assertEquals(PensionFundValueUnavailableReason.NO_TYPED_ACCOUNT,
            (classified.pensionFundValue(LegacyAccountType.ESPERO) as PensionFundValueResolution.Unavailable).reason)
        assertEquals(classified, ArchiveCodec.decode(ArchiveCodec.encode(classified)))

        // An edit never drops a migrated account's legacy code; leaving PENSION_FUND drops only the fund link.
        val renamed = Archive(accounts = listOf(allianz), funds = listOf(allianzFund)).updateAccount(allianz.copy(name = "Renamed", legacyType = null))
        assertEquals(LegacyAccountType.ALLIANZ_INSIEME, renamed.accounts.single().legacyType)
        val general = renamed.updateAccount(renamed.accounts.single().copy(type = AccountType.GENERAL, fundId = null))
        assertEquals(LegacyAccountType.ALLIANZ_INSIEME, general.accounts.single().legacyType)
        assertEquals(listOf(allianzFund), general.funds)
    }

    @Test fun pensionFlowsAreNeverAttributedThroughANameOnly() {
        val event = LedgerEvent("flow", date, 4, EventType.PERSONAL_CONTRIBUTION,
            Money(1_000), Confidence.USER_REPORTED, "source", accountId = legacyNamedAccount.id)
        val archive = Archive(events = listOf(event), accounts = listOf(legacyNamedAccount))

        assertNull(archive.accounts.single().type)
        assertEquals(FundAttachment(null, Sourced.nd()), archive.attachmentOf(event))
        assertEquals(PensionFundValueUnavailableReason.NO_TYPED_ACCOUNT,
            (archive.pensionFundValue(LegacyAccountType.ESPERO) as PensionFundValueResolution.Unavailable).reason)
    }
}