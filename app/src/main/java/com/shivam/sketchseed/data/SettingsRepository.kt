package com.shivam.sketchseed.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** User-facing toggles. */
data class Settings(
    /** When the last successful Drive backup finished, or null if never. */
    val lastDriveBackupEpochSecond: Long? = null,
    /**
     * When the app last asked GitHub for the newest release, or null if never.
     *
     * Only throttles the automatic check; see `UpdateSchedule`.
     */
    val lastUpdateCheckEpochSecond: Long? = null,
    /** Whether to back up to Drive on its own after each finished day. */
    val autoDriveBackup: Boolean = true,
    /**
     * Whether to nudge about an undrawn day. On by default — a habit app that
     * waits to be remembered is not doing its job.
     */
    val remindersEnabled: Boolean = true,
    /**
     * When each reminder fires, as minutes past midnight.
     *
     * Stored as minutes rather than a formatted string so there is nothing to
     * parse and no locale to get wrong.
     */
    val morningReminderMinute: Int = 9 * 60,
    val eveningReminderMinute: Int = 18 * 60,
    val nightReminderMinute: Int = 22 * 60,
    /**
     * This install's day 1.
     *
     * Set on first launch and again by resetting, so whenever someone starts —
     * today, or in six months — their day 1 is Apple rather than whatever the
     * calendar had reached. The trade is that two people who install on
     * different days are on different prompts; only the pack's order is shared,
     * not the schedule.
     *
     * Null only before the first launch has recorded it, in which case the
     * pack's own date stands in.
     */
    val startDateOverrideEpochDay: Long? = null,
) {
    val morningReminder: LocalTime get() = minutesToTime(morningReminderMinute)
    val eveningReminder: LocalTime get() = minutesToTime(eveningReminderMinute)
    val nightReminder: LocalTime get() = minutesToTime(nightReminderMinute)

    val startDateOverride: LocalDate?
        get() = startDateOverrideEpochDay?.let(LocalDate::ofEpochDay)

    /** Clamped so a corrupt or out-of-range value cannot crash the scheduler. */
    private fun minutesToTime(minutes: Int): LocalTime =
        LocalTime.of(minutes.coerceIn(0, 24 * 60 - 1) / 60, minutes.coerceIn(0, 24 * 60 - 1) % 60)

    /** The date day 1 falls on for this install. */
    fun startDate(packStartDate: LocalDate): LocalDate = startDateOverride ?: packStartDate
}

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<Settings> = dataStore.data
        .catch { cause ->
            if (cause is IOException) {
                Log.e(TAG, "Could not read settings; falling back to defaults", cause)
                emit(emptyPreferences())
            } else {
                throw cause
            }
        }
        .map { prefs ->
            Settings(
                lastDriveBackupEpochSecond = prefs[KEY_LAST_DRIVE_BACKUP],
                lastUpdateCheckEpochSecond = prefs[KEY_LAST_UPDATE_CHECK],
                autoDriveBackup = prefs[KEY_AUTO_DRIVE_BACKUP] ?: true,
                startDateOverrideEpochDay = prefs[KEY_START_DATE_OVERRIDE],
                remindersEnabled = prefs[KEY_REMINDERS] ?: true,
                morningReminderMinute = prefs[KEY_REMINDER_MORNING] ?: (9 * 60),
                eveningReminderMinute = prefs[KEY_REMINDER_EVENING] ?: (18 * 60),
                nightReminderMinute = prefs[KEY_REMINDER_NIGHT] ?: (22 * 60),
            )
        }

    suspend fun setRemindersEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_REMINDERS] = enabled }
    }

    suspend fun setMorningReminder(at: LocalTime) = setReminder(KEY_REMINDER_MORNING, at)

    suspend fun setEveningReminder(at: LocalTime) = setReminder(KEY_REMINDER_EVENING, at)

    suspend fun setNightReminder(at: LocalTime) = setReminder(KEY_REMINDER_NIGHT, at)

    private suspend fun setReminder(key: Preferences.Key<Int>, at: LocalTime) {
        dataStore.edit { it[key] = at.hour * 60 + at.minute }
    }

    /** Pass null to fall back to the pack's own date. */
    suspend fun setStartDateOverride(date: LocalDate?) {
        dataStore.edit { prefs ->
            if (date == null) {
                prefs.remove(KEY_START_DATE_OVERRIDE)
            } else {
                prefs[KEY_START_DATE_OVERRIDE] = date.toEpochDay()
            }
        }
    }

    suspend fun setAutoDriveBackup(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_DRIVE_BACKUP] = enabled }
    }

    suspend fun setLastDriveBackup(epochSecond: Long) {
        dataStore.edit { it[KEY_LAST_DRIVE_BACKUP] = epochSecond }
    }

    suspend fun setLastUpdateCheck(epochSecond: Long) {
        dataStore.edit { it[KEY_LAST_UPDATE_CHECK] = epochSecond }
    }

    private companion object {
        const val TAG = "SettingsRepository"
        val KEY_LAST_DRIVE_BACKUP = longPreferencesKey("last_drive_backup")
        val KEY_LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val KEY_AUTO_DRIVE_BACKUP = booleanPreferencesKey("auto_drive_backup")
        val KEY_START_DATE_OVERRIDE = longPreferencesKey("start_date_override")
        val KEY_REMINDERS = booleanPreferencesKey("reminders_enabled")
        val KEY_REMINDER_MORNING = intPreferencesKey("reminder_minute_morning")
        val KEY_REMINDER_EVENING = intPreferencesKey("reminder_minute_evening")
        val KEY_REMINDER_NIGHT = intPreferencesKey("reminder_minute_night")
    }
}
