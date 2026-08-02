package com.shivam.sketchseed.backup

import com.shivam.sketchseed.domain.model.DayRecord
import com.shivam.sketchseed.domain.model.Difficulty
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val archive = BackupArchive()

    private fun manifest(photoCount: Int = 0, recordCount: Int = 0, schema: Int = 1) =
        BackupManifest(
            schemaVersion = schema,
            createdAtEpochSecond = 1_785_000_000L,
            appVersion = "1.0",
            packId = "core-100",
            startDateIso = "2026-08-02",
            recordCount = recordCount,
            photoCount = photoCount,
        )

    private fun record(day: Int, photo: String? = null) = DayRecord(
        day = day,
        promptText = "Prompt $day",
        difficulty = Difficulty.EASY,
        completedOnEpochDay = 20_667L + day,
        photoFileName = photo,
        tip = if (day == 1) "Start with the silhouette." else null,
    )

    private fun photoFile(name: String, content: String): File =
        temp.newFile(name).apply { writeText(content) }

    private fun writeArchive(
        records: List<DayRecord>,
        photos: List<File> = emptyList(),
    ): ByteArray = ByteArrayOutputStream().also { out ->
        archive.write(out, manifest(photos.size, records.size), records, photos)
    }.toByteArray()

    private fun readArchive(bytes: ByteArray): BackupContents =
        archive.read(ByteArrayInputStream(bytes), temp.newFolder())

    // ── Round trip ───────────────────────────────────────────────────────────

    @Test
    fun `an archive survives a write and read round trip`() {
        val records = listOf(record(1, "day-001.jpg"), record(2))
        val photo = photoFile("day-001.jpg", "pretend-jpeg-bytes")

        val restored = readArchive(writeArchive(records, listOf(photo)))

        assertEquals(records, restored.records)
        assertEquals("core-100", restored.manifest.packId)
        assertEquals("2026-08-02", restored.manifest.startDateIso)
        assertEquals(1, restored.photos.size)
        assertEquals("pretend-jpeg-bytes", restored.photos.getValue("day-001.jpg").readText())
    }

    @Test
    fun `an archive with no photos is still valid`() {
        val restored = readArchive(writeArchive(listOf(record(1))))

        assertEquals(1, restored.records.size)
        assertTrue(restored.photos.isEmpty())
    }

    @Test
    fun `an empty journey round trips`() {
        val restored = readArchive(writeArchive(emptyList()))

        assertTrue(restored.records.isEmpty())
    }

    @Test
    fun `tips and photo names survive the round trip`() {
        val restored = readArchive(writeArchive(listOf(record(1, "day-001.jpg"))))

        assertEquals("Start with the silhouette.", restored.records.first().tip)
        assertEquals("day-001.jpg", restored.records.first().photoFileName)
    }

    @Test
    fun `a missing photo file is skipped rather than failing the whole backup`() {
        val present = photoFile("day-001.jpg", "here")
        val absent = File(temp.root, "day-002.jpg")

        val restored = readArchive(writeArchive(listOf(record(1)), listOf(present, absent)))

        assertEquals(setOf("day-001.jpg"), restored.photos.keys)
    }

    // ── Rejecting malformed input ────────────────────────────────────────────

    @Test
    fun `something that is not a zip is rejected`() {
        val error = assertThrows(BackupFormatException::class.java) {
            readArchive("this is just text, not an archive".toByteArray())
        }
        assertTrue(error.message!!.isNotBlank())
    }

    @Test
    fun `an archive with no manifest is rejected`() {
        val bytes = zipOf("journey.json" to "[]")

        assertThrows(BackupFormatException::class.java) { readArchive(bytes) }
    }

    @Test
    fun `an archive with no journey is rejected`() {
        val bytes = zipOf("manifest.json" to validManifestJson())

        assertThrows(BackupFormatException::class.java) { readArchive(bytes) }
    }

    @Test
    fun `a damaged journey is rejected`() {
        val bytes = zipOf(
            "manifest.json" to validManifestJson(),
            "journey.json" to "{ not valid json",
        )

        assertThrows(BackupFormatException::class.java) { readArchive(bytes) }
    }

    @Test
    fun `an archive from a newer app version is refused rather than misread`() {
        val bytes = zipOf(
            "manifest.json" to validManifestJson(schemaVersion = 99),
            "journey.json" to "[]",
        )

        val error = assertThrows(BackupFormatException::class.java) { readArchive(bytes) }
        assertTrue(
            "message should tell the user to update: ${error.message}",
            error.message!!.contains("newer", ignoreCase = true),
        )
    }

    // ── Hostile input ────────────────────────────────────────────────────────

    @Test
    fun `an entry escaping the sketches directory is rejected`() {
        val bytes = zipOf(
            "manifest.json" to validManifestJson(),
            "journey.json" to "[]",
            "sketches/../../../../tmp/evil.jpg" to "payload",
        )

        assertThrows(BackupFormatException::class.java) { readArchive(bytes) }
    }

    @Test
    fun `a nested path inside sketches is rejected`() {
        val bytes = zipOf(
            "manifest.json" to validManifestJson(),
            "journey.json" to "[]",
            "sketches/nested/deep.jpg" to "payload",
        )

        assertThrows(BackupFormatException::class.java) { readArchive(bytes) }
    }

    @Test
    fun `a non image entry in sketches is rejected`() {
        val bytes = zipOf(
            "manifest.json" to validManifestJson(),
            "journey.json" to "[]",
            "sketches/payload.sh" to "rm -rf /",
        )

        assertThrows(BackupFormatException::class.java) { readArchive(bytes) }
    }

    @Test
    fun `traversal cannot escape by writing outside the destination`() {
        val destination = temp.newFolder()
        val bytes = zipOf(
            "manifest.json" to validManifestJson(),
            "journey.json" to "[]",
            "sketches/../escaped.jpg" to "payload",
        )

        assertThrows(BackupFormatException::class.java) {
            archive.read(ByteArrayInputStream(bytes), destination)
        }
        assertFalse(File(destination.parentFile, "escaped.jpg").exists())
    }

    @Test
    fun `unknown entries are ignored so newer archives stay readable`() {
        val bytes = zipOf(
            "manifest.json" to validManifestJson(),
            "journey.json" to "[]",
            "future/whatever.dat" to "added by a later version",
        )

        val restored = readArchive(bytes)

        assertTrue(restored.records.isEmpty())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun validManifestJson(schemaVersion: Int = 1): String = """
        {"schemaVersion":$schemaVersion,"createdAtEpochSecond":1785000000,
         "appVersion":"1.0","packId":"core-100","startDateIso":"2026-08-02",
         "recordCount":0,"photoCount":0}
    """.trimIndent()

    /** Builds a zip by hand so tests can craft entries the writer never would. */
    private fun zipOf(vararg entries: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
}
