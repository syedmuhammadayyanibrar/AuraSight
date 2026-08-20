package com.aurasight.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.aurasight.app.ai.AppDatabase
import com.aurasight.app.ai.CartCalculator
import com.aurasight.app.ai.CartEntry
import com.aurasight.app.ai.CartToolSet
import com.aurasight.app.ai.CameraActionDelegate
import com.aurasight.app.ai.CameraToolSet
import com.aurasight.app.ai.KhataToolSet
import com.aurasight.app.ai.KhataSummary
import com.aurasight.app.ai.NavigationActionDelegate
import com.aurasight.app.ai.NavigationToolSet
import com.aurasight.app.ai.GemmaEngineManager
import com.aurasight.app.ai.ModelAssetExtractor
import com.aurasight.app.ui.AppTab
import com.aurasight.app.ai.RealCartCalculator
import com.aurasight.app.ai.WhisperEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

data class ChatMessage(val role: String, val text: String, val imageBitmap: android.graphics.Bitmap? = null)

/**
 * Manages the Gemma model lifecycle and loading state.
 *
 * Strategy:
 *  - EXTRACTING  : first-run only, copies the .litertlm file out of the APK (can be slow for ~2 GB)
 *  - LOADING     : warm-up phase, Engine.initialize() (~5–10 s on CPU)
 *  - READY       : engine is hot, the main UI becomes interactive
 *  - ERROR       : something failed — surface the message to the user
 *
 * The ViewModel survives configuration changes (rotation, etc.) so the
 * expensive init only runs once per process lifetime.
 */
class GemmaViewModel(application: Application) : AndroidViewModel(application), CameraActionDelegate, NavigationActionDelegate {

    sealed class State {
        /** Idle — engine not yet requested. Show main UI. */
        object Idle : State()
        /** First-run: locating/copying model file */
        data class Extracting(val statusText: String = "ماڈل تیار ہو رہا ہے…") : State()
        /** Engine.initialize() running */
        data class Loading(val statusText: String = "AI شروع ہو رہی ہے…") : State()
        /** Ready — Gemma is hot and accepting queries */
        object Ready : State()
        /** Something went wrong */
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // Concurrency and AI Processing State
    val isProcessing = MutableStateFlow(false)
    private val askMutex = Mutex()

    // Centralized Text-To-Speech
    private var tts: TextToSpeech? = null

