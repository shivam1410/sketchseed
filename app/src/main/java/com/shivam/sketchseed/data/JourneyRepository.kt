package com.shivam.sketchseed.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.shivam.sketchseed.domain.model.DayRecord
import com.shivam.sketchseed.domain.model.ExtraSketch
import com.shivam.sketchseed.domain.model.Prompt
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Stores the finished days.
 *
 * The whole journey is at most 100 small records, so it is kept as one JSON blob
 * rather than a database. That keeps the backed-up footprint tiny and removes an
 * entire schema-migration surface.
 */
class JourneyRepository(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = appJson,
) {
    private val serializer = ListSerializer(DayRecord.serializer())

    val records: Flow<List<DayRecord>> = dataStore.data
        .catch { cause ->
            if (cause is IOException) {
                Log.e(TAG, "Could not read journey data; treating as empty", cause)
                emit(emptyPreferences())
            } else {
                throw cause
            }
        }
        .map { prefs -> prefs.decodeRecords() }

    /**
     * Appends a finished day.
     *
     * Takes the wall-clock moment rather than a date: the drawing day it counts
     * for is derived from it once, here, and then fixed. See [DayRecord].
     */
    suspend fun complete(
        prompt: Prompt,
        completedAt: LocalDateTime,
        photoFileName: String? = null,
    ) = mutate { current ->
        if (current.any { it.day == prompt.day }) {
            current
        } else {
            current + DayRecord.of(prompt, completedAt, photoFileName)
        }
    }

    /** Attaches or replaces the sketch photo for an already-finished day. */
    suspend fun setPhoto(day: Int, photoFileName: String?) = mutate { current ->
        current.map { if (it.day == day) it.copy(photoFileName = photoFileName) else it }
    }

    /**
     * Adds a bonus sketch to an already-finished day.
     *
     * A no-op on a day that is not finished: extras hang off a completed day
     * rather than standing in for one.
     */
    suspend fun addExtra(day: Int, extra: ExtraSketch) = mutate { current ->
        current.map { if (it.day == day) it.copy(extras = it.extras + extra) else it }
    }

    suspend fun removeExtra(day: Int, extraId: String) = mutate { current ->
        current.map {
            if (it.day == day) {
                it.copy(extras = it.extras.filterNot { extra -> extra.id == extraId })
            } else {
                it
            }
        }
    }

    /**
     * Swaps the whole journey for [records].
     *
     * Used by restore, which deliberately replaces rather than merges: a
     * half-merged journey where some days came from the phone and some from a
     * backup would be impossible for anyone to reason about.
     */
    suspend fun replaceAll(records: List<DayRecord>) {
        dataStore.edit { prefs ->
            prefs[KEY_RECORDS] = json.encodeToString(serializer, records.sortedBy { it.day })
        }
    }

    suspend fun reset() {
        dataStore.edit { it.remove(KEY_RECORDS) }
    }

    private suspend fun mutate(block: (List<DayRecord>) -> List<DayRecord>) {
        dataStore.edit { prefs ->
            val updated = block(prefs.decodeRecords()).sortedBy { it.day }
            prefs[KEY_RECORDS] = json.encodeToString(serializer, updated)
        }
    }

    private fun Preferences.decodeRecords(): List<DayRecord> {
        val raw = this[KEY_RECORDS] ?: return emptyList()
        return try {
            json.decodeFromString(serializer, raw)
        } catch (e: SerializationException) {
            // A malformed blob is unrecoverable, but crashing on every launch is
            // worse than starting empty. Surface it loudly in the log.
            Log.e(TAG, "Journey data is corrupt and will be ignored", e)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "JourneyRepository"
        val KEY_RECORDS = stringPreferencesKey("journey_records")
    }
}
