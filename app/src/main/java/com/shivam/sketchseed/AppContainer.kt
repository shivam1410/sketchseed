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
import java.time.LocalDate

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
}
