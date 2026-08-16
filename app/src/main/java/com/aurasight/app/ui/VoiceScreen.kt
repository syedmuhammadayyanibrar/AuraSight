package com.aurasight.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aurasight.app.GemmaViewModel
import com.aurasight.app.ai.WhisperEngine
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
@Composable
@SuppressLint("MissingPermission")   // permission checked at runtime before AudioRecord.startRecording()
fun VoiceScreen(viewModel: GemmaViewModel) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // ── TTS setup ─────────────────────────────────────────────────────────────
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        lateinit var t: TextToSpeech
        t = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = t.setLanguage(Locale("ur", "PK"))
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    t.language = Locale.getDefault()
                    Log.w("AuraSight/TTS", "Urdu pack missing — using ${t.language}")
                } else {
                    Log.d("AuraSight/TTS", "TTS ready — ur-PK")
                }
                tts = t   // ← set ONLY after init succeeds (fixes silent-TTS race)
            } else {
                Log.e("AuraSight/TTS", "TTS init failed, status=$status")
            }
        }
        onDispose { t.stop(); t.shutdown() }
    }

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

            viewModel.addMessage("user", text)
            Log.d("AuraSight/STT", "Whisper → '$text'")

            try {
                val response = withContext(Dispatchers.IO) { viewModel.ask(text) }
                viewModel.addMessage("ai", response)
                voiceState = VoiceState.RESPONDING
                Log.d("AuraSight/TTS", "speak() → '${response.take(80)}'")
                if (tts != null) {
                    tts!!.speak(response, TextToSpeech.QUEUE_FLUSH, null, "reply")
                } else {
                    Log.w("AuraSight/TTS", "speak() skipped — TTS not ready yet")
                }
            } catch (e: Exception) {
                errorText = e.message ?: "Gemma خرابی"
                voiceState = VoiceState.IDLE
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
    ) {
        if (!viewModel.whisperReady) {
            Box(modifier = Modifier.padding(16.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF6E1B1B))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("آف لائن آواز ماڈل نہیں ملا", fontSize = 14.sp,
                        color = Color(0xFFFF7B72), fontWeight = FontWeight.Bold)
                    Text(
                        "adb push small-encoder.int8.onnx small-decoder.int8.onnx small-tokens.txt /sdcard/\nپھر ایپ دوبارہ کھولیں",
                        fontSize = 12.sp, color = Color(0xFFFFB3B3), lineHeight = 18.sp
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
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
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
                        bgColor = if (isUser) Color(0xFF238636) else Color(0xFF21262D),
                        textColor = Color(0xFFE6EDF3)
                    )
                }
            }
        }

        if (errorText.isNotEmpty()) {
            Text(
                errorText, 
                fontSize = 13.sp, 
                color = Color(0xFFFF7B72), 
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }

        // Bottom Input Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when (voiceState) {
                        VoiceState.IDLE       -> if (hasMicPermission) "بولنے کے لیے دبائیں" else "مائیک کی اجازت دیں"
                        VoiceState.LISTENING  -> "سن رہا ہوں… (روکنے کے لیے دبائیں)"
                        VoiceState.THINKING   -> "سوچ رہا ہوں…"
                        VoiceState.RESPONDING -> "جواب:"
                    },
                    fontSize = 14.sp,
                    color = Color(0xFF8B949E),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                val btnScale  = if (voiceState == VoiceState.LISTENING) pulseScale else 1f
                val btnColor  = when (voiceState) {
                    VoiceState.IDLE       -> Color(0xFF1F6FEB)
                    VoiceState.LISTENING  -> Color(0xFFCF222E)
                    VoiceState.THINKING   -> Color(0xFF6E7681)
                    VoiceState.RESPONDING -> Color(0xFF1A7F37)
                }

                Button(
                    onClick = {
                        when (voiceState) {
                            VoiceState.LISTENING  -> stopAndTranscribe()
                            VoiceState.IDLE,
                            VoiceState.RESPONDING -> startRecording()
                            VoiceState.THINKING   -> {}
                        }
                    },
                    enabled  = voiceState != VoiceState.THINKING,
                    modifier = Modifier.size(72.dp).scale(btnScale).clip(CircleShape),
                    colors   = ButtonDefaults.buttonColors(containerColor = btnColor),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = when (voiceState) {
                            VoiceState.LISTENING -> "⏹"
                            VoiceState.THINKING  -> "⏳"
                            else                 -> "🎤"
                        },
                        fontSize = 32.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ResponseBubble(label: String, text: String, bgColor: Color, textColor: Color) {
    Column(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(
                topStart = 16.dp, 
                topEnd = 16.dp, 
                bottomStart = if (label == "AuraSight:") 4.dp else 16.dp, 
                bottomEnd = if (label == "آپ:") 4.dp else 16.dp
            ))
            .background(bgColor)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, fontSize = 11.sp, color = Color.White.copy(alpha=0.6f), fontWeight = FontWeight.SemiBold)
        Text(text, fontSize = 15.sp, color = textColor, lineHeight = 22.sp)
    }
}
