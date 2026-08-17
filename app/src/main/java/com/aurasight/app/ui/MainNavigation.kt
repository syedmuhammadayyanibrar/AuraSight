package com.aurasight.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurasight.app.GemmaViewModel

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults

enum class AppTab(val icon: String, val label: String) {
    VOICE("🎤", "آواز"),      // Voice
    CAMERA("📷", "کیمرہ"),    // Camera
    CART("🛒", "بل"),         // Cart
    KHATA("📒", "کھاتہ")      // Khata (Ledger)
}

/**
 * Root navigation shown when Gemma is Ready.
 * Uses a premium TopAppBar for Camera/Cart, replacing the bottom tab bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigation(viewModel: GemmaViewModel) {
    val selectedTab by viewModel.currentTab.collectAsState()

    val pendingAction by viewModel.pendingCameraAction.collectAsState()
    LaunchedEffect(pendingAction) {
        if (pendingAction != null) {
            viewModel.currentTab.value = AppTab.CAMERA
        }
    }

    Scaffold(
        containerColor = Color.White
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (selectedTab) {
                AppTab.VOICE  -> VoiceScreen(viewModel)
                AppTab.CAMERA -> CameraScreen(viewModel)
                AppTab.CART   -> CartScreen(viewModel)
                AppTab.KHATA  -> KhataScreen(viewModel)
            }
        }
    }
}
