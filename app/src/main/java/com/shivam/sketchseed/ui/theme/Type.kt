package com.shivam.sketchseed.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Default type, with the prompt itself pushed up to display size. The prompt is
 * the entire point of the screen, so it is the only thing shouting.
 */
val SketchSeedTypography = Typography().let { base ->
    base.copy(
        displayMedium = base.displayMedium.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.5).sp,
        ),
        displaySmall = base.displaySmall.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
        ),
        labelLarge = base.labelLarge.copy(letterSpacing = 0.4.sp),
    )
}

/** Small-caps-ish label used for the day counter above the prompt. */
val OverlineStyle = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 13.sp,
    letterSpacing = 1.6.sp,
)
