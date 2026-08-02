package com.shivam.sketchseed.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real decode/encode path against Android's BitmapFactory.
 *
 * These have to be instrumented rather than JVM tests: the bug they guard
 * against lived in BitmapFactory's bounds-decoding contract, which the JVM stubs
 * do not reproduce.
 */
@RunWith(AndroidJUnit4::class)
class PhotoStoreTest {

    private lateinit var context: Context
    private lateinit var store: PhotoStore
    private val scratch = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = PhotoStore(context)
    }

    @After
    fun tearDown() = runTest {
        store.clear()
        scratch.forEach { it.delete() }
    }

    /** Writes a real JPEG to the cache and returns a readable URI for it. */
    private fun sourceImage(width: Int, height: Int): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawCircle(
                width / 2f,
                height / 2f,
                minOf(width, height) / 3f,
                Paint().apply { color = Color.DKGRAY },
            )
        }
        val file = File(context.cacheDir, "source-${width}x$height-${scratch.size}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        scratch += file
        return Uri.fromFile(file)
    }

    @Test
    fun savesAScannedSketchToDisk() = runTest {
        val fileName = store.save(sourceImage(2400, 3200), day = 7, includeInBackup = true)

        assertNotNull("save must return a file name", fileName)
        val stored = store.resolve(fileName!!)
        assertNotNull("the saved file must be findable", stored)
        assertTrue("the saved file must have content", stored!!.length() > 0)
    }

    @Test
    fun downscalesLargeImagesToTheBackupBudget() = runTest {
        val fileName = store.save(sourceImage(4000, 3000), day = 1, includeInBackup = true)
        val stored = store.resolve(fileName!!)!!

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(stored.absolutePath, bounds)

        assertTrue(
            "longest edge was ${maxOf(bounds.outWidth, bounds.outHeight)}",
            maxOf(bounds.outWidth, bounds.outHeight) <= 1600,
        )
        // A hundred of these must fit inside Auto Backup's 25 MB allowance.
        assertTrue("stored ${stored.length()} bytes", stored.length() < 512 * 1024)
    }

    @Test
    fun keepsAspectRatioWhenDownscaling() = runTest {
        val fileName = store.save(sourceImage(4000, 2000), day = 2, includeInBackup = true)
        val stored = store.resolve(fileName!!)!!

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(stored.absolutePath, bounds)

        val ratio = bounds.outWidth.toFloat() / bounds.outHeight
        assertTrue("ratio was $ratio", ratio in 1.9f..2.1f)
    }

    @Test
    fun reportsFailureInsteadOfThrowingOnAnUnreadableSource() = runTest {
        val missing = Uri.fromFile(File(context.cacheDir, "does-not-exist.jpg"))

        assertNull(store.save(missing, day = 3, includeInBackup = true))
    }

    @Test
    fun excludedSketchesLandOutsideTheBackedUpDirectory() = runTest {
        val fileName = store.save(sourceImage(800, 800), day = 4, includeInBackup = false)!!
        val stored = store.resolve(fileName)!!

        assertTrue(
            "expected no_backup, got ${stored.absolutePath}",
            stored.absolutePath.startsWith(context.noBackupFilesDir.absolutePath),
        )
    }

    @Test
    fun togglingBackupMovesExistingSketches() = runTest {
        val fileName = store.save(sourceImage(800, 800), day = 5, includeInBackup = true)!!
        assertTrue(
            store.resolve(fileName)!!.absolutePath.startsWith(context.filesDir.absolutePath),
        )

        store.applyBackupPreference(includeInBackup = false)

        val moved = store.resolve(fileName)
        assertNotNull("the sketch must survive the move", moved)
        assertTrue(
            "expected no_backup, got ${moved!!.absolutePath}",
            moved.absolutePath.startsWith(context.noBackupFilesDir.absolutePath),
        )

        store.applyBackupPreference(includeInBackup = true)
        assertTrue(
            store.resolve(fileName)!!.absolutePath.startsWith(context.filesDir.absolutePath),
        )
    }

    @Test
    fun usageCountsSketchesAcrossBothDirectories() = runTest {
        store.save(sourceImage(600, 600), day = 6, includeInBackup = true)
        store.save(sourceImage(600, 600), day = 8, includeInBackup = false)

        val usage = store.usage()

        assertEquals(2, usage.fileCount)
        assertTrue(usage.totalBytes > 0)
    }

    @Test
    fun deleteRemovesTheStoredSketch() = runTest {
        val fileName = store.save(sourceImage(600, 600), day = 9, includeInBackup = true)!!

        store.delete(fileName)

        assertNull(store.resolve(fileName))
    }

    @Test
    fun clearEmptiesBothDirectories() = runTest {
        store.save(sourceImage(600, 600), day = 10, includeInBackup = true)
        store.save(sourceImage(600, 600), day = 11, includeInBackup = false)

        store.clear()

        assertEquals(0, store.usage().fileCount)
    }

    @Test
    fun temporaryFilesAreNotLeftBehind() = runTest {
        val fileName = store.save(sourceImage(1200, 1200), day = 12, includeInBackup = true)!!
        val dir = store.resolve(fileName)!!.parentFile!!

        assertFalse(
            "a .tmp file was left behind",
            dir.listFiles().orEmpty().any { it.name.endsWith(".tmp") },
        )
    }
}
