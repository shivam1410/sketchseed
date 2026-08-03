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

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
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
     * rather than stacking three unread nudges for the same undrawn day.
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
                .setInitialDelay(
                    ReminderSlot.initialDelay(slot.timeIn(settings), now).toMinutes(),
                    TimeUnit.MINUTES,
                )
                .setInputData(ReminderWorker.inputFor(slot))
                .build()

            // UPDATE rather than KEEP so a changed slot time takes effect without
            // the user having to clear app data.
            work.enqueueUniquePeriodicWork(
                slot.workName,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

    fun cancel() {
        val work = WorkManager.getInstance(context)
        ReminderSlot.entries.forEach { work.cancelUniqueWork(it.workName) }
        clear()
    }

    private companion object {
        const val TAG = "SketchReminders"
        const val CHANNEL_ID = "daily-reminders"
        const val NOTIFICATION_ID = 1001
    }
}
