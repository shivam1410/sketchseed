package com.shivam.sketchseed.domain.model

import com.shivam.sketchseed.domain.Streaks
import java.time.LocalDate

/**
 * Everything the UI needs to know about where the user stands, derived from the
 * completed records plus what day it is now.
 *
 * Two counters that are easy to conflate are kept deliberately separate:
 *
 *  - [completedCount] / [currentDay] track the *journey*. They only ever move
 *    forward, one per finished sketch. Missing a day does not cost progress.
 *  - [currentStreak] tracks *consecutive calendar days*. Missing a day resets it.
 */
data class JourneyProgress(
    val records: List<DayRecord>,
    val totalDays: Int,
    val today: LocalDate,
    val currentStreak: Int,
    val bestStreak: Int,
) {
    /** How many sketches are finished. */
    val completedCount: Int get() = records.size

    /** True once all [totalDays] prompts are done. */
    val isComplete: Boolean get() = completedCount >= totalDays

    /** The day number the user is working on, e.g. "Day 17 of 100". */
    val currentDay: Int get() = (completedCount + 1).coerceAtMost(totalDays)

    /** Whether a sketch was already finished on today's date. */
    val drewToday: Boolean get() = records.any { it.completedOn == today }

    /** Whether there is still a prompt to draw today. */
    val canDrawToday: Boolean get() = !isComplete && !drewToday

    val percentComplete: Int
        get() = if (totalDays == 0) 0 else completedCount * 100 / totalDays

    /**
     * Whether [day]'s prompt may be shown.
     *
     * Finished days stay visible forever. The current day is shown only while it
     * is still drawable — once today's sketch is marked done, the next prompt
     * re-seals until tomorrow. That one rule is what keeps the surprise intact
     * and stops anyone reading ahead.
     */
    fun isRevealed(day: Int): Boolean =
        day in 1..completedCount || (day == currentDay && canDrawToday)

    fun recordFor(day: Int): DayRecord? = records.firstOrNull { it.day == day }

    companion object {
        fun from(
            records: List<DayRecord>,
            totalDays: Int,
            today: LocalDate,
        ): JourneyProgress {
            val sorted = records.sortedBy { it.day }
            val dates = sorted.mapTo(mutableSetOf()) { it.completedOn }
            return JourneyProgress(
                records = sorted,
                totalDays = totalDays,
                today = today,
                currentStreak = Streaks.current(dates, today),
                bestStreak = Streaks.best(dates),
            )
        }
    }
}
