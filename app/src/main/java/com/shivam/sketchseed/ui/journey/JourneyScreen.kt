package com.shivam.sketchseed.ui.journey

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.shivam.sketchseed.R
import com.shivam.sketchseed.ui.components.StatPill
import java.io.File

/**
 * The hundred days as a grid.
 *
 * A month calendar was the obvious alternative, but the journey is not
 * calendar-shaped: a missed day costs no prompt, so day 17 is simply the
 * seventeenth sketch, whenever it happened. Indexing by day number keeps that
 * honest, and the thumbnails make it worth scrolling back through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyScreen(
    onBack: () -> Unit,
    onOpenDay: (Int) -> Unit,
    resolvePhoto: (String) -> File?,
    viewModel: JourneyViewModel = viewModel(factory = JourneyViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.journey_title)) },
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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.progress?.let { progress ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    // Every number lives here rather than on Today, which stays
                    // reserved for the one thing that matters: the prompt.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            StatPill(
                                label = stringResource(R.string.journey_stat_completed),
                                value = stringResource(
                                    R.string.progress_fraction,
                                    progress.completedCount,
                                    progress.totalDays,
                                ),
                            )
                            StatPill(
                                label = stringResource(R.string.journey_stat_streak),
                                value = progress.currentStreak.toString(),
                            )
                            StatPill(
                                label = stringResource(R.string.journey_stat_best),
                                value = progress.bestStreak.toString(),
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        LinearProgressIndicator(
                            progress = {
                                progress.completedCount.toFloat() / progress.totalDays
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(50)),
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = stringResource(
                                R.string.progress_percent,
                                progress.percentComplete,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(state.cells, key = { it.day }) { cell ->
                DayTile(
                    cell = cell,
                    photo = (cell as? DayCell.Done)
                        ?.record
                        ?.photoFileName
                        ?.let(resolvePhoto),
                    onClick = { onOpenDay(cell.day) },
                )
            }
        }
    }
}

@Composable
private fun DayTile(
    cell: DayCell,
    photo: File?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val description = when (cell) {
        is DayCell.Done -> stringResource(
            R.string.journey_completed_day,
            cell.day,
            cell.record.promptText,
        )

        is DayCell.Current -> stringResource(R.string.journey_today_day, cell.day)
        is DayCell.Missed -> stringResource(R.string.journey_missed_day, cell.day, cell.prompt.text)
        is DayCell.Locked -> stringResource(R.string.journey_locked_day, cell.day)
    }

    val container = when (cell) {
        is DayCell.Done -> MaterialTheme.colorScheme.surfaceVariant
        is DayCell.Current -> MaterialTheme.colorScheme.primaryContainer
        is DayCell.Missed -> MaterialTheme.colorScheme.surfaceContainerLow
        is DayCell.Locked -> MaterialTheme.colorScheme.surfaceContainerLow
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(container)
            .then(
                when (cell) {
                    is DayCell.Current ->
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                    // Outlined rather than filled: still open, but overdue.
                    is DayCell.Missed -> Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        shape,
                    )
                    else -> Modifier
                },
            )
            .clickable(enabled = cell !is DayCell.Locked, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (cell) {
            is DayCell.Done -> {
                if (photo != null) {
                    AsyncImage(
                        model = photo,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                DayLabel(
                    day = cell.day,
                    caption = cell.record.promptText,
                    onImage = photo != null,
                )
                if (cell.record.extras.isNotEmpty()) {
                    ExtrasBadge(
                        count = cell.record.extras.size,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    )
                }
            }

            is DayCell.Current -> DayLabel(
                day = cell.day,
                caption = cell.prompt.text,
                onImage = false,
            )

            is DayCell.Missed -> DayLabel(
                day = cell.day,
                caption = cell.prompt.text,
                onImage = false,
                dimmed = true,
            )

            is DayCell.Locked -> Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Marks a day that carries bonus sketches beyond its own prompt. */
@Composable
private fun ExtrasBadge(count: Int, modifier: Modifier = Modifier) {
    val spoken = pluralStringResource(R.plurals.extras_count, count, count)
    Text(
        text = stringResource(R.string.extras_badge, count),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = spoken },
    )
}

@Composable
private fun DayLabel(
    day: Int,
    caption: String,
    onImage: Boolean,
    dimmed: Boolean = false,
) {
    // A scrim keeps the number legible over an arbitrary photograph.
    val scrim = if (onImage) {
        Modifier.background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
    } else {
        Modifier
    }
    val tint = when {
        onImage -> Color.White
        dimmed -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(scrim)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = tint,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = tint.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
