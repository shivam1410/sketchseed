package com.shivam.sketchseed.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.shivam.sketchseed.MainActivity
import com.shivam.sketchseed.R
import com.shivam.sketchseed.data.Settings
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Posts and schedules the daily reminders.
 *
 * Everything that needs a Context lives here so the rest of the app can reach
 * reminders through the container without holding one.
 */
class SketchReminders(private val context: Context) {

    private val manager get() = NotificationManagerCompat.from(context)

    /** True once the user has allowed notifications, or on versions without the prompt. */
    val permitted: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** False when the user has turned the app's notifications off in system settings. */
    val enabledInSystemSettings: Boolean get() = manager.areNotificationsEnabled()

    /**
     * Creates the reminder channel, retiring the earlier one.
     *
     * [NotificationManager.IMPORTANCE_HIGH] so each nudge gets a heads-up banner.
     * At IMPORTANCE_DEFAULT it made a sound but never surfaced, which combined
     * badly with the single notification id below: a later slot silently rewrote
     * an unread earlier one, so the evening and night nudges were trivial to miss
     * even when they had fired correctly.
     *
     * The id carries a version because Android locks a channel's importance once
     * it has been created — raising it in place reaches nobody who already has the
     * app. A new id is the only way the change lands on an existing install, and
     * the old channel is deleted so system settings does not list two.
     */
    fun ensureChannel() {
        manager.deleteNotificationChannel(RETIRED_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.reminder_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Shows the reminder for [day].
     *
     * A single notification id on purpose: a later slot replaces the earlier one
     * rather than stacking three unread nudges for the same undrawn day. That only
     * works because the channel is IMPORTANCE_HIGH — see [ensureChannel]. A silent
     * in-place rewrite is indistinguishable from no reminder at all.
     */
    fun show(day: Int, promptText: String, slot: ReminderSlot) {
        if (!permitted) {
            Log.i(TAG, "Not permitted to post notifications")
            return
        }

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = context.getString(
            when (slot) {
                ReminderSlot.MORNING -> R.string.reminder_morning_title
                ReminderSlot.EVENING -> R.string.reminder_evening_title
                ReminderSlot.NIGHT -> R.string.reminder_night_title
            },
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.reminder_body, day, promptText))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission can be revoked between the check above and here.
            Log.w(TAG, "Notification refused", e)
        }
    }

    /** Clears any showing reminder, e.g. once the day has been drawn. */
    fun clear() {
        manager.cancel(NOTIFICATION_ID)
    }

    // ── Scheduling ───────────────────────────────────────────────────────────

    /**
     * Queues all three slots as daily repeating work.
     *
     * WorkManager rather than exact alarms: a drawing reminder is fine arriving a
     * few minutes late, and this survives reboots and Doze without needing the
     * exact-alarm permission that Android now guards closely.
     */
    fun schedule(settings: Settings, now: LocalDateTime = LocalDateTime.now()) {
        ensureChannel()
        val work = WorkManager.getInstance(context)

        ReminderSlot.entries.forEach { slot ->
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
                // Milliseconds, not minutes: Duration.toMinutes() truncates, which
                // fired every reminder up to 59 seconds early.
                .setInitialDelay(
                    ReminderSlot.initialDelay(slot.timeIn(settings), now).toMillis(),
                    TimeUnit.MILLISECONDS,
                )
                .setInputData(ReminderWorker.inputFor(slot))
                .build()

            // CANCEL_AND_REENQUEUE, not UPDATE.
            //
            // WorkManager fires periodic work at last_enqueue_time + initialDelay,
            // and UPDATE keeps the *original* last_enqueue_time. So the delay —
            // correctly measured from now — was being added to whenever the slot
            // was first created, and every reminder fired early by exactly that
            // gap. Minutes in a test; hours on a phone that had been installed a
            // while, which is how the evening and night nudges ended up arming for
            // late morning instead of 6pm and 10pm.
            //
            // Re-enqueueing resets the anchor, which is the only thing that makes
            // setInitialDelay mean what it says. Re-running a slot is harmless:
            // ReminderWorker decides whether to notify at fire time.
            work.enqueueUniquePeriodicWork(
                slot.workName,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                request,
            )
        }
    }

    fun cancel() {
        val work = WorkManager.getInstance(context)
        ReminderSlot.entries.forEach { work.cancelUniqueWork(it.workName) }
        clear()
    }

    internal companion object {
        private const val TAG = "SketchReminders"

        /** Bump the suffix to change channel settings a user already has. */
        const val CHANNEL_ID = "daily-reminders-v2"

        /** The IMPORTANCE_DEFAULT channel this replaced; deleted on first run. */
        const val RETIRED_CHANNEL_ID = "daily-reminders"

        const val NOTIFICATION_ID = 1001
    }
}
