package com.shivam.sketchseed.ui.journey

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shivam.sketchseed.AppContainer
import com.shivam.sketchseed.SketchSeedApplication
import com.shivam.sketchseed.domain.model.DayRecord
import com.shivam.sketchseed.domain.model.JourneyProgress
import com.shivam.sketchseed.domain.model.Prompt
import com.shivam.sketchseed.domain.model.PromptPack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One square in the journey grid. */
sealed interface DayCell {
    val day: Int

    data class Done(override val day: Int, val record: DayRecord) : DayCell

    /** Today's prompt, revealed and waiting. */
    data class Current(override val day: Int, val prompt: Prompt) : DayCell

    /** A past day that went undrawn. Still open to back-filling. */
    data class Missed(override val day: Int, val prompt: Prompt) : DayCell

    /** Not yet reached. The prompt is deliberately not carried in this type. */
    data class Locked(override val day: Int) : DayCell
}

data class JourneyUiState(
    val loading: Boolean = true,
    val cells: List<DayCell> = emptyList(),
    val progress: JourneyProgress? = null,
)

class JourneyViewModel(container: AppContainer) : ViewModel() {

    private val pack = MutableStateFlow<PromptPack?>(null)
    private val today = MutableStateFlow(container.today())

    init {
        viewModelScope.launch {
            try {
                pack.value = container.promptRepository.pack()
            } catch (e: Exception) {
                Log.e(TAG, "Prompt pack could not be loaded", e)
            }
        }
    }

    val uiState: StateFlow<JourneyUiState> = combine(
        container.journeyRepository.records,
        pack,
        today,
    ) { records, loadedPack, now ->
        if (loadedPack == null) return@combine JourneyUiState()

        val progress = JourneyProgress.from(
            records = records,
            totalDays = loadedPack.totalDays,
            startDate = loadedPack.startDate,
            today = now,
        )
        val byDay = records.associateBy { it.day }

        val cells = (1..loadedPack.totalDays).map { day ->
            val record = byDay[day]
            val prompt = loadedPack.promptFor(day)

            when {
                record != null -> DayCell.Done(day, record)

                // Never hand a prompt to a cell the calendar has not reached.
                !progress.isRevealed(day) || prompt == null -> DayCell.Locked(day)

                day == progress.currentDay -> DayCell.Current(day, prompt)

                else -> DayCell.Missed(day, prompt)
            }
        }

        JourneyUiState(loading = false, cells = cells, progress = progress)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = JourneyUiState(),
    )

    companion object {
        private const val TAG = "JourneyViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as SketchSeedApplication
                JourneyViewModel(app.container)
            }
        }
    }
}
