package com.compressor.audio.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Vinland Design System adapted to strict mono gray.
 * Same geometry as vinland-design-system/tokens.md, blood/wheat removed.
 * Canvas #08090B, surfaces differ by 1 step, 1px hairline borders, radii 0/2/4.
 */
object MonoTokens {
    val Canvas = Color(0xFF08090B)
    val Iron = Color(0xFF111318)
    val Steel = Color(0xFF181B22)
    val Raised = Color(0xFF1F232E)

    val BorderBlade = Color(0xFF242833)
    val BorderSharp = Color(0xFF363B49)

    val Bone = Color(0xFFE6E4DD)
    val Ash = Color(0xFF828997)
    val Muted = Color(0xFF4B5160)
    val White = Color(0xFFFFFFFF)

    // Semantic: monochrome only. Success/error as light text on dim wash.
    val SuccessText = Color(0xFF9FD3AC)
    val SuccessWash = Color(0x249FD3AC)
    val ErrorText = Color(0xFFE86A72)
    val ErrorWash = Color(0x24E86A72)
}
