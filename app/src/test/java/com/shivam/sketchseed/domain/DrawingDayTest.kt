package com.shivam.sketchseed.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the day boundary sits.
 *
 * The bug this exists for: drawing at 00:14 was treated as the next calendar day,
 * so the app served tomorrow's prompt and counted the evening the user was sitting
 * in as missed.
 */
class DrawingDayTest {

    private fun at(date: String, time: String) =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time))

    @Test
    fun `the small hours belong to the day before`() {
        assertEquals(LocalDate.parse("2026-08-04"), DrawingDay.of(at("2026-08-05", "00:14")))
    }

    @Test
    fun `just before six is still the day before`() {
        assertEquals(LocalDate.parse("2026-08-04"), DrawingDay.of(at("2026-08-05", "05:59:59")))
    }

    @Test
    fun `six on the dot starts the new day`() {
        // Closed at the start, open at the end, so no instant lands in two days or
        // in neither.
        assertEquals(LocalDate.parse("2026-08-05"), DrawingDay.of(at("2026-08-05", "06:00")))
    }

    @Test
    fun `ordinary daytime is its own day`() {
        assertEquals(LocalDate.parse("2026-08-05"), DrawingDay.of(at("2026-08-05", "14:30")))
    }

    @Test
    fun `late evening is still its own day`() {
        assertEquals(LocalDate.parse("2026-08-05"), DrawingDay.of(at("2026-08-05", "23:59")))
    }

    @Test
    fun `every instant in a day maps to exactly one drawing day`() {
        val date = LocalDate.parse("2026-08-05")
        for (minute in 0 until 24 * 60) {
            val at = date.atTime(minute / 60, minute % 60)
            val expected = if (minute < 6 * 60) date.minusDays(1) else date
            assertEquals("at ${at.toLocalTime()}", expected, DrawingDay.of(at))
        }
    }

    @Test
    fun `the boundary crossing a month still lands on the previous day`() {
        assertEquals(LocalDate.parse("2026-07-31"), DrawingDay.of(at("2026-08-01", "01:00")))
    }
}
