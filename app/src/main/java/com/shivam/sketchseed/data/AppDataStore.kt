package com.shivam.sketchseed.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.serialization.json.Json

/**
 * The single DataStore for the app.
 *
 * It lands in `files/datastore/sketchseed.preferences_pb`, which is exactly what
 * `data_extraction_rules.xml` hands to Android Auto Backup — so the journey,
 * every prompt drawn, completion dates and streak history all restore onto a new
 * phone with no account and no sign-in.
 */
val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "sketchseed")

/** Lenient so a future field added to a record cannot brick an older install. */
val appJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
