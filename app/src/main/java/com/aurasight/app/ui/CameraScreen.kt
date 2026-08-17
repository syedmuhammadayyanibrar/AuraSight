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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
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
    // TTS is now centralized in GemmaViewModel.

    // ── State ─────────────────────────────────────────────────────────────────
    var cameraState by remember { mutableStateOf(CameraState.IDLE) }
    var description by remember { mutableStateOf("") }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val pendingAction by viewModel.pendingCameraAction.collectAsState()

    fun triggerCapture(action: String?, attempt: Int = 1) {
        if (cameraState != CameraState.IDLE && cameraState != CameraState.DONE && cameraState != CameraState.ANALYZING) return
        val ic = imageCapture ?: return
        cameraState = CameraState.ANALYZING
        description = ""
        
        ic.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(proxy: ImageProxy) {
                    val rawBitmap = proxy.toBitmap()
                    // Scale down to prevent exceeding max OpenGL texture size
                    val maxDim = 800f
                    val scale = kotlin.math.min(maxDim / rawBitmap.width, maxDim / rawBitmap.height)
                    val bitmap = if (scale < 1f) {
                        android.graphics.Bitmap.createScaledBitmap(
                            rawBitmap,
                            (rawBitmap.width * scale).toInt(),
                            (rawBitmap.height * scale).toInt(),
                            true
                        )
                    } else rawBitmap
                    viewModel.latestCameraBitmap = bitmap
                    val image = InputImage.fromMediaImage(proxy.image!!, proxy.imageInfo.rotationDegrees)
                    
                    if (action == "CURRENCY") {
                        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        recognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                proxy.close()
                                // Look for Pakistani note numbers
                                val denominations = listOf("10", "20", "50", "100", "500", "1000", "5000")
                                val words = visionText.text.split(Regex("\\s+"))
                                val found = words.filter { it in denominations }
                                
                                if (found.isNotEmpty()) {
                                    viewModel.cameraResultDeferred?.complete(found.joinToString(", "))
                                    viewModel.pendingCameraAction.value = null
                                    cameraState = CameraState.DONE
                                } else {
                                    if (attempt < 3) {
                                        scope.launch {
                                            viewModel.speakStatus("دوبارہ کوشش کریں")
                                            kotlinx.coroutines.delay(2000)
                                            triggerCapture(action, attempt + 1)
                                        }
                                    } else {
                                        viewModel.cameraResultDeferred?.complete("No clear numbers found.")
                                        viewModel.pendingCameraAction.value = null
                                        cameraState = CameraState.DONE
                                    }
                                }
                            }
                            .addOnFailureListener {
                                proxy.close()
                                if (attempt < 3) {
                                    scope.launch {
                                        viewModel.speakStatus("دوبارہ کوشش کریں")
                                        kotlinx.coroutines.delay(2000)
                                        triggerCapture(action, attempt + 1)
                                    }
                                } else {
                                    viewModel.cameraResultDeferred?.complete("Analysis failed")
                                    viewModel.pendingCameraAction.value = null
                                    cameraState = CameraState.DONE
                                }
                            }
                    } else {
                        // SCENE or MANUAL
                        val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.65f).build())
                        labeler.process(image)
                            .addOnSuccessListener { labels ->
                                proxy.close()
                                val labelText = labels.take(8).joinToString(", ") { it.text }
                                
                                if (action == "SCENE") {
                                    if (labelText.isEmpty()) {
                                        if (attempt < 3) {
                                            scope.launch {
                                                viewModel.speakStatus("دوبارہ کوشش کریں")
                                                kotlinx.coroutines.delay(2000)
                                                triggerCapture(action, attempt + 1)
                                            }
                                        } else {
                                            viewModel.cameraResultDeferred?.complete("Nothing clear seen.")
                                            viewModel.pendingCameraAction.value = null
                                            cameraState = CameraState.DONE
                                        }
                                    } else {
                                        viewModel.cameraResultDeferred?.complete(labelText)
                                        viewModel.pendingCameraAction.value = null
                                        cameraState = CameraState.DONE
                                    }
                                } else {
                                    // Manual trigger
                                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                    recognizer.process(image)
                                        .addOnSuccessListener { visionText ->
                                            val textFound = visionText.text.take(200)
                                            viewModel.pendingCameraContext = "Labels: $labelText. Text in image: $textFound"
                                            
                                            // Put picture in chat & navigate to Voice
                                            viewModel.addMessage("user", "", viewModel.latestCameraBitmap)
                                            viewModel.latestCameraBitmap = null
                                            viewModel.navigateTo("VOICE")
                                            cameraState = CameraState.IDLE
                                        }
                                        .addOnFailureListener {
                                            viewModel.pendingCameraContext = "Labels: $labelText. Text: (failed)"
                                            
                                            // Put picture in chat & navigate to Voice
                                            viewModel.addMessage("user", "", viewModel.latestCameraBitmap)
                                            viewModel.latestCameraBitmap = null
                                            viewModel.navigateTo("VOICE")
                                            cameraState = CameraState.IDLE
                                        }
                                }
                            }
                            .addOnFailureListener {
                                proxy.close()
                                if (action == "SCENE") {
                                    if (attempt < 3) {
                                        scope.launch {
                                            viewModel.speakStatus("دوبارہ کوشش کریں")
                                            kotlinx.coroutines.delay(2000)
                                            triggerCapture(action, attempt + 1)
                                        }
                                    } else {
                                        viewModel.cameraResultDeferred?.complete("Analysis failed")
                                        viewModel.pendingCameraAction.value = null
                                        cameraState = CameraState.DONE
                                    }
                                } else {
                                    viewModel.pendingCameraContext = "(Failed to analyze image)"
                                    viewModel.addMessage("user", "", viewModel.latestCameraBitmap)
                                    viewModel.latestCameraBitmap = null
                                    viewModel.navigateTo("VOICE")
                                    cameraState = CameraState.IDLE
                                }
                            }
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    if (action != null) {
                        viewModel.cameraResultDeferred?.complete("Camera error")
                        viewModel.pendingCameraAction.value = null
                    } else {
                        description = "کیمرہ خرابی: ${e.message}"
                    }
                    cameraState = CameraState.DONE
                }
            }
        )
    }

    LaunchedEffect(pendingAction, imageCapture) {
        if (pendingAction != null && imageCapture != null) {
            cameraState = CameraState.ANALYZING
            viewModel.speakStatus("کیمرہ سامنے رکھیں")
            kotlinx.coroutines.delay(3000)
            triggerCapture(pendingAction)
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
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
                    fontWeight = FontWeight.Bold, color = Color(0xFF111827),
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) { Text("اجازت دیں →", fontSize = 16.sp, color = Color.White) }
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
                        listOf(Color.Transparent, Color(0x66FFFFFF), Color.White)
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
                        .background(Color(0xFFEFF6FF).copy(alpha = 0.85f))
                        .padding(16.dp)
                ) {
                    Text(description, fontSize = 15.sp,
                        color = Color(0xFF2563EB), lineHeight = 22.sp)
                }
            }

            if (cameraState == CameraState.ANALYZING) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFE5E7EB).copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("AI تصویر دیکھ رہی ہے...", fontSize = 18.sp, color = Color(0xFF2563EB), fontWeight = FontWeight.Bold)
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
                fontSize = 14.sp, color = Color(0xFF4B5563)
            )

            // Describe button
            Button(
                onClick = { triggerCapture(null) },
                enabled = cameraState == CameraState.IDLE || cameraState == CameraState.DONE,
                modifier = Modifier.size(80.dp).clip(CircleShape),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (cameraState) {
                        CameraState.IDLE, CameraState.DONE -> Color(0xFF2563EB)
                        else -> Color(0xFF9CA3AF)
                    }
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = when (cameraState) {
                        CameraState.ANALYZING, CameraState.DESCRIBING -> "⏳"
                        else -> "📷"
                    },
                    fontSize = 32.sp,
                    color = Color.White
                )
            }
        }
    }
}
