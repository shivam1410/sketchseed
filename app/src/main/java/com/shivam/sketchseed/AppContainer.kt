package com.shivam.sketchseed

import android.content.Context
import com.shivam.sketchseed.backup.BackupRepository
import com.shivam.sketchseed.backup.DriveAuthorizer
import com.shivam.sketchseed.backup.DriveClient
import com.shivam.sketchseed.data.JourneyRepository
import com.shivam.sketchseed.data.PhotoStore
import com.shivam.sketchseed.data.PromptRepository
import com.shivam.sketchseed.data.SettingsRepository
import com.shivam.sketchseed.data.appDataStore
import com.shivam.sketchseed.domain.DrawingDay
import com.shivam.sketchseed.notify.SketchReminders
import com.shivam.sketchseed.update.UpdateChecker
import com.shivam.sketchseed.update.UpdateInstaller
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first

/**
 * Hand-rolled dependency container.
 *
 * The graph is five objects deep, so a DI framework would cost more than it
 * saves here.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val promptRepository = PromptRepository(appContext)
    val journeyRepository = JourneyRepository(appContext.appDataStore)
    val settingsRepository = SettingsRepository(appContext.appDataStore)
    val photoStore = PhotoStore(appContext)
    val reminders = SketchReminders(appContext)

    val updateChecker = UpdateChecker()
    val updateInstaller = UpdateInstaller(appContext)

    val driveAuthorizer = DriveAuthorizer(appContext)
    val backupRepository = BackupRepository(
        context = appContext,
        journeyRepository = journeyRepository,
        promptRepository = promptRepository,
        photoStore = photoStore,
        settingsRepository = settingsRepository,
        drive = DriveClient(),
        appVersion = BuildConfig.VERSION_NAME,
    )

    /** Indirection so tests can pin the clock instead of reading the system one. */
    val now: () -> LocalDateTime = LocalDateTime::now

    /**
     * The same clock as one number, for durations rather than dates.
     *
     * Encoded at UTC like [com.shivam.sketchseed.domain.model.DayRecord] does:
     * a way of writing down the local wall clock, not a real instant. Only the
     * update-check interval reads it, and that only needs two readings to be
     * comparable with each other.
     */
    val nowEpochSecond: () -> Long = { now().toEpochSecond(ZoneOffset.UTC) }

    /**
     * The drawing day the app treats as current.
     *
     * Turns over at 6am rather than midnight, so someone drawing after midnight is
     * still finishing the day they believe they are in. See [DrawingDay]. Reminder
     * scheduling deliberately reads the wall clock instead — a 9am nudge means 9am.
     */
    val today: () -> LocalDate = { DrawingDay.of(now()) }

    /**
     * Anchors day 1 to the day this install first ran.
     *
     * Without this, someone installing months after the pack's own start date
     * would open the app on day 60 and never see the beginner ramp at all.
     *
     * Only ever writes when nothing has been drawn yet. An install already part
     * way through a journey keeps whatever date its day numbers were counted
     * from, since moving that would renumber history underneath the user.
     */
    suspend fun anchorStartDateOnFirstRun() {
        if (settingsRepository.settings.first().startDateOverrideEpochDay != null) return
        if (journeyRepository.records.first().isNotEmpty()) return
        settingsRepository.setStartDateOverride(today())
    }
}
