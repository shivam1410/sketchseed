package com.shivam.sketchseed.domain

import com.shivam.sketchseed.domain.model.DayRecord
import com.shivam.sketchseed.domain.model.Difficulty
import com.shivam.sketchseed.domain.model.ExtraSketch
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
    fun `missing day eight is forgiven because seven straight days earned it`() {
        // Both friends drew days 1-7. This one skipped day 8. It is now day 9.
        //
        // The streak survives: a single gap is bridged when the two days behind it
        // were drawn, and seven consecutive days more than covers that. Day 8 is
        // bridged, never counted — the streak is the seven days actually drawn.
        val p = progressOn(9, (1..7).map { record(it) })

        assertEquals(9, p.currentDay)
        assertEquals(listOf(8), p.missedDays)
        assertTrue("day 8 must remain back-fillable", p.canComplete(8))
        assertEquals(7, p.currentStreak)
        assertEquals(7, p.completedCount)
        assertEquals("bridged, not counted", p.completedCount, p.currentStreak)
    }

    @Test
    fun `a second missed day in a row is not forgiven`() {
        // Days 1-7 drawn, 8 and 9 both missed, now day 10. The day behind the
        // second gap is itself missing, so the rule cannot be satisfied.
        val p = progressOn(10, (1..7).map { record(it) })

        assertEquals(listOf(8, 9), p.missedDays)
        assertEquals(0, p.currentStreak)
        assertEquals(7, p.bestStreak)
    }

    @Test
    fun `back-filling counts the day it was drawn, not the day it was for`() {
        // The invariant forgiveness does not change: a record is recorded against
        // the date the user actually drew. Back-filling can therefore never
        // manufacture attendance on a date they were absent.
        //
        // Days 1 and 4 drawn on their own dates, 2 and 3 both missed — an
        // unforgivable double gap. Back-filling day 2 on day 4 closes the hole in
        // the journey without putting anything on day 2's or day 3's date.
        val backFilled = record(day = 2, drawnOn = dateOfDay(4))
        val p = progressOn(4, listOf(record(1), record(4), backFilled))

        assertEquals(3, p.completedCount)
        assertEquals(listOf(3), p.missedDays)
        // Day 4 alone: day 3 is missing and day 2's date was never drawn on, so
        // there is nothing behind the gap to earn forgiveness with.
        assertEquals(1, p.currentStreak)
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

    // ── Extras are inert ─────────────────────────────────────────────────────
    //
    // A day can hold any number of bonus sketches. None of them may buy progress
    // through the hundred, or the whole point of one-a-day collapses.

    private fun extra(n: Int) = ExtraSketch(
        id = "extra-$n",
        title = "Bonus $n",
        photoFileName = "extra-$n.jpg",
        createdOnEpochDay = dateOfDay(n).toEpochDay(),
    )

    @Test
    fun `piling extras onto a day still counts as one day`() {
        val loaded = record(1).copy(extras = List(8) { extra(it) })
        val p = progressOn(2, listOf(loaded))

        assertEquals(1, p.completedCount)
        assertEquals(1, p.percentComplete)
        assertEquals(2, p.currentDay)
    }

    @Test
    fun `extras do not unlock the next day`() {
        val loaded = record(5).copy(extras = List(3) { extra(it) })
        val p = progressOn(5, listOf(loaded))

        assertFalse("day 5 is done, so nothing is drawable today", p.canDrawToday)
        assertFalse("day 6 must stay sealed", p.isRevealed(6))
    }

    @Test
    fun `extras do not extend the streak`() {
        // Drew day 1 only, then piled on five extras. It is now day 3, so day 2 is
        // a gap — forgiven, because day 1 was the only prior day that existed and
        // it was drawn.
        //
        // The point of this test is that the five extras contribute nothing: the
        // streak is identical with and without them.
        val loaded = record(1).copy(extras = List(5) { extra(it) })
        val withExtras = progressOn(3, listOf(loaded))
        val withoutExtras = progressOn(3, listOf(record(1)))

        assertEquals(withoutExtras.currentStreak, withExtras.currentStreak)
        assertEquals(withoutExtras.bestStreak, withExtras.bestStreak)
        assertEquals(1, withExtras.currentStreak)
    }

    @Test
    fun `a day with extras is not treated as missed`() {
        val p = progressOn(3, listOf(record(1).copy(extras = listOf(extra(1))), record(2)))

        assertTrue(p.missedDays.isEmpty())
        assertEquals(2, p.completedCount)
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
