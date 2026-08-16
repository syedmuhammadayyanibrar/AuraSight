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

    // MVP system prompt: keep short, every token costs latency on-device.
    private const val SYSTEM_PROMPT = """
        You are AuraSight, a voice assistant for a blind shopkeeper in Pakistan.
        Speak only in Urdu. Keep replies short (1-2 sentences), spoken-language style.
        CRITICAL: NEVER guess anything! You MUST ALWAYS use the provided tools:
        - For cart items, prices, or totals: use addItemToCart, getRunningTotal, getCartContents.
        - For Khata (Udhaar/Jama): use addKhataEntry, getKhataBalance, listKhataCustomers.
        - To identify currency notes (e.g. "یہ کتنے کا نوٹ ہے"): use identifyCurrency.
        - To see what is in front of the camera (e.g. "سامنے کیا ہے"): use describeScene.
        Do not just chat. Use the tools!
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
