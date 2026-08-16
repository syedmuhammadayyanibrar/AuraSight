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
        Never state a price, total, or change amount yourself — always use the
        provided tools for any calculation. Confirm with the user before any
        action that changes stock or money is finalized.
    """

    /** Call once. Model path = bundled .litertlm asset extracted to filesDir. */
    suspend fun initialize(context: Context, modelPath: String, toolSet: ToolSet) {
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
            tools = listOf(tool(toolSet)),
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
