package com.shivam.sketchseed.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * When a drawing day starts and ends.
 *
 * Not midnight. Someone still awake at half past midnight thinking "I should
 * draw today's" is, by any human account, finishing yesterday — but the calendar
 * had already moved on, so the app served them tomorrow's prompt and counted the
 * day they were sitting in as missed. Midnight is a boundary the clock cares
 * about and nobody else does.
 *
 * So a drawing day runs from [START] to [START] the next morning. Anything drawn
 * before 6am belongs to the day before. This is the same trick sleep trackers and
 * habit apps use, and 6am is late enough to cover a genuine night owl while still
 * being unambiguously "morning" for anyone who keeps ordinary hours.
 *
 * Only the *journey* uses this clock — which prompt is current, whether today is
 * drawn, and which dates a streak is counted over. Reminder scheduling
 * deliberately does not: a 9am nudge means 9am on the wall.
 */
object DrawingDay {

    /** The hour a drawing day begins. */
    val START: LocalTime = LocalTime.of(6, 0)

    /**
     * Which drawing day [now] falls in.
     *
     * Exactly [START] belongs to the new day, so the boundary is closed at the
     * start and open at the end — no instant belongs to two days or to neither.
     */
    fun of(now: LocalDateTime): LocalDate =
        if (now.toLocalTime() < START) now.toLocalDate().minusDays(1) else now.toLocalDate()
}
