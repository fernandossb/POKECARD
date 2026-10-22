package com.tcgtrainer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

class TrainerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = androidx.lifecycle.ViewModelProvider(this)[GameViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF7DE9DC), onPrimary = Color(0xFF073830),
                secondary = Color(0xFFF2BD67), background = Color(0xFF080F1C),
                surface = Color(0xFF142136), surfaceContainer = Color(0xFF1D2B41)
            )) {
                CompositionLocalProvider(LocalTextStyle provides TextStyle(fontSize = 14.sp)) {
                    TrainerScreen(vm)
                }
            }
        }
    }
}
