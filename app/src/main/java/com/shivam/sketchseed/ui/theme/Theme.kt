package com.shivam.sketchseed.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightScheme = lightColorScheme(
    primary = SeedGreen,
    onPrimary = Color.White,
    primaryContainer = SeedGreenLight,
    onPrimaryContainer = Color.Black,
    secondary = ClayAccent,
    secondaryContainer = ClayAccentLight,
    background = PaperLight,
    surface = PaperLight,
    onBackground = Graphite,
    onSurface = Graphite,
)

private val DarkScheme = darkColorScheme(
    primary = SeedGreenLight,
    onPrimary = Color.Black,
    primaryContainer = SeedGreen,
    onPrimaryContainer = Color.White,
    secondary = ClayAccentLight,
    secondaryContainer = ClayAccent,
    background = PaperDark,
    surface = PaperDark,
)

@Composable
fun SketchSeedTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // The Pixel supplies a wallpaper-derived palette; prefer it when present.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SketchSeedTypography,
        content = content,
    )
}
