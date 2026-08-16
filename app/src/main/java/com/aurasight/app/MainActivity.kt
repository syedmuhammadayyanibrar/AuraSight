package com.aurasight.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurasight.app.ui.MainNavigation
import com.aurasight.app.ui.SplashScreen
import com.aurasight.app.ui.theme.AuraSightTheme

class MainActivity : ComponentActivity() {

    private val gemmaViewModel: GemmaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // setContent is called ONCE only — never call it again in onResume
        setContent {
            AuraSightTheme(darkTheme = true, dynamicColor = false) {
                AppContent()
            }
        }
    }

    @Composable
    private fun AppContent() {
        // --- Permission gate (re-checked every time activity resumes) ---
        val hasPermission = remember { mutableStateOf(hasStoragePermission()) }

        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasPermission.value = hasStoragePermission()
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }

        if (!hasPermission.value) {
            PermissionScreen {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            return
        }

        // --- Normal app flow ---
        val gemmaState by gemmaViewModel.state.collectAsStateWithLifecycle()

        when (val state = gemmaState) {
            GemmaViewModel.State.Idle          -> MainScreen(gemmaViewModel)
            is GemmaViewModel.State.Extracting -> SplashScreen(statusText = state.statusText)
            is GemmaViewModel.State.Loading    -> SplashScreen(statusText = state.statusText)
            is GemmaViewModel.State.Error      -> ErrorScreen(state.message) { gemmaViewModel.ensureInitialized() }
            GemmaViewModel.State.Ready         -> MainNavigation(gemmaViewModel)
        }
    }

    private fun hasStoragePermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
}

// ── Permission request screen ──────────────────────────────────────────────────
@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D1117), Color(0xFF161B22)))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(40.dp)
        ) {
            Text("📁", fontSize = 56.sp)

            Text(
                text = "فائل رسائی ضروری ہے",   // "File access is required"
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE6EDF3),
                textAlign = TextAlign.Center
            )

            Text(
                text = "AuraSight کو /sdcard/ میں رکھے ہوئے AI ماڈل تک پہنچنے کے لیے\n\"تمام فائلوں تک رسائی\" کی اجازت چاہیے۔",
                // "AuraSight needs 'All files access' permission to reach the AI model in /sdcard/"
                fontSize = 14.sp,
                color = Color(0xFF8B949E),
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Text(
                text = "اگلی اسکرین پر AuraSight تلاش کریں اور اجازت دیں۔",
                // "On the next screen, find AuraSight and grant permission."
                fontSize = 13.sp,
                color = Color(0xFF484F58),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "اجازت دیں  →",    // "Grant permission →"
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ── Main screen ────────────────────────────────────────────────────────────────
@Composable
fun MainScreen(viewModel: GemmaViewModel) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D1117), Color(0xFF161B22)))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text("AuraSight", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE6EDF3))
            Text("بات کریں", fontSize = 16.sp, color = Color(0xFF8B949E))
            MicButton("🎤") { viewModel.ensureInitialized() }
            Text("مائیک کا بٹن دبائیں اور بولیں", fontSize = 13.sp, color = Color(0xFF484F58), textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun MicButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(100.dp).clip(CircleShape),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(label, fontSize = 40.sp)
    }
}

// ── Error screen ───────────────────────────────────────────────────────────────
@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text("⚠️", fontSize = 48.sp)
            Text("خرابی آ گئی", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF7B72))
            Text(message, fontSize = 13.sp, color = Color(0xFF8B949E), textAlign = TextAlign.Center)
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB))) {
                Text("دوبارہ کوشش کریں")
            }
        }
    }
}