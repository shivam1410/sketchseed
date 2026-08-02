package com.shivam.sketchseed.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shivam.sketchseed.data.JourneyRepository
import com.shivam.sketchseed.data.PhotoStore
import com.shivam.sketchseed.data.PromptRepository
import com.shivam.sketchseed.data.SettingsRepository
import com.shivam.sketchseed.domain.model.ExtraSketch
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the export/import round trip end to end.
 *
 * This exists because of a real bug: restore extracted the archive into a
 * scratch directory, then applied it *after* the cleanup that deleted the
 * scratch — so every sketch was copied from a file that no longer existed. It
 * had already wiped the local photos first, so the net effect was to destroy
 * the user's sketches rather than restore them.
 *
 * Drive restore and file import share [BackupRepository.applyRestore], so
 * exercising the file path guards both without needing a network or an account.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {

    private lateinit var context: Context
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var journey: JourneyRepository
    private lateinit var photos: PhotoStore
    private lateinit var repository: BackupRepository
    private lateinit var prefsFile: File
    private val scratch = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // A private DataStore, so the test never touches the real journey.
        prefsFile = File(context.cacheDir, "backup-test-${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create { prefsFile }

        journey = JourneyRepository(dataStore)
        photos = PhotoStore(context)
        repository = BackupRepository(
            context = context,
            journeyRepository = journey,
            promptRepository = PromptRepository(context),
            photoStore = photos,
            settingsRepository = SettingsRepository(dataStore),
            drive = DriveClient(),
            appVersion = "test",
        )
    }

    @After
    fun tearDown() = runTest {
        photos.clear()
        prefsFile.delete()
        scratch.forEach { it.delete() }
    }

    private fun tempFile(name: String): File =
        File(context.cacheDir, "$name-${System.nanoTime()}").also { scratch += it }

    /** Writes a real JPEG and returns a URI the store can read. */
    private fun sourceImage(): Uri {
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)
        val file = tempFile("source.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    /** Records a finished day with a stored sketch, as the app would. */
    private suspend fun completeDayWithPhoto(day: Int): String {
        val pack = PromptRepository(context).pack()
        val prompt = pack.promptFor(day)!!
        val fileName = photos.save(sourceImage(), day)!!
        journey.complete(prompt, LocalDate.of(2026, 8, 2).plusDays(day.toLong()), fileName)
        return fileName
    }

    @Test
    fun sketchesSurviveAnExportAndImportRoundTrip() = runTest {
        val first = completeDayWithPhoto(1)
        val second = completeDayWithPhoto(2)
        val archive = tempFile("backup.zip")

        val exported = repository.exportTo(Uri.fromFile(archive))
        assertTrue("export failed: $exported", exported is BackupOutcome.Exported)

        // Simulate a fresh device: nothing on disk, no records.
        photos.clear()
        journey.reset()
        assertEquals(0, photos.usage().fileCount)

        val restored = repository.importFrom(Uri.fromFile(archive))

        assertTrue("import failed: $restored", restored is BackupOutcome.Restored)
        assertEquals(2, (restored as BackupOutcome.Restored).records)
        assertEquals("both sketches must come back", 2, restored.photos)

        // The regression: the files must actually be on disk afterwards.
        assertNotNull("first sketch missing", photos.resolve(first))
        assertNotNull("second sketch missing", photos.resolve(second))
        assertTrue(photos.resolve(first)!!.length() > 0)
        assertEquals(2, photos.usage().fileCount)
    }

    @Test
    fun restoredRecordsStillPointAtTheirSketches() = runTest {
        val fileName = completeDayWithPhoto(1)
        val archive = tempFile("backup.zip")
        repository.exportTo(Uri.fromFile(archive))

        photos.clear()
        journey.reset()
        repository.importFrom(Uri.fromFile(archive))

        val record = journey.records.first().single()
        assertEquals(fileName, record.photoFileName)
        assertNotNull("the record points at a file that is not there", photos.resolve(fileName))
    }

    @Test
    fun aJourneyWithNoSketchesRoundTripsCleanly() = runTest {
        val pack = PromptRepository(context).pack()
        journey.complete(pack.promptFor(1)!!, LocalDate.of(2026, 8, 2))
        val archive = tempFile("backup.zip")
        repository.exportTo(Uri.fromFile(archive))

        journey.reset()
        val restored = repository.importFrom(Uri.fromFile(archive))

        assertEquals(1, (restored as BackupOutcome.Restored).records)
        assertEquals(0, restored.photos)
        assertNull(journey.records.first().single().photoFileName)
    }

    @Test
    fun importingSomethingThatIsNotABackupLeavesTheJourneyAlone() = runTest {
        val fileName = completeDayWithPhoto(1)
        val junk = tempFile("junk.zip").apply { writeText("definitely not a zip") }

        val outcome = repository.importFrom(Uri.fromFile(junk))

        assertTrue("expected failure, got $outcome", outcome is BackupOutcome.Failed)
        // Nothing may be destroyed on the way to failing.
        assertEquals(1, journey.records.first().size)
        assertNotNull("a failed import must not delete sketches", photos.resolve(fileName))
    }

    /**
     * Extras carry their own photos. Gathering only the day's main sketch left
     * every bonus drawing out of the archive, so a restore quietly deleted them.
     */
    @Test
    fun bonusSketchesSurviveTheRoundTripToo() = runTest {
        val dayPhoto = completeDayWithPhoto(1)
        val extraPhoto = photos.save(sourceImage(), day = 1)!!
        journey.addExtra(
            day = 1,
            extra = ExtraSketch(
                id = "extra-1",
                title = "Second go",
                photoFileName = extraPhoto,
                createdOnEpochDay = LocalDate.of(2026, 8, 3).toEpochDay(),
            ),
        )
        val archive = tempFile("backup.zip")
        repository.exportTo(Uri.fromFile(archive))

        photos.clear()
        journey.reset()
        val restored = repository.importFrom(Uri.fromFile(archive))

        assertEquals("both the day and its extra", 2, (restored as BackupOutcome.Restored).photos)
        assertNotNull(photos.resolve(dayPhoto))
        assertNotNull("the bonus sketch was dropped", photos.resolve(extraPhoto))

        val extra = journey.records.first().single().extras.single()
        assertEquals(extraPhoto, extra.photoFileName)
        assertEquals("Second go", extra.title)
    }

    @Test
    fun restoreReplacesRatherThanMerges() = runTest {
        completeDayWithPhoto(1)
        val archive = tempFile("backup.zip")
        repository.exportTo(Uri.fromFile(archive))

        // Draw another day after the backup was taken.
        completeDayWithPhoto(2)
        assertEquals(2, journey.records.first().size)

        repository.importFrom(Uri.fromFile(archive))

        val records = journey.records.first()
        assertEquals("the later day must be gone after a restore", 1, records.size)
        assertEquals(1, records.single().day)
        assertEquals("its orphaned sketch must be swept up too", 1, photos.usage().fileCount)
    }
}
