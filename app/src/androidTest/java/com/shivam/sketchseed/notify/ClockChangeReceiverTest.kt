package com.shivam.sketchseed.notify

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.shivam.sketchseed.data.Settings
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a clock change re-anchors the reminders.
 *
 * Scope worth being honest about: this drives [ClockChangeReceiver.onReceive]
 * directly. It does not prove Android delivers the broadcast — TIMEZONE_CHANGED is
 * protected, so it cannot be sent from a test or from adb, and changing the
 * emulator's timezone needs root a Play Store image does not give. Delivery rests
 * on the manifest filter; what is tested here is that the handler does the right
 * thing once called.
 */
@RunWith(AndroidJUnit4::class)
class ClockChangeReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val reminders = SketchReminders(context)
    private val work = WorkManager.getInstance(context)

    @After
    fun tearDown() {
        reminders.cancel()
    }

    private fun liveWork(slot: ReminderSlot) =
        work.getWorkInfosForUniqueWork(slot.workName).get()
            .filterNot { it.state.isFinished }
            .single()

    /**
     * The work's identity, which changes only if it was genuinely re-enqueued.
     *
     * Deliberately not the scheduled time. Re-anchoring recomputes the delay to the
     * *same* wall-clock target, so with the timezone unchanged the fire moment must
     * not move — that invariance is the fix working, not evidence of a no-op.
     */
    private fun idOf(slot: ReminderSlot) = liveWork(slot).id

    private fun nextRunOf(slot: ReminderSlot) = liveWork(slot).nextScheduleTimeMillis

    private fun deliver(action: String) =
        ClockChangeReceiver().onReceive(context, Intent(action))

    @Test
    fun aTimezoneChangeReEnqueuesEverySlot() {
        reminders.schedule(Settings(), LocalDateTime.now())
        val idsBefore = ReminderSlot.entries.associateWith(::idOf)
        val runsBefore = ReminderSlot.entries.associateWith(::nextRunOf)
        Thread.sleep(GAP_MS)

        deliver(Intent.ACTION_TIMEZONE_CHANGED)

        // onReceive hands off to a coroutine, so wait for the effect.
        assertTrue("nothing was re-enqueued within $SETTLE_MS ms", awaitReEnqueue(idsBefore))

        ReminderSlot.entries.forEach { slot ->
            assertTrue("$slot was not re-enqueued", idOf(slot) != idsBefore[slot])
            // Same target, freshly anchored: the delay shrank by exactly the time
            // that passed, so the moment it fires is unchanged.
            assertTrue(
                "$slot drifted off its wall-clock target",
                Math.abs(nextRunOf(slot) - (runsBefore[slot] ?: 0L)) < TOLERANCE_MS,
            )
        }
    }

    @Test
    fun aManualClockChangeAlsoReEnqueues() {
        reminders.schedule(Settings(), LocalDateTime.now())
        val idsBefore = ReminderSlot.entries.associateWith(::idOf)
        Thread.sleep(GAP_MS)

        deliver(Intent.ACTION_TIME_CHANGED)

        assertTrue("a manual clock set must re-anchor too", awaitReEnqueue(idsBefore))
    }

    @Test
    fun anUnrelatedBroadcastIsIgnored() {
        reminders.schedule(Settings(), LocalDateTime.now())
        val idsBefore = ReminderSlot.entries.associateWith(::idOf)
        Thread.sleep(GAP_MS)

        deliver(Intent.ACTION_BATTERY_LOW)
        Thread.sleep(SETTLE_MS)

        ReminderSlot.entries.forEach { slot ->
            assertEquals("$slot must be untouched", idsBefore[slot], idOf(slot))
        }
    }

    /** Polls rather than sleeping blind, so a fast machine is not punished. */
    private fun awaitReEnqueue(before: Map<ReminderSlot, *>): Boolean {
        val deadline = System.currentTimeMillis() + SETTLE_MS
        while (System.currentTimeMillis() < deadline) {
            if (ReminderSlot.entries.all { idOf(it) != before[it] }) return true
            Thread.sleep(200)
        }
        return false
    }

    private companion object {
        val GAP_MS = TimeUnit.SECONDS.toMillis(4)

        /** Enqueue latency; far below the 4s gap that would signal real drift. */
        val TOLERANCE_MS = TimeUnit.SECONDS.toMillis(2)
        val SETTLE_MS = TimeUnit.SECONDS.toMillis(10)
    }
}
