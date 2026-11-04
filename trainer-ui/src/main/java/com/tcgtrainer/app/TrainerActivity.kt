package com.tcgtrainer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

class TrainerActivity : ComponentActivity() {
    private var requestedDeckId by androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedDeckId = intent?.getStringExtra("selectedDeckId")?.takeIf { it.isNotBlank() }
        val vm = androidx.lifecycle.ViewModelProvider(this)[GameViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF7DE9DC), onPrimary = Color(0xFF073830),
                secondary = Color(0xFFF2BD67), background = Color(0xFF080F1C),
                surface = Color(0xFF142136), surfaceContainer = Color(0xFF1D2B41)
            )) {
                CompositionLocalProvider(LocalTextStyle provides TextStyle(fontSize = 14.sp)) {
                    TrainerScreen(vm, requestedDeckId)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedDeckId = intent.getStringExtra("selectedDeckId")?.takeIf { it.isNotBlank() }
        val vm = androidx.lifecycle.ViewModelProvider(this)[GameViewModel::class.java]
        vm.refreshSyncedDecks()
    }

    override fun onResume() {
        super.onResume()
        val vm = androidx.lifecycle.ViewModelProvider(this)[GameViewModel::class.java]
        vm.refreshSyncedDecks()
    }
}
