package com.shivam.sketchseed.ui.today

import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shivam.sketchseed.AppContainer
import com.shivam.sketchseed.R
import com.shivam.sketchseed.SketchSeedApplication
import com.shivam.sketchseed.ai.TipResult
import com.shivam.sketchseed.data.Settings
import com.shivam.sketchseed.domain.model.DayRecord
import com.shivam.sketchseed.domain.model.JourneyProgress
import com.shivam.sketchseed.domain.model.Prompt
import com.shivam.sketchseed.domain.model.PromptPack
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the user should be looking at today. */
sealed interface TodayMode {
    data object Loading : TodayMode

    /** There is a prompt waiting to be drawn. */
    data class Draw(val prompt: Prompt) : TodayMode

    /** Today's sketch is done; the next prompt stays sealed until tomorrow. */
    data class Rest(val finished: DayRecord, val nextDay: Int) : TodayMode

    /** All hundred are done. */
    data object Finished : TodayMode
}

sealed interface TipState {
    /** Tips are switched off, or there is nothing to draw. */
    data object Hidden : TipState
    data object Idle : TipState
    data object Working : TipState
    data class Ready(val text: String) : TipState
    data class Error(@param:StringRes val messageId: Int) : TipState
}

data class TodayUiState(
    val mode: TodayMode = TodayMode.Loading,
    val progress: JourneyProgress? = null,
    val tip: TipState = TipState.Hidden,
    val savingPhoto: Boolean = false,
)

private data class Snapshot(
    val records: List<DayRecord>,
    val settings: Settings,
    val pack: PromptPack?,
    val today: LocalDate,
    val tip: TipState,
)

class TodayViewModel(private val container: AppContainer) : ViewModel() {

    private val pack = MutableStateFlow<PromptPack?>(null)
    private val today = MutableStateFlow(container.today())
    private val tip = MutableStateFlow<TipState>(TipState.Idle)
    private val savingPhoto = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            try {
                pack.value = container.promptRepository.pack()
            } catch (e: Exception) {
                // Only thrown when the bundled asset is broken, which is a
                // packaging fault rather than something the user can act on.
                Log.e(TAG, "Prompt pack could not be loaded", e)
            }
        }
    }

    val uiState: StateFlow<TodayUiState> = combine(
        container.journeyRepository.records,
        container.settingsRepository.settings,
        pack,
        today,
        tip,
        ::Snapshot,
    ).combine(savingPhoto) { snapshot, saving ->
        snapshot.toUiState(saving)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = TodayUiState(),
    )

    private fun Snapshot.toUiState(saving: Boolean): TodayUiState {
        val loadedPack = pack ?: return TodayUiState(savingPhoto = saving)
        val progress = JourneyProgress.from(records, loadedPack.totalDays, today)

        val mode = when {
            progress.isComplete -> TodayMode.Finished

            progress.canDrawToday ->
                loadedPack.promptFor(progress.currentDay)
                    ?.let { TodayMode.Draw(it) }
                    ?: TodayMode.Finished

            else -> progress.records
                .lastOrNull { it.completedOn == today }
                ?.let { TodayMode.Rest(finished = it, nextDay = progress.currentDay) }
                ?: TodayMode.Finished
        }

        return TodayUiState(
            mode = mode,
            progress = progress,
            // A tip only makes sense while there is something left to draw.
            tip = if (mode is TodayMode.Draw && settings.aiTipsEnabled) tip else TipState.Hidden,
            savingPhoto = saving,
        )
    }

    /**
     * Re-reads the date.
     *
     * Called on resume so an app left open past midnight rolls over instead of
     * showing yesterday's state.
     */
    fun refreshDate() {
        val now = container.today()
        if (now != today.value) {
            today.value = now
            tip.value = TipState.Idle
        }
    }

    fun markDone() {
        val prompt = currentPrompt() ?: return
        viewModelScope.launch {
            container.journeyRepository.complete(
                prompt = prompt,
                completedOn = today.value,
                tip = (tip.value as? TipState.Ready)?.text,
            )
            tip.value = TipState.Idle
        }
    }

    /**
     * Stores a scanned sketch.
     *
     * If today's prompt is not marked done yet, scanning finishes it — turning up
     * with the drawing is the completion.
     */
    fun onSketchScanned(uri: Uri) {
        viewModelScope.launch {
            savingPhoto.value = true
            try {
                val backupSketches = container.settingsRepository.settings.first().backupSketches

                when (val mode = uiState.value.mode) {
                    is TodayMode.Draw -> {
                        val fileName = container.photoStore
                            .save(uri, mode.prompt.day, backupSketches)
                        container.journeyRepository.complete(
                            prompt = mode.prompt,
                            completedOn = today.value,
                            photoFileName = fileName,
                            tip = (tip.value as? TipState.Ready)?.text,
                        )
                        tip.value = TipState.Idle
                    }

                    is TodayMode.Rest -> {
                        val day = mode.finished.day
                        val fileName = container.photoStore.save(uri, day, backupSketches)
                        if (fileName != null) {
                            // Replace, so an old photo does not linger on disk.
                            mode.finished.photoFileName
                                ?.let { container.photoStore.delete(it) }
                            container.journeyRepository.setPhoto(day, fileName)
                        }
                    }

                    else -> Unit
                }
            } finally {
                savingPhoto.value = false
            }
        }
    }

    fun requestTip() {
        val prompt = currentPrompt() ?: return
        if (tip.value is TipState.Working) return

        viewModelScope.launch {
            tip.value = TipState.Working
            val result = container.tipGenerator.generate(prompt.text, prompt.difficulty)
            tip.value = when (result) {
                is TipResult.Success -> TipState.Ready(result.tip)
                TipResult.Unsupported -> TipState.Error(R.string.tip_unavailable)
                is TipResult.Failed -> {
                    Log.w(TAG, "Tip generation failed", result.cause)
                    TipState.Error(R.string.tip_failed)
                }
            }
        }
    }

    fun dismissTip() {
        tip.value = TipState.Idle
    }

    private fun currentPrompt(): Prompt? = (uiState.value.mode as? TodayMode.Draw)?.prompt

    companion object {
        private const val TAG = "TodayViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as SketchSeedApplication
                TodayViewModel(app.container)
            }
        }
    }
}
