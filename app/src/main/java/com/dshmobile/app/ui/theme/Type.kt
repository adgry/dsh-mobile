package com.dshmobile.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Code and token counters read better monospaced; everything else uses the platform font. */
val MonoFamily: FontFamily = FontFamily.Monospace

private val defaults = Typography()

val AppTypography: Typography = defaults.copy(
    headlineSmall = defaults.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = defaults.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = defaults.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    // Chat transcripts are read in long stretches; a little extra leading helps a lot.
    bodyLarge = defaults.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = defaults.bodyMedium.copy(fontSize = 14.5.sp, lineHeight = 22.sp),
    labelSmall = defaults.labelSmall.copy(letterSpacing = 0.4.sp),
)

val CodeTextStyle: TextStyle = TextStyle(
    fontFamily = MonoFamily,
    fontSize = 13.sp,
    lineHeight = 19.sp,
)
