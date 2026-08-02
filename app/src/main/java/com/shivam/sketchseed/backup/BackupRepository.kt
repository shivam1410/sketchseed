package com.shivam.sketchseed.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.shivam.sketchseed.data.JourneyRepository
import com.shivam.sketchseed.data.PhotoStore
import com.shivam.sketchseed.data.PromptRepository
import com.shivam.sketchseed.data.SettingsRepository
import com.shivam.sketchseed.domain.model.DayRecord
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
            val photos = records.flatMap(::photoNamesOf).mapNotNull(photoStore::resolve)

            val manifest = BackupManifest(
                createdAtEpochSecond = Instant.now().epochSecond,
                appVersion = appVersion,
                packId = pack.packId,
                startDateIso = pack.startDateIso,
                recordCount = records.size,
                photoCount = photos.size,
            )

            staged.outputStream().use { archive.write(it, manifest, records, photos) }

            rotatePrevious(token)
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

            // Applied *inside* the block: the extracted sketches live in the
            // scratch directory, which is deleted the moment this returns.
            withDownloaded(token, file.id) { local, scratch ->
                applyRestore(local.inputStream().use { archive.read(it, scratch) })
            }
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

    /**
     * Demotes the current backup before a new one replaces it.
     *
     * Keeping one generation back is the difference between an unlucky upload
     * costing you a day and costing you everything. A backup that only ever
     * holds the newest state is only as trustworthy as the state that produced
     * it: anything that empties the journey locally would, one automatic run
     * later, empty the backup too.
     */
    private suspend fun rotatePrevious(token: String) {
        val current = drive.list(token, ARCHIVE_NAME).firstOrNull() ?: return
        try {
            drive.list(token, PREVIOUS_NAME).forEach { drive.delete(token, it.id) }
            drive.rename(token, current.id, PREVIOUS_NAME)
        } catch (e: DriveException) {
            // Losing the older generation must not stop today's backup.
            Log.w(TAG, "Could not rotate the previous backup", e)
        }
    }

    private suspend fun latestBackupFile(token: String): DriveFile? =
        drive.list(token, ARCHIVE_NAME).firstOrNull()
            ?: drive.list(token, PREVIOUS_NAME).firstOrNull()
                .also { if (it != null) Log.i(TAG, "Falling back to the previous backup") }

    /** Downloads to scratch space and cleans up afterwards, whatever happens. */
    private suspend fun <T> withDownloaded(
        token: String,
        fileId: String,
        block: suspend (local: File, scratch: File) -> T,
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
            val photos = records.flatMap(::photoNamesOf).mapNotNull(photoStore::resolve)

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
     * Every sketch file a day owns.
     *
     * Extras carry their own photos, so gathering only [DayRecord.photoFileName]
     * would quietly leave bonus sketches out of every backup.
     */
    private fun photoNamesOf(record: DayRecord): List<String> =
        listOfNotNull(record.photoFileName) + record.extras.mapNotNull { it.photoFileName }

    /**
     * Replaces the journey with [contents].
     *
     * Shared by Drive restore and file import so the two can never drift apart
     * in how they treat photos or dangling references.
     */
    private suspend fun applyRestore(contents: BackupContents): BackupOutcome {
        // Adopt before deleting anything. If this is interrupted the user is
        // left with both copies rather than neither, which is the only failure
        // mode worth optimising for in a restore.
        val adopted = buildSet {
            contents.photos.forEach { (name, file) ->
                if (photoStore.adopt(file, name)) add(name) else Log.e(TAG, "Could not restore $name")
            }
        }

        // Nothing may point at a sketch that is not on disk, so drop references
        // the archive did not carry or that failed to copy — extras included.
        val records = contents.records.map { record ->
            record.copy(
                photoFileName = record.photoFileName?.takeIf { it in adopted },
                extras = record.extras.map { extra ->
                    extra.copy(photoFileName = extra.photoFileName?.takeIf { it in adopted })
                },
            )
        }
        journeyRepository.replaceAll(records)

        // Only now remove what the restored journey no longer refers to.
        photoStore.retainOnly(adopted)

        Log.i(TAG, "Restored ${records.size} days and ${adopted.size} sketches")
        return BackupOutcome.Restored(records = records.size, photos = adopted.size)
    }

    private companion object {
        const val TAG = "BackupRepository"
        const val ARCHIVE_NAME = "sketchseed-backup.zip"
        const val PREVIOUS_NAME = "sketchseed-backup-previous.zip"
        const val DEFAULT_ERROR = "Something went wrong talking to Google Drive."
        const val CANNOT_WRITE = "Could not write the backup file."
        const val CANNOT_READ = "That file could not be read as a SketchSeed backup."
    }
}
