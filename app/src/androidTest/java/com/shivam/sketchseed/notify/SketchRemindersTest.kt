package com.shivam.sketchseed.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.shivam.sketchseed.data.Settings
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards when the reminders are actually scheduled to fire.
 *
 * Instrumented rather than a JVM test because the bug lived in WorkManager's own
 * bookkeeping, not in our arithmetic. [ReminderSlot.initialDelay] was always
 * correct; what went wrong was the point WorkManager measured it from.
 *
 * WorkManager fires periodic work at `last_enqueue_time + initialDelay`, and
 * [androidx.work.ExistingPeriodicWorkPolicy.UPDATE] preserves the *original*
 * `last_enqueue_time`. Rescheduling therefore wrote a fresh, correct delay and
 * then added it to a stale anchor, so every reminder fired early by however long
 * ago that slot was first created — which is why the 6pm and 10pm nudges ended
 * up arming for late morning.
 *
 * The invariant these tests hold is the one that was violated: a slot must be
 * scheduled `initialDelay` from *now*, no matter how many times it has already
 * been scheduled.
 */
@RunWith(AndroidJUnit4::class)
class SketchRemindersTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val reminders = SketchReminders(context)
    private val work = WorkManager.getInstance(context)

    @After
    fun tearDown() {
        reminders.cancel()
    }

    /**
     * How far off the promised fire time each slot currently is, in millis.
     *
     * Filtered to the live record rather than taking the only one: cancelling and
     * re-enqueueing leaves the cancelled generation in the table until WorkManager
     * prunes it, so a unique name can legitimately report more than one.
     */
    private fun driftPerSlot(): Map<ReminderSlot, Long> {
        val at = System.currentTimeMillis()
        return ReminderSlot.entries.associateWith { slot ->
            val live = work.getWorkInfosForUniqueWork(slot.workName).get()
                .filterNot { it.state.isFinished }
            require(live.size == 1) { "$slot has ${live.size} live work records, expected 1" }
            val info = live.single()
            (info.nextScheduleTimeMillis - at) - info.initialDelayMillis
        }
    }

    @Test
    fun everySlotIsScheduledItsFullDelayFromNow() {
        reminders.schedule(Settings(), LocalDateTime.now())

        driftPerSlot().forEach { (slot, drift) ->
            assertTrue(
                "$slot is scheduled ${-drift}ms earlier than its delay promises",
                kotlin.math.abs(drift) < TOLERANCE_MS,
            )
        }
    }

    @Test
    fun reschedulingReAnchorsInsteadOfKeepingTheOriginalEnqueueTime() {
        // The gap is load-bearing. Under the old UPDATE policy the anchor stayed
        // put, so the second schedule came out exactly this much early; with the
        // two calls back to back the error would be milliseconds and invisible.
        reminders.schedule(Settings(), LocalDateTime.now())
        Thread.sleep(GAP_MS)
        reminders.schedule(Settings(), LocalDateTime.now())

        driftPerSlot().forEach { (slot, drift) ->
            assertTrue(
                "$slot kept its old anchor and fires ${-drift}ms early after rescheduling",
                kotlin.math.abs(drift) < TOLERANCE_MS,
            )
        }
    }

    @Test
    fun movingASlotSchedulesItForTheNewTimeAndLeavesTheOthersAlone() {
        val now = LocalDateTime.now()
        reminders.schedule(Settings(), now)
        Thread.sleep(GAP_MS)

        // Whatever the user picks, the slot must land that far out and no nearer.
        val moved = Settings(morningReminderMinute = now.toLocalTime().plusHours(3).let {
            it.hour * 60 + it.minute
        })
        reminders.schedule(moved, LocalDateTime.now())

        val drift = driftPerSlot()
        assertTrue(
            "moved slot fires ${-(drift[ReminderSlot.MORNING] ?: 0)}ms early",
            kotlin.math.abs(drift[ReminderSlot.MORNING] ?: Long.MAX_VALUE) < TOLERANCE_MS,
        )
        assertTrue(
            "untouched evening slot drifted by ${drift[ReminderSlot.EVENING]}ms",
            kotlin.math.abs(drift[ReminderSlot.EVENING] ?: Long.MAX_VALUE) < TOLERANCE_MS,
        )
    }

    @Test
    fun aMovedSlotIsNotRoundedDownToTheMinute() {
        // Duration.toMinutes() truncates, which fired every reminder up to 59
        // seconds early. Picking a time with seconds still on the clock is what
        // exposes it.
        val now = LocalDateTime.now().withSecond(37)
        val target = now.toLocalTime().plusMinutes(90)
        val settings = Settings(nightReminderMinute = target.hour * 60 + target.minute)

        reminders.schedule(settings, now)

        val drift = driftPerSlot()[ReminderSlot.NIGHT] ?: Long.MAX_VALUE
        assertTrue("night slot truncated by ${-drift}ms", kotlin.math.abs(drift) < TOLERANCE_MS)
    }

    @Test
    fun theChannelIsHighImportanceSoEachNudgeActuallySurfaces() {
        // At IMPORTANCE_DEFAULT a reminder made a sound but never showed a banner,
        // and since all three slots share one notification id, a later slot
        // silently rewrote an unread earlier one. Correct timing was not enough to
        // make the evening and night nudges noticeable.
        reminders.ensureChannel()

        val channel = NotificationManagerCompat.from(context)
            .getNotificationChannelCompat(SketchReminders.CHANNEL_ID)

        assertNotNull("reminder channel was not created", channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel?.importance)
    }

    @Test
    fun theRetiredChannelIsRemovedRatherThanLeftAlongsideTheNewOne() {
        // Android locks a channel's importance after creation, so the id had to
        // change. Leaving the old one behind would show the user two identical
        // "Daily reminders" entries in system settings, only one of which works.
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(
                SketchReminders.RETIRED_CHANNEL_ID,
                "stale",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )

        reminders.ensureChannel()

        assertNull(
            "the old IMPORTANCE_DEFAULT channel is still listed",
            manager.getNotificationChannelCompat(SketchReminders.RETIRED_CHANNEL_ID),
        )
    }

    @Test
    fun cancellingLeavesNoScheduledWorkBehind() {
        reminders.schedule(Settings(), LocalDateTime.now())
        reminders.cancel()

        ReminderSlot.entries.forEach { slot ->
            val infos = work.getWorkInfosForUniqueWork(slot.workName).get()
            assertTrue(
                "$slot still has live work after cancel",
                infos.all { it.state.isFinished },
            )
        }
    }

    private companion object {
        /** Enough to absorb enqueue latency, far tighter than the bug's error. */
        val TOLERANCE_MS = TimeUnit.SECONDS.toMillis(2)
        val GAP_MS = TimeUnit.SECONDS.toMillis(6)
    }
}
