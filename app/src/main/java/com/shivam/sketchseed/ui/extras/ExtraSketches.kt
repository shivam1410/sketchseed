package com.shivam.sketchseed.ui.extras

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.shivam.sketchseed.R
import com.shivam.sketchseed.domain.model.ExtraSketch
import com.shivam.sketchseed.ui.components.SectionHeader
import java.io.File

/**
 * Everything else drawn on this day.
 *
 * Shown only once the day's own prompt is done — the point is somewhere to put
 * spare appetite after the day is earned, not a way around it.
 */
@Composable
fun ExtraSketchesSection(
    extras: List<ExtraSketch>,
    resolvePhoto: (String) -> File?,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        SectionHeader(stringResource(R.string.extras_header))

        Text(
            text = if (extras.isEmpty()) {
                stringResource(R.string.extras_empty)
            } else {
                pluralStringResource(R.plurals.extras_count, extras.size, extras.size)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        extras.forEach { extra ->
            Spacer(Modifier.height(20.dp))
            ExtraItem(
                extra = extra,
                photo = extra.photoFileName?.let(resolvePhoto),
                onRemove = { onRemove(extra.id) },
            )
        }

        Spacer(Modifier.height(24.dp))

        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.extras_add))
        }
    }
}

/**
 * One bonus sketch, shown at the same size as the day's own.
 *
 * An extra is a drawing, not a list entry — it gets the full width and the same
 * corner radius and scaling as the main photo, so a day with four sketches reads
 * as four sketches rather than one drawing and three attachments.
 */
@Composable
private fun ExtraItem(
    extra: ExtraSketch,
    photo: File?,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = extra.title.ifBlank { stringResource(R.string.extras_untitled) },
                style = MaterialTheme.typography.titleMedium,
                color = if (extra.title.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.extras_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (photo != null) {
            Spacer(Modifier.height(8.dp))
            AsyncImage(
                model = photo,
                contentDescription = extra.title.ifBlank { null },
                // Matches the day's own photo exactly.
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp)),
            )
        }
    }
}

/** Title field plus an optional photo, before the extra is committed. */
@Composable
fun ExtraComposer(
    draft: ExtraDraft,
    photo: File?,
    onTitleChange: (String) -> Unit,
    onAddPhoto: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.extras_composer_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = onTitleChange,
                    label = { Text(stringResource(R.string.extras_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))

                if (photo != null) {
                    AsyncImage(
                        model = photo,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(12.dp))
                }

                OutlinedButton(
                    onClick = onAddPhoto,
                    enabled = !draft.saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (draft.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
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
                            if (photo == null) {
                                R.string.extras_add_photo
                            } else {
                                R.string.retake_sketch_photo
                            },
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = draft.canSave) {
                Text(stringResource(R.string.extras_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
    )
}
