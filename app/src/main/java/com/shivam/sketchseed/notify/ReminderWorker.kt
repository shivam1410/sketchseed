package com.shivam.sketchseed.notify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shivam.sketchseed.SketchSeedApplication
import com.shivam.sketchseed.domain.model.JourneyProgress
import kotlinx.coroutines.flow.first

/**
 * Nudges about a day that has not been drawn yet.
 *
 * The "no further notifications once the day is done" rule is enforced here
 * rather than by cancelling work when a day is completed. Deciding at fire time
 * is the version that cannot drift: whatever else happens — the app is killed,
 * the day is drawn on another device and restored, the clock rolls over — the
 * worker asks the same question it always asks, which is whether there is
 * something to draw right now.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? SketchSeedApplication)?.container
            ?: return Result.failure()

        val settings = container.settingsRepository.settings.first()
        if (!settings.remindersEnabled) {
            Log.i(TAG, "Reminders are switched off")
            return Result.success()
        }

        // A held-back job runs at the next opportunity, which is usually the moment
        // the app is opened — so without this the user taps the icon and is handed a
        // reminder for a slot hours gone. Better to say nothing than the wrong thing.
        val slot = slot()
        val at = slot.timeIn(settings)
        if (ReminderSlot.isStale(at, container.now())) {
            Log.i(TAG, "$slot is ${ReminderSlot.drift(at, container.now()).toMinutes()}min off $at; skipping")
            return Result.success()
        }

        val pack = try {
            container.promptRepository.pack()
        } catch (e: Exception) {
            Log.e(TAG, "Prompt pack unreadable; nothing to remind about", e)
            return Result.failure()
        }

        val progress = JourneyProgress.from(
            records = container.journeyRepository.records.first(),
            totalDays = pack.totalDays,
            startDate = settings.startDate(pack.startDate),
            today = container.today(),
        )

        // Covers every "nothing to nudge about" case in one question: already
        // drawn today, the hundred finished, the window closed, or not started.
        if (!progress.canDrawToday) {
            container.reminders.clear()
            return Result.success()
        }

        val day = progress.currentDay ?: return Result.success()
        val prompt = pack.promptFor(day) ?: return Result.success()

        container.reminders.show(day = day, promptText = prompt.text, slot = slot)
        return Result.success()
    }

    private fun slot(): ReminderSlot =
        inputData.getString(KEY_SLOT)
            ?.let { name -> ReminderSlot.entries.firstOrNull { it.name == name } }
            ?: ReminderSlot.MORNING

    companion object {
        private const val TAG = "ReminderWorker"
        private const val KEY_SLOT = "slot"

        fun inputFor(slot: ReminderSlot): Data = workDataOf(KEY_SLOT to slot.name)
    }
}
