package com.shivam.sketchseed.domain.model

import kotlinx.serialization.Serializable

/** How demanding a prompt is. The pack ramps from EASY to HARD across the journey. */
@Serializable
enum class Difficulty {
    EASY,
    MEDIUM,
    HARD,
}
