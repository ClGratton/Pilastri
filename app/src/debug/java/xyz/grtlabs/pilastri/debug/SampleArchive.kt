package xyz.grtlabs.pilastri.debug

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import xyz.grtlabs.pilastri.data.*
import xyz.grtlabs.pilastri.domain.*

/**
 * A fully synthetic archive ("Dati di esempio") for showing the interface on an emulator or in screenshots.
 * Nothing here is a real account, balance or merchant relationship. Every date is relative to the `today` passed in,
 * and every record that carries a `source` starts it with [SampleArchive.PREFIX]. The archive passes
 * `ArchiveCodec.encode` validation; imported movements, ledger records, transfers and trades are produced through the
 * app's own `withImportBatch` and `withExternalDecision`, so they are consistent with what a real review would create.
 *
 * Note: the archive validator compares dates with the device clock (`LocalDate.now()`), so `today` must not be later than it.
 */
object SampleArchive {
    const val PREFIX = "Dati di esempio"

    fun build(today: LocalDate): Archive = SampleBuilder(today).build()
}

private fun sampleSource(text: String) = "${SampleArchive.PREFIX} · $text"

private object Acc {
    const val ISYBANK = "sample-isybank"
    const val PAYPAL = "sample-paypal"
    const val EMERGENZE = "sample-emergenze"
    const val PROGETTI = "sample-progetti"
    const val TRADE_REPUBLIC = "sample-trade-republic"
    const val ALLIANZ = "sample-allianz"
    const val ESPERO = "sample-espero"
}

private object Cat {
    const val CASA = "casa"
    const val SPESA = "spesa"
    const val TRASPORTI = "trasporti"
    const val RISTORANTI = "ristoranti"
    const val SVAGO = "svago"
    const val ABBONAMENTI = "abbonamenti"
    const val SALUTE = "salute"
    const val ALTRO = "altro"
    const val STIPENDIO = "stipendio"
}

private const val ISIN = "IE00B4L5Y983"
private const val ETF_NAME = "iShares Core MSCI World UCITS ETF"

/** What happens to an imported row in the sample review. */
private sealed interface Fate {
    /** Still waiting for the user. */
    data object Pending : Fate
    /** Reviewed as an outflow (a WITHDRAWAL record). */
    data object Spend : Fate
    /** Reviewed as income and tagged as salary. */
    data object Salary : Fate
    /** Reviewed as a purchase of [instrument]. */
    data class Buy(val instrument: String) : Fate
    /** Outgoing leg of an own transfer; applying it creates the transfer with the imported counterpart. */
    data class PairedOut(val counterpartKey: String, val counterpart: ImportProvider) : Fate
    /** Incoming leg, reviewed together with its outgoing leg. */
    data object PairedIn : Fate
    /** A leg of an own transfer whose other account is not imported; links to the plain transfer "[prefix]-[date]". */
    data class Linked(val prefix: String) : Fate
}

private data class Row(
    val provider: ImportProvider,
    val key: String,
    val date: LocalDate,
    val cents: Long,
    val description: String,
    val kind: ExternalKind,
    val fate: Fate = Fate.Spend,
    val rawType: String? = null,
    val label: String? = null,
    val isin: String? = null,
    val quantity: BigDecimal? = null,
) {
    val id: String get() = externalId(provider, key)
    val role: MovementRole? get() = when (fate) {
        Fate.Salary -> MovementRole.SALARY
        is Fate.PairedOut, Fate.PairedIn, is Fate.Linked -> MovementRole.OWN_TRANSFER
        else -> null
    }
}

/** A monthly pattern on the bank account; [backs] are the months it occurs in (0 = this month, 1 = last month, 2 = two months ago). */
private class Template(
    val key: String, val day: Int, val cents: Long, val description: String, val kind: ExternalKind,
    val label: String? = null, val rawType: String? = null, val vary: Boolean = true,
    val backs: Set<Int> = setOf(0, 1, 2), val fate: Fate = Fate.Spend,
)

private fun card(key: String, day: Int, cents: Long, description: String, label: String? = null, vary: Boolean = true, backs: Set<Int> = setOf(0, 1, 2)) =
    Template(key, day, -cents, description, ExternalKind.CARD_PAYMENT, label, "Pagamento con carta", vary, backs)

private fun debit(key: String, day: Int, cents: Long, description: String, label: String?, rawType: String, vary: Boolean = true) =
    Template(key, day, -cents, description, ExternalKind.WITHDRAWAL, label, rawType, vary)

/** Deterministic month-to-month wobble of -14%..+16% so past months resemble, but do not copy, the current one. */
private fun varied(cents: Long, key: String, back: Int): Long {
    if (back == 0) return cents
    val percent = 86 + Math.floorMod("$key/$back".hashCode(), 31)
    val magnitude = (Math.abs(cents) * percent + 50) / 100
    return if (cents < 0) -magnitude else magnitude
}

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

