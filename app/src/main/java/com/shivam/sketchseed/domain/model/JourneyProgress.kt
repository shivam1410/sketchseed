package com.shivam.sketchseed.domain.model

import com.shivam.sketchseed.domain.Streaks
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Where the user stands, derived from their records plus today's date.
 *
 * Day numbers come from the calendar, not from personal progress: day N is the
 * Nth day after the pack's [startDate], the same for every install of this
 * build. That is what lets two people compare sketches without a server.
 *
 * Three ideas that are easy to conflate are kept apart:
 *
 *  - [currentDay] — which prompt the calendar says it is. Identical for everyone.
 *  - [completedCount] — how many you have actually finished, including ones
 *    back-filled late. Personal.
 *  - [currentStreak] — consecutive calendar days you drew on. Personal, and
 *    unforgiving: back-filling day 8 on day 9 does not repair it, because you
 *    genuinely did not draw on day 8.
 *
 * Missing a day therefore costs the streak but never costs the hundred: the
 * prompt stays open to [canComplete] afterwards.
 */
data class JourneyProgress(
    val records: List<DayRecord>,
    val totalDays: Int,
    val today: LocalDate,
    val startDate: LocalDate,
    val currentStreak: Int,
    val bestStreak: Int,
) {
    /**
     * Today's day number, which may run past [totalDays] once the hundred days
     * have elapsed, or below 1 if the device clock predates [startDate].
     */
    val rawDayNumber: Int
        get() = ChronoUnit.DAYS.between(startDate, today).toInt() + 1

    /** Today's prompt number, or null when today is outside the hundred days. */
    val currentDay: Int?
        get() = rawDayNumber.takeIf { it in 1..totalDays }

    /** True before day 1 — only reachable with a misconfigured device clock. */
    val hasNotStarted: Boolean get() = rawDayNumber < 1

    /** The hundred days have elapsed; only back-filling is left. */
    val isWindowClosed: Boolean get() = rawDayNumber > totalDays

    /** Highest day number the calendar has unsealed so far. */
    val revealedThrough: Int get() = rawDayNumber.coerceIn(0, totalDays)

    val completedCount: Int get() = records.size

    val isComplete: Boolean get() = completedCount >= totalDays

    val percentComplete: Int
        get() = if (totalDays == 0) 0 else completedCount * 100 / totalDays

    fun recordFor(day: Int): DayRecord? = records.firstOrNull { it.day == day }

    /** Today's finished sketch, if it is done. */
    val todayRecord: DayRecord? get() = currentDay?.let(::recordFor)

    /** There is still a prompt to draw for today's date. */
    val canDrawToday: Boolean get() = currentDay != null && todayRecord == null

    /**
     * Whether [day]'s prompt may be shown.
     *
     * Everything the calendar has already reached is fair game — those prompts
     * were public knowledge on their day. Anything ahead stays sealed.
     */
    fun isRevealed(day: Int): Boolean = day in 1..revealedThrough

    /** Whether [day] can still be drawn, whether that is today's or a back-fill. */
    fun canComplete(day: Int): Boolean = isRevealed(day) && recordFor(day) == null

    /** Past days left undrawn, excluding today's, oldest first. */
    val missedDays: List<Int>
        get() = (1..revealedThrough).filter { it != currentDay && recordFor(it) == null }

    companion object {
        fun from(
            records: List<DayRecord>,
            totalDays: Int,
            startDate: LocalDate,
            today: LocalDate,
        ): JourneyProgress {
            val sorted = records.sortedBy { it.day }
            // Streaks count the dates drawn on, which for a back-filled day is
            // the day it was actually drawn, not the day it belonged to.
            val dates = sorted.mapTo(mutableSetOf()) { it.completedOn }
            return JourneyProgress(
                records = sorted,
                totalDays = totalDays,
                today = today,
                startDate = startDate,
                currentStreak = Streaks.current(dates, today),
                bestStreak = Streaks.best(dates),
            )
        }
    }
}
