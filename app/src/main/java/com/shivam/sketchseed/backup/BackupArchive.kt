package com.shivam.sketchseed.backup

import com.shivam.sketchseed.data.appJson
import com.shivam.sketchseed.domain.model.DayRecord
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Reads and writes the backup archive.
 *
 * Layout:
 * ```
 * manifest.json      what this archive is
 * journey.json       the day records
 * sketches/<name>    one JPEG per photographed day
 * ```
 *
 * A plain zip rather than a bespoke format, so a backup is something you can
 * open on a laptop and pick apart by hand if the app ever lets you down.
 *
 * Reading treats the archive as **untrusted input**. It arrives from cloud
 * storage where it could have been altered or corrupted, so entry names are
 * validated against path traversal and the reader refuses to expand more than
 * [MAX_TOTAL_BYTES] or [MAX_ENTRIES]. Without those guards a malicious archive
 * could write outside the sketches directory or exhaust the disk.
 */
class BackupArchive(private val json: Json = appJson) {

    private val recordsSerializer = ListSerializer(DayRecord.serializer())

    fun write(
        destination: OutputStream,
        manifest: BackupManifest,
        records: List<DayRecord>,
        photos: Collection<File>,
    ) {
        ZipOutputStream(destination.buffered()).use { zip ->
            zip.putEntry(MANIFEST, json.encodeToString(BackupManifest.serializer(), manifest))
            zip.putEntry(JOURNEY, json.encodeToString(recordsSerializer, records))

            photos.forEach { photo ->
                if (!photo.isFile) return@forEach
                zip.putNextEntry(ZipEntry("$SKETCH_DIR${photo.name}"))
                photo.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads an archive, extracting sketches into [photoDestination].
     *
     * @throws BackupFormatException if the archive is not a SketchSeed backup,
     *   was written by a newer app, or looks hostile.
     */
    fun read(source: InputStream, photoDestination: File): BackupContents {
        photoDestination.mkdirs()
        val safeRoot = photoDestination.canonicalFile

        var manifestJson: String? = null
        var journeyJson: String? = null
        val photos = mutableMapOf<String, File>()
        var totalBytes = 0L
        var entryCount = 0

        try {
            ZipInputStream(source.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entryCount > MAX_ENTRIES) {
                        throw BackupFormatException("This backup has too many files in it.")
                    }
                    if (entry.isDirectory) continue

                    when {
                        entry.name == MANIFEST ->
                            manifestJson = zip.readBoundedText { totalBytes += it }

                        entry.name == JOURNEY ->
                            journeyJson = zip.readBoundedText { totalBytes += it }

                        entry.name.startsWith(SKETCH_DIR) -> {
                            val fileName = entry.name.removePrefix(SKETCH_DIR)
                            if (!fileName.isSafeFileName()) {
                                throw BackupFormatException(
                                    "This backup contains an unexpected file path.",
                                )
                            }
                            val target = File(safeRoot, fileName)
                            // Belt and braces: even a name that passed the
                            // pattern check must resolve inside the directory.
                            if (target.canonicalFile.parentFile != safeRoot) {
                                throw BackupFormatException(
                                    "This backup tried to write outside its folder.",
                                )
                            }
                            target.outputStream().use { out ->
                                totalBytes += zip.copyBounded(out, MAX_TOTAL_BYTES - totalBytes)
                            }
                            photos[fileName] = target
                        }

                        // Unknown entries are ignored so a newer app can add
                        // files without breaking this reader.
                        else -> Unit
                    }

                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw BackupFormatException("This backup is unexpectedly large.")
                    }
                }
            }
        } catch (e: IOException) {
            throw BackupFormatException("This file could not be read as a backup.", e)
        }

        val manifest = manifestJson
            ?: throw BackupFormatException("This file is not a SketchSeed backup.")
        val journey = journeyJson
            ?: throw BackupFormatException("This backup has no journey data in it.")

        return try {
            val parsedManifest = json.decodeFromString(BackupManifest.serializer(), manifest)
            if (parsedManifest.schemaVersion > BackupManifest.CURRENT_SCHEMA_VERSION) {
                throw BackupFormatException(
                    "This backup was made by a newer version of SketchSeed. " +
                        "Update the app and try again.",
                )
            }
            BackupContents(
                manifest = parsedManifest,
                records = json.decodeFromString(recordsSerializer, journey),
                photos = photos,
            )
        } catch (e: SerializationException) {
            throw BackupFormatException("This backup is damaged and cannot be read.", e)
        }
    }

    private fun ZipOutputStream.putEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }

    private fun InputStream.readBoundedText(onBytes: (Long) -> Unit): String {
        val bytes = readBounded(MAX_TEXT_BYTES)
        onBytes(bytes.size.toLong())
        return bytes.decodeToString()
    }

    private fun InputStream.readBounded(limit: Long): ByteArray {
        val buffer = ByteArray(BUFFER)
        val out = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) {
                throw BackupFormatException("This backup is unexpectedly large.")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun InputStream.copyBounded(out: OutputStream, limit: Long): Long {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) {
                throw BackupFormatException("This backup is unexpectedly large.")
            }
            out.write(buffer, 0, read)
        }
        return total
    }

    /** Plain file names only: no separators, no traversal, no hidden files. */
    private fun String.isSafeFileName(): Boolean =
        isNotBlank() &&
            length <= MAX_NAME_LENGTH &&
            SAFE_NAME.matches(this)

    private companion object {
        const val MANIFEST = "manifest.json"
        const val JOURNEY = "journey.json"
        const val SKETCH_DIR = "sketches/"

        const val BUFFER = 16 * 1024
        const val MAX_ENTRIES = 1_000
        const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
        const val MAX_TEXT_BYTES = 8L * 1024 * 1024
        const val MAX_NAME_LENGTH = 128

        val SAFE_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*\.(?i:jpg|jpeg|png|webp)""")
    }
}