private class SampleBuilder(private val today: LocalDate) {
    private fun ago(days: Long): LocalDate = today.minusDays(days)
    private fun stamp(date: LocalDate): String = date.atTime(8, 12).toInstant(ZoneOffset.UTC).toString()
    private val thisMonth = YearMonth.from(today)
    private fun month(back: Int): YearMonth = thisMonth.minusMonths(back.toLong())

    private val bankCoverage = ago(3)
    private val paypalCoverage = ago(1)
    private val importFrom = month(2).atDay(1)

    // Latest account values (cents). Pillar values are composed from these: P1 2.140, P2 7.800, P3 3.200, P4 24.630 euro.
    private val isybankCents = 205_240L
    private val paypalCents = 8_760L
    private val emergenzeCents = 780_000L
    private val progettiCents = 320_000L
    private val tradeRepublicCents = 1_400_000L
    private val allianzCents = 663_000L
    private val esperoCents = 400_000L
    private val allianzDate = ago(43)
    private val esperoDate = ago(5)

    // Installment anchor dates.
    private val klarnaThird = today.plusDays(12)
    private val zalandoPaid = ago(20)
    private val scalapayPaid = ago(21)

    private val contributionEvents = buildContributionEvents()

    fun build(): Archive {
        val base = ArchiveMigration.toV14(Archive(
            indices = Istat.snapshot(),
            accounts = accounts(),
            accountValuations = accountValuations(),
            events = contributionEvents,
            transfers = plainTransfers(),
            projections = listOf(projection()),
            planning = planning(),
            pension = pension(),
        ))
        val bank = bankRows()
        val broker = brokerRows()
        val paypal = paypalRows()
        var archive = loadFeed(base, bank, ImportProvider.ISYBANK, Acc.ISYBANK, "isybank", ago(3), importFrom, bankCoverage)
        archive = loadFeed(archive, broker, ImportProvider.TRADE_REPUBLIC, Acc.TRADE_REPUBLIC, "Trade Republic", today, importFrom, today,
            positions = listOf(ImportedPosition(ISIN, ETF_NAME, BigDecimal("139.2150"), 9_885L)))
        archive = loadFeed(archive, paypal, ImportProvider.PAYPAL, Acc.PAYPAL, "PayPal", ago(1), importFrom, paypalCoverage)
        archive = review(archive, bank + broker + paypal)
        archive = archive.copy(installments = installments(archive), installmentSync = installmentSync())
        return archive.withSamplePrefix().copy(audit = emptyList())
    }

    // ---- Registry, values and history ------------------------------------------------------------------------

    private fun accounts() = listOf(
        Account(Acc.ISYBANK, "isybank", 1, sampleSource("conto fittizio"), AccountType.GENERAL),
        Account(Acc.PAYPAL, "PayPal", 1, sampleSource("conto fittizio"), AccountType.GENERAL),
        Account(Acc.EMERGENZE, "Conto Emergenze", 2, sampleSource("conto fittizio"), AccountType.GENERAL),
        Account(Acc.PROGETTI, "Salvadanaio Progetti", 3, sampleSource("conto fittizio"), AccountType.GENERAL),
        Account(Acc.TRADE_REPUBLIC, "Trade Republic", 4, sampleSource("conto fittizio"), AccountType.GENERAL),
        // The two funds reproduce an archive migrated from v13 (PENSION_FUND + legacyType), so the pre-Council-4 cards render.
        Account(Acc.ALLIANZ, "Allianz Insieme", 4, sampleSource("fondo fittizio"), AccountType.PENSION_FUND, legacyType = LegacyAccountType.ALLIANZ_INSIEME),
        Account(Acc.ESPERO, "Espero", 4, sampleSource("fondo fittizio"), AccountType.PENSION_FUND, legacyType = LegacyAccountType.ESPERO),
    )

    private fun valuation(account: String, date: LocalDate, cents: Long, confidence: Confidence, what: String) =
        AccountValuation(account, date, Money(cents), sampleSource(what), confidence)

