package com.compressor.audio.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Vinland geometry meets the Pineapple brand.
 * Canvas stays near-black; the accent is pineapple flesh gold and leaf
 * green taken from the logo. Radii 0/2/4, 1px hairlines, no gradients.
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

    // Pineapple brand accents (from the logo).
    val Pineapple = Color(0xFFF5A301)
    val OnPineapple = Color(0xFF1A1206)
    val Leaf = Color(0xFF35A853)

    // Semantic: leaf green for success, warm red for errors.
    val SuccessText = Color(0xFF7BD88F)
    val SuccessWash = Color(0x247BD88F)
    val ErrorText = Color(0xFFE86A72)
    val ErrorWash = Color(0x24E86A72)
}
