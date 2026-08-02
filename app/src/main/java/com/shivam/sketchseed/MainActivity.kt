package com.shivam.sketchseed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.shivam.sketchseed.ui.SketchSeedNavHost
import com.shivam.sketchseed.ui.theme.SketchSeedTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val photoStore = (application as SketchSeedApplication).container.photoStore

        setContent {
            SketchSeedTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SketchSeedNavHost(resolvePhoto = photoStore::resolve)
                }
            }
        }
    }
}
