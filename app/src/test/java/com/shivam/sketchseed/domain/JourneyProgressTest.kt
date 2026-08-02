package com.shivam.sketchseed.domain

import com.shivam.sketchseed.domain.model.DayRecord
import com.shivam.sketchseed.domain.model.Difficulty
import com.shivam.sketchseed.domain.model.JourneyProgress
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyProgressTest {

    private val today = LocalDate.of(2026, 8, 2)
    private val totalDays = 100

    private fun record(day: Int, completedOn: LocalDate) = DayRecord(
        day = day,
        promptText = "Prompt $day",
        difficulty = Difficulty.EASY,
        completedOnEpochDay = completedOn.toEpochDay(),
    )

    private fun progress(records: List<DayRecord>, now: LocalDate = today) =
        JourneyProgress.from(records, totalDays, now)

    @Test
    fun `a fresh journey starts on day one with nothing done`() {
        val p = progress(emptyList())

        assertEquals(0, p.completedCount)
        assertEquals(1, p.currentDay)
        assertEquals(0, p.percentComplete)
        assertTrue(p.canDrawToday)
        assertFalse(p.isComplete)
    }

    @Test
    fun `only day one is revealed at the start`() {
        val p = progress(emptyList())

        assertTrue(p.isRevealed(1))
        assertFalse(p.isRevealed(2))
        assertFalse(p.isRevealed(100))
    }

    @Test
    fun `finishing today advances the journey`() {
        val p = progress(listOf(record(1, today)))

        assertEquals(1, p.completedCount)
        assertEquals(2, p.currentDay)
        assertTrue(p.drewToday)
        assertFalse(p.canDrawToday)
    }

    @Test
    fun `tomorrows prompt stays sealed after todays sketch is done`() {
        // The core surprise rule: finishing day 1 must not expose day 2 early.
        val p = progress(listOf(record(1, today)))

        assertEquals(2, p.currentDay)
        assertTrue(p.isRevealed(1))
        assertFalse(p.isRevealed(2))
    }

    @Test
    fun `the next prompt unlocks the following day`() {
        val p = progress(listOf(record(1, today.minusDays(1))))

        assertTrue(p.canDrawToday)
        assertEquals(2, p.currentDay)
        assertTrue(p.isRevealed(2))
        assertFalse(p.isRevealed(3))
    }

    @Test
    fun `missing days costs the streak but never costs journey progress`() {
        // Drew day 1 a week ago, nothing since.
        val p = progress(listOf(record(1, today.minusDays(7))))

        assertEquals(1, p.completedCount)
        assertEquals(2, p.currentDay)
        assertEquals(0, p.currentStreak)
        assertEquals(1, p.bestStreak)
        assertTrue("the journey should still be drawable", p.canDrawToday)
    }

    @Test
    fun `progress and streak are counted independently`() {
        // Sixteen sketches, but the last three were on consecutive days only.
        val records = buildList {
            repeat(13) { i -> add(record(i + 1, today.minusDays((30 - i).toLong()))) }
            add(record(14, today.minusDays(2)))
            add(record(15, today.minusDays(1)))
            add(record(16, today))
        }
        val p = progress(records)

        assertEquals(16, p.completedCount)
        assertEquals(17, p.currentDay)
        assertEquals(16, p.percentComplete)
        assertEquals(3, p.currentStreak)
    }

    @Test
    fun `records are exposed in day order regardless of input order`() {
        val shuffled = listOf(
            record(3, today.minusDays(1)),
            record(1, today.minusDays(3)),
            record(2, today.minusDays(2)),
        )
        val p = progress(shuffled)

        assertEquals(listOf(1, 2, 3), p.records.map { it.day })
    }

    @Test
    fun `a finished journey stops offering new prompts`() {
        val records = (1..totalDays).map { record(it, today.minusDays((totalDays - it).toLong())) }
        val p = progress(records)

        assertTrue(p.isComplete)
        assertFalse(p.canDrawToday)
        assertEquals(100, p.percentComplete)
        assertEquals(totalDays, p.currentDay)
        assertTrue(p.isRevealed(totalDays))
    }

    @Test
    fun `lookup returns the record for a finished day and null for an unstarted one`() {
        val p = progress(listOf(record(1, today.minusDays(1))))

        assertEquals("Prompt 1", p.recordFor(1)?.promptText)
        assertNull(p.recordFor(2))
    }

    @Test
    fun `day zero and out of range days are never revealed`() {
        val p = progress(listOf(record(1, today.minusDays(1))))

        assertFalse(p.isRevealed(0))
        assertFalse(p.isRevealed(-1))
        assertFalse(p.isRevealed(totalDays + 1))
    }
}
