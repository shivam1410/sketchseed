package com.shivam.sketchseed.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** User-facing toggles. */
data class Settings(
    val backupSketches: Boolean = true,
    val aiTipsEnabled: Boolean = true,
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
                backupSketches = prefs[KEY_BACKUP_SKETCHES] ?: true,
                aiTipsEnabled = prefs[KEY_AI_TIPS] ?: true,
            )
        }

    suspend fun setBackupSketches(enabled: Boolean) {
        dataStore.edit { it[KEY_BACKUP_SKETCHES] = enabled }
    }

    suspend fun setAiTipsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_AI_TIPS] = enabled }
    }

    private companion object {
        const val TAG = "SettingsRepository"
        val KEY_BACKUP_SKETCHES = booleanPreferencesKey("backup_sketches")
        val KEY_AI_TIPS = booleanPreferencesKey("ai_tips_enabled")
    }
}
