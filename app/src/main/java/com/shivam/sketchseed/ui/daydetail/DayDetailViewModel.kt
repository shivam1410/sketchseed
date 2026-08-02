package com.shivam.sketchseed.ui.daydetail

import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shivam.sketchseed.AppContainer
import com.shivam.sketchseed.R
import com.shivam.sketchseed.SketchSeedApplication
import com.shivam.sketchseed.domain.model.DayRecord
import com.shivam.sketchseed.domain.model.JourneyProgress
import com.shivam.sketchseed.domain.model.Prompt
import com.shivam.sketchseed.domain.model.PromptPack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DayDetailUiState(
    val day: Int = 0,
    val loading: Boolean = true,
    val record: DayRecord? = null,
    /** Set once the calendar has reached this day; null while still sealed. */
    val prompt: Prompt? = null,
    /** Revealed, undrawn, and therefore still completable. */
    val canComplete: Boolean = false,
    /** True when this is the day the calendar is on right now. */
    val isToday: Boolean = false,
    val savingPhoto: Boolean = false,
    @param:StringRes val errorMessage: Int? = null,
) {
    val isLocked: Boolean get() = !loading && record == null && prompt == null

    /** A past day being drawn late rather than on its own day. */
    val isBackfill: Boolean get() = canComplete && !isToday
}

private data class Transient(
    val savingPhoto: Boolean,
    @param:StringRes val errorMessage: Int?,
)

class DayDetailViewModel(
    private val container: AppContainer,
    private val day: Int,
) : ViewModel() {

    private val pack = MutableStateFlow<PromptPack?>(null)
    private val today = MutableStateFlow(container.today())
    private val savingPhoto = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<Int?>(null)

    init {
        viewModelScope.launch {
            try {
                pack.value = container.promptRepository.pack()
            } catch (e: Exception) {
                Log.e(TAG, "Prompt pack could not be loaded", e)
            }
        }
    }

    private val transient = combine(savingPhoto, errorMessage, ::Transient)

    val uiState: StateFlow<DayDetailUiState> = combine(
        container.journeyRepository.records,
        pack,
        today,
        transient,
    ) { records, loadedPack, now, extras ->
        if (loadedPack == null) {
            return@combine DayDetailUiState(day = day, savingPhoto = extras.savingPhoto)
        }

        val progress = JourneyProgress.from(
            records = records,
            totalDays = loadedPack.totalDays,
            startDate = loadedPack.startDate,
            today = now,
        )
        val record = records.firstOrNull { it.day == day }

        DayDetailUiState(
            day = day,
            loading = false,
            record = record,
            prompt = loadedPack.promptFor(day).takeIf { progress.isRevealed(day) },
            canComplete = progress.canComplete(day),
            isToday = progress.currentDay == day,
            savingPhoto = extras.savingPhoto,
            errorMessage = extras.errorMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DayDetailUiState(day = day),
    )

    /**
     * Completes this day, possibly long after the fact.
     *
     * The completion date recorded is today, not the day it belonged to — which
     * is why back-filling cannot resurrect a broken streak.
     */
    fun markDone() {
        val state = uiState.value
        val prompt = state.prompt ?: return
        if (!state.canComplete) return

        viewModelScope.launch {
            container.journeyRepository.complete(prompt = prompt, completedOn = today.value)
        }
    }

    fun onSketchScanned(uri: Uri) {
        viewModelScope.launch {
            savingPhoto.value = true
            try {
                val state = uiState.value
                val backupSketches = container.settingsRepository.settings.first().backupSketches
                val fileName = container.photoStore.save(uri, day, backupSketches)

                if (fileName == null) {
                    errorMessage.value = R.string.photo_save_failed
                    // Still record the day if scanning was how they completed it.
                    if (state.canComplete) {
                        state.prompt?.let {
                            container.journeyRepository.complete(it, today.value)
                        }
                    }
                    return@launch
                }

                if (state.canComplete) {
                    state.prompt?.let {
                        container.journeyRepository.complete(
                            prompt = it,
                            completedOn = today.value,
                            photoFileName = fileName,
                        )
                    }
                } else {
                    state.record?.photoFileName?.let { container.photoStore.delete(it) }
                    container.journeyRepository.setPhoto(day, fileName)
                }
            } finally {
                savingPhoto.value = false
            }
        }
    }

    fun removePhoto() {
        viewModelScope.launch {
            uiState.value.record?.photoFileName?.let { container.photoStore.delete(it) }
            container.journeyRepository.setPhoto(day, null)
        }
    }

    fun dismissError() {
        errorMessage.value = null
    }

    companion object {
        private const val TAG = "DayDetailViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L
        const val ARG_DAY = "day"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as SketchSeedApplication
                val day = createSavedStateHandle().get<Int>(ARG_DAY) ?: 1
                DayDetailViewModel(app.container, day)
            }
        }
    }
}