    /**
     * Latest values plus one earlier value per account about a month before, so a month-over-month change exists:
     * P1 was 96 euro higher, P2 and P3 150 lower, P4 208 lower. Allianz is stale (43 days) and has a third, older value.
     */
    private fun accountValuations() = listOf(
        valuation(Acc.ISYBANK, ago(3), isybankCents, Confidence.DOCUMENT, "saldo isybank"),
        valuation(Acc.ISYBANK, ago(33), isybankCents + 7_100, Confidence.DOCUMENT, "saldo isybank"),
        valuation(Acc.PAYPAL, ago(3), paypalCents, Confidence.DOCUMENT, "saldo PayPal"),
        valuation(Acc.PAYPAL, ago(33), paypalCents + 2_500, Confidence.DOCUMENT, "saldo PayPal"),
        valuation(Acc.EMERGENZE, ago(2), emergenzeCents, Confidence.USER_REPORTED, "saldo dichiarato"),
        valuation(Acc.EMERGENZE, ago(32), emergenzeCents - 15_000, Confidence.USER_REPORTED, "saldo dichiarato"),
        valuation(Acc.PROGETTI, ago(2), progettiCents, Confidence.USER_REPORTED, "saldo dichiarato"),
        valuation(Acc.PROGETTI, ago(32), progettiCents - 15_000, Confidence.USER_REPORTED, "saldo dichiarato"),
        valuation(Acc.TRADE_REPUBLIC, today, tradeRepublicCents, Confidence.DOCUMENT, "saldo Trade Republic"),
        valuation(Acc.TRADE_REPUBLIC, ago(30), tradeRepublicCents - 13_000, Confidence.DOCUMENT, "saldo Trade Republic"),
        valuation(Acc.ALLIANZ, allianzDate, allianzCents, Confidence.DOCUMENT, "estratto Allianz Insieme"),
        valuation(Acc.ALLIANZ, allianzDate.minusDays(30), allianzCents - 4_000, Confidence.DOCUMENT, "estratto Allianz Insieme"),
        valuation(Acc.ALLIANZ, allianzDate.minusDays(60), allianzCents - 8_500, Confidence.DOCUMENT, "estratto Allianz Insieme"),
        valuation(Acc.ESPERO, esperoDate, esperoCents, Confidence.DOCUMENT, "estratto Espero"),
        valuation(Acc.ESPERO, esperoDate.minusDays(30), esperoCents - 3_800, Confidence.DOCUMENT, "estratto Espero"),
    )

    /** Personal contributions (some in the year before the ISTAT snapshot, so they have no index) and the employer's share. */
    private fun buildContributionEvents(): List<LedgerEvent> {
        fun own(account: String, date: LocalDate, cents: Long, what: String) = LedgerEvent(
            "sample-own-$account-$date", date, 4, EventType.PERSONAL_CONTRIBUTION, Money(cents), Confidence.DOCUMENT, sampleSource(what), accountId = account)
        val lastYear = today.year - 1
        val allianz = listOf(3 to 14, 6 to 13, 9 to 12, 12 to 12).map { (m, d) -> LocalDate.of(lastYear, m, d) } + LocalDate.of(today.year, 3, 13)
        val monthlyDates = (1..12).map { YearMonth.of(today.year, it).atDay(10) }
        return (allianz.filter { !it.isAfter(today) }.map { own(Acc.ALLIANZ, it, 45_000, "versamento Allianz Insieme") } +
            monthlyDates.filter { !it.isAfter(today) }.flatMap { date ->
                listOf(
                    own(Acc.ESPERO, date, 6_000, "trattenuta in busta paga"),
                    LedgerEvent("sample-employer-${Acc.ESPERO}-$date", date, 4, EventType.EMPLOYER_CONTRIBUTION, Money(4_120),
                        Confidence.DOCUMENT, sampleSource("contributo del datore"), accountId = Acc.ESPERO),
                )
            }).sortedBy { it.date }
    }

    /**
     * Own transfers for the whole year: 150 euro to Emergenze and Progetti on the 28th, 150 euro to Trade Republic on the 1st.
     * The Trade Republic legs of the imported months come from the reviewed bank/broker pairs instead (see [brokerRows]).
     */
    private fun plainTransfers(): List<AccountTransfer> {
        val months = (1..12).map { YearMonth.of(today.year, it) }
        fun transfer(prefix: String, date: LocalDate, to: String, what: String) =
            AccountTransfer("$prefix-$date", date, Acc.ISYBANK, to, Money(15_000), sampleSource(what))
        val monthEnd = months.map { it.atDay(28) }.filter { !it.isAfter(today) }
        val tradeRepublic = months.map { it.atDay(1) }.filter { !it.isAfter(today) && it.isBefore(importFrom) }
        return (monthEnd.map { transfer("sample-t-em", it, Acc.EMERGENZE, "giroconto automatico verso Emergenze") } +
            monthEnd.map { transfer("sample-t-prog", it, Acc.PROGETTI, "giroconto automatico verso Progetti") } +
            tradeRepublic.map { transfer("sample-t-tr", it, Acc.TRADE_REPUBLIC, "bonifico mensile verso Trade Republic") }).sortedBy { it.date }
    }

    // ---- Imported movements -----------------------------------------------------------------------------------

