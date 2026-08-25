package com.shivam.sketchseed.ui.settings

import android.content.Intent
import android.util.Log
import com.shivam.sketchseed.AppContainer
import com.shivam.sketchseed.update.DownloadOutcome
import com.shivam.sketchseed.update.GitHubRelease
import com.shivam.sketchseed.update.UpdateCheck
import com.shivam.sketchseed.update.UpdateSchedule
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where the update row has got to. */
sealed interface UpdateState {
    /** Nothing asked yet, and nothing to say. */
    data object Idle : UpdateState

    data object Checking : UpdateState
    data object UpToDate : UpdateState

    /** The check did not complete, which is not the same as finding nothing. */
    data object CheckFailed : UpdateState

    data class Available(val release: GitHubRelease) : UpdateState
    data class Downloading(
        val release: GitHubRelease,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateState

    data class ReadyToInstall(val release: GitHubRelease, val apk: File) : UpdateState

    /**
     * @param corrupt true when bytes arrived but did not match the published
     *   digest, which is worth saying differently from a transfer that stopped.
     */
    data class DownloadFailed(val release: GitHubRelease, val corrupt: Boolean) : UpdateState

    /** Android will not open the installer until the user allows it, in settings. */
    data class NeedsInstallPermission(val release: GitHubRelease, val apk: File) : UpdateState
}

/**
 * Finding, fetching and handing over a new release.
 *
 * Kept out of [SettingsViewModel] for the same reason `ExtraSketchEditor` is:
 * this is a small state machine with its own vocabulary, and folding it into a
 * screen that already juggles Drive, files and reminders would bury both.
 *
 * The check runs by itself when Settings opens, at most twice a day. Everything
 * after that is a deliberate tap — the app never downloads an APK, and certainly
 * never installs one, because a timer said so.
 */
class UpdateFlow(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Where the user is sent to allow installs. Only meaningful while asking. */
    val installPermissionIntent: Intent
        get() = container.updateInstaller.permissionSettingsIntent()

    /**
     * The automatic half.
     *
     * Skipped entirely once something has been found or is in flight: re-opening
     * Settings should not throw away a download the user is halfway through.
     */
    fun checkOnOpen() {
        if (_state.value != UpdateState.Idle && _state.value != UpdateState.UpToDate) return

        scope.launch {
            val settings = container.settingsRepository.settings.first()
            val due = UpdateSchedule.isDue(
                lastCheckEpochSecond = settings.lastUpdateCheckEpochSecond,
                nowEpochSecond = container.nowEpochSecond(),
            )
            if (due) check()
        }
    }

    /** The manual half. Always asks, whatever the schedule thinks. */
    fun checkNow() {
        if (_state.value is UpdateState.Checking) return
        scope.launch { check() }
    }

    private suspend fun check() {
        _state.value = UpdateState.Checking

        _state.value = when (val outcome = container.updateChecker.check()) {
            is UpdateCheck.Available -> UpdateState.Available(outcome.release)
            UpdateCheck.UpToDate -> UpdateState.UpToDate
            UpdateCheck.Failed -> UpdateState.CheckFailed
        }

        // Recorded even on failure. The interval exists to stop the app hammering
        // an endpoint that is refusing it, and a refusal is exactly when a retry
        // loop would do the most damage.
        container.settingsRepository.setLastUpdateCheck(container.nowEpochSecond())
    }

    fun download() {
        val release = _state.value.releaseOrNull() ?: return
        if (_state.value is UpdateState.Downloading) return

        scope.launch {
            _state.value = UpdateState.Downloading(release, 0, release.apkSizeBytes)

            val outcome = container.updateInstaller.download(release) { downloaded, total ->
                _state.value = UpdateState.Downloading(release, downloaded, total)
            }

            _state.value = when (outcome) {
                is DownloadOutcome.Ready -> UpdateState.ReadyToInstall(release, outcome.apk)
                DownloadOutcome.Corrupt -> UpdateState.DownloadFailed(release, corrupt = true)
                DownloadOutcome.Failed -> UpdateState.DownloadFailed(release, corrupt = false)
            }
        }
    }

    /**
     * Opens the system installer.
     *
     * The permission is checked here rather than up front because it can be
     * granted and revoked while the app is open, and because asking for it
     * before there is anything to install is a question with no context.
     */
    fun install() {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return

        if (!container.updateInstaller.canInstallPackages) {
            _state.value = UpdateState.NeedsInstallPermission(ready.release, ready.apk)
            return
        }

        if (!container.updateInstaller.install(ready.apk)) {
            Log.w(TAG, "The installer could not be opened for ${ready.release.tag}")
            _state.value = UpdateState.DownloadFailed(ready.release, corrupt = false)
        }
    }

    /**
     * Puts the Install button back once the permission has been granted.
     *
     * The permission is granted on a different screen, in another app, so the
     * only way to notice is to look again when this one comes forward. Without
     * this the copy is a dead end: it tells the user to come back and tap
     * Install while the only button still says "Open settings".
     *
     * Deliberately does not install on its own. Returning from a settings screen
     * to find an installer already open is a different thing from asking for one.
     */
    fun refreshInstallPermission() {
        val asking = _state.value as? UpdateState.NeedsInstallPermission ?: return
        if (container.updateInstaller.canInstallPackages) {
            _state.value = UpdateState.ReadyToInstall(asking.release, asking.apk)
        }
    }

    private fun UpdateState.releaseOrNull(): GitHubRelease? = when (this) {
        is UpdateState.Available -> release
        is UpdateState.DownloadFailed -> release
        is UpdateState.Downloading -> release
        is UpdateState.ReadyToInstall -> release
        is UpdateState.NeedsInstallPermission -> release
        UpdateState.Checking, UpdateState.CheckFailed, UpdateState.Idle, UpdateState.UpToDate -> null
    }

    private companion object {
        const val TAG = "UpdateFlow"
    }
}
