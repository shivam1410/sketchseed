package com.shivam.sketchseed.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shivam.sketchseed.BuildConfig
import com.shivam.sketchseed.R
import com.shivam.sketchseed.ai.TipAvailability
import com.shivam.sketchseed.ui.components.SectionHeader

private const val TAG = "SettingsScreen"

/** "2 hours ago" style, so the last-backup line reads at a glance. */
private fun formatTimestamp(epochSecond: Long): String =
    DateUtils.getRelativeTimeSpanString(
        epochSecond * 1_000L,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

/**
 * Maps the ViewModel's sentinels onto localised text.
 *
 * The ViewModel deals in sentinels rather than strings so it stays free of
 * Android resource lookups; anything it could not classify is already a
 * human-readable message from the Drive layer and passes through unchanged.
 */
@Composable
private fun driveMessageText(message: String): String = when {
    message.startsWith(SettingsViewModel.BACKED_UP) -> {
        val bytes = message.substringAfter(':', "").toLongOrNull() ?: 0L
        stringResource(
            R.string.settings_drive_backed_up,
            Formatter.formatShortFileSize(LocalContext.current, bytes),
        )
    }

    message == SettingsViewModel.NO_BACKUP -> stringResource(R.string.settings_drive_none)
    message == SettingsViewModel.SIGN_IN_FAILED ->
        stringResource(R.string.settings_drive_signin_cancelled)

    message == SettingsViewModel.SIGN_IN_CANCELLED ->
        stringResource(R.string.settings_drive_signin_cancelled)

    message.startsWith(SettingsViewModel.RESTORED) -> {
        val parts = message.split(':')
        stringResource(
            R.string.settings_drive_restored,
            parts.getOrNull(1)?.toIntOrNull() ?: 0,
            parts.getOrNull(2)?.toIntOrNull() ?: 0,
        )
    }

    else -> message
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val consentRequest by viewModel.consentRequest.collectAsStateWithLifecycle()
    val driveMessage by viewModel.driveMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmingReset by remember { mutableStateOf(false) }
    var confirmingRestore by remember { mutableStateOf(false) }

    // Google hands back a PendingIntent when the user has to approve Drive
    // access; the result carries the token, so it goes straight back to the VM.
    val consentLauncher = rememberLauncherForActivityResult(StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onConsentResult(result.data)
        } else {
            viewModel.onConsentDismissed()
        }
    }

    LaunchedEffect(consentRequest) {
        consentRequest?.let { pending ->
            consentLauncher.launch(IntentSenderRequest.Builder(pending).build())
        }
    }

    driveMessage?.let { message ->
        val text = driveMessageText(message)
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeDriveMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Google backup ────────────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_backup_header))

            Text(
                text = stringResource(R.string.settings_backup_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_backup_images_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.settings_backup_images_summary))
                },
                trailingContent = {
                    Switch(
                        checked = state.settings.backupSketches,
                        enabled = !state.busy,
                        onCheckedChange = viewModel::setBackupSketches,
                    )
                },
            )

            Text(
                text = pluralStringResource(
                    R.plurals.settings_backup_storage,
                    state.usage.fileCount,
                    Formatter.formatShortFileSize(context, state.usage.totalBytes),
                    state.usage.fileCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(
                    if (state.settings.backupSketches) {
                        R.string.settings_backup_state_on
                    } else {
                        R.string.settings_backup_state_off
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            if (state.nearBackupQuota) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_backup_quota_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(onClick = { openSystemBackupSettings(context) }) {
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.settings_backup_open_system),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // ── Google Drive ─────────────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_drive_header))

            Text(
                text = stringResource(R.string.settings_drive_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = state.settings.lastDriveBackupEpochSecond
                    ?.let { stringResource(R.string.settings_drive_last, formatTimestamp(it)) }
                    ?: stringResource(R.string.settings_drive_never),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = viewModel::backUpToDrive,
                enabled = !state.driveBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.driveBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(stringResource(R.string.settings_drive_backup_now))
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { confirmingRestore = true },
                enabled = !state.driveBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_drive_restore))
            }

            Spacer(Modifier.height(4.dp))

            TextButton(
                onClick = viewModel::disconnectDrive,
                enabled = !state.driveBusy,
            ) {
                Text(stringResource(R.string.settings_drive_signout))
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // ── On-device AI ─────────────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_ai_header))

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_ai_tips_title)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_ai_tips_summary))
                },
                trailingContent = {
                    Switch(
                        checked = state.settings.aiTipsEnabled,
                        enabled = state.aiAvailability != TipAvailability.UNSUPPORTED,
                        onCheckedChange = viewModel::setAiTipsEnabled,
                    )
                },
            )

            state.aiAvailability?.let { availability ->
                Text(
                    text = stringResource(
                        when (availability) {
                            TipAvailability.READY -> R.string.settings_ai_status_available
                            TipAvailability.NEEDS_DOWNLOAD -> R.string.settings_ai_status_downloadable
                            TipAvailability.UNSUPPORTED -> R.string.settings_ai_status_unavailable
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // ── Journey ──────────────────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_danger_header))

            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(R.string.settings_reset_title),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                supportingContent = {
                    Text(stringResource(R.string.settings_reset_summary))
                },
                modifier = Modifier.clickableIfNotBusy(state.busy) { confirmingReset = true },
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // ── About ────────────────────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_about_header))

            Text(
                text = stringResource(R.string.settings_about_tagline),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(40.dp))
        }
    }

    if (confirmingRestore) {
        AlertDialog(
            onDismissRequest = { confirmingRestore = false },
            title = { Text(stringResource(R.string.settings_drive_restore_confirm_title)) },
            text = { Text(stringResource(R.string.settings_drive_restore_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRestore = false
                        viewModel.restoreFromDrive()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_drive_restore_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRestore = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.settings_reset_confirm_body,
                        state.completedCount,
                        state.completedCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingReset = false
                        viewModel.resetJourney()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_reset_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun Modifier.clickableIfNotBusy(busy: Boolean, onClick: () -> Unit): Modifier =
    this.clickable(enabled = !busy, onClick = onClick)

/**
 * Opens the system screen where Google backup is switched on.
 *
 * There is no public intent aimed squarely at backup settings, so this tries the
 * privacy screen that hosts it and falls back to the settings root.
 */
private fun openSystemBackupSettings(context: android.content.Context) {
    val candidates = listOf(
        Intent("android.settings.BACKUP_AND_RESET_SETTINGS"),
        Intent(AndroidSettings.ACTION_PRIVACY_SETTINGS),
        Intent(AndroidSettings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, "Settings screen ${intent.action} not present", e)
        }
    }
    Log.w(TAG, "No system settings activity could be opened")
}