    private val bankTemplates: List<Template> = listOf(
        // Casa (fixed costs, a utility that varies, and a one-off purchase this month)
        debit("condominio", 3, 15_760, "CONDOMINIO VIA ROMA 12 QUOTA MENSILE", "Casa e utenze", "Bonifico SEPA", vary = false),
        debit("enel", 5, 9_450, "ENEL ENERGIA ADDEBITO DIRETTO", "Casa e utenze", "Addebito diretto SDD"),
        debit("fastweb", 8, 2_790, "FASTWEB SPA FIBRA", "Casa e utenze", "Addebito diretto SDD", vary = false),
        card("ferramenta", 19, 24_000, "FERRAMENTA ROSSI", backs = setOf(0)),
        card("leroy", 13, 6_480, "LEROY MERLIN MILANO", "Casa e utenze", backs = setOf(1)),
        // Spesa
        card("esselunga-1", 2, 4_120, "ESSELUNGA SPA MILANO", "Supermercati"),
        card("coop-1", 6, 3_875, "COOP LOMBARDIA", "Supermercati"),
        card("conad-1", 10, 5_230, "CONAD CITY", "Supermercati"),
        card("lidl", 14, 2_985, "LIDL ITALIA", "Supermercati"),
        card("esselunga-2", 18, 4_760, "ESSELUNGA SPA MILANO", "Supermercati"),
        card("coop-2", 22, 3_410, "COOP LOMBARDIA", "Supermercati"),
        card("conad-2", 26, 4_220, "CONAD CITY", "Supermercati"),
        // Trasporti
        card("eni", 3, 4_850, "ENI STATION", "Trasporti e carburante"),
        card("trenitalia", 8, 2_490, "TRENITALIA BIGLIETTI", "Trasporti e carburante"),
        card("q8", 17, 3_860, "Q8 CARBURANTI", "Trasporti e carburante"),
        card("atm", 21, 3_000, "ATM MILANO ABBONAMENTO", "Trasporti e carburante", vary = false),
        // Ristoranti (the rest of the month is on PayPal; one more bar payment is still waiting for review)
        card("pizzeria", 9, 3_200, "PIZZERIA DA MICHELE", "Ristoranti e bar"),
        card("locanda", 24, 4_900, "RISTORANTE LA LOCANDA", "Ristoranti e bar"),
        card("trattoria", 20, 4_100, "TRATTORIA DEL PORTO", "Ristoranti e bar", backs = setOf(1, 2)),
        // Svago (the Klarna instalment rows are added separately)
        card("uci", 18, 2_125, "UCI CINEMAS", "Tempo libero"),
        card("lido", 16, 4_500, "LIDO BAGNI MARINA", "Tempo libero", backs = setOf(2)),
        // Abbonamenti: three recurring merchants, the Disney one is new (9,99 euro, first seen in the oldest month)
        card("netflix", 4, 1_999, "NETFLIX.COM", vary = false),
        card("spotify", 6, 1_699, "SPOTIFY", vary = false),
        card("disney", 11, 999, "DISNEY PLUS", vary = false),
        // Salute, Altro
        card("farmacia-1", 7, 2_460, "FARMACIA COMUNALE", "Salute e benessere"),
        card("farmacia-2", 22, 1_340, "FARMACIA S MARCO", "Salute e benessere"),
        card("tabacchi", 14, 1_273, "TABACCHI EDICOLA CENTRO"),
        // Smaller purchases in the older months (this month the PayPal rows and the one-off carry the "Altro" and "Casa" totals)
        card("lavanderia", 12, 1_800, "LAVANDERIA AZZURRA", backs = setOf(1, 2)),
        card("amazon", 15, 2_990, "AMAZON.IT MARKETPLACE", backs = setOf(1, 2)),
        card("brico", 23, 5_490, "BRICO IO", "Casa e utenze", backs = setOf(2)),
        // Salary and the fixed monthly giroconti
        Template("stipendio", 27, 184_200, "Stipendio ACME SRL", ExternalKind.DEPOSIT, rawType = "Bonifico SEPA in entrata", vary = false, fate = Fate.Salary),
        Template("giroconto-emergenze", 28, -15_000, "GIROCONTO A CONTO EMERGENZE", ExternalKind.WITHDRAWAL, rawType = "Giroconto", vary = false, fate = Fate.Linked("sample-t-em")),
        Template("giroconto-progetti", 28, -15_000, "GIROCONTO A SALVADANAIO PROGETTI", ExternalKind.WITHDRAWAL, rawType = "Giroconto", vary = false, fate = Fate.Linked("sample-t-prog")),
    )

