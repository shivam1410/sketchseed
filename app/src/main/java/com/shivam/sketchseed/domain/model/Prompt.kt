package com.shivam.sketchseed.domain.model

import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single day's drawing subject, as authored in the bundled pack.
 *
 * [text] stays short because it doubles as the image-search term.
 */
@Serializable
data class Prompt(
    val day: Int,
    val text: String,
    val difficulty: Difficulty,
)

/** The bundled 100-prompt pack. */
@Serializable
data class PromptPack(
    val packId: String,
    val title: String,
    /** ISO date the pack is anchored to. See [startDate]. */
    @SerialName("startDate") val startDateIso: String,
    val prompts: List<Prompt>,
) {
    val totalDays: Int get() = prompts.size

    /**
     * The calendar date day 1 falls on.
     *
     * Day numbers are derived from this rather than from personal progress, so
     * every install of this build shows the same prompt on the same date — two
     * people can compare sketches without any server or account between them.
     */
    val startDate: LocalDate get() = LocalDate.parse(startDateIso)

    /**
     * The prompt for [day], or null when [day] falls outside the pack.
     *
     * Callers must not use this to look ahead. Whether a day may be shown is
     * [JourneyProgress.isRevealed]'s decision, not this function's.
     */
    fun promptFor(day: Int): Prompt? = prompts.getOrNull(day - 1)
}
