package com.shivam.sketchseed.domain

import com.shivam.sketchseed.domain.model.DayRecord
import com.shivam.sketchseed.domain.model.Difficulty
import com.shivam.sketchseed.domain.model.Prompt
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a finished day remembers.
 *
 * The interesting part is the completion time, added after the app had already
 * written records without one. Those older records cannot be recovered, so the
 * question these tests answer is what the app claims about them.
 */
class DayRecordTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val prompt = Prompt(day = 3, text = "Leaf", difficulty = Difficulty.EASY)

    @Test
    fun `a record drawn after midnight belongs to the previous drawing day`() {
        // The reported bug. Drawing at 00:14 on the 5th is finishing the 4th.
        val record = DayRecord.of(prompt, LocalDateTime.parse("2026-08-05T00:14:00"))

        assertEquals(LocalDate.parse("2026-08-04"), record.completedOn)
    }

    @Test
    fun `a record keeps the wall clock time it was actually drawn at`() {
        val at = LocalDateTime.parse("2026-08-05T00:14:00")
        val record = DayRecord.of(prompt, at)

        assertEquals(at, record.completedAt)
        assertTrue(record.hasRecordedTime)
    }

    @Test
    fun `a daytime record belongs to its own date`() {
        val record = DayRecord.of(prompt, LocalDateTime.parse("2026-08-05T14:30:00"))

        assertEquals(LocalDate.parse("2026-08-05"), record.completedOn)
    }

    @Test
    fun `the drawing day is stored, not recomputed from the timestamp`() {
        // This is what protects history. If the day were re-derived on read, moving
        // the boundary would silently renumber every record ever written.
        val record = DayRecord.of(prompt, LocalDateTime.parse("2026-08-05T00:14:00"))
            .copy(completedAtEpochSecond = null)

        assertEquals(LocalDate.parse("2026-08-04"), record.completedOn)
    }

    @Test
    fun `a record written before times were kept reads as midnight`() {
        // Decodes JSON with no completedAtEpochSecond at all — exactly what is
        // already on disk for days finished before this field existed.
        val old = """
            {"day":1,"promptText":"Apple","difficulty":"EASY","completedOnEpochDay":20668}
        """.trimIndent()

        val record = json.decodeFromString(DayRecord.serializer(), old)

        assertFalse("the time was never recorded", record.hasRecordedTime)
        assertEquals(record.completedOn.atStartOfDay(), record.completedAt)
        assertEquals(0, record.completedAt.hour)
        assertEquals(0, record.completedAt.minute)
    }

    @Test
    fun `an old record keeps the date it already had`() {
        // Midnight is on the wrong side of the 6am boundary, so if anything did
        // re-derive the day from that fallback the date would silently shift back
        // one. It must not.
        val day = LocalDate.parse("2026-08-05")
        val old = """
            {"day":1,"promptText":"Apple","difficulty":"EASY","completedOnEpochDay":${day.toEpochDay()}}
        """.trimIndent()

        val record = json.decodeFromString(DayRecord.serializer(), old)

        assertEquals(day, record.completedOn)
    }

    @Test
    fun `a record survives a round trip through json`() {
        val record = DayRecord.of(prompt, LocalDateTime.parse("2026-08-05T23:41:07"))

        val restored = json.decodeFromString(
            DayRecord.serializer(),
            json.encodeToString(DayRecord.serializer(), record),
        )

        assertEquals(record, restored)
        assertEquals(record.completedAt, restored.completedAt)
    }

    @Test
    fun `the stored time is a local clock reading, not an instant`() {
        // Encoded at UTC deliberately, as a way of storing a local datetime in one
        // number. Travel to another timezone and the recorded time should still
        // read as the time on the clock you drew under.
        val at = LocalDateTime.parse("2026-08-05T23:41:00")
        val record = DayRecord.of(prompt, at)

        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(at, record.completedAt)
            assertEquals(LocalDate.parse("2026-08-05"), record.completedOn)
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }
}