    private fun bankRows(): List<Row> {
        val recurring = (2 downTo 0).flatMap { back ->
            bankTemplates.filter { back in it.backs }.mapNotNull { t ->
                val date = month(back).atDay(t.day)
                if (date.isAfter(bankCoverage)) return@mapNotNull null
                Row(ImportProvider.ISYBANK, "iy-$date-${t.key}", date, if (t.vary) varied(t.cents, t.key, back) else t.cents,
                    t.description, t.kind, t.fate, t.rawType, t.label)
            }
        }
        // Klarna: four instalments of 74,75 euro, the third due klarnaThird; the first two were debited here.
        val klarna = listOf(klarnaThird.minusMonths(2), klarnaThird.minusMonths(1)).filter { !it.isAfter(bankCoverage) }.map { date ->
            Row(ImportProvider.ISYBANK, klarnaKey(date), date, -7_475, "KLARNA BANK AB RATA MEDIAWORLD", ExternalKind.WITHDRAWAL, Fate.Spend, "Addebito diretto SDD")
        }
        // Monthly 150 euro to Trade Republic: reviewed pairs for the imported months (the broker side is in brokerRows).
        val pac = pacDates().map { date ->
            Row(ImportProvider.ISYBANK, "iy-pac-$date", date, -15_000, "BONIFICO A TRADE REPUBLIC PIANO MENSILE", ExternalKind.WITHDRAWAL,
                Fate.PairedOut("tr-in-$date", ImportProvider.TRADE_REPUBLIC), "Bonifico SEPA")
        }
        // Still waiting for review: a bar payment nobody classified, a friend's refund, and half of a possible own transfer.
        val pending = listOf(
            Row(ImportProvider.ISYBANK, "iy-pending-bar", ago(6), -2_340, "POS BAR CENTRALE", ExternalKind.CARD_PAYMENT, Fate.Pending, "Pagamento con carta"),
            Row(ImportProvider.ISYBANK, "iy-pending-credit", ago(7), 3_500, "BONIFICO DA MARIO BIANCHI CENA", ExternalKind.DEPOSIT, Fate.Pending, "Bonifico SEPA in entrata"),
            Row(ImportProvider.ISYBANK, "iy-pending-out", ago(5), -20_000, "Bonifico a Trade Republic", ExternalKind.WITHDRAWAL, Fate.Pending, "Bonifico SEPA"),
        )
        return recurring + klarna + pac + pending
    }

    private fun klarnaKey(date: LocalDate) = "iy-klarna-$date"

    private fun pacDates(): List<LocalDate> = (2 downTo 0).map { month(it).atDay(1) }.filter { !it.isAfter(bankCoverage) }

    /** Monthly accumulation plan: the deposit from the bank on the 1st, the purchase on the 2nd. Plus one deposit still unreviewed. */
    private fun brokerRows(): List<Row> {
        val prices = mapOf(2 to 9_690L, 1 to 9_780L, 0 to 9_885L)
        val deposits = pacDates().map { date ->
            Row(ImportProvider.TRADE_REPUBLIC, "tr-in-$date", date, 15_000, "Deposito da isybank", ExternalKind.DEPOSIT, Fate.PairedIn, "Bonifico in entrata")
        }
        val buys = (2 downTo 0).mapNotNull { back ->
            val date = month(back).atDay(2)
            if (date.isAfter(today)) return@mapNotNull null
            val quantity = BigDecimal(15_000).divide(BigDecimal(prices.getValue(back)), 4, RoundingMode.HALF_UP)
            Row(ImportProvider.TRADE_REPUBLIC, "tr-buy-$date", date, -15_000, "$ETF_NAME · piano di accumulo", ExternalKind.BUY,
                Fate.Buy(ETF_NAME), "Esecuzione piano di accumulo", isin = ISIN, quantity = quantity)
        }
        val pending = Row(ImportProvider.TRADE_REPUBLIC, "tr-pending-in", ago(5), 20_000, "Deposito", ExternalKind.DEPOSIT, Fate.Pending, "Bonifico in entrata")
        return deposits + buys + pending
    }

    private fun zalandoKey() = "pp-zalando-$zalandoPaid"

    private fun paypalRows() = listOf(
        Row(ImportProvider.PAYPAL, zalandoKey(), zalandoPaid, -4_500, "ZALANDO PAYPAL PAGA IN 3 RATE (1/3)", ExternalKind.CARD_PAYMENT, Fate.Spend, "Pagamento rateale"),
        Row(ImportProvider.PAYPAL, "pp-justeat-${ago(16)}", ago(16), -1_360, "JUST EAT ITALIA", ExternalKind.CARD_PAYMENT, Fate.Spend, "Pagamento PayPal"),
        Row(ImportProvider.PAYPAL, "pp-amazon-${ago(11)}", ago(11), -1_330, "AMAZON.IT MARKETPLACE", ExternalKind.CARD_PAYMENT, Fate.Spend, "Pagamento PayPal"),
        // Earlier months (sample): the PayPal feed reaches back to the bank's start, so Spese has previous windows with covered days.
        Row(ImportProvider.PAYPAL, "pp-justeat-${ago(45)}", ago(45), -1_890, "JUST EAT ITALIA", ExternalKind.CARD_PAYMENT, Fate.Spend, "Pagamento PayPal"),
        Row(ImportProvider.PAYPAL, "pp-amazon-${ago(52)}", ago(52), -2_460, "AMAZON.IT MARKETPLACE", ExternalKind.CARD_PAYMENT, Fate.Spend, "Pagamento PayPal"),
    )

