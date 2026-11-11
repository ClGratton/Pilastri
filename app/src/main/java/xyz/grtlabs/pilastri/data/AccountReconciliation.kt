package xyz.grtlabs.pilastri.data

import java.time.LocalDate
import xyz.grtlabs.pilastri.domain.AccountValuation
import xyz.grtlabs.pilastri.domain.Valuation

data class AccountPillarReconciliation(
    val pillar: Int,
    val accounts: List<AccountSnapshot>,
    val latestPillarValuation: Valuation?,
    val comparison: AccountPillarComparison,
)

data class AccountSnapshot(
    val account: Account,
    val latest: AccountValuation?,
)

sealed interface AccountPillarComparison {
    data class Available(
        val date: LocalDate,
        val accountTotalCents: Long,
        val pillarTotalCents: Long,
        /** Sum of dated account snapshots minus the explicit pillar valuation. */
        val differenceCents: Long,
    ) : AccountPillarComparison

    data class Unavailable(
        val reason: Reason,
        /** Accounts without a snapshot on the explicit pillar valuation date. */
        val accountsWithoutMatchingDate: List<AccountSnapshot> = emptyList(),
    ) : AccountPillarComparison

    enum class Reason { NO_REGISTERED_ACCOUNTS, NO_PILLAR_VALUATION, MISSING_SAME_DATE_SNAPSHOT, TOTAL_OVERFLOW }
}

/** Read-only dated comparison; it does not establish that the account registry is complete. */
fun reconcileAccountValuations(data: Archive): List<AccountPillarReconciliation> = (1..4).map { pillar ->
    val accounts = data.accounts.filter { it.pillar == pillar }.sortedBy { it.name }
    val snapshots = accounts.map { account ->
        AccountSnapshot(account, data.accountValuations.filter { it.accountId == account.id }.maxByOrNull { it.date })
    }
    val pillarValue = data.valuations.filter { it.pillar == pillar }.maxByOrNull { it.date }
    val comparison = when {
        accounts.isEmpty() -> AccountPillarComparison.Unavailable(AccountPillarComparison.Reason.NO_REGISTERED_ACCOUNTS)
        pillarValue == null -> AccountPillarComparison.Unavailable(AccountPillarComparison.Reason.NO_PILLAR_VALUATION)
        else -> {
            val missing = snapshots.filter { snapshot ->
                data.accountValuations.none { it.accountId == snapshot.account.id && it.date == pillarValue.date }
            }
            if (missing.isNotEmpty()) {
                AccountPillarComparison.Unavailable(
                    AccountPillarComparison.Reason.MISSING_SAME_DATE_SNAPSHOT,
                    accountsWithoutMatchingDate = missing,
                )
            } else try {
                val total = accounts.fold(0L) { sum, account ->
                    val sameDate = data.accountValuations.first { it.accountId == account.id && it.date == pillarValue.date }
                    Math.addExact(sum, sameDate.amount.cents)
                }
                AccountPillarComparison.Available(
                    date = pillarValue.date,
                    accountTotalCents = total,
                    pillarTotalCents = pillarValue.amount.cents,
                    differenceCents = Math.subtractExact(total, pillarValue.amount.cents),
                )
            } catch (_: ArithmeticException) {
                AccountPillarComparison.Unavailable(AccountPillarComparison.Reason.TOTAL_OVERFLOW)
            }
        }
    }
    AccountPillarReconciliation(pillar, snapshots, pillarValue, comparison)
}
