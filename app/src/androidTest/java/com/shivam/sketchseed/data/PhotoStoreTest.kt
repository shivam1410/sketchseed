package com.shivam.sketchseed.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
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
    fun savesASketchToDisk() = runTest {
        val fileName = store.save(sourceImage(2400, 3200), day = 7)

        assertNotNull("save must return a file name", fileName)
        val stored = store.resolve(fileName!!)
        assertNotNull("the saved file must be findable", stored)
        assertTrue("the saved file must have content", stored!!.length() > 0)
    }

    @Test
    fun downscalesLargeImages() = runTest {
        val fileName = store.save(sourceImage(4000, 3000), day = 1)
        val stored = store.resolve(fileName!!)!!

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(stored.absolutePath, bounds)

        assertTrue(
            "longest edge was ${maxOf(bounds.outWidth, bounds.outHeight)}",
            maxOf(bounds.outWidth, bounds.outHeight) <= 1600,
        )
        // Keeps a hundred-sketch Drive backup to a sane size.
        assertTrue("stored ${stored.length()} bytes", stored.length() < 512 * 1024)
    }

    @Test
    fun keepsAspectRatioWhenDownscaling() = runTest {
        val fileName = store.save(sourceImage(4000, 2000), day = 2)
        val stored = store.resolve(fileName!!)!!

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(stored.absolutePath, bounds)

        val ratio = bounds.outWidth.toFloat() / bounds.outHeight
        assertTrue("ratio was $ratio", ratio in 1.9f..2.1f)
    }

    // ── EXIF orientation ─────────────────────────────────────────────────────
    //
    // Phone cameras store the sensor image as-is and record the display rotation
    // in metadata. BitmapFactory ignores that tag, so without correction every
    // photo taken holding the phone upright is saved on its side.

    /** Writes a JPEG whose EXIF says "rotate me before displaying". */
    private fun rotatedSourceImage(width: Int, height: Int, orientation: Int): Uri {
        val uri = sourceImage(width, height)
        val path = uri.path!!
        ExifInterface(path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return uri
    }

    private fun storedBounds(fileName: String): BitmapFactory.Options {
        val stored = store.resolve(fileName)!!
        return BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(stored.absolutePath, this)
        }
    }

    @Test
    fun aSidewaysPhotoIsSavedUpright() = runTest {
        // Landscape pixels tagged "rotate 90" is what a phone held upright
        // actually produces.
        val source = rotatedSourceImage(1200, 600, ExifInterface.ORIENTATION_ROTATE_90)

        val bounds = storedBounds(store.save(source, day = 20)!!)

        assertTrue(
            "expected portrait after rotation, got ${bounds.outWidth}x${bounds.outHeight}",
            bounds.outHeight > bounds.outWidth,
        )
    }

    @Test
    fun anUprightPhotoIsLeftAlone() = runTest {
        val source = rotatedSourceImage(1200, 600, ExifInterface.ORIENTATION_NORMAL)

        val bounds = storedBounds(store.save(source, day = 21)!!)

        assertTrue(
            "expected landscape to stay landscape, got ${bounds.outWidth}x${bounds.outHeight}",
            bounds.outWidth > bounds.outHeight,
        )
    }

    @Test
    fun aTransposedPhotoIsAlsoCorrected() = runTest {
        val source = rotatedSourceImage(1200, 600, ExifInterface.ORIENTATION_TRANSPOSE)

        val bounds = storedBounds(store.save(source, day = 22)!!)

        assertTrue(
            "expected portrait after transpose, got ${bounds.outWidth}x${bounds.outHeight}",
            bounds.outHeight > bounds.outWidth,
        )
    }

    @Test
    fun theSavedSketchCarriesNoOrientationTagOfItsOwn() = runTest {
        // The rotation is baked into the pixels, so anything reading the file —
        // a file browser after a Drive restore, say — sees it the right way up
        // without needing to honour a tag.
        val source = rotatedSourceImage(1200, 600, ExifInterface.ORIENTATION_ROTATE_90)
        val stored = store.resolve(store.save(source, day = 23)!!)!!

        val orientation = ExifInterface(stored.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)

        // NORMAL and UNDEFINED both mean "display as-is"; re-encoding drops the
        // tag entirely, so which of the two shows up is not ours to pin down.
        // What matters is that nothing asks for another rotation on top.
        assertTrue(
            "saved sketch still asks to be rotated: $orientation",
            orientation == ExifInterface.ORIENTATION_NORMAL ||
                orientation == ExifInterface.ORIENTATION_UNDEFINED,
        )
    }

    @Test
    fun reportsFailureInsteadOfThrowingOnAnUnreadableSource() = runTest {
        val missing = Uri.fromFile(File(context.cacheDir, "does-not-exist.jpg"))

        assertNull(store.save(missing, day = 3))
    }

    @Test
    fun sketchesLandInTheSingleStoreDirectory() = runTest {
        val fileName = store.save(sourceImage(800, 800), day = 4)!!
        val stored = store.resolve(fileName)!!

        assertTrue(
            "expected filesDir, got ${stored.absolutePath}",
            stored.absolutePath.startsWith(context.filesDir.absolutePath),
        )
    }

    /**
     * The Auto Backup era kept excluded sketches under no_backup. Those photos
     * must stay reachable, or upgrading the app would silently lose them.
     */
    @Test
    fun sketchesLeftBehindByTheOldLayoutAreStillFound() = runTest {
        val legacy = legacySketch("day-099-legacy.jpg")

        val resolved = store.resolve(legacy.name)

        assertNotNull("a pre-upgrade sketch must still resolve", resolved)
        assertEquals(legacy.absolutePath, resolved!!.absolutePath)
    }

    @Test
    fun usageCountsSketchesFromBothLayouts() = runTest {
        store.save(sourceImage(600, 600), day = 6)
        legacySketch("day-098-legacy.jpg")

        val usage = store.usage()

        assertEquals(2, usage.fileCount)
        assertTrue(usage.totalBytes > 0)
    }

    @Test
    fun clearRemovesSketchesFromBothLayouts() = runTest {
        store.save(sourceImage(600, 600), day = 7)
        legacySketch("day-097-legacy.jpg")

        store.clear()

        assertEquals(0, store.usage().fileCount)
    }

    /** Plants a file where the pre-Drive version of the app kept them. */
    private fun legacySketch(name: String): File {
        val dir = File(context.noBackupFilesDir, "sketches").apply { mkdirs() }
        return File(dir, name).apply { writeText("legacy sketch bytes") }
    }

    @Test
    fun deleteRemovesTheStoredSketch() = runTest {
        val fileName = store.save(sourceImage(600, 600), day = 9)!!

        store.delete(fileName)

        assertNull(store.resolve(fileName))
    }

    @Test
    fun temporaryFilesAreNotLeftBehind() = runTest {
        val fileName = store.save(sourceImage(1200, 1200), day = 12)!!
        val dir = store.resolve(fileName)!!.parentFile!!

        assertFalse(
            "a .tmp file was left behind",
            dir.listFiles().orEmpty().any { it.name.endsWith(".tmp") },
        )
    }
}
