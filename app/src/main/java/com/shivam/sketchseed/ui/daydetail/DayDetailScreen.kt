package com.shivam.sketchseed.ui.daydetail

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.shivam.sketchseed.R
import com.shivam.sketchseed.domain.model.DayRecord
import com.shivam.sketchseed.domain.model.Difficulty
import com.shivam.sketchseed.ui.components.DifficultyChip
import com.shivam.sketchseed.ui.scan.rememberSketchScanner
import com.shivam.sketchseed.ui.search.ReferenceSearch
import com.shivam.sketchseed.ui.theme.OverlineStyle
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailScreen(
    onBack: () -> Unit,
    resolvePhoto: (String) -> File?,
    viewModel: DayDetailViewModel = viewModel(factory = DayDetailViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val startScan = rememberSketchScanner(onScanned = viewModel::onSketchScanned)

    var searchFor by remember { mutableStateOf<String?>(null) }
    val noBrowserMessage = stringResource(R.string.no_browser_found)

    state.errorMessage?.let { messageId ->
        val message = stringResource(messageId)
        LaunchedEffect(messageId) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.day_detail_title, state.day)) },
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
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val record = state.record
            val prompt = state.prompt

            when {
                state.loading -> Unit

                state.isLocked -> {
                    Spacer(Modifier.height(64.dp))
                    Text(
                        text = stringResource(R.string.day_detail_locked),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                record != null -> CompletedDay(
                    record = record,
                    photo = record.photoFileName?.let(resolvePhoto),
                    savingPhoto = state.savingPhoto,
                    onAddPhoto = startScan,
                    onRemovePhoto = viewModel::removePhoto,
                )

                prompt != null -> OpenDay(
                    promptText = prompt.text,
                    difficulty = prompt.difficulty,
                    isBackfill = state.isBackfill,
                    savingPhoto = state.savingPhoto,
                    onFindReferences = { searchFor = prompt.text },
                    onMarkDone = viewModel::markDone,
                    onAddPhoto = startScan,
                )
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

/** A revealed day that has not been drawn yet — today's, or one being caught up. */
@Composable
private fun OpenDay(
    promptText: String,
    difficulty: Difficulty,
    isBackfill: Boolean,
    savingPhoto: Boolean,
    onFindReferences: () -> Unit,
    onMarkDone: () -> Unit,
    onAddPhoto: () -> Unit,
) {
    Spacer(Modifier.height(32.dp))

    Text(
        text = promptText,
        style = MaterialTheme.typography.displaySmall,
        textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(12.dp))
    DifficultyChip(difficulty)

    if (isBackfill) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.day_detail_backfill_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }

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
private fun CompletedDay(
    record: DayRecord,
    photo: File?,
    savingPhoto: Boolean,
    onAddPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
) {
    Spacer(Modifier.height(16.dp))

    Text(
        text = record.promptText,
        style = MaterialTheme.typography.displaySmall,
        textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(12.dp))
    DifficultyChip(record.difficulty)
    Spacer(Modifier.height(8.dp))

    Text(
        text = stringResource(
            R.string.day_detail_completed_on,
            record.completedOn.format(DATE_FORMAT),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(24.dp))

    if (photo != null) {
        AsyncImage(
            model = photo,
            contentDescription = record.promptText,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.ImageNotSupported,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.day_detail_no_photo),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    record.tip?.let { tip ->
        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.tip_heading).uppercase(),
                        style = OverlineStyle,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(text = tip, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    Spacer(Modifier.height(24.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(
            onClick = onAddPhoto,
            enabled = !savingPhoto,
            modifier = Modifier.weight(1f),
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

        if (photo != null) {
            TextButton(onClick = onRemovePhoto) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.delete_photo))
            }
        }
    }
}
