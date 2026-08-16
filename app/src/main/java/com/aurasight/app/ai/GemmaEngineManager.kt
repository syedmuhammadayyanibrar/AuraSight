package com.aurasight.app.ai
import com.google.ai.edge.litertlm.ToolSet

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.flow.Flow

/**
 * Singleton wrapper around LiteRT-LM Engine for Gemma 3n E2B.
 * Owns model lifecycle. Call initialize() once from a background coroutine
 * (Application.onCreate or a splash/loading screen) — never on main thread,
 * model load can take up to ~10s.
 */
object GemmaEngineManager {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    private const val SYSTEM_PROMPT = """
        You are AuraSight, a voice assistant for a blind shopkeeper in Pakistan.
        Speak only in Urdu. Keep replies short (1-2 sentences), spoken-language style.
        CRITICAL: NEVER guess data! You MUST ALWAYS use the provided tools:
        1. CART: For items/prices/bill, ALWAYS use addItemToCart, getRunningTotal, or getCartContents.
        2. KHATA: For Udhaar/Jama, ALWAYS use addKhataEntry, getKhataBalance, or listKhataCustomers.
        3. CAMERA: To identify currency or describe the scene ("یہ کیا ہے", "کیمرہ کھولو"), ALWAYS use identifyCurrency or describeScene.
        4. NAVIGATION: For sighted observers, if asked to open Khata or Cart tabs, use navigateTo.
        WARNING: NEVER use navigateTo for the Camera! If the user says "Open Camera", you MUST use describeScene to take a picture for them.
    """

    /** Call once. Model path = bundled .litertlm asset extracted to filesDir. */
    suspend fun initialize(context: Context, modelPath: String, toolSets: List<ToolSet>) {
        check(engine == null) { "GemmaEngineManager already initialized" }

        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = Backend.CPU(numOfThreads = 4), // GPU() once confirmed stable on target 4GB devices
            cacheDir = context.cacheDir.path,
        )
        val newEngine = Engine(engineConfig)
        newEngine.initialize()
        engine = newEngine

        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(SYSTEM_PROMPT.trimIndent()),
            samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.6),
            tools = toolSets.map { tool(it) },
        )
        conversation = newEngine.createConversation(conversationConfig)
    }

    /** Streaming ask — preferred for voice UX (start TTS on first tokens). */
    fun askStreaming(text: String): Flow<com.google.ai.edge.litertlm.Message> {
        val c = conversation ?: error("Not initialized")
        return c.sendMessageAsync(text)
    }

    /** Blocking ask — simpler for early MVP wiring / tests. */
    suspend fun ask(text: String): String {
        val c = conversation ?: error("Not initialized")
        return c.sendMessage(Contents.of(text)).toString()
    }

    fun shutdown() {
        conversation?.close()
        engine?.close()
        conversation = null
        engine = null
    }
}
