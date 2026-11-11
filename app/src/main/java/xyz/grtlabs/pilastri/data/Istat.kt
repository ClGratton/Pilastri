package xyz.grtlabs.pilastri.data

import java.math.BigDecimal
import java.time.YearMonth

/** ISTAT Tabella 6, NIC inclusive of tobacco, release 16 Oct 2026, base 2025=100.
 * https://www.istat.it/wp-content/uploads/2026/10/serie09_2026.xlsx
 * This frozen snapshot is not a live feed; unpublished months remain missing. */
object Istat {
    const val SERIES = "ISTAT NIC generale 2025=100"
    fun snapshot(): List<IndexPoint> = listOf("100.4", "101.1", "101.6", "102.7", "103.1", "103.1", "103.4", "103.9", "104.6")
        .mapIndexed { index, value -> IndexPoint(YearMonth.of(2026, index + 1), BigDecimal(value), SERIES) }
}
