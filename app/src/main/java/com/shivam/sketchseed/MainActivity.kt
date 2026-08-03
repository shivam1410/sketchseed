package com.shivam.sketchseed

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.shivam.sketchseed.ui.SketchSeedNavHost
import com.shivam.sketchseed.ui.theme.SketchSeedTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as SketchSeedApplication).container

        setContent {
            SketchSeedTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AskForNotificationsOnce(needed = !container.reminders.permitted)
                    SketchSeedNavHost(resolvePhoto = container.photoStore::resolve)
                }
            }
        }
    }
}

/**
 * Asks for notification permission, at most once per launch.
 *
 * Reminders default to on, so the prompt appears on first open rather than being
 * buried behind the Settings toggle — a reminder nobody was asked about is a
 * reminder that silently never arrives.
 *
 * Declining costs only the reminders. Android itself stops showing the dialog
 * after two refusals, so this cannot become nagging.
 */
@Composable
private fun AskForNotificationsOnce(needed: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Either way the app carries on; Settings reports the outcome. */ }

    LaunchedEffect(needed) {
        if (needed) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
