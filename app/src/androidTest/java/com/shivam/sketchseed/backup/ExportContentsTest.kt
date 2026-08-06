package com.shivam.sketchseed.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shivam.sketchseed.data.JourneyRepository
import com.shivam.sketchseed.data.PhotoStore
import com.shivam.sketchseed.data.PromptRepository
import com.shivam.sketchseed.data.SettingsRepository
import com.shivam.sketchseed.data.appDataStore
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What an exported archive actually contains.
 *
 * Written after a report that restoring brought the journey back without the
 * sketches, and that the zip appeared to have no images in it. The round-trip test
 * next door proves export-then-import preserves photos, which means it would pass
 * even if both halves agreed on carrying nothing. This looks inside the file.
 */
@RunWith(AndroidJUnit4::class)
class ExportContentsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val photos = PhotoStore(context)
    private val journey = JourneyRepository(context.appDataStore)
    private val settings = SettingsRepository(context.appDataStore)

    private lateinit var repository: BackupRepository
    private val temps = mutableListOf<File>()

    @Before
    fun setUp() = runTest {
        journey.reset()
        photos.clear()
        repository = BackupRepository(
            context = context,
            journeyRepository = journey,
            promptRepository = PromptRepository(context),
            settingsRepository = settings,
            photoStore = photos,
            drive = DriveClient(),
            archive = BackupArchive(),
            appVersion = "test",
        )
    }

    @After
    fun tearDown() = runTest {
        temps.forEach { it.delete() }
        journey.reset()
        photos.clear()
    }

    private fun tempFile(name: String) = File(context.cacheDir, name).also { temps.add(it) }

    /** A real JPEG on disk, so PhotoStore has something genuine to store. */
    private fun sourceImage(): Uri {
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.CYAN)
        val file = tempFile("source-${System.nanoTime()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private fun entriesOf(zip: File): List<String> = buildList {
        ZipInputStream(zip.inputStream().buffered()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                add(entry.name)
                stream.closeEntry()
            }
        }
    }

    @Test
    fun anExportCarriesTheSketchesInTheirOwnFolder() = runTest {
        val pack = PromptRepository(context).pack()
        val fileName = photos.save(sourceImage(), 1)
        assertEquals("the photo must be on disk to begin with", true, fileName != null)
        journey.complete(pack.promptFor(1)!!, LocalDate.of(2026, 8, 2).atTime(12, 0), fileName)

        val archive = tempFile("export-contents.zip")
        repository.exportTo(Uri.fromFile(archive))

        val entries = entriesOf(archive)
        assertTrue("manifest.json missing, got $entries", entries.contains("manifest.json"))
        assertTrue("journey.json missing, got $entries", entries.contains("journey.json"))
        assertTrue(
            "no sketches/ entry — the archive carries no images at all. Got $entries",
            entries.any { it.startsWith("sketches/") },
        )
        assertTrue(
            "the sketch is not named as the record refers to it. Got $entries",
            entries.contains("sketches/$fileName"),
        )
    }

    @Test
    fun theRecordStillPointsAtItsSketchAfterExport() = runTest {
        // If a record's photoFileName were ever nulled, export would find nothing to
        // carry and every later export would be imageless too — a loss that spreads.
        val pack = PromptRepository(context).pack()
        val fileName = photos.save(sourceImage(), 1)
        journey.complete(pack.promptFor(1)!!, LocalDate.of(2026, 8, 2).atTime(12, 0), fileName)

        repository.exportTo(Uri.fromFile(tempFile("export-pointer.zip")))

        assertEquals(fileName, journey.records.first().single().photoFileName)
    }

    @Test
    fun anExtraSketchIsCarriedToo() = runTest {
        val pack = PromptRepository(context).pack()
        val dayPhoto = photos.save(sourceImage(), 1)
        journey.complete(pack.promptFor(1)!!, LocalDate.of(2026, 8, 2).atTime(12, 0), dayPhoto)
        val extraPhoto = photos.save(sourceImage(), 1)
        journey.addExtra(
            day = 1,
            extra = com.shivam.sketchseed.domain.model.ExtraSketch(
                id = "extra-1",
                title = "Second apple",
                photoFileName = extraPhoto,
                createdOnEpochDay = LocalDate.of(2026, 8, 2).toEpochDay(),
            ),
        )

        val archive = tempFile("export-extra.zip")
        repository.exportTo(Uri.fromFile(archive))

        val entries = entriesOf(archive)
        assertTrue("day sketch missing from $entries", entries.contains("sketches/$dayPhoto"))
        assertTrue("extra sketch missing from $entries", entries.contains("sketches/$extraPhoto"))
    }
}
