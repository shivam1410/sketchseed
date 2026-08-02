package com.shivam.sketchseed.ui.daydetail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shivam.sketchseed.AppContainer
import com.shivam.sketchseed.SketchSeedApplication
import com.shivam.sketchseed.domain.model.DayRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DayDetailUiState(
    val day: Int = 0,
    val loading: Boolean = true,
    val record: DayRecord? = null,
    val savingPhoto: Boolean = false,
)

class DayDetailViewModel(
    private val container: AppContainer,
    private val day: Int,
) : ViewModel() {

    private val savingPhoto = MutableStateFlow(false)

    val uiState: StateFlow<DayDetailUiState> = combine(
        container.journeyRepository.records.map { records -> records.firstOrNull { it.day == day } },
        savingPhoto,
    ) { record, saving ->
        DayDetailUiState(
            day = day,
            loading = false,
            record = record,
            savingPhoto = saving,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DayDetailUiState(day = day),
    )

    fun onSketchScanned(uri: Uri) {
        viewModelScope.launch {
            savingPhoto.value = true
            try {
                val backupSketches = container.settingsRepository.settings.first().backupSketches
                val fileName = container.photoStore.save(uri, day, backupSketches) ?: return@launch

                uiState.value.record?.photoFileName
                    ?.let { container.photoStore.delete(it) }
                container.journeyRepository.setPhoto(day, fileName)
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

    companion object {
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
