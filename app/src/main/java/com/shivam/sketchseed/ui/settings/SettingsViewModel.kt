package com.shivam.sketchseed.ui.settings

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shivam.sketchseed.AppContainer
import com.shivam.sketchseed.SketchSeedApplication
import com.shivam.sketchseed.backup.AuthOutcome
import com.shivam.sketchseed.backup.AutoBackupScheduler
import com.shivam.sketchseed.backup.BackupOutcome
import com.shivam.sketchseed.data.PhotoUsage
import com.shivam.sketchseed.data.Settings
import com.shivam.sketchseed.notify.ReminderSlot
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: Settings = Settings(),
    val usage: PhotoUsage = PhotoUsage(),
    val completedCount: Int = 0,
    val busy: Boolean = false,
    val driveBusy: Boolean = false,
    /** False when Android has notifications switched off for the whole app. */
    val notificationsAllowed: Boolean = true,
)

private data class Flags(
    val busy: Boolean,
    val driveBusy: Boolean,
    val notificationsAllowed: Boolean,
)

/** What to do once a Drive token is in hand. */
private enum class DriveAction { BACK_UP, RESTORE }

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val usage = MutableStateFlow(PhotoUsage())
    private val busy = MutableStateFlow(false)
    private val driveBusy = MutableStateFlow(false)
    private val notificationsAllowed = MutableStateFlow(true)

    /** Set when Google needs the user to approve Drive access. */
    private val _consentRequest = MutableStateFlow<PendingIntent?>(null)
    val consentRequest: StateFlow<PendingIntent?> = _consentRequest.asStateFlow()

    /** One-shot message describing the last Drive operation. */
    private val _driveMessage = MutableStateFlow<String?>(null)
    val driveMessage: StateFlow<String?> = _driveMessage.asStateFlow()

    private var pendingAction: DriveAction? = null

    /** Finding and fetching a newer release. See [UpdateFlow]. */
    val update = UpdateFlow(container = container, scope = viewModelScope)

    init {
        refreshUsage()
        refreshNotificationState()
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        container.settingsRepository.settings,
        container.journeyRepository.records.map { it.size },
        usage,
        combine(busy, driveBusy, notificationsAllowed, ::Flags),
    ) { settings, completed, photoUsage, flags ->
        SettingsUiState(
            settings = settings,
            usage = photoUsage,
            completedCount = completed,
            busy = flags.busy,
            driveBusy = flags.driveBusy,
            notificationsAllowed = flags.notificationsAllowed,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = SettingsUiState(),
    )

    // ── Toggles ──────────────────────────────────────────────────────────────

    fun setAutoDriveBackup(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setAutoDriveBackup(enabled)
            if (enabled) {
                AutoBackupScheduler.schedulePeriodic(container.appContext)
            } else {
                AutoBackupScheduler.cancel(container.appContext)
            }
        }
    }

    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setRemindersEnabled(enabled)
            val settings = container.settingsRepository.settings.first()
            if (enabled) container.reminders.schedule(settings) else container.reminders.cancel()
            notificationsAllowed.value = container.reminders.enabledInSystemSettings
        }
    }

    /**
     * Moves a reminder, then re-queues everything.
     *
     * All three are rescheduled rather than just the one changed: the work is
     * unique per slot so re-queueing the others is free, and it avoids a second
     * code path that could drift from the first.
     */
    fun setReminderTime(slot: ReminderSlot, at: LocalTime) {
        viewModelScope.launch {
            when (slot) {
                ReminderSlot.MORNING -> container.settingsRepository.setMorningReminder(at)
                ReminderSlot.EVENING -> container.settingsRepository.setEveningReminder(at)
                ReminderSlot.NIGHT -> container.settingsRepository.setNightReminder(at)
            }
            val settings = container.settingsRepository.settings.first()
            if (settings.remindersEnabled) container.reminders.schedule(settings)
        }
    }

    /** Re-read on resume, since the user may have changed it in system settings. */
    fun refreshNotificationState() {
        notificationsAllowed.value =
            container.reminders.permitted && container.reminders.enabledInSystemSettings
    }

    // ── Google Drive ─────────────────────────────────────────────────────────

    fun backUpToDrive() = startDriveAction(DriveAction.BACK_UP)

    fun restoreFromDrive() = startDriveAction(DriveAction.RESTORE)

    private fun startDriveAction(action: DriveAction) {
        if (driveBusy.value) return
        pendingAction = action

        viewModelScope.launch {
            driveBusy.value = true
            when (val outcome = container.driveAuthorizer.authorize()) {
                is AuthOutcome.Token -> execute(action, outcome.accessToken)

                // Hand the consent screen to the UI; execution resumes in
                // onConsentResult once the user has approved.
                is AuthOutcome.NeedsConsent -> _consentRequest.value = outcome.pendingIntent

                is AuthOutcome.Failed -> {
                    Log.w(TAG, "Drive authorization failed", outcome.cause)
                    finishWith(SIGN_IN_FAILED)
                }
            }
        }
    }

    fun onConsentResult(data: Intent?) {
        _consentRequest.value = null
        val action = pendingAction

        viewModelScope.launch {
            when (val outcome = container.driveAuthorizer.fromConsentResult(data)) {
                is AuthOutcome.Token ->
                    if (action == null) finishWith(null) else execute(action, outcome.accessToken)

                else -> finishWith(SIGN_IN_CANCELLED)
            }
        }
    }

    fun onConsentDismissed() {
        _consentRequest.value = null
        finishWith(SIGN_IN_CANCELLED)
    }

    private suspend fun execute(action: DriveAction, token: String) {
        val outcome = when (action) {
            DriveAction.BACK_UP -> container.backupRepository.backUp(token)
            DriveAction.RESTORE -> container.backupRepository.restoreLatest(token)
        }

        usage.value = container.photoStore.usage()
        finishWith(describe(outcome))
    }

    private fun describe(outcome: BackupOutcome): String = when (outcome) {
        is BackupOutcome.BackedUp -> "$BACKED_UP:${outcome.bytes}"
        is BackupOutcome.Restored -> "$RESTORED:${outcome.records}:${outcome.photos}"
        is BackupOutcome.Exported -> "$EXPORTED:${outcome.bytes}"
        BackupOutcome.NoBackupFound -> NO_BACKUP
        is BackupOutcome.NeedsSignIn -> SIGN_IN_FAILED
        is BackupOutcome.Failed -> outcome.message
    }

    // ── Plain file export / import ───────────────────────────────────────────

    /**
     * Writes a copy the user can keep.
     *
     * No Drive scope and no account involved — this goes wherever the system
     * file picker points, including a USB stick or a different cloud entirely.
     */
    fun exportToFile(destination: Uri) {
        viewModelScope.launch {
            driveBusy.value = true
            finishWith(describe(container.backupRepository.exportTo(destination)))
        }
    }

    fun importFromFile(source: Uri) {
        viewModelScope.launch {
            driveBusy.value = true
            val outcome = container.backupRepository.importFrom(source)
            usage.value = container.photoStore.usage()
            finishWith(describe(outcome))
        }
    }

    private fun finishWith(message: String?) {
        pendingAction = null
        driveBusy.value = false
        _driveMessage.value = message
    }

    fun consumeDriveMessage() {
        _driveMessage.value = null
    }

    fun disconnectDrive() {
        viewModelScope.launch {
            driveBusy.value = true
            container.driveAuthorizer.signOut()
            finishWith(null)
        }
    }

    // ── Journey ──────────────────────────────────────────────────────────────

    /**
     * Wipes the journey and starts again at day 1 today.
     *
     * Re-anchoring the start date is what makes this a genuine restart. Without
     * it the records would clear but the calendar would not, leaving someone who
     * reset on day 40 staring at day 40 with 39 days to catch up.
     */
    fun resetJourney() {
        viewModelScope.launch {
            busy.value = true
            try {
                container.journeyRepository.reset()
                container.photoStore.clear()
                container.settingsRepository.setStartDateOverride(container.today())
                usage.value = container.photoStore.usage()
            } finally {
                busy.value = false
            }
        }
    }

    fun refreshUsage() {
        viewModelScope.launch { usage.value = container.photoStore.usage() }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Sentinels the screen maps onto localised strings. */
        const val BACKED_UP = "backed_up"
        const val RESTORED = "restored"
        const val EXPORTED = "exported"
        const val NO_BACKUP = "no_backup"
        const val SIGN_IN_FAILED = "sign_in_failed"
        const val SIGN_IN_CANCELLED = "sign_in_cancelled"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as SketchSeedApplication
                SettingsViewModel(app.container)
            }
        }
    }
}
