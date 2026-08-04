package com.shivam.sketchseed.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class StreaksTest {

    private val today = LocalDate.of(2026, 8, 2)

    /** Far enough back that the "did this day exist" filter never interferes. */
    private val longAgo = today.minusDays(60)

    private fun daysAgo(vararg offsets: Long): Set<LocalDate> =
        offsets.mapTo(mutableSetOf()) { today.minusDays(it) }

    private fun current(dates: Set<LocalDate>, startDate: LocalDate = longAgo) =
        Streaks.current(dates, today, startDate)

    private fun best(dates: Set<LocalDate>, startDate: LocalDate = longAgo) =
        Streaks.best(dates, startDate)

    // ── The basics ───────────────────────────────────────────────────────────

    @Test
    fun `no drawings means no streak`() {
        assertEquals(0, current(emptySet()))
    }

    @Test
    fun `drawing only today is a one day streak`() {
        assertEquals(1, current(daysAgo(0)))
    }

    @Test
    fun `consecutive days ending today all count`() {
        assertEquals(4, current(daysAgo(0, 1, 2, 3)))
    }

    @Test
    fun `streak stays alive when yesterday was drawn but today has not been yet`() {
        // The day is not over. Showing a live streak here is the whole point:
        // the user should see what they stand to lose.
        assertEquals(3, current(daysAgo(1, 2, 3)))
    }

    @Test
    fun `drawing today after a long gap restarts the streak at one`() {
        assertEquals(1, current(daysAgo(0, 5, 6, 7)))
    }

    // ── Forgiveness ──────────────────────────────────────────────────────────

    @Test
    fun `a single gap is bridged when the two days behind it were drawn`() {
        // Drew 5,4,3 days ago, missed 2 days ago, drew yesterday and today.
        // Five days drawn, one forgiven.
        assertEquals(5, current(daysAgo(0, 1, 3, 4, 5)))
    }

    @Test
    fun `the forgiven day is bridged but never counted`() {
        // A streak can never exceed the number of sketches behind it, otherwise
        // the number stops meaning "days I drew".
        val dates = daysAgo(0, 1, 3, 4)
        assertEquals(dates.size, current(dates))
    }

    @Test
    fun `a gap is not forgiven when only one of the two days behind it was drawn`() {
        // Drew 3 days ago but not 4, so nothing has been earned.
        assertEquals(2, current(daysAgo(0, 1, 3)))
    }

    @Test
    fun `two gaps in a row are never both forgiven`() {
        // Missed 2 and 3 days ago. The day behind the second gap is itself
        // missing, so the rule cannot be satisfied.
        assertEquals(2, current(daysAgo(0, 1, 4, 5, 6)))
    }

    @Test
    fun `drawing every other day does not sustain a streak`() {
        // The whole reason forgiveness has to be earned. Under an unconditional
        // one-day grace this would climb forever on half the effort.
        assertEquals(1, current(daysAgo(0, 2, 4, 6, 8, 10)))
    }

    @Test
    fun `a streak is still alive with yesterday forgiven and today not yet drawn`() {
        // Drew 2,3,4 days ago, missed yesterday, today is not over. Alive, but it
        // survives only if the user draws today — which is the tension a streak is
        // for. Before forgiveness existed this was zero.
        assertEquals(3, current(daysAgo(2, 3, 4)))
    }

    @Test
    fun `that at-risk streak dies if the day passes undrawn`() {
        // Same set, one day later: now two consecutive days are missing.
        assertEquals(0, Streaks.current(daysAgo(2, 3, 4), today.plusDays(1), longAgo))
    }

    @Test
    fun `two full days missed ends the streak`() {
        assertEquals(0, current(daysAgo(3, 4, 5)))
    }

    // ── The first days of a journey ──────────────────────────────────────────

    @Test
    fun `a gap on the second day is forgiven by the single day behind it`() {
        // Reported case: drew day 1, missed day 2, drew day 3. Demanding two drawn
        // days behind the gap is unsatisfiable this early, so the requirement is
        // every prior day *within the journey* — here just day 1.
        val dayOne = LocalDate.of(2026, 8, 3)
        val dates = setOf(dayOne, LocalDate.of(2026, 8, 5))

        assertEquals(2, Streaks.current(dates, LocalDate.of(2026, 8, 5), dayOne))
    }

    @Test
    fun `a day before the journey started is not held against the user`() {
        val dayOne = today.minusDays(2)

        // The day before day one cannot have been drawn, so it must not be the
        // thing that denies forgiveness.
        assertEquals(2, current(setOf(dayOne, today), startDate = dayOne))
    }

    @Test
    fun `a gap on day one itself is not forgiven`() {
        // There is no history to have earned anything with.
        val dayOne = today.minusDays(1)

        assertEquals(1, current(setOf(today), startDate = dayOne))
    }

    @Test
    fun `once the journey is old enough both days behind a gap are required`() {
        val dayOne = today.minusDays(10)

        // Drew 3 days ago but not 4, and now the journey is old enough that day 4
        // genuinely existed and was skipped.
        assertEquals(2, current(daysAgo(0, 1, 3), startDate = dayOne))
    }

    // ── Best ever ────────────────────────────────────────────────────────────

    @Test
    fun `best streak is zero with no drawings`() {
        assertEquals(0, best(emptySet()))
    }

    @Test
    fun `best streak finds the longest historical run`() {
        // Runs of 2 (20,19), then 5 (10..6), then 1 (day 1).
        assertEquals(5, best(daysAgo(20, 19, 10, 9, 8, 7, 6, 1)))
    }

    @Test
    fun `best streak counts a single isolated day as one`() {
        assertEquals(1, best(daysAgo(9)))
    }

    @Test
    fun `best streak survives a dead current streak`() {
        val dates = daysAgo(30, 29, 28)

        assertEquals(3, best(dates))
        assertEquals(0, current(dates))
    }

    @Test
    fun `best streak applies the same forgiveness as the current one`() {
        // Otherwise best could come out lower than current, which is nonsense.
        val dates = daysAgo(10, 9, 7, 6)

        assertEquals(4, best(dates))
    }

    @Test
    fun `best is never smaller than current`() {
        val dates = daysAgo(0, 1, 3, 4, 5)

        assertEquals(current(dates), best(dates))
    }

    // ── Calendar edges ───────────────────────────────────────────────────────

    @Test
    fun `streak spans a month boundary`() {
        val augFirst = LocalDate.of(2026, 8, 1)
        val dates = setOf(
            LocalDate.of(2026, 7, 30),
            LocalDate.of(2026, 7, 31),
            augFirst,
        )

        assertEquals(3, Streaks.current(dates, augFirst, LocalDate.of(2026, 7, 1)))
    }

    @Test
    fun `streak spans a leap day`() {
        val mar1 = LocalDate.of(2028, 3, 1)
        val dates = setOf(
            LocalDate.of(2028, 2, 28),
            LocalDate.of(2028, 2, 29),
            mar1,
        )

        assertEquals(3, Streaks.current(dates, mar1, LocalDate.of(2028, 2, 1)))
    }

    @Test
    fun `forgiveness bridges a month boundary`() {
        val dates = setOf(
            LocalDate.of(2026, 7, 29),
            LocalDate.of(2026, 7, 30),
            // 31 July missed
            LocalDate.of(2026, 8, 1),
        )

        assertEquals(3, Streaks.current(dates, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 7, 1)))
    }
}
