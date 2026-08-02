package com.shivam.sketchseed.domain.model

import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * A sketch drawn on top of the day's prompt.
 *
 * Some prompts take a minute. Rather than let that spare appetite go nowhere —
 * or worse, tempt someone into burning tomorrow's prompt early — a finished day
 * can hold as many of these as the user likes.
 *
 * Extras are deliberately inert: they never count toward the hundred, never move
 * the streak, and never unlock the next day. The journey stays exactly one
 * prompt per calendar day; this is just somewhere to put the rest.
 *
 * [id] is generated rather than derived from the title so two sketches can share
 * a name, and so renaming never breaks the link to the photo.
 */
@Serializable
data class ExtraSketch(
    val id: String,
    val title: String = "",
    val photoFileName: String? = null,
    val createdOnEpochDay: Long,
) {
    val createdOn: LocalDate get() = LocalDate.ofEpochDay(createdOnEpochDay)

    /** An extra with neither a name nor a picture would be a blank row. */
    val isEmpty: Boolean get() = title.isBlank() && photoFileName == null
}
