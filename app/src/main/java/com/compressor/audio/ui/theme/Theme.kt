package com.compressor.audio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Scheme: ColorScheme = darkColorScheme(
    background = MonoTokens.Canvas,
    surface = MonoTokens.Iron,
    surfaceVariant = MonoTokens.Steel,
    onBackground = MonoTokens.Bone,
    onSurface = MonoTokens.Bone,
    onSurfaceVariant = MonoTokens.Ash,
    primary = MonoTokens.Bone,
    onPrimary = MonoTokens.Canvas,
    secondary = MonoTokens.Ash,
    outline = MonoTokens.BorderBlade,
    outlineVariant = MonoTokens.BorderSharp,
    error = MonoTokens.ErrorText,
)

// Chiseled geometry: 0 / 2 / 4 only. No pills anywhere.
private val VinlandShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(4.dp),
)

private val VinlandType = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = 2.sp,
        color = MonoTokens.Bone,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        color = MonoTokens.Bone,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = MonoTokens.Bone,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = MonoTokens.Bone,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = MonoTokens.Ash,
    ),
)

@Composable
fun CompressorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        shapes = VinlandShapes,
        typography = VinlandType,
        content = content,
    )
}
