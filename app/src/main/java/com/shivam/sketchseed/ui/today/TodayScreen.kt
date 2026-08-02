package com.shivam.sketchseed.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.shivam.sketchseed.ui.components.StatPill
import com.shivam.sketchseed.ui.scan.rememberSketchScanner
import com.shivam.sketchseed.ui.search.ReferenceSearch
import com.shivam.sketchseed.ui.theme.OverlineStyle
import java.io.File
import kotlinx.coroutines.launch

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

    var searchFor by remember { mutableStateOf<String?>(null) }
    val noBrowserMessage = stringResource(R.string.no_browser_found)

    val startScan = rememberSketchScanner(onScanned = viewModel::onSketchScanned)

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

                is TodayMode.Draw -> DrawBlock(
                    prompt = mode.prompt,
                    progress = state.progress,
                    tip = state.tip,
                    savingPhoto = state.savingPhoto,
                    onFindReferences = { searchFor = mode.prompt.text },
                    onMarkDone = viewModel::markDone,
                    onAddPhoto = startScan,
                    onRequestTip = viewModel::requestTip,
                    onDismissTip = viewModel::dismissTip,
                )

                is TodayMode.Rest -> RestBlock(
                    finished = mode.finished,
                    nextDay = mode.nextDay,
                    savingPhoto = state.savingPhoto,
                    photo = mode.finished.photoFileName?.let(resolvePhoto),
                    onAddPhoto = startScan,
                    onOpenDay = { onOpenDay(mode.finished.day) },
                )

                TodayMode.Finished -> FinishedBlock(
                    completed = state.progress?.completedCount ?: 0,
                    onOpenJourney = onOpenJourney,
                )
            }

            state.progress?.let {
                Spacer(Modifier.height(32.dp))
                ProgressFooter(it)
            }

            Spacer(Modifier.height(32.dp))
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
    Spacer(Modifier.height(40.dp))

    progress?.let {
        Text(
            text = stringResource(R.string.day_counter, it.currentDay, it.totalDays),
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

    TipSection(
        tip = tip,
        onRequestTip = onRequestTip,
        onDismissTip = onDismissTip,
    )

    Spacer(Modifier.height(28.dp))

    OutlinedButton(
        onClick = onFindReferences,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.find_references))
    }

    Spacer(Modifier.height(12.dp))

    Button(
        onClick = onMarkDone,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.mark_as_done))
    }

    Spacer(Modifier.height(12.dp))

    FilledTonalButton(
        onClick = onAddPhoto,
        enabled = !savingPhoto,
        modifier = Modifier.fillMaxWidth(),
    ) {
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
        Text(stringResource(R.string.add_sketch_photo))
    }
}

@Composable
private fun TipSection(
    tip: TipState,
    onRequestTip: () -> Unit,
    onDismissTip: () -> Unit,
) {
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

        TipState.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.tip_generating),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onDismissTip,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        }

        is TipState.Error -> Text(
            text = stringResource(tip.messageId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RestBlock(
    finished: DayRecord,
    nextDay: Int,
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

    Text(
        text = stringResource(R.string.done_today_body, nextDay),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(24.dp))

    FilledTonalButton(
        onClick = onAddPhoto,
        enabled = !savingPhoto,
        modifier = Modifier.fillMaxWidth(),
    ) {
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
        Text(
            stringResource(
                if (photo == null) R.string.add_sketch_photo else R.string.retake_sketch_photo,
            ),
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

@Composable
private fun ProgressFooter(progress: JourneyProgress) {
    HorizontalDivider()
    Spacer(Modifier.height(20.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        StatPill(
            label = stringResource(R.string.journey_stat_streak),
            value = progress.currentStreak.toString(),
        )
        StatPill(
            label = stringResource(R.string.journey_stat_completed),
            value = stringResource(
                R.string.progress_fraction,
                progress.completedCount,
                progress.totalDays,
            ),
        )
        StatPill(
            label = stringResource(R.string.journey_stat_best),
            value = progress.bestStreak.toString(),
        )
    }

    Spacer(Modifier.height(16.dp))

    LinearProgressIndicator(
        progress = { progress.completedCount.toFloat() / progress.totalDays },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50)),
    )

    Spacer(Modifier.height(8.dp))

    Text(
        text = stringResource(R.string.progress_percent, progress.percentComplete),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
