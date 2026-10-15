package dev.yuwixx.resonance.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

class StreakCalculatorTest {

    private val today = "2026-08-10"
    private val yesterday = "2026-08-09"

    @Test
    fun `empty dates yield zero streak`() {
        assertEquals(0, StreakCalculator.computeStreak(emptyList(), today, yesterday))
        assertEquals(0, StreakCalculator.computeLongestStreak(emptyList()))
    }

    @Test
    fun `streak is zero when most recent listen predates yesterday`() {
        val dates = listOf("2026-08-01", "2026-07-31")
        assertEquals(0, StreakCalculator.computeStreak(dates, today, yesterday))
    }

    @Test
    fun `streak counts consecutive days anchored at today`() {
        // Most-recent-first, as HistoryDao.getDistinctListenDates() returns them.
        val dates = listOf(today, "2026-08-09", "2026-08-08", "2026-08-05")
        assertEquals(3, StreakCalculator.computeStreak(dates, today, yesterday))
    }

    @Test
    fun `streak counts consecutive days anchored at yesterday when nothing listened today yet`() {
        val dates = listOf(yesterday, "2026-08-08", "2026-08-07")
        assertEquals(3, StreakCalculator.computeStreak(dates, today, yesterday))
    }

    @Test
    fun `single day is a streak of one`() {
        assertEquals(1, StreakCalculator.computeStreak(listOf(today), today, yesterday))
    }

    @Test
    fun `longest streak finds the best run anywhere in the list, not just the recent one`() {
        // Recent run is only 1 day (today), but there's a 4-day run further back.
        val dates = listOf(
            today,
            "2026-08-01", "2026-07-31", "2026-07-30", "2026-07-29",
            "2026-07-20",
        )
        assertEquals(4, StreakCalculator.computeLongestStreak(dates))
    }

    @Test
    fun `longest streak with no gaps equals the full list size`() {
        val dates = listOf(today, yesterday, "2026-08-08", "2026-08-07")
        assertEquals(4, StreakCalculator.computeLongestStreak(dates))
    }
}