    private fun loadFeed(
        archive: Archive, rows: List<Row>, provider: ImportProvider, accountId: String, label: String,
        syncedOn: LocalDate, from: LocalDate, to: LocalDate, positions: List<ImportedPosition> = emptyList(),
    ): Archive {
        val batch = ImportBatch(provider, label, Instant.parse(stamp(syncedOn)), from, to,
            rows.map { ImportedMovement(it.key, it.date, it.cents, "EUR", it.description, it.kind, it.rawType, it.isin, it.quantity, it.label) },
            positions = positions)
        val outcome = archive.withImportBatch(batch, accountId, at = stamp(syncedOn))
        check(outcome.added == rows.size) { "Dati di esempio: importati ${outcome.added} movimenti su ${rows.size} (${provider.label}); la data è nel futuro rispetto all'orologio?" }
        return outcome.archive
    }

    /** Categorizes and tags the imported rows as the app would, then applies the sample review decisions. */
    private fun review(archive: Archive, rows: List<Row>): Archive {
        val byId = rows.associateBy { it.id }
        val at = stamp(today)
        val tagged = archive.copy(externalMovements = archive.externalMovements.map { m ->
            val (categoryId, source) = archive.planning.categorize(m)
            m.copy(categoryId = categoryId, categorySource = source, role = byId.getValue(m.id).role)
        })
        return rows.fold(tagged) { acc, row ->
            when (val fate = row.fate) {
                Fate.Pending, Fate.PairedIn -> acc
                Fate.Spend -> acc.withExternalDecision(row.id, ExternalDecision.AsEvent(EventType.WITHDRAWAL), at)
                Fate.Salary -> acc.withExternalDecision(row.id, ExternalDecision.AsEvent(EventType.INCOME), at)
                is Fate.Buy -> acc.withExternalDecision(row.id, ExternalDecision.AsTrade(TradeSide.BUY, fate.instrument), at)
                is Fate.PairedOut -> acc.withExternalDecision(row.id, ExternalDecision.AsTransfer(externalId(fate.counterpart, fate.counterpartKey)), at)
                is Fate.Linked -> acc.linkedTo(row.id, "${fate.prefix}-${row.date}")
            }
        }
    }

    private fun Archive.linkedTo(movementId: String, recordId: String): Archive {
        check(transfers.any { it.id == recordId }) { "Trasferimento di esempio mancante: $recordId" }
        return copy(externalMovements = externalMovements.map {
            if (it.id == movementId) it.copy(status = ExternalStatus.APPLIED, linkedRecordId = recordId) else it
        })
    }

    /** Records created by the app's review functions carry the provider as their source; the sample marks them too. */
    private fun Archive.withSamplePrefix(): Archive {
        fun fix(text: String) = if (text.startsWith(SampleArchive.PREFIX)) text else sampleSource(text).take(500)
        return copy(
            events = events.map { it.copy(source = fix(it.source)) },
            trades = trades.map { it.copy(source = fix(it.source)) },
            transfers = transfers.map { it.copy(source = fix(it.source)) },
            instruments = instruments.map { it.copy(source = fix(it.source)) },
            holdings = holdings.map { it.copy(source = fix(it.source)) },
        )
    }

    // ---- Planning ---------------------------------------------------------------------------------------------

    private fun planning(): Planning {
        fun rule(name: String, pattern: String, category: String) = CategoryRule("sample-rule-$name", pattern, category, RuleOrigin.USER)
        fun provider(label: String, category: String) = ProviderCategory(ImportProvider.ISYBANK, label, category)
        val first = YearMonth.of(today.year, 1)
        return Planning(
            categories = listOf(
                Category(Cat.CASA, "Casa", CategoryKind.SPEND, Essential.ESSENTIAL),
                Category(Cat.SPESA, "Spesa", CategoryKind.SPEND, Essential.ESSENTIAL),
                Category(Cat.TRASPORTI, "Trasporti", CategoryKind.SPEND, Essential.ESSENTIAL),
                Category(Cat.RISTORANTI, "Ristoranti", CategoryKind.SPEND, Essential.DISCRETIONARY),
                Category(Cat.SVAGO, "Svago", CategoryKind.SPEND, Essential.DISCRETIONARY),
                Category(Cat.ABBONAMENTI, "Abbonamenti", CategoryKind.SPEND, Essential.DISCRETIONARY),
                Category(Cat.SALUTE, "Salute", CategoryKind.SPEND, Essential.ESSENTIAL),
                Category(Cat.ALTRO, "Altro", CategoryKind.SPEND),
                Category(Cat.STIPENDIO, "Stipendio", CategoryKind.INCOME),
            ),
            rules = listOf(
                rule("netflix", "netflix", Cat.ABBONAMENTI), rule("spotify", "spotify", Cat.ABBONAMENTI), rule("disney", "disney", Cat.ABBONAMENTI),
                rule("klarna", "klarna", Cat.SVAGO), rule("zalando", "zalando", Cat.ALTRO), rule("amazon", "amazon", Cat.ALTRO),
                rule("justeat", "just eat", Cat.RISTORANTI), rule("tabacchi", "tabacchi", Cat.ALTRO), rule("lavanderia", "lavanderia", Cat.ALTRO),
                rule("ferramenta", "ferramenta", Cat.CASA), rule("acme", "acme", Cat.STIPENDIO),
            ),
            providerCategories = listOf(
                provider("Casa e utenze", Cat.CASA), provider("Supermercati", Cat.SPESA), provider("Trasporti e carburante", Cat.TRASPORTI),
                provider("Ristoranti e bar", Cat.RISTORANTI), provider("Tempo libero", Cat.SVAGO), provider("Salute e benessere", Cat.SALUTE),
            ),
            budgets = listOf(
                Budget(Cat.CASA, 55_000, first), Budget(Cat.SPESA, 35_000, first), Budget(Cat.TRASPORTI, 15_000, first),
                Budget(Cat.RISTORANTI, 10_000, first), Budget(Cat.SVAGO, 15_000, first), Budget(Cat.ABBONAMENTI, 5_000, first),
                Budget(Cat.SALUTE, 8_000, first), Budget(Cat.ALTRO, 27_000, first),
            ),
            goals = listOf(Goal("sample-goal-bici", "Bici nuova", 120_000, today.plusMonths(6), Acc.PROGETTI, 60_000)),
            targets = listOf(
                PillarTarget(1, TargetKind.BUFFER_CENTS, cents = 250_000, userSet = true),
                PillarTarget(2, TargetKind.BUFFER_CENTS, cents = 900_000, userSet = true),
                PillarTarget(3, TargetKind.BUFFER_CENTS, cents = 750_000, userSet = true),
            ),
            incomeRules = listOf(IncomeRule("sample-salary-acme", "Stipendio", Acc.ISYBANK, "ACME", 25, 29, 170_000, 200_000, stamp(month(2).atDay(28)))),
        )
    }

