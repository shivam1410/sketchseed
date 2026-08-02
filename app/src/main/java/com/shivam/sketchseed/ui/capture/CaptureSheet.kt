package com.shivam.sketchseed.ui.capture

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shivam.sketchseed.R

/**
 * "Add your sketch" — camera or gallery, chosen per sketch.
 *
 * Renders its own bottom sheet and returns the callback that opens it, so a
 * screen only needs one value to wire up a button.
 *
 * @return invoke to offer the choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberSketchCaptureSheet(
    onCaptured: (Uri) -> Unit,
    onFailed: () -> Unit = {},
): () -> Unit {
    var showing by remember { mutableStateOf(false) }
    val currentOnCaptured by rememberUpdatedState(onCaptured)

    val capture = rememberSketchCapture(
        onCaptured = { uri ->
            showing = false
            currentOnCaptured(uri)
        },
        onFailed = {
            showing = false
            onFailed()
        },
    )

    if (showing) {
        ModalBottomSheet(
            onDismissRequest = { showing = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Text(
                text = stringResource(R.string.capture_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.capture_take_photo)) },
                supportingContent = {
                    Text(stringResource(R.string.capture_take_photo_summary))
                },
                leadingContent = {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                },
                modifier = Modifier.clickable { capture.takePhoto() },
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.capture_pick_gallery)) },
                supportingContent = {
                    Text(stringResource(R.string.capture_pick_gallery_summary))
                },
                leadingContent = {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                },
                modifier = Modifier.clickable { capture.pickFromGallery() },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    return remember { { showing = true } }
}
