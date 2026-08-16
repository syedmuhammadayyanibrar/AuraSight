package com.aurasight.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.aurasight.app.GemmaViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.launch
import android.speech.tts.TextToSpeech
import java.util.Locale

enum class CameraState { IDLE, ANALYZING, DESCRIBING, DONE }

/**
 * Scene description screen:
 * Live camera preview → tap "دیکھیں" → ML Kit labels objects →
 * labels sent to Gemma → Gemma describes in Urdu → TTS speaks it
 */
@Composable
fun CameraScreen(viewModel: GemmaViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // ── Camera permission ─────────────────────────────────────────────────────
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasCameraPermission = it }

    // ── TTS ───────────────────────────────────────────────────────────────────
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        lateinit var t: TextToSpeech
        t = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = t.setLanguage(Locale("ur", "PK"))
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    t.language = Locale.getDefault()
                }
            }
        }
        tts = t
        onDispose { t.stop(); t.shutdown() }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    var cameraState by remember { mutableStateOf(CameraState.IDLE) }
    var description by remember { mutableStateOf("") }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    // ── UI ────────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
    ) {
        if (!hasCameraPermission) {
            // Permission screen
            Column(
                modifier = Modifier.fillMaxSize().padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("📷", fontSize = 56.sp)
                Spacer(Modifier.height(16.dp))
                Text("کیمرہ اجازت ضروری ہے", fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, color = Color(0xFFE6EDF3),
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB))
                ) { Text("اجازت دیں →", fontSize = 16.sp) }
            }
            return
        }

        // ── CameraX preview ───────────────────────────────────────────────────
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    imageCapture = capture
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Bottom overlay ────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xE6000000))
                    )
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Description bubble
            if (description.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0D419D).copy(alpha = 0.85f))
                        .padding(16.dp)
                ) {
                    Text(description, fontSize = 15.sp,
                        color = Color(0xFF79C0FF), lineHeight = 22.sp)
                }
            }

            // Status text
            Text(
                text = when (cameraState) {
                    CameraState.IDLE       -> "سامنے کا رخ کریں اور دبائیں"
                    CameraState.ANALYZING  -> "تصویر کا تجزیہ ہو رہا ہے…"
                    CameraState.DESCRIBING -> "AuraSight بتا رہا ہے…"
                    CameraState.DONE       -> "دوبارہ دیکھنے کے لیے دبائیں"
                },
                fontSize = 14.sp, color = Color(0xFF8B949E)
            )

            // Describe button
            Button(
                onClick = {
                    if (cameraState == CameraState.IDLE || cameraState == CameraState.DONE) {
                        cameraState = CameraState.ANALYZING
                        description = ""
                        val ic = imageCapture ?: return@Button
                        ic.takePicture(
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(proxy: ImageProxy) {
                                    val image = InputImage.fromMediaImage(
                                        proxy.image!!, proxy.imageInfo.rotationDegrees
                                    )
                                    val labeler = ImageLabeling.getClient(
                                        ImageLabelerOptions.Builder()
                                            .setConfidenceThreshold(0.65f).build()
                                    )
                                    labeler.process(image)
                                        .addOnSuccessListener { labels ->
                                            proxy.close()
                                            val labelText = labels.take(8)
                                                .joinToString(", ") { it.text }
                                            if (labelText.isEmpty()) {
                                                description = "کچھ نظر نہیں آیا"
                                                cameraState = CameraState.DONE
                                                return@addOnSuccessListener
                                            }
                                            cameraState = CameraState.DESCRIBING
                                            scope.launch {
                                                try {
                                                    val prompt = "Camera sees: $labelText. Describe this briefly in Urdu in 1-2 sentences for a blind person."
                                                    description = viewModel.ask(prompt)
                                                    tts?.speak(description, TextToSpeech.QUEUE_FLUSH, null, "desc")
                                                    cameraState = CameraState.DONE
                                                } catch (e: Exception) {
                                                    description = "خرابی: ${e.message}"
                                                    cameraState = CameraState.DONE
                                                }
                                            }
                                        }
                                        .addOnFailureListener {
                                            proxy.close()
                                            description = "تجزیہ ناکام"
                                            cameraState = CameraState.DONE
                                        }
                                }
                                override fun onError(e: ImageCaptureException) {
                                    description = "کیمرہ خرابی: ${e.message}"
                                    cameraState = CameraState.DONE
                                }
                            }
                        )
                    }
                },
                enabled = cameraState == CameraState.IDLE || cameraState == CameraState.DONE,
                modifier = Modifier.size(80.dp).clip(CircleShape),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (cameraState) {
                        CameraState.IDLE, CameraState.DONE -> Color(0xFF1F6FEB)
                        else -> Color(0xFF6E7681)
                    }
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = when (cameraState) {
                        CameraState.ANALYZING, CameraState.DESCRIBING -> "⏳"
                        else -> "👁"
                    },
                    fontSize = 32.sp
                )
            }
        }
    }
}
