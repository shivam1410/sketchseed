package com.shivam.sketchseed.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.shivam.sketchseed.data.JourneyRepository
import com.shivam.sketchseed.data.PhotoStore
import com.shivam.sketchseed.data.PromptRepository
import com.shivam.sketchseed.data.SettingsRepository
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Outcome of a backup or restore, in terms the UI can show directly. */
sealed interface BackupOutcome {
    data class BackedUp(val bytes: Long, val atEpochSecond: Long) : BackupOutcome
    data class Restored(val records: Int, val photos: Int) : BackupOutcome
    data class Exported(val bytes: Long) : BackupOutcome
    data object NoBackupFound : BackupOutcome
    data class NeedsSignIn(val cause: Throwable? = null) : BackupOutcome
    data class Failed(val message: String, val cause: Throwable? = null) : BackupOutcome
}

/**
 * Ties the journey together with Drive.
 *
 * Backup writes a zip into the cache, uploads it, then deletes the local copy —
 * the archive is never kept on the phone, so it costs no permanent storage.
 *
 * Restore **replaces** rather than merges. Merging two journeys would produce a
 * state neither the user nor the streak maths could explain, so the UI asks for
 * explicit confirmation and this then overwrites wholesale.
 */
class BackupRepository(
    private val context: Context,
    private val journeyRepository: JourneyRepository,
    private val promptRepository: PromptRepository,
    private val photoStore: PhotoStore,
    private val settingsRepository: SettingsRepository,
    private val drive: DriveClient,
    private val archive: BackupArchive = BackupArchive(),
    private val appVersion: String,
) {

    suspend fun backUp(token: String): BackupOutcome = withContext(Dispatchers.IO) {
        val staged = File(context.cacheDir, ARCHIVE_NAME)
        try {
            val records = journeyRepository.records.first()
            val pack = promptRepository.pack()
            val photos = records.mapNotNull { it.photoFileName?.let(photoStore::resolve) }

            val manifest = BackupManifest(
                createdAtEpochSecond = Instant.now().epochSecond,
                appVersion = appVersion,
                packId = pack.packId,
                startDateIso = pack.startDateIso,
                recordCount = records.size,
                photoCount = photos.size,
            )

            staged.outputStream().use { archive.write(it, manifest, records, photos) }

            val uploaded = drive.upload(token, ARCHIVE_NAME, staged)

            val now = Instant.now().epochSecond
            settingsRepository.setLastDriveBackup(now)
            Log.i(TAG, "Backed up ${records.size} days as ${uploaded.id}")

            BackupOutcome.BackedUp(bytes = staged.length(), atEpochSecond = now)
        } catch (e: DriveException) {
            if (e.isAuthExpired) BackupOutcome.NeedsSignIn(e)
            else BackupOutcome.Failed(e.message ?: DEFAULT_ERROR, e)
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            BackupOutcome.Failed(DEFAULT_ERROR, e)
        } finally {
            staged.delete()
        }
    }

    /** Reads the manifest of the newest backup without applying it. */
    suspend fun peek(token: String): Result<BackupManifest?> = withContext(Dispatchers.IO) {
        runCatching {
            val file = latestBackupFile(token) ?: return@runCatching null
            withDownloaded(token, file.id) { local, scratch ->
                local.inputStream().use { archive.read(it, scratch) }.manifest
            }
        }
    }

    suspend fun restoreLatest(token: String): BackupOutcome = withContext(Dispatchers.IO) {
        try {
            val file = latestBackupFile(token) ?: return@withContext BackupOutcome.NoBackupFound

            val contents = withDownloaded(token, file.id) { local, scratch ->
                local.inputStream().use { archive.read(it, scratch) }
            }

            applyRestore(contents)
        } catch (e: BackupFormatException) {
            BackupOutcome.Failed(e.message ?: DEFAULT_ERROR, e)
        } catch (e: DriveException) {
            if (e.isAuthExpired) BackupOutcome.NeedsSignIn(e)
            else BackupOutcome.Failed(e.message ?: DEFAULT_ERROR, e)
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            BackupOutcome.Failed(DEFAULT_ERROR, e)
        }
    }

    private suspend fun latestBackupFile(token: String): DriveFile? =
        drive.list(token, ARCHIVE_NAME).firstOrNull()

    /** Downloads to scratch space and cleans up afterwards, whatever happens. */
    private suspend fun <T> withDownloaded(
        token: String,
        fileId: String,
        block: (local: File, scratch: File) -> T,
    ): T {
        val local = File(context.cacheDir, "restore-$ARCHIVE_NAME")
        val scratch = File(context.cacheDir, "restore-sketches")
        return try {
            drive.download(token, fileId, local)
            block(local, scratch)
        } finally {
            local.delete()
            scratch.deleteRecursively()
        }
    }

    // ── Plain file export / import ───────────────────────────────────────────

    /**
     * Writes the same archive to [destination], a document the user picked.
     *
     * The Drive copy is deliberately hidden, which means its owner cannot open,
     * move or hand it to anyone. This is the escape hatch: an ordinary zip on
     * ordinary storage, needing no account and no Drive scope, that can be
     * restored into any build of the app.
     */
    suspend fun exportTo(destination: Uri): BackupOutcome = withContext(Dispatchers.IO) {
        try {
            val records = journeyRepository.records.first()
            val pack = promptRepository.pack()
            val photos = records.mapNotNull { it.photoFileName?.let(photoStore::resolve) }

            val manifest = BackupManifest(
                createdAtEpochSecond = Instant.now().epochSecond,
                appVersion = appVersion,
                packId = pack.packId,
                startDateIso = pack.startDateIso,
                recordCount = records.size,
                photoCount = photos.size,
            )

            val written = context.contentResolver.openOutputStream(destination)?.use { out ->
                archive.write(out, manifest, records, photos)
                true
            } ?: false

            if (!written) {
                BackupOutcome.Failed(CANNOT_WRITE)
            } else {
                val size = context.contentResolver
                    .openFileDescriptor(destination, "r")
                    ?.use { it.statSize }
                    ?: 0L
                BackupOutcome.Exported(size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            BackupOutcome.Failed(CANNOT_WRITE, e)
        }
    }

    /** Restores from a zip the user picked, with the same replace semantics. */
    suspend fun importFrom(source: Uri): BackupOutcome = withContext(Dispatchers.IO) {
        val scratch = File(context.cacheDir, "import-sketches")
        try {
            val contents = context.contentResolver.openInputStream(source)?.use { stream ->
                archive.read(stream, scratch)
            } ?: return@withContext BackupOutcome.Failed(CANNOT_READ)

            applyRestore(contents)
        } catch (e: BackupFormatException) {
            BackupOutcome.Failed(e.message ?: CANNOT_READ, e)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            BackupOutcome.Failed(CANNOT_READ, e)
        } finally {
            scratch.deleteRecursively()
        }
    }

    /**
     * Replaces the journey with [contents].
     *
     * Shared by Drive restore and file import so the two can never drift apart
     * in how they treat photos or dangling references.
     */
    private suspend fun applyRestore(contents: BackupContents): BackupOutcome {
        photoStore.clear()
        var restoredPhotos = 0
        contents.photos.forEach { (name, file) ->
            if (photoStore.adopt(file, name)) restoredPhotos++
        }

        // Drop photo references the archive did not actually carry, rather than
        // leaving records pointing at files that are not there.
        val records = contents.records.map { record ->
            val name = record.photoFileName
            if (name != null && !contents.photos.containsKey(name)) {
                record.copy(photoFileName = null)
            } else {
                record
            }
        }
        journeyRepository.replaceAll(records)

        Log.i(TAG, "Restored ${records.size} days and $restoredPhotos sketches")
        return BackupOutcome.Restored(records = records.size, photos = restoredPhotos)
    }

    private companion object {
        const val TAG = "BackupRepository"
        const val ARCHIVE_NAME = "sketchseed-backup.zip"
        const val DEFAULT_ERROR = "Something went wrong talking to Google Drive."
        const val CANNOT_WRITE = "Could not write the backup file."
        const val CANNOT_READ = "That file could not be read as a SketchSeed backup."
    }
}
