package com.shivam.sketchseed.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shivam.sketchseed.AppContainer
import com.shivam.sketchseed.SketchSeedApplication
import com.shivam.sketchseed.ai.TipAvailability
import com.shivam.sketchseed.data.PhotoUsage
import com.shivam.sketchseed.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: Settings = Settings(),
    val usage: PhotoUsage = PhotoUsage(),
    /** Null while still being probed. */
    val aiAvailability: TipAvailability? = null,
    val completedCount: Int = 0,
    val busy: Boolean = false,
) {
    /** Auto Backup silently drops app data past its quota, so warn before that. */
    val nearBackupQuota: Boolean
        get() = settings.backupSketches && usage.totalBytes > QUOTA_WARNING_BYTES

    private companion object {
        const val QUOTA_WARNING_BYTES = 20L * 1024 * 1024
    }
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val usage = MutableStateFlow(PhotoUsage())
    private val aiAvailability = MutableStateFlow<TipAvailability?>(null)
    private val busy = MutableStateFlow(false)

    init {
        refreshUsage()
        viewModelScope.launch {
            aiAvailability.value = container.tipGenerator.availability()
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        container.settingsRepository.settings,
        container.journeyRepository.records.map { it.size },
        usage,
        aiAvailability,
        busy,
    ) { settings, completed, photoUsage, availability, isBusy ->
        SettingsUiState(
            settings = settings,
            usage = photoUsage,
            aiAvailability = availability,
            completedCount = completed,
            busy = isBusy,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = SettingsUiState(),
    )

    /**
     * Auto Backup rules are static XML, so the toggle physically relocates the
     * photos between the backed-up and no-backup directories.
     */
    fun setBackupSketches(enabled: Boolean) {
        viewModelScope.launch {
            busy.value = true
            try {
                container.settingsRepository.setBackupSketches(enabled)
                container.photoStore.applyBackupPreference(enabled)
                usage.value = container.photoStore.usage()
            } finally {
                busy.value = false
            }
        }
    }

    fun setAiTipsEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setAiTipsEnabled(enabled) }
    }

    fun resetJourney() {
        viewModelScope.launch {
            busy.value = true
            try {
                container.journeyRepository.reset()
                container.photoStore.clear()
                usage.value = container.photoStore.usage()
            } finally {
                busy.value = false
            }
        }
    }

    fun refreshUsage() {
        viewModelScope.launch { usage.value = container.photoStore.usage() }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as SketchSeedApplication
                SettingsViewModel(app.container)
            }
        }
    }
}