    // ---- Installments -----------------------------------------------------------------------------------------

    private fun installments(archive: Archive): List<InstallmentPlan> {
        val known = archive.externalMovements.map { it.id }.toSet()
        fun linked(provider: ImportProvider, key: String) = externalId(provider, key).takeIf { it in known }
        val updated = stamp(today)
        val klarnaFirst = klarnaThird.minusMonths(2)
        val klarnaSecond = klarnaThird.minusMonths(1)

        val zalando = InstallmentPlan(
            id = "paypal-pay-in-3:5XY12345AB", lender = "PayPal Paga in 3 rate", merchant = "Zalando", totalCents = 13_500, currency = "EUR", count = 3,
            installments = listOf(
                Installment(1, zalandoPaid, 4_500, InstallmentState.PAID, zalandoPaid, linked(ImportProvider.PAYPAL, zalandoKey())),
                Installment(2, today.plusDays(3), 4_500, InstallmentState.DUE),
                Installment(3, today.plusDays(33), 4_500, InstallmentState.DUE),
            ),
            purchaseDate = zalandoPaid, categoryId = Cat.ALTRO, fundingAccountId = Acc.PAYPAL, source = InstallmentSource.PAYPAL,
            evidence = listOf(
                Evidence("paypal:5XY12345AB", zalandoPaid, "Acquisto Zalando con Paga in 3 rate: 3 rate da 45,00 €"),
                Evidence("gmail:18c2f3a4b5", zalandoPaid, "Email PayPal: piano confermato, prima rata addebitata"),
            ),
            review = PlanReview.CONFIRMED, extractedByAgent = false, updatedAt = updated,
        )
        val klarna = InstallmentPlan(
            id = "klarna:KL7A3F92", lender = "Klarna", merchant = "MediaWorld", totalCents = 29_900, currency = "EUR", count = 4,
            installments = listOf(
                Installment(1, klarnaFirst, 7_475, InstallmentState.PAID, klarnaFirst, linked(ImportProvider.ISYBANK, klarnaKey(klarnaFirst))),
                Installment(2, klarnaSecond, 7_475, InstallmentState.PAID, klarnaSecond, linked(ImportProvider.ISYBANK, klarnaKey(klarnaSecond))),
                Installment(3, klarnaThird, 7_475, InstallmentState.DUE),
                Installment(4, today.plusDays(42), 7_475, InstallmentState.DUE),
            ),
            purchaseDate = klarnaFirst, categoryId = Cat.SVAGO, fundingAccountId = Acc.ISYBANK, source = InstallmentSource.GMAIL,
            evidence = listOf(
                Evidence("gmail:18b7e0d9c1", klarnaFirst, "Klarna: ordine MediaWorld da 299,00 € in 4 rate"),
                Evidence("gmail:18c1a05f77", klarnaSecond, "Klarna: ricevuta della rata 2 di 4 (74,75 €)"),
            ),
            review = PlanReview.CONFIRMED, extractedByAgent = false, updatedAt = updated,
        )
        val scalapay = InstallmentPlan(
            id = "scalapay:SC91B4C2D7", lender = "Scalapay", merchant = "IKEA", totalCents = null, currency = "EUR", count = 3,
            installments = listOf(
                Installment(1, scalapayPaid, 3_830, InstallmentState.PAID, scalapayPaid),
                Installment(2, today.plusDays(9), 3_830, InstallmentState.DUE),
                Installment(3, today.plusDays(39), null, InstallmentState.DUE),
            ),
            purchaseDate = scalapayPaid, categoryId = Cat.CASA, fundingAccountId = null, source = InstallmentSource.GMAIL,
            evidence = listOf(Evidence("gmail:18d4a9c0e2", scalapayPaid, "Scalapay: pagamento della rata 1 di 3 per IKEA (38,30 €)")),
            review = PlanReview.PENDING, extractedByAgent = false, updatedAt = updated,
        )
        return listOf(zalando, klarna, scalapay)
    }

