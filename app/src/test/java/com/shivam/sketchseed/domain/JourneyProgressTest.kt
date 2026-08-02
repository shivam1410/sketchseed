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

    private val start = LocalDate.of(2026, 8, 2)
    private val totalDays = 100

    /** The calendar date day [n] falls on. */
    private fun dateOfDay(n: Int): LocalDate = start.plusDays((n - 1).toLong())

    /** A record for [day], drawn on [drawnOn] (defaults to its own day). */
    private fun record(day: Int, drawnOn: LocalDate = dateOfDay(day)) = DayRecord(
        day = day,
        promptText = "Prompt $day",
        difficulty = Difficulty.EASY,
        completedOnEpochDay = drawnOn.toEpochDay(),
    )

    private fun progressOn(dayNumber: Int, records: List<DayRecord>) =
        JourneyProgress.from(records, totalDays, start, dateOfDay(dayNumber))

    // ── Day numbers come from the calendar ───────────────────────────────────

    @Test
    fun `day one is the start date`() {
        val p = progressOn(1, emptyList())

        assertEquals(1, p.currentDay)
        assertTrue(p.canDrawToday)
        assertEquals(0, p.completedCount)
    }

    @Test
    fun `the day number tracks elapsed calendar days, not personal progress`() {
        // Nothing drawn at all, but the calendar has moved on to day 8.
        val p = progressOn(8, emptyList())

        assertEquals(8, p.currentDay)
    }

    @Test
    fun `two people on the same date are on the same day regardless of what they have drawn`() {
        val diligent = progressOn(8, (1..7).map { record(it) })
        val lapsed = progressOn(8, emptyList())

        assertEquals(diligent.currentDay, lapsed.currentDay)
        assertEquals(8, diligent.currentDay)
    }

    // ── Reveal rules ─────────────────────────────────────────────────────────

    @Test
    fun `everything up to today is revealed and everything after is sealed`() {
        val p = progressOn(8, emptyList())

        assertTrue(p.isRevealed(1))
        assertTrue(p.isRevealed(8))
        assertFalse(p.isRevealed(9))
        assertFalse(p.isRevealed(100))
    }

    @Test
    fun `finishing today does not unseal tomorrow`() {
        val p = progressOn(8, (1..8).map { record(it) })

        assertFalse(p.canDrawToday)
        assertFalse(p.isRevealed(9))
    }

    @Test
    fun `day zero and out of range days are never revealed`() {
        val p = progressOn(8, emptyList())

        assertFalse(p.isRevealed(0))
        assertFalse(p.isRevealed(-1))
        assertFalse(p.isRevealed(totalDays + 1))
    }

    // ── The two-friends scenario ─────────────────────────────────────────────

    @Test
    fun `missing day eight breaks the streak but leaves the prompt open`() {
        // Both friends drew days 1-7. This one skipped day 8. It is now day 9.
        val p = progressOn(9, (1..7).map { record(it) })

        assertEquals(9, p.currentDay)
        assertEquals(listOf(8), p.missedDays)
        assertTrue("day 8 must remain back-fillable", p.canComplete(8))
        assertEquals("the streak is gone", 0, p.currentStreak)
        assertEquals(7, p.bestStreak)
        assertEquals(7, p.completedCount)
    }

    @Test
    fun `back-filling day eight on day nine does not repair the streak`() {
        val backFilled = record(day = 8, drawnOn = dateOfDay(9))
        val p = progressOn(9, (1..7).map { record(it) } + backFilled)

        assertEquals(8, p.completedCount)
        assertTrue(p.missedDays.isEmpty())
        // Drawing today starts a fresh streak of one; it does not restore seven.
        assertEquals(1, p.currentStreak)
        assertEquals(7, p.bestStreak)
    }

    @Test
    fun `the diligent friend keeps an unbroken streak`() {
        val p = progressOn(9, (1..8).map { record(it) })

        assertEquals(8, p.currentStreak)
        assertTrue(p.canDrawToday)
        assertTrue(p.missedDays.isEmpty())
    }

    @Test
    fun `back-filling several days in one sitting counts once toward the streak`() {
        val today = dateOfDay(10)
        val records = (1..3).map { record(it) } +
            listOf(4, 5, 6, 7, 8, 9).map { record(it, drawnOn = today) }
        val p = progressOn(10, records)

        assertEquals(9, p.completedCount)
        assertEquals(1, p.currentStreak)
    }

    // ── Joining late ─────────────────────────────────────────────────────────

    @Test
    fun `someone installing on day forty can still work back to day one`() {
        val p = progressOn(40, emptyList())

        assertEquals(40, p.currentDay)
        assertEquals(39, p.missedDays.size)
        assertEquals(1, p.missedDays.first())
        assertTrue(p.canComplete(1))
        assertTrue(p.canComplete(39))
        assertFalse(p.canComplete(41))
    }

    // ── Counting ─────────────────────────────────────────────────────────────

    @Test
    fun `today is not counted as missed while it is still drawable`() {
        val p = progressOn(5, (1..4).map { record(it) })

        assertTrue(p.missedDays.isEmpty())
        assertTrue(p.canDrawToday)
    }

    @Test
    fun `already completed days cannot be completed again`() {
        val p = progressOn(5, listOf(record(3)))

        assertFalse(p.canComplete(3))
        assertTrue(p.canComplete(2))
    }

    @Test
    fun `percent complete counts finished days out of the whole pack`() {
        val p = progressOn(40, (1..25).map { record(it) })

        assertEquals(25, p.completedCount)
        assertEquals(25, p.percentComplete)
        assertFalse(p.isComplete)
    }

    @Test
    fun `records are exposed in day order regardless of input order`() {
        val shuffled = listOf(record(3), record(1), record(2))
        val p = progressOn(5, shuffled)

        assertEquals(listOf(1, 2, 3), p.records.map { it.day })
    }

    @Test
    fun `lookup returns the record for a finished day and null otherwise`() {
        val p = progressOn(5, listOf(record(1)))

        assertEquals("Prompt 1", p.recordFor(1)?.promptText)
        assertNull(p.recordFor(2))
    }

    // ── Window edges ─────────────────────────────────────────────────────────

    @Test
    fun `after the hundred days elapse there is no current day but gaps stay open`() {
        val p = progressOn(totalDays + 5, (1..90).map { record(it) })

        assertNull(p.currentDay)
        assertTrue(p.isWindowClosed)
        assertFalse(p.canDrawToday)
        assertEquals(10, p.missedDays.size)
        assertTrue(p.canComplete(100))
        assertFalse(p.isComplete)
    }

    @Test
    fun `completing all hundred finishes the journey`() {
        val p = progressOn(totalDays, (1..totalDays).map { record(it) })

        assertTrue(p.isComplete)
        assertEquals(100, p.percentComplete)
        assertTrue(p.missedDays.isEmpty())
    }

    @Test
    fun `a clock set before the start date reveals nothing`() {
        val p = JourneyProgress.from(emptyList(), totalDays, start, start.minusDays(3))

        assertTrue(p.hasNotStarted)
        assertNull(p.currentDay)
        assertFalse(p.isRevealed(1))
        assertFalse(p.canDrawToday)
        assertEquals(0, p.revealedThrough)
    }
}
