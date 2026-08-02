package com.shivam.sketchseed.backup

import android.content.Context
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

            val folderId = drive.findOrCreateFolder(token, FOLDER_NAME)
            val uploaded = drive.upload(token, folderId, ARCHIVE_NAME, staged)

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

            val keepPhotosInBackup = settingsRepository.settings.first().backupSketches

            // Replace wholesale: clear what is here before adopting the archive,
            // so no orphaned sketch from the old journey survives.
            photoStore.clear()
            var restoredPhotos = 0
            contents.photos.forEach { (name, file) ->
                if (photoStore.adopt(file, name, keepPhotosInBackup)) restoredPhotos++
            }

            // Drop photo references the archive did not actually carry, rather
            // than leaving records pointing at files that are not there.
            val records = contents.records.map { record ->
                if (record.photoFileName != null && !contents.photos.containsKey(record.photoFileName)) {
                    record.copy(photoFileName = null)
                } else {
                    record
                }
            }
            journeyRepository.replaceAll(records)

            Log.i(TAG, "Restored ${records.size} days and $restoredPhotos sketches")
            BackupOutcome.Restored(records = records.size, photos = restoredPhotos)
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

    private suspend fun latestBackupFile(token: String): DriveFile? {
        val folderId = drive.findOrCreateFolder(token, FOLDER_NAME)
        return drive.list(token, folderId).firstOrNull { it.name == ARCHIVE_NAME }
    }

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

    private companion object {
        const val TAG = "BackupRepository"
        const val FOLDER_NAME = "SketchSeed"
        const val ARCHIVE_NAME = "sketchseed-backup.zip"
        const val DEFAULT_ERROR = "Something went wrong talking to Google Drive."
    }
}
