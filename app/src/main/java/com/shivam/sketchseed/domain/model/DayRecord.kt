package com.shivam.sketchseed.domain.model

import com.shivam.sketchseed.domain.DrawingDay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.Serializable

/**
 * A finished day.
 *
 * The prompt text and difficulty are snapshotted rather than looked up from the
 * pack, so editing or replacing the pack later never rewrites what the user
 * actually drew.
 *
 * Dates and times are stored as plain numbers so the record serializes without a
 * parser and survives timezone changes without shifting.
 */
@Serializable
data class DayRecord(
    val day: Int,
    val promptText: String,
    val difficulty: Difficulty,
    /**
     * The drawing day this was finished on — see [DrawingDay]. Decided once, when
     * the record is written, and never re-derived from [completedAtEpochSecond].
     *
     * That matters: this field is the authority on which day a sketch counts for,
     * and recomputing it later would silently renumber history the first time the
     * day boundary moved.
     */
    val completedOnEpochDay: Long,
    /**
     * Wall-clock moment of completion, or null for records written before the app
     * kept the time. Additive detail only — nothing derives the drawing day from
     * it.
     *
     * Encoded as an epoch second at UTC, which here is a way of storing a *local*
     * datetime as one number rather than a real instant. Same reasoning as the
     * epoch day above: travel to another timezone and the recorded wall clock
     * should still read as the time on the clock you drew under.
     */
    val completedAtEpochSecond: Long? = null,
    val photoFileName: String? = null,
    /**
     * Bonus sketches drawn the same day. Never counted toward the hundred; see
     * [ExtraSketch].
     */
    val extras: List<ExtraSketch> = emptyList(),
) {
    val completedOn: LocalDate get() = LocalDate.ofEpochDay(completedOnEpochDay)

    /**
     * When this was finished.
     *
     * Records predating [completedAtEpochSecond] fall back to midnight of their
     * drawing day. That is an assumption, not a measurement — the time was never
     * recorded and cannot be recovered — so treat it as "unknown" rather than as
     * evidence about when the user actually drew.
     */
    val completedAt: LocalDateTime
        get() = completedAtEpochSecond?.let { LocalDateTime.ofEpochSecond(it, 0, ZoneOffset.UTC) }
            ?: completedOn.atStartOfDay()

    /** True when the time was actually recorded rather than assumed. */
    val hasRecordedTime: Boolean get() = completedAtEpochSecond != null

    companion object {
        fun of(
            prompt: Prompt,
            completedAt: LocalDateTime,
            photoFileName: String? = null,
        ): DayRecord = DayRecord(
            day = prompt.day,
            promptText = prompt.text,
            difficulty = prompt.difficulty,
            completedOnEpochDay = DrawingDay.of(completedAt).toEpochDay(),
            completedAtEpochSecond = completedAt.toEpochSecond(ZoneOffset.UTC),
            photoFileName = photoFileName,
        )
    }
}
