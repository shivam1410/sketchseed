package com.shivam.sketchseed.backup

import com.shivam.sketchseed.domain.model.DayRecord
import java.io.File
import kotlinx.serialization.Serializable

/**
 * Describes a backup archive, so a restore can be explained to the user before
 * it overwrites anything.
 *
 * [schemaVersion] is what lets a future build recognise an old archive rather
 * than misreading it. Bump it whenever the archive layout changes in a way an
 * older reader could not cope with.
 */
@Serializable
data class BackupManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val createdAtEpochSecond: Long,
    val appVersion: String,
    val packId: String,
    val startDateIso: String,
    val recordCount: Int,
    val photoCount: Int,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/** Everything recovered from an archive. */
data class BackupContents(
    val manifest: BackupManifest,
    val records: List<DayRecord>,
    /** Extracted sketch files, keyed by the file name the records refer to. */
    val photos: Map<String, File>,
)

/** Why an archive could not be read. Surfaced to the user, so keep it plain. */
class BackupFormatException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