    private fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(getApplication()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Urdu Language
                val locale = Locale("ur", "PK")
                val result = tts?.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("AuraSight/TTS", "Urdu not supported or missing data")
                } else {
                    // Slow down slightly for better comprehension
                    tts?.setSpeechRate(0.9f)
                }
            } else {
                Log.e("AuraSight/TTS", "TTS Initialization failed")
            }
        }
    }

    private val _chatHistory = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatHistory: StateFlow<List<ChatMessage>> = _chatHistory.asStateFlow()

    var latestCameraBitmap: android.graphics.Bitmap? = null

    fun addMessage(role: String, text: String, bitmap: android.graphics.Bitmap? = null) {
        val current = _chatHistory.value.toMutableList()
        current.add(ChatMessage(role, text, bitmap))
        _chatHistory.value = current
    }

    /** True once WhisperEngine has been loaded successfully. VoiceScreen reads this. */
    var whisperReady by mutableStateOf(false)
        private set

    private var cartCalculator: CartCalculator? = null
    val cartEntries: StateFlow<List<CartEntry>>
        get() = cartCalculator?.entries ?: MutableStateFlow(emptyList())
    val cartTotal: StateFlow<Double>
        get() = cartCalculator?.totalState ?: MutableStateFlow(0.0)

    // Khata State
    val khataSummaries: StateFlow<List<KhataSummary>> = AppDatabase.getInstance(getApplication()).khataDao().getKhataSummaries()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Camera Integration State for UI to observe
    val pendingCameraAction = MutableStateFlow<String?>(null)
    var cameraResultDeferred: kotlinx.coroutines.CompletableDeferred<String>? = null

    // Navigation State for UI to observe
    val currentTab = MutableStateFlow(AppTab.VOICE)

    var pendingCameraContext: String? = null

    override suspend fun requestCameraAction(action: String): String {
        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
        cameraResultDeferred = deferred
        pendingCameraAction.value = action
        return kotlinx.coroutines.withTimeoutOrNull(15_000L) {
            deferred.await()
        } ?: run {
            cameraResultDeferred = null
            pendingCameraAction.value = null
            "Camera action timed out"
        }
    }

    /** True once initialize() has been called, so we don't double-init */
    private var initStarted = false
    
    // Hardware triggers
    val hardwareMicTrigger = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun processHardwareCameraTrigger() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (isProcessing.value) return@launch
            isProcessing.value = true
            try {
                speakStatus("تصویر لے رہا ہوں")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    navigateTo("CAMERA")
                }
                val imageResult = requestCameraAction("SCENE")
                val promptText = "User asked: 'سامنے کیا ہے'. Image shows: $imageResult. Describe this briefly in Urdu."
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    navigateTo("VOICE")
                }
                askAndSpeak(promptText)
            } catch (e: Exception) {
                // Ignore or handle
            } finally {
                isProcessing.value = false
            }
        }
    }

    /**
     * Call this lazily — only when the user first tries to speak.
     * Safe to call multiple times; subsequent calls are no-ops.
     */
    fun ensureInitialized() {
        if (initStarted) return
        initStarted = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Step 1 — Extract model if this is the first run
                _state.value = State.Extracting("ماڈل فائل چیک ہو رہی ہے…")


                // Step 2 — Initialize the engine
                _state.value = State.Loading("AI دماغ لوڈ ہو رہا ہے…")
                val db = AppDatabase.getInstance(getApplication())
                cartCalculator = RealCartCalculator(db.itemDao())
                
                val toolSets = listOf(
                    CartToolSet(cartCalculator!!),
                    CameraToolSet(this@GemmaViewModel),
                    KhataToolSet(db.khataDao()),
                    NavigationToolSet(this@GemmaViewModel)
                )
                
                GemmaEngineManager.initialize(
                    context = getApplication(),
                    toolSets = toolSets
                )

                // Step 3 — Initialize offline Whisper STT (optional — skips if files absent)
                _state.value = State.Loading("آواز پہچان لوڈ ہو رہی ہے…")
                try {
                    WhisperEngine.initialize(
                        encoderPath = ModelAssetExtractor.whisperEncoderPath(getApplication()),
                        decoderPath = ModelAssetExtractor.whisperDecoderPath(getApplication()),
                        tokensPath  = ModelAssetExtractor.whisperTokensPath(getApplication())
                    )
                    whisperReady = true
                } catch (_: Exception) {
                    // Model not on device yet — VoiceScreen will warn the user
                    whisperReady = false
                }

                // Step 4 — Done
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    initTts()
                }
                _state.value = State.Ready

            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "نامعلوم خرابی")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        GemmaEngineManager.shutdown()
        WhisperEngine.shutdown()
        tts?.stop()
        tts?.shutdown()
    }

    /** Send a message to Gemma. Only call when state == Ready. */
    suspend fun ask(text: String): String {
        return askMutex.withLock {
            GemmaEngineManager.ask(text)
        }
    }

    /** 
     * Core architectural rule: ALL final AI replies must be spoken through this function.
     * Includes a concurrency lock to prevent LiteRT-LM "Session not prefilled yet" errors.
     */
    suspend fun askAndSpeak(text: String, addToHistory: Boolean = true): String {
        isProcessing.value = true
        try {
            // ask() already acquires the lock
            val response = ask(text)
            if (addToHistory) {
                addMessage("ai", response, latestCameraBitmap)
                latestCameraBitmap = null
            }
            tts?.speak(response, TextToSpeech.QUEUE_FLUSH, null, "reply")
            return response
        } finally {
            isProcessing.value = false
        }
    }

    /** Intercept voice commands to trigger camera directly, bypassing one LLM pass. */
    fun processVoiceCommand(text: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (isProcessing.value) return@launch
            isProcessing.value = true
            try {
                val sceneTriggers = listOf("یہ کیا ہے", "کیا ہے یہ", "سامنے کیا ہے")
                val currencyTriggers = listOf("نوٹ", "کتنے کا نوٹ", "کرنسی")
                
                var promptText = text
                var cameraAction = ""
                
                if (pendingCameraContext != null) {
                    promptText = "Image context: $pendingCameraContext. User asked: '$text'. Answer the user's question briefly in Urdu."
                    pendingCameraContext = null
                    addMessage("user", text)
                } else if (sceneTriggers.any { text.contains(it) }) {
                    cameraAction = "SCENE"
                } else if (currencyTriggers.any { text.contains(it) }) {
                    cameraAction = "CURRENCY"
                }
                
                if (cameraAction.isEmpty() && pendingCameraContext == null) {
                    addMessage("user", text)
                }
                
                if (cameraAction.isNotEmpty()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        navigateTo("CAMERA")
                    }
                    val imageResult = requestCameraAction(cameraAction)
                    promptText = "User asked: '$text'. Image shows: $imageResult. ${if (cameraAction == "SCENE") "Describe this briefly in Urdu." else "Tell them the amount in Urdu."}"
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        navigateTo("VOICE")
                    }
                }
                
                askAndSpeak(promptText)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onError(e.message ?: "Error processing command")
                }
            } finally {
                isProcessing.value = false
            }
        }
    }

    /** Speak an interstitial UI cue (like "کیمرہ سامنے رکھیں") without invoking the AI engine. */
    fun speakStatus(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "status")
    }
    // ── Navigation Delegate ───────────────────────────────────────────────────
    override fun navigateTo(tab: String) {
        val appTab = try {
            AppTab.valueOf(tab)
        } catch (e: Exception) {
            return
        }
        currentTab.value = appTab
    }
}

