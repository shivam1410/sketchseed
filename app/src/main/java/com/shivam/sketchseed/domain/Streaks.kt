package com.shivam.sketchseed.domain

import java.time.LocalDate

/**
 * Streak maths over the set of calendar dates on which the user drew.
 *
 * A streak is about consecutive *calendar days*, which is deliberately separate
 * from journey progress. Missing a day costs the streak but never costs a day
 * of the hundred: the journey waits.
 */
object Streaks {

    /**
     * Length of the streak that is still alive as of [today].
     *
     * Drawing today obviously continues the streak. Having drawn yesterday but
     * not yet today also counts as alive, because the day is not over — the
     * streak only dies once a full calendar day passes with nothing drawn.
     */
    fun current(dates: Set<LocalDate>, today: LocalDate): Int {
        val anchor = when {
            dates.contains(today) -> today
            dates.contains(today.minusDays(1)) -> today.minusDays(1)
            else -> return 0
        }

        var length = 0
        var cursor = anchor
        while (dates.contains(cursor)) {
            length++
            cursor = cursor.minusDays(1)
        }
        return length
    }

    /** Longest run of consecutive days ever achieved. */
    fun best(dates: Set<LocalDate>): Int {
        if (dates.isEmpty()) return 0

        val sorted = dates.sorted()
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i - 1].plusDays(1) == sorted[i]) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }
}
