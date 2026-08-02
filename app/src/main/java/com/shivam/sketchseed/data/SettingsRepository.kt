package com.shivam.sketchseed.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** User-facing toggles. */
data class Settings(
    val aiTipsEnabled: Boolean = true,
    /** When the last successful Drive backup finished, or null if never. */
    val lastDriveBackupEpochSecond: Long? = null,
    /** Whether to back up to Drive on its own after each finished day. */
    val autoDriveBackup: Boolean = true,
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
    val startDateOverride: LocalDate?
        get() = startDateOverrideEpochDay?.let(LocalDate::ofEpochDay)

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
                aiTipsEnabled = prefs[KEY_AI_TIPS] ?: true,
                lastDriveBackupEpochSecond = prefs[KEY_LAST_DRIVE_BACKUP],
                autoDriveBackup = prefs[KEY_AUTO_DRIVE_BACKUP] ?: true,
                startDateOverrideEpochDay = prefs[KEY_START_DATE_OVERRIDE],
            )
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

    suspend fun setAiTipsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_AI_TIPS] = enabled }
    }

    suspend fun setLastDriveBackup(epochSecond: Long) {
        dataStore.edit { it[KEY_LAST_DRIVE_BACKUP] = epochSecond }
    }

    private companion object {
        const val TAG = "SettingsRepository"
        val KEY_AI_TIPS = booleanPreferencesKey("ai_tips_enabled")
        val KEY_LAST_DRIVE_BACKUP = longPreferencesKey("last_drive_backup")
        val KEY_AUTO_DRIVE_BACKUP = booleanPreferencesKey("auto_drive_backup")
        val KEY_START_DATE_OVERRIDE = longPreferencesKey("start_date_override")
    }
}
