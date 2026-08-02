package com.shivam.sketchseed.domain.model

import kotlinx.serialization.Serializable

/** A single day's drawing subject, as authored in the bundled pack. */
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
    val prompts: List<Prompt>,
) {
    val totalDays: Int get() = prompts.size

    /**
     * The prompt for [day], or null when [day] falls outside the pack.
     *
     * Callers must not use this to look ahead: the UI only ever asks for days
     * that are already unlocked. Keeping future prompts unseen is the point.
     */
    fun promptFor(day: Int): Prompt? = prompts.getOrNull(day - 1)
}
