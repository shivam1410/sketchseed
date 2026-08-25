package com.shivam.sketchseed.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shivam.sketchseed.BuildConfig
import com.shivam.sketchseed.R
import com.shivam.sketchseed.notify.ReminderSlot
import com.shivam.sketchseed.ui.components.SectionHeader
import com.shivam.sketchseed.update.UpdateChecker
import java.time.LocalTime
import java.util.Calendar

private const val TAG = "SettingsScreen"

private const val ZIP_MIME = "application/zip"

/** Some file pickers hide zips behind a generic type, so offer both. */
private const val ANY_MIME = "*/*"

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

    message.startsWith(SettingsViewModel.EXPORTED) -> {
        val bytes = message.substringAfter(':', "").toLongOrNull() ?: 0L
        stringResource(
            R.string.settings_file_exported,
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
    val updateState by viewModel.update.state.collectAsStateWithLifecycle()
    val driveMessage by viewModel.driveMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmingReset by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf<ReminderSlot?>(null) }

    // The user may flip notifications in system settings and come straight back.
    // The update check rides along: this is the moment the screen is in front of
    // someone, and UpdateFlow decides whether enough time has passed to ask.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshNotificationState()
        // The install permission may have been granted while we were away.
        viewModel.update.refreshInstallPermission()
        viewModel.update.checkOnOpen()
        onPauseOrDispose { }
    }
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

    // Storage Access Framework: no permissions, and the user picks the location,
    // so an exported backup can land anywhere including a different cloud.
    val defaultExportName = stringResource(R.string.settings_file_default_name)
    val exportLauncher = rememberLauncherForActivityResult(CreateDocument(ZIP_MIME)) { uri ->
        uri?.let(viewModel::exportToFile)
    }
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        // Confirm before overwriting, same as the Drive restore.
        pendingImport = uri
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

            // ── Google Drive ─────────────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_drive_header))

            Text(
                text = stringResource(R.string.settings_drive_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_drive_auto_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.settings_drive_auto_summary))
                },
                trailingContent = {
                    Switch(
                        checked = state.settings.autoDriveBackup,
                        enabled = !state.busy,
                        onCheckedChange = viewModel::setAutoDriveBackup,
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

            // ── Plain file export / import ───────────────────────────────────
            SectionHeader(stringResource(R.string.settings_file_header))

            Text(
                text = stringResource(R.string.settings_file_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { exportLauncher.launch(defaultExportName) },
                enabled = !state.driveBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_file_export))
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf(ZIP_MIME, ANY_MIME)) },
                enabled = !state.driveBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_file_import))
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // ── Reminders ────────────────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_reminders_header))

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_reminders_title)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_reminders_summary))
                },
                trailingContent = {
                    Switch(
                        checked = state.settings.remindersEnabled,
                        onCheckedChange = viewModel::setRemindersEnabled,
                    )
                },
            )

            // Each slot is a tappable row showing its current time.
            if (state.settings.remindersEnabled) {
                ReminderSlot.entries.forEach { slot ->
                    val at = slot.timeIn(state.settings)
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(
                                    when (slot) {
                                        ReminderSlot.MORNING -> R.string.settings_reminder_first
                                        ReminderSlot.EVENING -> R.string.settings_reminder_second
                                        ReminderSlot.NIGHT -> R.string.settings_reminder_third
                                    },
                                ),
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { editingSlot = slot }) {
                                Text(
                                    text = formatTime(at),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        },
                    )
                }
            }

            // Saying so beats a toggle that looks on while nothing arrives.
            if (state.settings.remindersEnabled && !state.notificationsAllowed) {
                Text(
                    text = stringResource(R.string.settings_reminders_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { openAppNotificationSettings(context) }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_reminders_open_system),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
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

            Spacer(Modifier.height(8.dp))

            UpdateRow(
                state = updateState,
                onCheck = viewModel.update::checkNow,
                onDownload = viewModel.update::download,
                onInstall = viewModel.update::install,
                onOpenPermissionSettings = {
                    openIntent(context, viewModel.update.installPermissionIntent)
                },
                onOpenReleaseNotes = { openUrl(context, UpdateChecker.RELEASES_PAGE_URL) },
            )

            Spacer(Modifier.height(40.dp))
        }
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.settings_file_import_confirm_title)) },
            text = { Text(stringResource(R.string.settings_file_import_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImport = null
                        viewModel.importFromFile(uri)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_drive_restore_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
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

    editingSlot?.let { slot ->
        ReminderTimePicker(
            initial = slot.timeIn(state.settings),
            onDismiss = { editingSlot = null },
            onConfirm = { at ->
                viewModel.setReminderTime(slot, at)
                editingSlot = null
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

/**
 * The one place an update is ever mentioned.
 *
 * Every state says what happened *and* what it means for the app on the phone,
 * because the honest answer to most of them is "nothing changed" — a check that
 * failed and a download that was thrown away both leave a working app, and
 * saying so is the difference between an update prompt and an alarm.
 */
@Composable
private fun UpdateRow(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onOpenReleaseNotes: () -> Unit,
) {
    val context = LocalContext.current

    val headline = when (state) {
        UpdateState.Idle -> stringResource(R.string.settings_update_check)
        UpdateState.Checking -> stringResource(R.string.settings_update_checking)
        UpdateState.UpToDate -> stringResource(R.string.settings_update_current)
        UpdateState.CheckFailed -> stringResource(R.string.settings_update_check_failed)
        is UpdateState.Available ->
            stringResource(R.string.settings_update_available, state.release.version)

        is UpdateState.Downloading ->
            stringResource(R.string.settings_update_downloading, state.release.version)

        is UpdateState.ReadyToInstall ->
            stringResource(R.string.settings_update_ready, state.release.version)

        is UpdateState.NeedsInstallPermission ->
            stringResource(R.string.settings_update_permission)

        is UpdateState.DownloadFailed -> stringResource(
            if (state.corrupt) R.string.settings_update_corrupt else R.string.settings_update_failed,
        )
    }

    val supporting = when (state) {
        UpdateState.Idle -> stringResource(R.string.settings_update_check_summary)
        UpdateState.Checking -> null
        UpdateState.UpToDate -> stringResource(R.string.settings_update_current_summary)
        UpdateState.CheckFailed -> stringResource(R.string.settings_update_check_failed_summary)
        is UpdateState.Available -> stringResource(
            R.string.settings_update_available_summary,
            state.release.name,
            Formatter.formatShortFileSize(context, state.release.apkSizeBytes),
        )

        is UpdateState.Downloading -> stringResource(
            R.string.settings_update_downloading_summary,
            Formatter.formatShortFileSize(context, state.downloadedBytes),
            Formatter.formatShortFileSize(context, state.totalBytes),
        )

        is UpdateState.ReadyToInstall -> stringResource(R.string.settings_update_ready_summary)
        is UpdateState.NeedsInstallPermission ->
            stringResource(R.string.settings_update_permission_summary)

        is UpdateState.DownloadFailed -> stringResource(
            if (state.corrupt) {
                R.string.settings_update_corrupt_summary
            } else {
                R.string.settings_update_failed_summary
            },
        )
    }

    // Only the states with nothing in flight re-check on a tap. Making the row
    // clickable mid-download would offer to throw the download away by accident.
    val onRowClick = when (state) {
        UpdateState.Idle, UpdateState.UpToDate, UpdateState.CheckFailed -> onCheck
        else -> null
    }

    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = {
            when (state) {
                UpdateState.Checking -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )

                is UpdateState.Downloading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )

                is UpdateState.Available -> Button(onClick = onDownload) {
                    Text(stringResource(R.string.settings_update_download))
                }

                is UpdateState.ReadyToInstall -> Button(onClick = onInstall) {
                    Text(stringResource(R.string.settings_update_install))
                }

                is UpdateState.NeedsInstallPermission ->
                    Button(onClick = onOpenPermissionSettings) {
                        Text(stringResource(R.string.settings_update_permission_action))
                    }

                is UpdateState.DownloadFailed -> OutlinedButton(onClick = onDownload) {
                    Text(stringResource(R.string.settings_update_retry))
                }

                UpdateState.Idle, UpdateState.UpToDate, UpdateState.CheckFailed -> Unit
            }
        },
        modifier = onRowClick?.let { Modifier.clickable(onClick = it) } ?: Modifier,
    )

    // Offered only once there is a specific release to read about.
    if (state is UpdateState.Available || state is UpdateState.ReadyToInstall) {
        TextButton(onClick = onOpenReleaseNotes) {
            Icon(
                Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_update_notes))
        }
    }
}

/** Opens the releases page in whatever the user browses with. */
private fun openUrl(context: Context, url: String) {
    openIntent(context, Intent(Intent.ACTION_VIEW, url.toUri()))
}

private fun openIntent(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Nothing on this device could handle ${intent.action}", e)
    }
}

private fun Modifier.clickableIfNotBusy(busy: Boolean, onClick: () -> Unit): Modifier =
    this.clickable(enabled = !busy, onClick = onClick)

/**
 * Opens this app's notification settings.
 *
 * APP_NOTIFICATION_SETTINGS lands directly on the right screen; the app-details
 * page is the fallback for anything that does not honour it.
 */
private fun openAppNotificationSettings(context: Context) {
    val candidates = listOf(
        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
        Intent(
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri(),
        ),
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, "Settings screen ${intent.action} not present", e)
        }
    }
    Log.w(TAG, "No notification settings screen could be opened")
}

/** Localised clock time, so 18:00 or 6:00 PM depending on the phone. */
@Composable
private fun formatTime(at: LocalTime): String {
    val context = LocalContext.current
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, at.hour)
        set(Calendar.MINUTE, at.minute)
    }
    return DateFormat.getTimeFormat(context).format(calendar.time)
}

/** Wraps Material's time picker in a dialog, since it does not ship as one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimePicker(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_reminder_pick)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                Text(stringResource(R.string.settings_reminder_set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
