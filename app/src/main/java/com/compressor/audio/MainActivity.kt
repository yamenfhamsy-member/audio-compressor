package com.compressor.audio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.compressor.audio.ui.screens.HomeScreen
import com.compressor.audio.ui.theme.CompressorTheme
import com.compressor.audio.ui.theme.MonoTokens

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompressorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MonoTokens.Canvas,
                ) {
                    HomeScreen()
                }
            }
        }
    }
}
