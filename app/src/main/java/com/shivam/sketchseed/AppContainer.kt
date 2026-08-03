package com.shivam.sketchseed

import android.content.Context
import com.shivam.sketchseed.ai.TipGenerator
import com.shivam.sketchseed.backup.BackupRepository
import com.shivam.sketchseed.backup.DriveAuthorizer
import com.shivam.sketchseed.backup.DriveClient
import com.shivam.sketchseed.data.JourneyRepository
import com.shivam.sketchseed.data.PhotoStore
import com.shivam.sketchseed.data.PromptRepository
import com.shivam.sketchseed.data.SettingsRepository
import com.shivam.sketchseed.data.appDataStore
import com.shivam.sketchseed.notify.SketchReminders
import java.time.LocalDate
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
    val tipGenerator = TipGenerator()
    val reminders = SketchReminders(appContext)

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

    /** Indirection so tests can pin "today" instead of reading the system clock. */
    val today: () -> LocalDate = LocalDate::now

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
