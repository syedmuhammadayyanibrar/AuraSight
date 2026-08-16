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
    CART("🛒", "کھاتہ"),      // Cart
}

/**
 * Root navigation shown when Gemma is Ready.
 * Uses a premium TopAppBar for Camera/Cart, replacing the bottom tab bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigation(viewModel: GemmaViewModel) {
    var selectedTab by remember { mutableStateOf(AppTab.VOICE) }

    val pendingAction by viewModel.pendingCameraAction.collectAsState()
    LaunchedEffect(pendingAction) {
        if (pendingAction != null) {
            selectedTab = AppTab.CAMERA
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text("AuraSight", fontWeight = FontWeight.Bold, color = Color(0xFFE6EDF3), fontSize = 20.sp) 
                },
                navigationIcon = {
                    if (selectedTab != AppTab.VOICE) {
                        IconButton(onClick = { selectedTab = AppTab.VOICE }) {
                            Text("⬅️", fontSize = 20.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF161B22)
                ),
                actions = {
                    if (selectedTab != AppTab.CAMERA) {
                        IconButton(onClick = { selectedTab = AppTab.CAMERA }) {
                            Text("📷", fontSize = 20.sp)
                        }
                    }
                    if (selectedTab != AppTab.CART) {
                        IconButton(onClick = { selectedTab = AppTab.CART }) {
                            Text("🛒", fontSize = 20.sp)
                        }
                    }
                }
            )
        },
        containerColor = Color(0xFF0D1117)
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (selectedTab) {
                AppTab.VOICE  -> VoiceScreen(viewModel)
                AppTab.CAMERA -> CameraScreen(viewModel)
                AppTab.CART   -> CartScreen(viewModel)
            }
        }
    }
}
