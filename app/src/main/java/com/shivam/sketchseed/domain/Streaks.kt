package com.shivam.sketchseed.domain

import java.time.LocalDate

/**
 * Streak maths over the set of drawing days on which the user drew.
 *
 * A streak is about consecutive *drawing days* — see [DrawingDay], which do not
 * begin at midnight — and is deliberately separate from journey progress. Missing
 * a day can cost the streak but never costs a day of the hundred: the journey
 * waits.
 *
 * ## One missed day can be forgiven, but it has to be earned
 *
 * A single gap is bridged only if every one of the two days behind it that
 * *existed* was drawn. That condition is the whole point of the rule. Forgiving
 * every isolated gap unconditionally would let someone draw on alternate days
 * forever and watch a streak climb on half the effort, which makes the number
 * meaningless. Requiring drawn days behind the gap means forgiveness cannot be
 * spent before it has been built up, and two gaps in a row can never both be
 * forgiven — the day behind the second one is itself missing.
 *
 * "That existed" is load-bearing. A gap on the journey's second day has only one
 * day behind it, and holding out for two would make the rule unsatisfiable at
 * exactly the point a new habit is most likely to slip. So the requirement is
 * every prior day within the journey, up to two — not two unconditionally.
 *
 * The forgiven day is bridged, never counted. A streak is always the number of
 * days actually drawn, so it can never exceed the number of sketches behind it.
 */
object Streaks {

    /**
     * Length of the streak that is still alive as of [today].
     *
     * Drawing today obviously continues it. Not having drawn yet today also counts
     * as alive, because the day is not over — and with forgiveness in play a
     * streak can still be alive with yesterday missing too, so long as the days
     * behind that gap were drawn. In that state it survives only if the user draws
     * today, which is exactly the tension a streak is for.
     */
    fun current(dates: Set<LocalDate>, today: LocalDate, startDate: LocalDate): Int =
        // The day is not over, so an undrawn today does not by itself end anything.
        runEndingAt(dates, if (dates.contains(today)) today else today.minusDays(1), startDate)

    /** Longest run ever achieved, under the same forgiveness rule. */
    fun best(dates: Set<LocalDate>, startDate: LocalDate): Int =
        // At most a hundred dates, so the straightforward pass over each end point
        // is cheaper to trust than an incremental one is to get right.
        dates.maxOfOrNull { runEndingAt(dates, it, startDate) } ?: 0

    /**
     * Walks backwards from [end], counting drawn days and stepping over gaps the
     * user has earned the right to skip.
     */
    private fun runEndingAt(dates: Set<LocalDate>, end: LocalDate, startDate: LocalDate): Int {
        var length = 0
        var cursor = end

        while (!cursor.isBefore(startDate)) {
            if (dates.contains(cursor)) {
                length++
                cursor = cursor.minusDays(1)
                continue
            }

            val behind = cursor.minusDays(1)
            if (!isForgiven(dates, cursor, startDate)) return length
            cursor = behind
        }

        return length
    }

    /**
     * Whether the gap at [gap] has been earned out of.
     *
     * Empty means the gap sits on or before day 1, where there is no history to
     * have earned anything with.
     */
    private fun isForgiven(dates: Set<LocalDate>, gap: LocalDate, startDate: LocalDate): Boolean {
        val behind = listOf(gap.minusDays(1), gap.minusDays(2))
            .filter { !it.isBefore(startDate) }

        return behind.isNotEmpty() && behind.all(dates::contains)
    }
}
