package com.shivam.sketchseed.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How much space the saved sketches take up. */
data class PhotoUsage(
    val fileCount: Int = 0,
    val totalBytes: Long = 0L,
)

/**
 * Saves sketches to internal storage, downscaled and re-encoded.
 *
 * Everything lives in one directory. An earlier version split sketches between
 * a backed-up and a no-backup directory to drive Android Auto Backup's static
 * XML rules; Auto Backup is gone, so that split is gone with it. [LEGACY_DIR] is
 * still read so photos written by that version are never orphaned.
 *
 * Images are downscaled on the way in. Nothing forces it now that the 25 MB
 * quota is irrelevant, but a hundred full-resolution photos would make every
 * Drive backup slow and large for no visible gain on a phone screen.
 */
class PhotoStore(private val context: Context) {

    private val sketchesDir: File get() = File(context.filesDir, DIR_NAME)

    /** Where the Auto Backup era kept sketches the user excluded from backup. */
    private val legacyDir: File get() = File(context.noBackupFilesDir, DIR_NAME)

    private fun targetDir(): File = sketchesDir.apply { mkdirs() }

    /**
     * Compresses [source] and stores it for [day].
     *
     * @return the stored file name, or null if the image could not be read.
     */
    suspend fun save(source: Uri, day: Int): String? = withContext(Dispatchers.IO) {
        val bitmap = decodeDownscaled(source) ?: return@withContext null
        val fileName = "day-%03d-%d.jpg".format(day, System.currentTimeMillis())
        val destination = File(targetDir(), fileName)
        // Write to a temp file first so an interrupted save cannot leave a
        // truncated image referenced by a record.
        val temp = File(destination.parentFile, "$fileName.tmp")
        try {
            FileOutputStream(temp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (!temp.renameTo(destination)) {
                throw IOException("Could not move ${temp.name} into place")
            }
            fileName
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save sketch for day $day", e)
            temp.delete()
            null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Takes ownership of an already-decoded sketch, e.g. one extracted from a
     * backup archive.
     *
     * The bytes are copied verbatim rather than re-encoded: they were already
     * downscaled when first saved, and a second JPEG pass would visibly degrade
     * them for no gain.
     *
     * @return true if the file is now stored under [fileName].
     */
    suspend fun adopt(source: File, fileName: String): Boolean = withContext(Dispatchers.IO) {
        val destination = File(targetDir(), fileName)
        try {
            source.copyTo(destination, overwrite = true)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Could not store restored sketch $fileName", e)
            false
        }
    }

    /** Finds a sketch, falling back to the pre-Drive location. */
    fun resolve(fileName: String): File? =
        File(sketchesDir, fileName).takeIf { it.exists() }
            ?: File(legacyDir, fileName).takeIf { it.exists() }

    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        resolve(fileName)?.delete()
        Unit
    }

    suspend fun usage(): PhotoUsage = withContext(Dispatchers.IO) {
        val files = buildList {
            sketchesDir.listFiles()?.let { addAll(it) }
            legacyDir.listFiles()?.let { addAll(it) }
        }.filter { it.isFile && !it.name.endsWith(".tmp") }

        PhotoUsage(fileCount = files.size, totalBytes = files.sumOf { it.length() })
    }

    /**
     * Deletes every stored sketch except those in [keep].
     *
     * Restore uses this instead of [clear] so the new sketches are already on
     * disk before anything is removed. Clearing first would mean a failure
     * halfway through left the user with neither copy.
     */
    suspend fun retainOnly(keep: Set<String>) = withContext(Dispatchers.IO) {
        listOf(sketchesDir, legacyDir).forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name !in keep) file.delete()
            }
        }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        sketchesDir.deleteRecursively()
        legacyDir.deleteRecursively()
        Unit
    }

    /**
     * Decodes [source] at roughly [MAX_DIMENSION] on its longest edge.
     *
     * Two passes: read the bounds only, pick a power-of-two sample size, then
     * decode. That keeps a large photo from ever being fully expanded in memory.
     */
    private fun decodeDownscaled(source: Uri): Bitmap? {
        // Pass one: bounds only. decodeStream deliberately returns null when
        // inJustDecodeBounds is set — it reports through the options object — so
        // the null check here must be on the *stream*, never on the return value.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val readBounds = openStream(source) { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
            true
        }
        if (readBounds != true) return null

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.e(TAG, "Sketch image had no usable dimensions")
            return null
        }

        // Pass two: decode at roughly the target size.
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = openStream(source) { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        if (decoded == null) {
            Log.e(TAG, "Sketch image could not be decoded")
            return null
        }

        // Rotate after scaling: same result, less work, since the bitmap is
        // already down to its final size.
        return uprighted(scaleToBound(decoded), orientationOf(source))
    }

    /**
     * Reads the EXIF orientation a camera recorded for [source].
     *
     * Phone cameras write the sensor image as-is and note the display rotation
     * in metadata. BitmapFactory ignores that tag completely, so without this
     * step every photo taken holding the phone upright lands on its side.
     */
    private fun orientationOf(source: Uri): Int =
        openStream(source) { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

    /**
     * Bakes [orientation] into the pixels.
     *
     * Baking rather than copying the tag across is deliberate: the sketch is
     * re-encoded here and the output carries no EXIF at all, so an image that
     * relied on a tag would be displayed wrongly by anything that reads it —
     * including a plain file browser after a Drive restore.
     */
    private fun uprighted(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            // NORMAL, UNDEFINED, or anything unrecognised: leave it alone.
            else -> return bitmap
        }

        return try {
            val upright = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true,
            )
            if (upright !== bitmap) bitmap.recycle()
            upright
        } catch (e: OutOfMemoryError) {
            // A sideways sketch beats no sketch.
            Log.e(TAG, "Not enough memory to rotate the sketch; keeping it as shot", e)
            bitmap
        }
    }

    /**
     * Opens [source] and runs [block] on it.
     *
     * A camera or picker hands back a content URI owned by another app, so this
     * can fail with [SecurityException] as well as [IOException] if the grant has
     * lapsed. Both are reported rather than thrown, since a failed read must not
     * take down the coroutine that is also recording the day.
     */
    private fun <T> openStream(source: Uri, block: (InputStream) -> T?): T? = try {
        context.contentResolver.openInputStream(source)?.use(block)
            ?: run {
                Log.e(TAG, "Could not open $source; no stream returned")
                null
            }
    } catch (e: IOException) {
        Log.e(TAG, "Could not read $source", e)
        null
    } catch (e: SecurityException) {
        Log.e(TAG, "Not permitted to read $source", e)
        null
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= MAX_DIMENSION || height / (sample * 2) >= MAX_DIMENSION) {
            sample *= 2
        }
        return sample
    }

    /** Sample size only halves, so trim the remainder to hit the bound exactly. */
    private fun scaleToBound(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) return bitmap

        val ratio = MAX_DIMENSION.toFloat() / longest
        val scaled = bitmap.scale(
            width = (bitmap.width * ratio).toInt().coerceAtLeast(1),
            height = (bitmap.height * ratio).toInt().coerceAtLeast(1),
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private companion object {
        const val TAG = "PhotoStore"
        const val DIR_NAME = "sketches"
        const val LEGACY_DIR = "no_backup/sketches"
        const val MAX_DIMENSION = 1600
        const val JPEG_QUALITY = 80
    }
}
