package com.shivam.sketchseed.notify

import com.shivam.sketchseed.data.Settings
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The three reminders in a day.
 *
 * Three rather than one because a single nudge is easy to swipe away and forget,
 * and easy to miss entirely if it lands mid-meeting. Spread across the day, at
 * least one usually arrives when the person can act on it.
 *
 * The enum is identity only — which reminder this is, and the name its scheduled
 * work goes by. The times live in [Settings], defaults included, because the user
 * moves them: someone who draws on the commute wants 7am, someone who draws in
 * bed wants midnight.
 *
 * Deliberately no default time here. An earlier version carried one alongside the
 * defaults in [Settings], which meant two places claiming to define 9am and
 * nothing keeping them honest — changing this one silently did nothing at all.
 */
enum class ReminderSlot(val workName: String) {
    MORNING("sketchseed-reminder-morning"),
    EVENING("sketchseed-reminder-evening"),
    NIGHT("sketchseed-reminder-night"),
    ;

    /** Where the user has put this reminder. */
    fun timeIn(settings: Settings): LocalTime = when (this) {
        MORNING -> settings.morningReminder
        EVENING -> settings.eveningReminder
        NIGHT -> settings.nightReminder
    }

    companion object {
        /**
         * How far a run may drift from its slot and still be worth posting.
         *
         * A nudge an hour late is still a nudge. Several hours late is a different
         * message entirely — "last chance today" arriving at breakfast, or a
         * morning reminder at teatime.
         */
        val TOLERANCE: Duration = Duration.ofHours(2)

        /**
         * How long until [at] next comes round.
         *
         * Rolls to tomorrow when the time has already passed today, which is the
         * case for most slots whenever reminders are switched on. Exactly-now
         * counts as passed, so moving a reminder onto the current minute does not
         * fire one on the spot.
         */
        fun initialDelay(at: LocalTime, now: LocalDateTime): Duration {
            val todayAt = now.toLocalDate().atTime(at)
            val next = if (todayAt.isAfter(now)) todayAt else todayAt.plusDays(1)
            return Duration.between(now, next)
        }

        /**
         * How far [now] sits from the nearest occurrence of [at]; positive is late.
         *
         * Measured against the nearest occurrence in either direction rather than
         * today's, so a run just after midnight is judged against last night's slot
         * instead of being scored as almost a full day early.
         */
        fun drift(at: LocalTime, now: LocalDateTime): Duration {
            val todayAt = now.toLocalDate().atTime(at)
            return listOf(todayAt.minusDays(1), todayAt, todayAt.plusDays(1))
                .map { Duration.between(it, now) }
                .minByOrNull { it.absolute() }
                ?: Duration.ZERO
        }

        /**
         * Whether a run at [now] has drifted too far from [at] to post.
         *
         * WorkManager is inexact by design, and Doze, battery saver or an app
         * upgrade can hold a job for hours — it then runs at the next opportunity,
         * which is usually the moment the app is opened. Without this the user gets
         * a reminder the instant they open the app, naming a time long past.
         */
        fun isStale(at: LocalTime, now: LocalDateTime, tolerance: Duration = TOLERANCE): Boolean =
            drift(at, now).absolute() > tolerance

        /** [Duration.abs] is Java 18; this has to run on API 26. */
        private fun Duration.absolute(): Duration = if (isNegative) negated() else this
    }
}
