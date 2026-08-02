package com.shivam.sketchseed.domain.model

import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * A finished day.
 *
 * The prompt text and difficulty are snapshotted rather than looked up from the
 * pack, so editing or replacing the pack later never rewrites what the user
 * actually drew.
 *
 * The date is stored as an epoch day so the record serializes as a plain number
 * and survives timezone changes without shifting.
 */
@Serializable
data class DayRecord(
    val day: Int,
    val promptText: String,
    val difficulty: Difficulty,
    val completedOnEpochDay: Long,
    val photoFileName: String? = null,
    val tip: String? = null,
    /** The focus line shown on the day, snapshotted alongside the prompt. */
    val focus: String? = null,
) {
    val completedOn: LocalDate get() = LocalDate.ofEpochDay(completedOnEpochDay)

    companion object {
        fun of(
            prompt: Prompt,
            completedOn: LocalDate,
            photoFileName: String? = null,
            tip: String? = null,
        ): DayRecord = DayRecord(
            day = prompt.day,
            promptText = prompt.text,
            difficulty = prompt.difficulty,
            completedOnEpochDay = completedOn.toEpochDay(),
            photoFileName = photoFileName,
            tip = tip,
            focus = prompt.focus.takeIf { it.isNotBlank() },
        )
    }
}
