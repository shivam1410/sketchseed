package com.shivam.sketchseed.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shivam.sketchseed.SketchSeedApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-anchors the reminders when the wall clock moves under them.
 *
 * Reminder delays are elapsed durations, computed once from local time to the next
 * occurrence of a slot. Change timezone after that and the duration is still
 * counted out faithfully, so a 9am reminder set in one country arrives at whatever
 * 9am-minus-the-offset happens to be in the next. It self-corrects the next time
 * the app is opened, but "next time you open the app" is a poor guarantee for the
 * one feature whose whole job is to work while the app is closed.
 *
 * No new permission: [Intent.ACTION_TIMEZONE_CHANGED] and [Intent.ACTION_TIME_CHANGED]
 * are both freely broadcast. Deliberately not BOOT_COMPLETED — WorkManager restores
 * its own jobs across a reboot, and that one does need a permission.
 *
 * A DST rollover inside one zone broadcasts neither, so those still lag until the
 * next launch. One reminder an hour out, twice a year, is not worth a poll.
 */
class ClockChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return

        val container = (context.applicationContext as? SketchSeedApplication)?.container ?: return

        // goAsync would be the tidier tool, but the work is a DataStore read plus a
        // WorkManager enqueue — both fast, and neither worth holding a wake lock for.
        // A missed re-anchor costs one reminder, and the next launch fixes it.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val settings = container.settingsRepository.settings.first()
                if (!settings.remindersEnabled) return@launch

                container.reminders.schedule(settings)
                Log.i(TAG, "Re-anchored reminders after ${intent.action}")
            } catch (e: Exception) {
                // Never let a broadcast take the app down; the next launch reschedules.
                Log.e(TAG, "Could not re-anchor reminders after ${intent.action}", e)
            }
        }
    }

    private companion object {
        const val TAG = "ClockChangeReceiver"

        val HANDLED = setOf(
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
        )
    }
}
