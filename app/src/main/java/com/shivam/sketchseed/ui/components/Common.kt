package com.shivam.sketchseed.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shivam.sketchseed.R
import com.shivam.sketchseed.domain.model.Difficulty
import com.shivam.sketchseed.ui.theme.OverlineStyle

@Composable
fun DifficultyChip(difficulty: Difficulty, modifier: Modifier = Modifier) {
    val (labelId, container) = when (difficulty) {
        Difficulty.EASY -> R.string.difficulty_easy to MaterialTheme.colorScheme.secondaryContainer
        Difficulty.MEDIUM -> R.string.difficulty_medium to MaterialTheme.colorScheme.tertiaryContainer
        Difficulty.HARD -> R.string.difficulty_hard to MaterialTheme.colorScheme.errorContainer
    }

    Text(
        text = stringResource(labelId),
        style = MaterialTheme.typography.labelMedium,
        color = contentColorOn(container),
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(container)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** A label above a number, used for the streak / progress row. */
@Composable
fun StatPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label.uppercase(),
            style = OverlineStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = OverlineStyle,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

@Composable
fun LabelledRow(
    label: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        trailing()
    }
}

/**
 * Picks readable text for an arbitrary container colour.
 *
 * Dynamic colour means the container is not known at build time, so contrast is
 * computed rather than hard-coded.
 */
@Composable
private fun contentColorOn(background: Color): Color {
    val luminance = background.red * 0.299f + background.green * 0.587f + background.blue * 0.114f
    return if (luminance > 0.55f) Color.Black.copy(alpha = 0.82f) else Color.White
}
