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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Restoring an archive whose images are missing but whose records still name them.
 *
 * This is what a hand-edited backup looks like when the zip tool drops or renames
 * the sketches folder — and the app was told to hand-edit, so it is a real shape,
 * not a hypothetical. Reported as "I restored and the images didn't come".
 *
 * The danger is not that the photos fail to arrive; there are none to arrive. It is
 * that restore was replacing wholesale: references to absent photos were dropped and
 * `retainOnly` then deleted the local copies, so a sketch that was safely on disk
 * before the restore is gone after it, with nothing pointing at it either.
 *
 * A photo already on this phone under the name a record asks for is the same photo.
 * Keeping it costs nothing and is the difference between a confusing restore and a
 * destructive one.
 */
@RunWith(AndroidJUnit4::class)
class RestoreWithoutPhotosTest {

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
            photoStore = photos,
            settingsRepository = settings,
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

    private fun sourceImage(): Uri {
        val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.MAGENTA)
        val file = tempFile("src-${System.nanoTime()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    /** Rewrites [zip], keeping the json entries and dropping every image. */
    private fun strippedOfImages(zip: File): File {
        val out = tempFile("stripped-${System.nanoTime()}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { writer ->
            ZipInputStream(zip.inputStream().buffered()).use { reader ->
                while (true) {
                    val entry = reader.nextEntry ?: break
                    if (entry.isDirectory || entry.name.startsWith("sketches/")) {
                        reader.closeEntry(); continue
                    }
                    writer.putNextEntry(ZipEntry(entry.name))
                    reader.copyTo(writer)
                    writer.closeEntry()
                    reader.closeEntry()
                }
            }
        }
        return out
    }

    @Test
    fun aSketchAlreadyOnThisPhoneSurvivesAnArchiveThatOmitsIt() = runTest {
        val pack = PromptRepository(context).pack()
        val fileName = photos.save(sourceImage(), 1)!!
        journey.complete(pack.promptFor(1)!!, java.time.LocalDate.of(2026, 8, 2).atTime(12, 0), fileName)

        val full = tempFile("full.zip")
        repository.exportTo(Uri.fromFile(full))
        val stripped = strippedOfImages(full)

        repository.importFrom(Uri.fromFile(stripped))

        val restored = journey.records.first().single()
        assertEquals("the reference must not be dropped", fileName, restored.photoFileName)
        assertNotNull("the file must still be on disk", photos.resolve(fileName))
        assertTrue("the sketch must not have been deleted", photos.usage().fileCount >= 1)
    }

    @Test
    fun theJourneyItselfStillRestores() = runTest {
        val pack = PromptRepository(context).pack()
        val fileName = photos.save(sourceImage(), 1)!!
        journey.complete(pack.promptFor(1)!!, java.time.LocalDate.of(2026, 8, 2).atTime(12, 0), fileName)

        val stripped = strippedOfImages(tempFile("full2.zip").also {
            repository.exportTo(Uri.fromFile(it))
        })

        val outcome = repository.importFrom(Uri.fromFile(stripped))

        assertTrue("import should still succeed, got $outcome", outcome is BackupOutcome.Restored)
        assertEquals(1, journey.records.first().size)
    }

    @Test
    fun aReferenceToASketchNobodyHasIsStillDropped() = runTest {
        // The other half of the rule: a name that is neither in the archive nor on
        // this phone points at nothing, and must not survive as a broken reference.
        val pack = PromptRepository(context).pack()
        val fileName = photos.save(sourceImage(), 1)!!
        journey.complete(pack.promptFor(1)!!, java.time.LocalDate.of(2026, 8, 2).atTime(12, 0), fileName)

        val stripped = strippedOfImages(tempFile("full3.zip").also {
            repository.exportTo(Uri.fromFile(it))
        })
        // Remove the local copy too, so nothing anywhere has it.
        photos.clear()

        repository.importFrom(Uri.fromFile(stripped))

        assertEquals(null, journey.records.first().single().photoFileName)
    }
}
