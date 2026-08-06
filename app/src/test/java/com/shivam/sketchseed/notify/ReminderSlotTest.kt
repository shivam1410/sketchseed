package com.shivam.sketchseed.notify

import com.shivam.sketchseed.data.Settings
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When each slot next comes round.
 *
 * Worth testing because the failure is invisible: get the roll-to-tomorrow wrong
 * and reminders either fire the instant they are switched on, or silently not at
 * all for a day.
 */
class ReminderSlotTest {

    private val day = LocalDate.of(2026, 8, 3)

    private fun at(hour: Int, minute: Int = 0) = LocalDateTime.of(day, LocalTime.of(hour, minute))

    @Test
    fun `slots default to morning, evening and late evening`() {
        // Asserted through Settings, which is the only place the defaults live.
        // An earlier version also carried them on the enum, so changing one
        // quietly changed nothing.
        val fresh = Settings()

        assertEquals(LocalTime.of(9, 0), ReminderSlot.MORNING.timeIn(fresh))
        assertEquals(LocalTime.of(18, 0), ReminderSlot.EVENING.timeIn(fresh))
        assertEquals(LocalTime.of(22, 0), ReminderSlot.NIGHT.timeIn(fresh))
    }

    @Test
    fun `a slot later today is waited for, not skipped`() {
        val delay = ReminderSlot.initialDelay(LocalTime.of(18, 0), at(14))

        assertEquals(4 * 60, delay.toMinutes())
    }

    @Test
    fun `a slot already past today rolls to tomorrow`() {
        val delay = ReminderSlot.initialDelay(LocalTime.of(9, 0), at(14))

        assertEquals(19 * 60, delay.toMinutes())
    }

    @Test
    fun `enabling exactly on the hour does not fire immediately`() {
        // Otherwise switching reminders on at 09:00 would nudge on the spot,
        // which reads as a bug rather than a reminder.
        val delay = ReminderSlot.initialDelay(LocalTime.of(9, 0), at(9))

        assertEquals(24 * 60, delay.toMinutes())
    }

    @Test
    fun `a minute before the slot waits only that minute`() {
        val delay = ReminderSlot.initialDelay(LocalTime.of(22, 0), at(21, 59))

        assertEquals(1, delay.toMinutes())
    }

    @Test
    fun `every slot is always within the next twenty four hours`() {
        for (hour in 0..23) {
            ReminderSlot.entries.forEach { slot ->
                val minutes = ReminderSlot.initialDelay(slot.timeIn(Settings()), at(hour)).toMinutes()
                assertTrue(
                    "$slot at $hour:00 gave $minutes minutes",
                    minutes in 1..(24 * 60),
                )
            }
        }
    }

    @Test
    fun `a slot reads its time from settings, not its default`() {
        val settings = Settings(morningReminderMinute = 7 * 60 + 30)

        assertEquals(LocalTime.of(7, 30), ReminderSlot.MORNING.timeIn(settings))
        // Untouched slots still sit on their defaults.
        assertEquals(LocalTime.of(18, 0), ReminderSlot.EVENING.timeIn(settings))
    }

    @Test
    fun `an out of range stored minute cannot crash the scheduler`() {
        val settings = Settings(nightReminderMinute = 99_999)

        assertEquals(LocalTime.of(23, 59), ReminderSlot.NIGHT.timeIn(settings))
    }

    @Test
    fun `each slot has its own work name so they do not overwrite each other`() {
        val names = ReminderSlot.entries.map { it.workName }

        assertEquals(names.size, names.toSet().size)
    }

    // ── Staleness: a job the OS held back must not nudge for a slot long gone ──

    @Test
    fun `a run on time is not stale`() {
        assertFalse(ReminderSlot.isStale(LocalTime.of(9, 0), at(9, 0)))
    }

    @Test
    fun `a run a few minutes late is still worth posting`() {
        // WorkManager is inexact by design; nine seconds to a few minutes is normal.
        assertFalse(ReminderSlot.isStale(LocalTime.of(9, 0), at(9, 12)))
    }

    @Test
    fun `a run just inside the tolerance still posts`() {
        assertFalse(ReminderSlot.isStale(LocalTime.of(9, 0), at(10, 59)))
    }

    @Test
    fun `a run hours late is stale`() {
        // The reported symptom: a deferred job runs when the app is next opened, and
        // a morning nudge arrives at teatime.
        assertTrue(ReminderSlot.isStale(LocalTime.of(9, 0), at(15, 30)))
    }

    @Test
    fun `lateness is measured against the nearest occurrence, not today's`() {
        // 00:30 against a 22:00 slot is two and a half hours late, not twenty-one
        // and a half early. Getting this wrong would mark every after-midnight run
        // stale regardless of its slot.
        assertEquals(150, ReminderSlot.drift(LocalTime.of(22, 0), at(0, 30)).toMinutes())
    }

    @Test
    fun `a night slot run shortly after midnight is judged on that basis`() {
        assertFalse(ReminderSlot.isStale(LocalTime.of(22, 0), at(23, 30)))
        assertTrue(ReminderSlot.isStale(LocalTime.of(22, 0), at(1, 30)))
    }

    @Test
    fun `drift is positive when late and negative when early`() {
        assertTrue(ReminderSlot.drift(LocalTime.of(9, 0), at(10, 0)).toMinutes() > 0)
        assertTrue(ReminderSlot.drift(LocalTime.of(9, 0), at(8, 0)).toMinutes() < 0)
    }

    @Test
    fun `every slot at its own time is fresh, and hours off is not`() {
        val settings = Settings()

        ReminderSlot.entries.forEach { slot ->
            val on = slot.timeIn(settings)
            assertFalse("$slot on time", ReminderSlot.isStale(on, day.atTime(on)))
            assertTrue(
                "$slot five hours late",
                ReminderSlot.isStale(on, day.atTime(on).plusHours(5)),
            )
        }
    }
}
