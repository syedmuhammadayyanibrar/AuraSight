package com.aurasight.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aurasight.app.GemmaViewModel
import com.aurasight.app.ai.WhisperEngine
import com.aurasight.app.ui.AppTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class VoiceState { IDLE, LISTENING, THINKING, RESPONDING }

private const val SAMPLE_RATE = 16_000

/**
 * Full voice interaction screen shown once Gemma is Ready.
 *
 * STT path (fully offline):
 *   AudioRecord (16 kHz mono PCM) → WhisperEngine (sherpa-onnx) → Gemma → TTS
 *
 * If WhisperEngine model files are not yet on-device, the user sees a
 * setup banner instead of the mic button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("MissingPermission")   // permission checked at runtime before AudioRecord.startRecording()
fun VoiceScreen(viewModel: GemmaViewModel) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    
    val isProcessing by viewModel.isProcessing.collectAsState()

    // ── State ─────────────────────────────────────────────────────────────────
    var voiceState   by remember { mutableStateOf(VoiceState.IDLE) }
    var spokenText   by remember { mutableStateOf("") }
    var gemmaResponse by remember { mutableStateOf("") }
    var errorText    by remember { mutableStateOf("") }

    // Recording internals — not Compose state (mutated on IO thread)
    val sampleBuffer = remember { ArrayList<Short>(SAMPLE_RATE * 30) }  // pre-alloc ~30 s
    var audioRecord  by remember { mutableStateOf<AudioRecord?>(null) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }

    // ── Mic permission ────────────────────────────────────────────────────────
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    // ── Start recording ───────────────────────────────────────────────────────
    fun startRecording() {
        if (isProcessing) return
        if (!hasMicPermission) { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO); return }

        errorText = ""; gemmaResponse = ""; spokenText = ""
        sampleBuffer.clear()
        voiceState = VoiceState.LISTENING

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4
        )
        audioRecord = recorder

        recordingJob = scope.launch(Dispatchers.IO) {
            recorder.startRecording()
            val buf = ShortArray(1024)
            try {
                while (isActive) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) repeat(read) { sampleBuffer.add(buf[it]) }
                }
            } finally {
                recorder.stop()
                recorder.release()
                audioRecord = null
            }
        }
    }

    val chatHistory by viewModel.chatHistory.collectAsState()

    // ── Stop recording → transcribe → ask Gemma ───────────────────────────────
    fun stopAndTranscribe() {
        voiceState = VoiceState.THINKING
        scope.launch {
            recordingJob?.cancelAndJoin()
            recordingJob = null

            val shorts  = sampleBuffer.toShortArray()
            val floats  = FloatArray(shorts.size) { shorts[it] / 32_768f }

            if (floats.size < SAMPLE_RATE / 2) {
                errorText = "بہت مختصر آواز — دوبارہ کوشش کریں"
                voiceState = VoiceState.IDLE
                return@launch
            }

            val text = try {
                withContext(Dispatchers.IO) { WhisperEngine.transcribe(floats) }
            } catch (e: Exception) {
                errorText = "Whisper خرابی: ${e.message}"
                voiceState = VoiceState.IDLE
                return@launch
            }

            if (text.isBlank()) {
                errorText = "کچھ سمجھ نہیں آیا — واضح بولیں"
                voiceState = VoiceState.IDLE
                return@launch
            }

            Log.d("AuraSight/STT", "Whisper → '$text'")
            
            voiceState = VoiceState.IDLE // Will be overridden by isProcessing in UI
            viewModel.processVoiceCommand(
                text = text,
                onSuccess = { voiceState = VoiceState.RESPONDING },
                onError = { errorMsg ->
                    errorText = errorMsg
                    voiceState = VoiceState.IDLE
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.hardwareMicTrigger.collect {
            when (voiceState) {
                VoiceState.LISTENING -> stopAndTranscribe()
                VoiceState.IDLE, VoiceState.RESPONDING -> startRecording()
                else -> {}
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse"
    )

    // ── UI ─────────────────────────────────────────────────────────────────────
    var expandedMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "AuraSight",
                        color = Color(0xFF2563EB), // text-blue-600
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 24.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(AuraIcons.Menu, contentDescription = "Menu", tint = Color(0xFF374151))
                    }
                },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(AuraIcons.Camera, contentDescription = "Camera", tint = Color(0xFF374151))
                    }
                    Box {
                        IconButton(onClick = { expandedMenu = true }) {
                            Icon(AuraIcons.MoreVert, contentDescription = "More", tint = Color(0xFF374151))
                        }
                        DropdownMenu(
                            expanded = expandedMenu,
                            onDismissRequest = { expandedMenu = false },
                            modifier = Modifier.background(Color.White)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Cart", color = Color(0xFF111827), fontWeight = FontWeight.SemiBold) },
                                onClick = { 
                                    expandedMenu = false
                                    viewModel.currentTab.value = AppTab.CART 
                                },
                                leadingIcon = { Icon(AuraIcons.Cart, contentDescription = null, tint = Color(0xFF2563EB)) }
                            )
                            HorizontalDivider(color = Color(0xFFE5E7EB))
                            DropdownMenuItem(
                                text = { Text("Khata", color = Color(0xFF111827), fontWeight = FontWeight.SemiBold) },
                                onClick = { 
                                    expandedMenu = false
                                    viewModel.currentTab.value = AppTab.KHATA 
                                },
                                leadingIcon = { Icon(AuraIcons.Book, contentDescription = null, tint = Color(0xFF16A34A)) }
                            )
                            HorizontalDivider(color = Color(0xFFE5E7EB))
                            DropdownMenuItem(
                                text = { Text("History", color = Color(0xFF111827), fontWeight = FontWeight.SemiBold) },
                                onClick = { expandedMenu = false }, // TODO: History Tab
                                leadingIcon = { Icon(AuraIcons.History, contentDescription = null, tint = Color(0xFF2563EB)) }
                            )
                            HorizontalDivider(color = Color(0xFFE5E7EB))
                            DropdownMenuItem(
                                text = { Text("Vision", color = Color(0xFF111827), fontWeight = FontWeight.SemiBold) },
                                onClick = { 
                                    expandedMenu = false
                                    viewModel.currentTab.value = AppTab.CAMERA
                                },
                                leadingIcon = { Icon(AuraIcons.Vision, contentDescription = null, tint = Color(0xFF16A34A)) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFF9FAFB) // bg-gray-50
                )
            )
        },
        containerColor = Color.White // bg-white
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.White)
        ) {
            if (!viewModel.whisperReady) {
                Box(modifier = Modifier.padding(16.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFFEE2E2)) // Light red for error
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("آف لائن آواز ماڈل نہیں ملا", fontSize = 14.sp,
                            color = Color(0xFF991B1B), fontWeight = FontWeight.Bold)
                        Text(
                            "adb push small-encoder.int8.onnx small-decoder.int8.onnx small-tokens.txt /sdcard/\nپھر ایپ دوبارہ کھولیں",
                            fontSize = 12.sp, color = Color(0xFFB91C1C), lineHeight = 18.sp
                        )
                    }
                }
                return@Column
            }

            // Chat History List
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp), // px-margin-edge
                contentPadding = PaddingValues(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(chatHistory.size) { index ->
                    val msg = chatHistory[index]
                    val isUser = msg.role == "user"
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        ResponseBubble(
                            label = if (isUser) "آپ:" else "AuraSight:",
                            text = msg.text,
                            bgColor = if (isUser) Color(0xFF16A34A) else Color(0xFFF3F4F6), // bg-green-600 vs bg-gray-100
                            textColor = if (isUser) Color.White else Color(0xFF111827), // text-white vs text-gray-900
                            imageBitmap = msg.imageBitmap
                        )
                    }
                }
            }

            if (errorText.isNotEmpty()) {
                Text(
                    errorText,
                    fontSize = 13.sp,
                    color = Color(0xFFDC2626), // text-red-600
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            }

            // Bottom Input Area (Orb Design)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(bottom = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                val btnScale = if (voiceState == VoiceState.LISTENING) pulseScale else 1f
                val borderColor = when (voiceState) {
                    VoiceState.LISTENING -> Color(0xFFDC2626) // Red
                    VoiceState.RESPONDING -> Color(0xFF16A34A) // Green
                    VoiceState.THINKING -> Color(0xFF9CA3AF) // Gray
                    else -> Color(0xFF3B82F6) // border-blue-500
                }
                
                val iconColor = when (voiceState) {
                    VoiceState.LISTENING -> Color(0xFFDC2626)
                    VoiceState.RESPONDING -> Color(0xFF16A34A)
                    VoiceState.THINKING -> Color(0xFF9CA3AF)
                    else -> Color(0xFF2563EB) // text-blue-600
                }

                Button(
                    onClick = {
                        when (voiceState) {
                            VoiceState.LISTENING -> stopAndTranscribe()
                            VoiceState.IDLE,
                            VoiceState.RESPONDING -> startRecording()
                            VoiceState.THINKING -> {}
                        }
                    },
                    enabled = voiceState != VoiceState.THINKING && !isProcessing,
                    modifier = Modifier
                        .size(96.dp) // w-24 h-24
                        .scale(btnScale)
                        .clip(CircleShape),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White), // bg-white
                    border = androidx.compose.foundation.BorderStroke(2.dp, borderColor), // border-2
                    contentPadding = PaddingValues(0.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    if (isProcessing || voiceState == VoiceState.THINKING) {
                        Text("⏳", fontSize = 36.sp, color = iconColor)
                    } else if (voiceState == VoiceState.LISTENING) {
                        Text("⏹", fontSize = 36.sp, color = iconColor)
                    } else {
                        Icon(AuraIcons.Mic, contentDescription = "Mic", modifier = Modifier.size(36.dp), tint = iconColor)
                    }
                }

                Text(
                    text = when {
                        isProcessing -> "سوچ رہا ہوں…"
                        voiceState == VoiceState.IDLE -> if (hasMicPermission) "بولنے کے لیے دبائیں" else "مائیک کی اجازت دیں"
                        voiceState == VoiceState.LISTENING -> "سن رہا ہوں… (روکنے کے لیے دبائیں)"
                        voiceState == VoiceState.THINKING -> "سوچ رہا ہوں…"
                        voiceState == VoiceState.RESPONDING -> "جواب:"
                        else -> ""
                    },
                    fontSize = 16.sp, // font-label-lg
                    color = iconColor, // text-blue-600
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomCenter) // absolute bottom-0
                )
            }
        }
    }
}

@Composable
private fun ResponseBubble(label: String, text: String, bgColor: Color, textColor: Color, imageBitmap: android.graphics.Bitmap? = null) {
    val isUser = bgColor == Color(0xFF16A34A)
    Column(
        modifier = Modifier
            .fillMaxWidth(0.85f) // max-w-[85%]
            .let { if (isUser) it.shadow(8.dp, RoundedCornerShape(12.dp)) else it } // shadow-lg for user
            .clip(RoundedCornerShape(12.dp)) // rounded-xl
            .background(bgColor)
            .padding(16.dp), // p-4
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        imageBitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Captured image",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }
        
        // Emulating HTML's "Product identified: Lipton Yellow Label 250g" bolding if it's the AI.
        // We will just print the text, but using the specified typography.
        Text(text, fontSize = 20.sp, color = textColor, lineHeight = 30.sp, fontWeight = FontWeight.Medium) // font-body-lg text-gray-900 / text-white
        
        // Timestamp
        val timeColor = if (isUser) Color.Transparent else Color(0xFF6B7280) // opacity-0 for user, text-gray-500 for AI
        val timeAlign = if (isUser) TextAlign.Right else TextAlign.Left
        Text(
            text = "12:42 PM", // Mock timestamp from HTML
            fontSize = 14.sp,
            color = timeColor,
            fontWeight = FontWeight.SemiBold,
            textAlign = timeAlign,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }
}
