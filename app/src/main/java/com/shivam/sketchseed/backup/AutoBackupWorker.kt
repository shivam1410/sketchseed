package com.shivam.sketchseed.backup

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shivam.sketchseed.SketchSeedApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Backs the journey up to Drive without the user asking.
 *
 * Runs in the background, so it must never show UI. If Google needs consent —
 * the user has never signed in, or has revoked access — the worker gives up
 * quietly and leaves it to the manual button in Settings. Silently failing is
 * correct here; a background job that threw up a sign-in sheet would be worse
 * than no automatic backup at all.
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? SketchSeedApplication)?.container
            ?: return Result.failure()

        if (!container.settingsRepository.settings.first().autoDriveBackup) {
            Log.i(TAG, "Automatic backup is switched off")
            return Result.success()
        }

        // Nothing drawn yet means nothing worth uploading.
        if (container.journeyRepository.records.first().isEmpty()) {
            return Result.success()
        }

        val token = when (val outcome = container.driveAuthorizer.authorize()) {
            is AuthOutcome.Token -> outcome.accessToken

            is AuthOutcome.NeedsConsent -> {
                Log.i(TAG, "Drive consent required; leaving it to the user")
                return Result.success()
            }

            is AuthOutcome.Failed -> {
                Log.w(TAG, "Could not authorize in the background", outcome.cause)
                return Result.retry()
            }
        }

        return when (val outcome = container.backupRepository.backUp(token)) {
            is BackupOutcome.BackedUp -> {
                Log.i(TAG, "Automatic backup wrote ${outcome.bytes} bytes")
                Result.success()
            }

            // A stale token or a flaky network both deserve another go; the
            // constraints mean the retry lands when conditions are better.
            is BackupOutcome.NeedsSignIn -> Result.success()
            is BackupOutcome.Failed -> {
                Log.w(TAG, "Automatic backup failed: ${outcome.message}", outcome.cause)
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }

            else -> Result.success()
        }
    }

    private companion object {
        const val TAG = "AutoBackupWorker"
        const val MAX_ATTEMPTS = 3
    }
}

/** Schedules the automatic backups. */
object AutoBackupScheduler {

    /**
     * Queues a backup shortly after the journey changes.
     *
     * [ExistingWorkPolicy.REPLACE] with a short delay coalesces a burst of edits
     * — finishing a day, then attaching a photo — into one upload instead of
     * three.
     */
    fun scheduleSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setConstraints(constraints())
            .setInitialDelay(COALESCE_MINUTES, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_AFTER_CHANGE, ExistingWorkPolicy.REPLACE, request)
    }

    /** A daily safety net, in case a change-triggered run never landed. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(WORK_AFTER_CHANGE)
            cancelUniqueWork(WORK_PERIODIC)
        }
    }

    /**
     * Any network rather than unmetered: a backup is a couple of hundred
     * kilobytes, and waiting for Wi-Fi could mean days without one.
     */
    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    private const val WORK_AFTER_CHANGE = "sketchseed-auto-backup"
    private const val WORK_PERIODIC = "sketchseed-auto-backup-daily"
    private const val COALESCE_MINUTES = 2L
}
