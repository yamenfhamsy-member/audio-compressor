package com.compressor.audio

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.compressor.audio.ui.screens.HomeScreen
import com.compressor.audio.ui.theme.CompressorTheme
import com.compressor.audio.ui.theme.MonoTokens

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompressorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MonoTokens.Canvas,
                ) {
                    var lang by remember { mutableStateOf(LocaleHelper.getLang(this)) }
                    HomeScreen(
                        lang = lang,
                        onToggleLang = {
                            val next = if (lang == LocaleHelper.AR) LocaleHelper.EN else LocaleHelper.AR
                            LocaleHelper.setLang(this, next)
                            lang = next
                            recreate()
                        },
                    )
                }
            }
        }
    }
}
