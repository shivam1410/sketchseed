package com.shivam.sketchseed.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How much space the saved sketches take up. */
data class PhotoUsage(
    val fileCount: Int = 0,
    val totalBytes: Long = 0L,
)

/**
 * Saves scanned sketches to internal storage, downscaled and re-encoded.
 *
 * Two directories, chosen by the Settings toggle:
 *
 *  - `filesDir/sketches`      — included in Android Auto Backup
 *  - `noBackupFilesDir/sketches` — never backed up, by platform definition
 *
 * Flipping the toggle physically moves the files between them, because Auto
 * Backup rules are static XML and cannot be switched at runtime.
 *
 * Images are downscaled because Auto Backup allows only [BACKUP_QUOTA_BYTES] per
 * app; a hundred full-resolution photos would blow straight past it.
 */
class PhotoStore(private val context: Context) {

    private val backedUpDir: File get() = File(context.filesDir, DIR_NAME)
    private val localOnlyDir: File get() = File(context.noBackupFilesDir, DIR_NAME)

    private fun targetDir(includeInBackup: Boolean): File =
        (if (includeInBackup) backedUpDir else localOnlyDir).apply { mkdirs() }

    /**
     * Compresses [source] and stores it for [day].
     *
     * @return the stored file name, or null if the image could not be read.
     */
    suspend fun save(source: Uri, day: Int, includeInBackup: Boolean): String? =
        withContext(Dispatchers.IO) {
            val bitmap = decodeDownscaled(source) ?: return@withContext null
            val fileName = "day-%03d-%d.jpg".format(day, System.currentTimeMillis())
            val destination = File(targetDir(includeInBackup), fileName)
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

    /** Locates a stored sketch in whichever directory currently holds it. */
    fun resolve(fileName: String): File? =
        File(backedUpDir, fileName).takeIf { it.exists() }
            ?: File(localOnlyDir, fileName).takeIf { it.exists() }

    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        resolve(fileName)?.delete()
        Unit
    }

    /** Moves every stored sketch into the directory the toggle now selects. */
    suspend fun applyBackupPreference(includeInBackup: Boolean) = withContext(Dispatchers.IO) {
        val from = if (includeInBackup) localOnlyDir else backedUpDir
        val to = targetDir(includeInBackup)
        from.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val destination = File(to, file.name)
            if (!file.renameTo(destination)) {
                // Different mount points would defeat rename; fall back to copy.
                try {
                    file.copyTo(destination, overwrite = true)
                    file.delete()
                } catch (e: IOException) {
                    Log.e(TAG, "Could not relocate ${file.name} for backup change", e)
                }
            }
        }
        Unit
    }

    suspend fun usage(): PhotoUsage = withContext(Dispatchers.IO) {
        val files = buildList {
            backedUpDir.listFiles()?.let { addAll(it) }
            localOnlyDir.listFiles()?.let { addAll(it) }
        }.filter { it.isFile && !it.name.endsWith(".tmp") }

        PhotoUsage(fileCount = files.size, totalBytes = files.sumOf { it.length() })
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        backedUpDir.deleteRecursively()
        localOnlyDir.deleteRecursively()
        Unit
    }

    /**
     * Decodes [source] at roughly [MAX_DIMENSION] on its longest edge.
     *
     * Two passes: read the bounds only, pick a power-of-two sample size, then
     * decode. That keeps a large photo from ever being fully expanded in memory.
     */
    private fun decodeDownscaled(source: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return null
        } catch (e: IOException) {
            Log.e(TAG, "Could not read scanned image bounds", e)
            return null
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.e(TAG, "Scanned image had no usable dimensions")
            return null
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = try {
            context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not decode scanned image", e)
            null
        } ?: return null

        return scaleToBound(decoded)
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

    companion object {
        /** Android Auto Backup's per-app allowance. */
        const val BACKUP_QUOTA_BYTES = 25L * 1024 * 1024

        private const val TAG = "PhotoStore"
        private const val DIR_NAME = "sketches"
        private const val MAX_DIMENSION = 1600
        private const val JPEG_QUALITY = 80
    }
}
