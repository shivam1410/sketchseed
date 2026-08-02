package com.shivam.sketchseed.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class StreaksTest {

    private val today = LocalDate.of(2026, 8, 2)

    private fun daysAgo(vararg offsets: Long): Set<LocalDate> =
        offsets.mapTo(mutableSetOf()) { today.minusDays(it) }

    @Test
    fun `no drawings means no streak`() {
        assertEquals(0, Streaks.current(emptySet(), today))
    }

    @Test
    fun `drawing only today is a one day streak`() {
        assertEquals(1, Streaks.current(daysAgo(0), today))
    }

    @Test
    fun `consecutive days ending today all count`() {
        assertEquals(4, Streaks.current(daysAgo(0, 1, 2, 3), today))
    }

    @Test
    fun `streak stays alive when yesterday was drawn but today has not been yet`() {
        // The day is not over. Showing a live streak here is the whole point:
        // the user should see what they stand to lose.
        assertEquals(3, Streaks.current(daysAgo(1, 2, 3), today))
    }

    @Test
    fun `streak is dead once a full day passes with nothing drawn`() {
        assertEquals(0, Streaks.current(daysAgo(2, 3, 4), today))
    }

    @Test
    fun `drawing today after a gap restarts the streak at one`() {
        assertEquals(1, Streaks.current(daysAgo(0, 5, 6, 7), today))
    }

    @Test
    fun `gap in the middle only counts the run touching today`() {
        assertEquals(2, Streaks.current(daysAgo(0, 1, 3, 4, 5), today))
    }

    @Test
    fun `best streak is zero with no drawings`() {
        assertEquals(0, Streaks.best(emptySet()))
    }

    @Test
    fun `best streak finds the longest historical run`() {
        // Runs of 2 (days 20,19), then 5 (days 10..6), then 1 (day 1).
        val dates = daysAgo(20, 19, 10, 9, 8, 7, 6, 1)
        assertEquals(5, Streaks.best(dates))
    }

    @Test
    fun `best streak counts a single isolated day as one`() {
        assertEquals(1, Streaks.best(daysAgo(9)))
    }

    @Test
    fun `best streak survives a dead current streak`() {
        val dates = daysAgo(30, 29, 28)
        assertEquals(3, Streaks.best(dates))
        assertEquals(0, Streaks.current(dates, today))
    }

    @Test
    fun `streak spans a month boundary`() {
        val julyEnd = LocalDate.of(2026, 8, 1)
        val dates = setOf(
            LocalDate.of(2026, 7, 30),
            LocalDate.of(2026, 7, 31),
            LocalDate.of(2026, 8, 1),
        )
        assertEquals(3, Streaks.current(dates, julyEnd))
    }

    @Test
    fun `streak spans a leap day`() {
        val mar1 = LocalDate.of(2028, 3, 1)
        val dates = setOf(
            LocalDate.of(2028, 2, 28),
            LocalDate.of(2028, 2, 29),
            LocalDate.of(2028, 3, 1),
        )
        assertEquals(3, Streaks.current(dates, mar1))
    }
}
