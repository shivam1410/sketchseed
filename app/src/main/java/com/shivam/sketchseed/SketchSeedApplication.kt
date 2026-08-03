package com.shivam.sketchseed

import android.app.Application
import com.shivam.sketchseed.backup.AutoBackupScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SketchSeedApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        scope.launch { container.anchorStartDateOnFirstRun() }
        scheduleBackups()
        scheduleReminders()
    }

    /**
     * Keeps the daily reminders queued.
     *
     * Re-scheduling on every launch is harmless — the work is unique per slot —
     * and it repairs the case where WorkManager's queue was cleared by the system
     * or an app upgrade.
     */
    private fun scheduleReminders() {
        scope.launch {
            val settings = container.settingsRepository.settings.first()
            if (settings.remindersEnabled) {
                container.reminders.schedule(settings)
            } else {
                container.reminders.cancel()
            }
        }
    }

    /**
     * Keeps Drive in step with the journey.
     *
     * Watching the records here rather than calling the scheduler from each
     * screen means every path that changes the journey — finishing a day,
     * attaching a photo, restoring — schedules a backup, and none can be
     * forgotten. The first emission is dropped because it is just the initial
     * read, not a change.
     */
    private fun scheduleBackups() {
        scope.launch {
            if (!container.settingsRepository.settings.first().autoDriveBackup) return@launch

            AutoBackupScheduler.schedulePeriodic(this@SketchSeedApplication)

            container.journeyRepository.records
                .drop(1)
                .collect { AutoBackupScheduler.scheduleSoon(this@SketchSeedApplication) }
        }
    }
}
