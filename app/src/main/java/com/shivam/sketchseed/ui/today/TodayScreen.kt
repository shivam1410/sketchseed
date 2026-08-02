package com.shivam.sketchseed.ui.today

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.shivam.sketchseed.R
import com.shivam.sketchseed.domain.model.DayRecord
import com.shivam.sketchseed.domain.model.JourneyProgress
import com.shivam.sketchseed.domain.model.Prompt
import com.shivam.sketchseed.ui.components.DifficultyChip
import com.shivam.sketchseed.ui.capture.rememberSketchCaptureSheet
import com.shivam.sketchseed.ui.search.ReferenceSearch
import com.shivam.sketchseed.ui.theme.OverlineStyle
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

private val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenJourney: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDay: (Int) -> Unit,
    resolvePhoto: (String) -> File?,
    viewModel: TodayViewModel = viewModel(factory = TodayViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // An app left open overnight must roll over to the new day.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshDate()
        onPauseOrDispose { }
    }

    state.errorMessage?.let { messageId ->
        val message = stringResource(messageId)
        LaunchedEffect(messageId) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissError()
        }
    }

    var searchFor by remember { mutableStateOf<String?>(null) }
    val noBrowserMessage = stringResource(R.string.no_browser_found)
    val captureFailedMessage = stringResource(R.string.capture_failed)
    val addPhoto = rememberSketchCaptureSheet(
        onCaptured = viewModel::onSketchCaptured,
        onFailed = { scope.launch { snackbarHostState.showSnackbar(captureFailedMessage) } },
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenJourney) {
                        Icon(
                            Icons.Outlined.GridView,
                            contentDescription = stringResource(R.string.journey_title),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings_open),
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
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val mode = state.mode) {
                TodayMode.Loading -> LoadingBlock()

                is TodayMode.NotStarted -> MessageBlock(
                    title = stringResource(R.string.not_started_title),
                    body = stringResource(
                        R.string.not_started_body,
                        mode.startDate.format(LONG_DATE),
                    ),
                )

                is TodayMode.Draw -> DrawBlock(
                    prompt = mode.prompt,
                    progress = state.progress,
                    tip = state.tip,
                    savingPhoto = state.savingPhoto,
                    onFindReferences = { searchFor = mode.prompt.text },
                    onMarkDone = viewModel::markDone,
                    onAddPhoto = addPhoto,
                    onRequestTip = viewModel::requestTip,
                    onDismissTip = viewModel::dismissTip,
                )

                is TodayMode.Rest -> RestBlock(
                    finished = mode.finished,
                    nextDay = mode.nextDay,
                    savingPhoto = state.savingPhoto,
                    photo = mode.finished.photoFileName?.let(resolvePhoto),
                    onAddPhoto = addPhoto,
                    onOpenDay = { onOpenDay(mode.finished.day) },
                )

                is TodayMode.WindowClosed -> MessageBlock(
                    title = stringResource(R.string.window_closed_title),
                    body = stringResource(R.string.window_closed_body, mode.completed),
                )

                TodayMode.Finished -> FinishedBlock(
                    completed = state.progress?.completedCount ?: 0,
                    onOpenJourney = onOpenJourney,
                )
            }

            if (state.missedCount > 0 && state.mode !is TodayMode.Loading) {
                Spacer(Modifier.height(20.dp))
                CatchUpLink(count = state.missedCount, onClick = onOpenJourney)
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    searchFor?.let { promptText ->
        val query = stringResource(R.string.search_query_suffix, promptText)
        ModalBottomSheet(
            onDismissRequest = { searchFor = null },
            sheetState = rememberModalBottomSheetState(),
        ) {
            fun openAndClose(open: () -> Boolean) {
                val opened = open()
                searchFor = null
                if (!opened) {
                    scope.launch { snackbarHostState.showSnackbar(noBrowserMessage) }
                }
            }

            Text(
                text = stringResource(R.string.search_sheet_title, promptText),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.search_pinterest)) },
                leadingContent = { Icon(Icons.Outlined.Search, contentDescription = null) },
                modifier = Modifier.clickable {
                    openAndClose {
                        ReferenceSearch.open(context, ReferenceSearch.pinterest(query))
                    }
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.search_google_images)) },
                leadingContent = { Icon(Icons.Outlined.Search, contentDescription = null) },
                modifier = Modifier.clickable {
                    openAndClose {
                        ReferenceSearch.open(context, ReferenceSearch.googleImages(query))
                    }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MessageBlock(title: String, body: String) {
    Spacer(Modifier.height(64.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun CatchUpLink(count: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(pluralStringResource(R.plurals.catch_up_prompt, count, count))
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun DrawBlock(
    prompt: Prompt,
    progress: JourneyProgress?,
    tip: TipState,
    savingPhoto: Boolean,
    onFindReferences: () -> Unit,
    onMarkDone: () -> Unit,
    onAddPhoto: () -> Unit,
    onRequestTip: () -> Unit,
    onDismissTip: () -> Unit,
) {
    Spacer(Modifier.height(48.dp))

    progress?.currentDay?.let { day ->
        Text(
            text = stringResource(R.string.day_counter, day, progress.totalDays),
            style = OverlineStyle,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    Spacer(Modifier.height(20.dp))

    Text(
        text = stringResource(R.string.todays_sketch).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))

    Text(
        text = prompt.text,
        style = MaterialTheme.typography.displayMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(Modifier.height(16.dp))
    DifficultyChip(prompt.difficulty)
    Spacer(Modifier.height(24.dp))

    TipSection(tip = tip, onRequestTip = onRequestTip, onDismissTip = onDismissTip)

    Spacer(Modifier.height(28.dp))

    OutlinedButton(onClick = onFindReferences, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.find_references))
    }

    Spacer(Modifier.height(12.dp))

    Button(onClick = onMarkDone, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.mark_as_done))
    }

    Spacer(Modifier.height(12.dp))

    PhotoButton(
        savingPhoto = savingPhoto,
        labelId = R.string.add_sketch_photo,
        onClick = onAddPhoto,
    )
}

@Composable
private fun PhotoButton(
    savingPhoto: Boolean,
    labelId: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    FilledTonalButton(onClick = onClick, enabled = !savingPhoto, modifier = modifier) {
        if (savingPhoto) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Outlined.PhotoCamera,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(labelId))
    }
}

@Composable
private fun TipSection(
    tip: TipState,
    onRequestTip: () -> Unit,
    onDismissTip: () -> Unit,
) {
    val context = LocalContext.current

    when (tip) {
        TipState.Hidden -> Unit

        TipState.Idle -> TextButton(onClick = onRequestTip) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.tip_get))
        }

        TipState.Working -> BusyRow(stringResource(R.string.tip_generating))

        is TipState.Preparing -> BusyRow(
            // Say what is actually happening: this is a model download, not
            // inference, and it can take minutes on first use.
            if (tip.bytesDownloaded > 0) {
                stringResource(
                    R.string.tip_preparing,
                    Formatter.formatShortFileSize(context, tip.bytesDownloaded),
                )
            } else {
                stringResource(R.string.tip_preparing_starting)
            },
        )

        is TipState.Ready -> Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.tip_heading).uppercase(),
                    style = OverlineStyle,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(text = tip.text, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismissTip, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.close))
                }
            }
        }

        is TipState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(tip.messageId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onRequestTip) { Text(stringResource(R.string.tip_get)) }
        }
    }
}

