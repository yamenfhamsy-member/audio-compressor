package com.compressor.audio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compressor.audio.ui.theme.MonoTokens

/**
 * Sharp primary button: bone fill, canvas text, radius 0. Hover = white.
 * Secondary = steel surface + 1px blade border. No gradients, no pills, no shadows.
 */
@Composable
fun VinlandButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        !enabled -> MonoTokens.Steel
        primary -> MonoTokens.Bone
        else -> MonoTokens.Steel
    }
    val fg = when {
        !enabled -> MonoTokens.Muted
        primary -> MonoTokens.Canvas
        else -> MonoTokens.Bone
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(0.dp))
            .background(bg)
            .then(if (!primary && enabled) Modifier.border(1.dp, MonoTokens.BorderBlade) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            letterSpacing = 1.sp,
            color = fg,
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = MonoTokens.Ash,
    )
}

/** 2px hairline progress bar. White fill on steel track. No rounded pills. */
@Composable
fun VinlandProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(MonoTokens.Steel),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(2.dp)
                .background(MonoTokens.Bone),
        )
    }
}

/** Segmented preset selector: sharp boxes, active = bone fill. */
@Composable
fun PresetSegment(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MonoTokens.BorderBlade),
    ) {
        options.forEachIndexed { i, name ->
            val active = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (active) MonoTokens.Bone else MonoTokens.Iron)
                    .clickable { onSelect(i) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.uppercase(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp,
                    color = if (active) MonoTokens.Canvas else MonoTokens.Ash,
                )
            }
        }
    }
}
