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
    }
}