@Composable
private fun BusyRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RestBlock(
    finished: DayRecord,
    nextDay: Int?,
    savingPhoto: Boolean,
    photo: File?,
    onAddPhoto: () -> Unit,
    onOpenDay: () -> Unit,
) {
    Spacer(Modifier.height(48.dp))

    Icon(
        Icons.Filled.Check,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(44.dp),
    )

    Spacer(Modifier.height(16.dp))

    Text(
        text = stringResource(R.string.done_today_title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(8.dp))

    Text(
        text = finished.promptText,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(20.dp))

    if (photo != null) {
        AsyncImage(
            model = photo,
            contentDescription = finished.promptText,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onOpenDay),
        )
        Spacer(Modifier.height(16.dp))
    }

    nextDay?.let {
        Text(
            text = stringResource(R.string.done_today_body, it),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
    }

    PhotoButton(
        savingPhoto = savingPhoto,
        labelId = if (photo == null) R.string.add_sketch_photo else R.string.retake_sketch_photo,
        onClick = onAddPhoto,
    )

    Spacer(Modifier.height(20.dp))

    // Somewhere for spare appetite to go on a day that took two minutes,
    // without letting it eat into tomorrow's prompt.
    TextButton(onClick = onOpenDay) {
        Text(
            text = if (finished.extras.isEmpty()) {
                stringResource(R.string.done_today_extras)
            } else {
                pluralStringResource(
                    R.plurals.extras_count,
                    finished.extras.size,
                    finished.extras.size,
                )
            },
        )
    }
}

@Composable
private fun FinishedBlock(completed: Int, onOpenJourney: () -> Unit) {
    Spacer(Modifier.height(64.dp))

    Text(
        text = stringResource(R.string.journey_complete_title),
        style = MaterialTheme.typography.displaySmall,
        textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(12.dp))

    Text(
        text = stringResource(R.string.journey_complete_body, completed),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(28.dp))

    Button(onClick = onOpenJourney, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.view_journey))
    }
}
