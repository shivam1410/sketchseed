package com.shivam.sketchseed.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.IOException
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
)

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
            )
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
    }
}
