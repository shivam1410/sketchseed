package com.shivam.sketchseed.ui.scan

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

private const val TAG = "SketchScanner"

/**
 * Launches the ML Kit document scanner and hands back the captured page.
 *
 * The scanner is worth the dependency here: it finds the edges of the paper,
 * corrects the perspective, and flattens the shadow a phone inevitably casts
 * over a sketchbook. A page shot at an angle on a desk comes back looking like
 * it was put through a flatbed scanner, which is what makes the journey grid
 * worth looking at later.
 *
 * Capture runs inside Play Services' own activity, so this app never needs the
 * CAMERA permission.
 *
 * @param onScanned invoked with the JPEG page URI on success.
 * @param onUnavailable invoked when the scanner cannot start, e.g. Play Services
 *   is too old or the module could not be fetched.
 * @return a callback to start scanning.
 */
@Composable
fun rememberSketchScanner(
    onScanned: (Uri) -> Unit,
    onUnavailable: () -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)

    val scanner = remember {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                // One sketch per day, so one page.
                .setPageLimit(1)
                // Let the user pick an existing photo if they already shot it.
                .setGalleryImportAllowed(true)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                // FULL includes the edge-detection and cleanup editor.
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build(),
        )
    }

    val launcher = rememberLauncherForActivityResult(StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult

        val page = GmsDocumentScanningResult
            .fromActivityResultIntent(result.data)
            ?.pages
            ?.firstOrNull()

        if (page == null) {
            Log.w(TAG, "Scanner returned no pages")
        } else {
            currentOnScanned(page.imageUri)
        }
    }

    return remember(scanner, launcher) {
        {
            val activity = context.findActivity()
            if (activity == null) {
                Log.e(TAG, "No host activity; cannot start the scanner")
                currentOnUnavailable()
            } else {
                scanner.getStartScanIntent(activity)
                    .addOnSuccessListener { sender ->
                        launcher.launch(IntentSenderRequest.Builder(sender).build())
                    }
                    .addOnFailureListener { error ->
                        Log.e(TAG, "Could not start the document scanner", error)
                        currentOnUnavailable()
                    }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
