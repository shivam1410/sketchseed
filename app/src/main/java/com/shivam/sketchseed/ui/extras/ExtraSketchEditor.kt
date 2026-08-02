package com.shivam.sketchseed.ui.extras

import android.net.Uri
import com.shivam.sketchseed.AppContainer
import com.shivam.sketchseed.R
import com.shivam.sketchseed.domain.model.ExtraSketch
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The "another sketch" composer, while it is open. */
data class ExtraDraft(
    val title: String = "",
    val photoFileName: String? = null,
    val saving: Boolean = false,
) {
    /** A nameless, pictureless extra would just be a blank row. */
    val canSave: Boolean get() = !saving && (title.isNotBlank() || photoFileName != null)
}

/**
 * Adding and removing bonus sketches.
 *
 * Shared rather than duplicated: Today and day detail offer the same thing, and
 * two copies of "save the photo, then commit, and clean up the orphan if the
 * user backs out" is two places for that cleanup to rot.
 *
 * [day] is a supplier because Today's day number moves with the calendar while
 * day detail's is fixed.
 */
class ExtraSketchEditor(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val day: () -> Int?,
    private val today: () -> LocalDate,
    /** Receives a string resource id describing what went wrong. */
    private val onError: (Int) -> Unit,
) {
    private val _draft = MutableStateFlow<ExtraDraft?>(null)
    val draft: StateFlow<ExtraDraft?> = _draft.asStateFlow()

    fun start() {
        _draft.value = ExtraDraft()
    }

    fun cancel() {
        // Drop any photo already taken, so backing out cannot strand a file.
        val orphan = _draft.value?.photoFileName
        _draft.value = null
        if (orphan != null) scope.launch { container.photoStore.delete(orphan) }
    }

    fun setTitle(title: String) {
        _draft.update { it?.copy(title = title) }
    }

    /** Saves the photo immediately so the composer can show a thumbnail. */
    fun onPhotoCaptured(uri: Uri) {
        val target = day() ?: return
        scope.launch {
            _draft.update { it?.copy(saving = true) }
            val previous = _draft.value?.photoFileName
            val fileName = container.photoStore.save(uri, target)

            if (fileName == null) {
                onError(R.string.photo_save_failed)
                _draft.update { it?.copy(saving = false) }
                return@launch
            }

            // Replacing the photo mid-draft must not strand the old one.
            if (previous != null) container.photoStore.delete(previous)
            _draft.update { it?.copy(photoFileName = fileName, saving = false) }
        }
    }

    fun save() {
        val draft = _draft.value ?: return
        val target = day() ?: return
        if (!draft.canSave) return

        scope.launch {
            container.journeyRepository.addExtra(
                day = target,
                extra = ExtraSketch(
                    id = UUID.randomUUID().toString(),
                    title = draft.title.trim(),
                    photoFileName = draft.photoFileName,
                    createdOnEpochDay = today().toEpochDay(),
                ),
            )
            _draft.value = null
        }
    }

    fun remove(extra: ExtraSketch) {
        val target = day() ?: return
        scope.launch {
            extra.photoFileName?.let { container.photoStore.delete(it) }
            container.journeyRepository.removeExtra(target, extra.id)
        }
    }
}
