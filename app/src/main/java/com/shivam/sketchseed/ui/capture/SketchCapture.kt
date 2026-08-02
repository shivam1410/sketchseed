package com.shivam.sketchseed.ui.capture

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.io.IOException

private const val TAG = "SketchCapture"

/** Ways of getting a sketch into the app. */
interface SketchCapture {
    /** Hands off to the phone's camera app. */
    fun takePhoto()

    /** Opens the system photo picker. */
    fun pickFromGallery()
}

/**
 * Camera and gallery capture for a finished sketch.
 *
 * Photography is delegated to whatever camera app the user already has, via
 * `ACTION_IMAGE_CAPTURE`. That deliberately avoids declaring the `CAMERA`
 * permission: the moment an app declares it, the platform starts *requiring* it
 * for this intent, so not declaring it is what keeps the flow permission-free.
 * The photo picker needs no permission either.
 *
 * The camera writes into a temp file we own and expose through a [FileProvider];
 * [com.shivam.sketchseed.data.PhotoStore] then downscales it into place, so the
 * temp file is disposable.
 */
@Composable
fun rememberSketchCapture(
    onCaptured: (Uri) -> Unit,
    onFailed: () -> Unit = {},
): SketchCapture {
    val context = LocalContext.current
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnFailed by rememberUpdatedState(onFailed)

    // Survives the process death that a camera app can trigger on low-memory
    // devices; without it the result would arrive with nowhere to put it.
    var pendingPhotoUri by rememberSaveable { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val uri = pendingPhotoUri
        pendingPhotoUri = null
        when {
            !saved -> Log.d(TAG, "Camera cancelled or failed to save")
            uri == null -> Log.w(TAG, "Camera returned success with no target uri")
            else -> currentOnCaptured(uri.toUri())
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) currentOnCaptured(uri)
    }

    return remember(cameraLauncher, galleryLauncher, context) {
        object : SketchCapture {
            override fun takePhoto() {
                val target = context.newCameraTargetUri()
                if (target == null) {
                    currentOnFailed()
                    return
                }
                pendingPhotoUri = target.toString()
                try {
                    cameraLauncher.launch(target)
                } catch (e: Exception) {
                    // No camera app installed, or it refused the intent.
                    Log.e(TAG, "Could not start the camera", e)
                    pendingPhotoUri = null
                    currentOnFailed()
                }
            }

            override fun pickFromGallery() {
                try {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Could not open the photo picker", e)
                    currentOnFailed()
                }
            }
        }
    }
}

/**
 * A fresh file in cache the camera app is allowed to write to.
 *
 * Earlier captures are swept first. PhotoStore re-encodes into its own storage,
 * so once a shot has been handled the original here is dead weight — and at
 * 1.5 MB a Pixel photo, leaving them to accumulate quietly eats real space.
 * Nothing else can be mid-capture at this point, since this runs as a new one
 * is being started.
 */
private fun Context.newCameraTargetUri(): Uri? = try {
    val dir = File(cacheDir, "capture").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File.createTempFile("sketch-", ".jpg", dir)
    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
} catch (e: IOException) {
    Log.e(TAG, "Could not create a file for the camera", e)
    null
} catch (e: IllegalArgumentException) {
    // Thrown when the provider paths do not cover the directory.
    Log.e(TAG, "FileProvider is not configured for the capture directory", e)
    null
}
