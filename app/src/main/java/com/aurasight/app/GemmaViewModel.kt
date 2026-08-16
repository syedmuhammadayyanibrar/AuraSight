package com.aurasight.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

data class ChatMessage(val role: String, val text: String)

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

    private val _chatHistory = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatHistory: StateFlow<List<ChatMessage>> = _chatHistory.asStateFlow()

    fun addMessage(role: String, text: String) {
        val current = _chatHistory.value.toMutableList()
        current.add(ChatMessage(role, text))
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

    override suspend fun requestCameraAction(action: String): String {
        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
        cameraResultDeferred = deferred
        pendingCameraAction.value = action
        return deferred.await()
    }

    /** True once initialize() has been called, so we don't double-init */
    private var initStarted = false

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
                val modelPath = ModelAssetExtractor.ensureModelExtracted(getApplication())

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
                    modelPath = modelPath,
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
    }

    /** Send a message to Gemma. Only call when state == Ready. */
    suspend fun ask(text: String): String = GemmaEngineManager.ask(text)
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