    private fun installmentSync() = listOf(
        InstallmentSync(InstallmentSource.GMAIL, stamp(today), today.minusDays(90), today, sampleSource("2 piani trovati nelle email")),
        InstallmentSync(InstallmentSource.PAYPAL, stamp(today), today.minusDays(90), today, sampleSource("1 piano trovato in PayPal")),
    )

    // ---- Pension and scenarios --------------------------------------------------------------------------------

    private fun pension(): PensionData {
        fun total(account: String, type: EventType, to: LocalDate) =
            contributionEvents.filter { it.accountId == account && it.type == type && !it.date.isAfter(to) }.sumOf { it.amount.cents }
        val statements = listOf(
            PensionStatement(
                id = "sample-ps-allianz", accountId = Acc.ALLIANZ, positionDate = allianzDate, positionCents = allianzCents,
                ownCents = total(Acc.ALLIANZ, EventType.PERSONAL_CONTRIBUTION, allianzDate), employerCents = null, tfrCents = null,
                costsCents = 6_120, taxesCents = null, periodFrom = LocalDate.of(today.year - 1, 1, 1), periodTo = allianzDate,
                documentHash = sha256("${SampleArchive.PREFIX} · estratto Allianz Insieme $allianzDate"),
                source = sampleSource("estratto Allianz Insieme"), extractedByAgent = false, confirmedAt = stamp(allianzDate.plusDays(2)),
            ),
            PensionStatement(
                id = "sample-ps-espero", accountId = Acc.ESPERO, positionDate = esperoDate, positionCents = esperoCents,
                ownCents = total(Acc.ESPERO, EventType.PERSONAL_CONTRIBUTION, esperoDate),
                employerCents = total(Acc.ESPERO, EventType.EMPLOYER_CONTRIBUTION, esperoDate), tfrCents = null,
                costsCents = 1_850, taxesCents = null, periodFrom = LocalDate.of(today.year, 1, 1), periodTo = esperoDate,
                documentHash = sha256("${SampleArchive.PREFIX} · estratto Espero $esperoDate"),
                source = sampleSource("estratto Espero"), extractedByAgent = false, confirmedAt = stamp(esperoDate.plusDays(1)),
            ),
        )
        return PensionData(
            statements = statements,
            profiles = listOf(
                FundProfile(Acc.ALLIANZ, FundRegime.PRIVATE, null, null, null, sampleSource("scheda del fondo")),
                FundProfile(Acc.ESPERO, FundRegime.PUBLIC, "Esempio: TFR conferito al fondo", LocalDate.of(today.year - 1, 1, 1), BigDecimal("1.00"), sampleSource("scheda del fondo")),
            ),
            deductionYears = listOf(DeductionYear(today.year, 530_000, sampleSource("tetto deducibilità"), today)),
            inps = listOf(InpsEstimate(
                asOf = ago(14), monthlyNetCents = 148_000, retirementDate = LocalDate.of(today.year + 33, 6, 1),
                assumptions = "Scenario di esempio: carriera continua senza periodi scoperti, rivalutazione annua 1,0%. Non è una stima personale.",
                source = sampleSource("simulatore INPS (scenario)"),
            )),
        )
    }

    private fun projection() = SavedProjection(
        id = "sample-scenario-lungo-termine", name = "Lungo termine · scenario prudente",
        assumptions = ProjectionAssumptions(
            startYear = maxOf(today.year, ProjectionAssumptions.FIRST_PROJECTION_YEAR),
            endYear = maxOf(today.year, ProjectionAssumptions.FIRST_PROJECTION_YEAR) + 30,
            initialWealthCents = tradeRepublicCents + allianzCents + esperoCents,
            annualPersonalAdditionCents = 252_000, annualEmployerAdditionCents = 49_440,
            annualNominalGrowthPercent = BigDecimal("4.00"), annualInflationPercent = BigDecimal("2.00"),
            priceReference = ProjectionPriceReference.START_2026,
        ),
        source = sampleSource("scenario ipotetico"), savedAt = stamp(ago(12)),
    )
}
