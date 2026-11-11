package xyz.grtlabs.pilastri.ui.screens

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.grtlabs.pilastri.data.Coverage

/** 1a-20 / U-06: Mese opens on the latest month with cash-flow data, never on an empty running month. */
class MonthDefaultTest {
    private val nov8 = LocalDate.of(2026, 11, 8)
    private val oct = YearMonth.of(2026, 10)
    private val nov = YearMonth.of(2026, 11)
    private val timeline = listOf(YearMonth.of(2026, 9), oct, nov) // November is on the timeline through a dated pillar value only

    @Test fun opensOnTheLatestMonthWithFlowDataNotTheEmptyRunningMonth() {
        assertEquals(oct, defaultShownMonth(null, timeline, setOf(YearMonth.of(2026, 9), oct), nov8))
    }

    @Test fun aChosenMonthOnTheTimelineWins() {
        assertEquals(nov, defaultShownMonth("2026-11", timeline, setOf(oct), nov8))
        assertEquals(oct, defaultShownMonth("2027-01", timeline, setOf(oct), nov8)) // not on the timeline: falls back
    }

    @Test fun runningMonthWithCoverageIsTheLatest() {
        assertEquals(nov, defaultShownMonth(null, timeline, setOf(oct, nov), nov8))
    }

    @Test fun noFlowDataAtAllFallsBackToTheLatestOnTheTimeline() {
        assertEquals(nov, defaultShownMonth(null, timeline, emptySet(), nov8))
        assertEquals(nov, defaultShownMonth(null, emptyList(), emptySet(), nov8))
    }
}
