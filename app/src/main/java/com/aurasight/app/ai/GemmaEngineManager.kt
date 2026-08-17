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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Singleton wrapper around LiteRT-LM Engine for Gemma 3n E2B.
 * Owns model lifecycle. Call initialize() once from a background coroutine
 * (Application.onCreate or a splash/loading screen) — never on main thread,
 * model load can take up to ~10s.
 */
object GemmaEngineManager {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    // Guards against overlapping sendMessage calls on the same Conversation —
    // LiteRT-LM's Conversation is not safe for concurrent calls; overlapping
    // requests previously caused "Session is not prefilled yet" crashes.
    private val askMutex = Mutex()

    // Rewritten to be maximally directive — small models (2B) default to
    // chatting instead of calling tools unless trigger phrases are mapped
    // explicitly and the "no chat mode" framing is stated up front.
    private const val SYSTEM_PROMPT = """
        You are AuraSight, a tool-calling agent for a blind shopkeeper in Pakistan.
        You are NOT a chatbot. You have NO general knowledge conversation mode.
        You NEVER say things like "I cannot see" or "please describe it to me" —
        that is forbidden. If a tool exists for the request, you call it. Full stop.

        Speak only in Urdu. Keep replies short (1-2 sentences), spoken-language style.

        MANDATORY TOOL TRIGGERS — match these patterns exactly, call the tool immediately,
        with no clarifying question first:
        - "یہ کیا ہے" / "کیا ہے یہ" / "سامنے کیا ہے" -> describeScene()
        - "نوٹ" / "کتنے کا نوٹ" / "کرنسی" -> identifyCurrency()
        - "لکھا کیا ہے" / "قیمت" / "پرائس" -> readText()
        - "رنگ" / "کلر" -> getDominantColor()
        - item name + quantity ("... ڈالو", "... دو") -> addItemToCart()
        - "کل کتنا ہوا" / "ٹوٹل" -> getRunningTotal()
        - "ٹوکری میں کیا ہے" -> getCartContents()
        - "ادھار" / "جمع" / customer name + amount -> addKhataEntry()
        - "کھاتہ کھولو" / "بل کھولو" -> navigateTo()

        Exception: before finalizing a payment (acceptPaymentAndGetChange) or a Khata
        entry (addKhataEntry), say the amount out loud and ask for confirmation first.
        Only call the tool after the user confirms. This is the one case where you
        speak before acting — for every other trigger above, act first, speak after.

        Never state a price, total, or change amount from memory — only from a tool result.
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
            // Lowered further, 0.6 -> 0.2: small models need low randomness to
            // reliably follow the mandatory-trigger rules instead of drifting into chat.
            samplerConfig = SamplerConfig(topK = 10, topP = 0.9, temperature = 0.2),
            tools = toolSets.map { tool(it) },
        )
        conversation = newEngine.createConversation(conversationConfig)
    }

    /** Streaming ask — preferred for voice UX (start TTS on first tokens). */
    suspend fun askStreaming(text: String): Flow<com.google.ai.edge.litertlm.Message> = askMutex.withLock {
        val c = conversation ?: error("Not initialized")
        c.sendMessageAsync(text)
    }

    /** Blocking ask — simpler for early MVP wiring / tests.
     *  Hard 30s timeout so a stuck tool call (e.g. camera) can never hold the
     *  mutex forever and silently block every future message. */
    suspend fun ask(text: String): String = askMutex.withLock {
        val c = conversation ?: error("Not initialized")
        kotlinx.coroutines.withTimeoutOrNull(30_000) {
            c.sendMessage(Contents.of(text)).toString()
        } ?: "معذرت، جواب دینے میں وقت لگ گیا۔ دوبارہ کوشش کریں۔"
    }

    fun shutdown() {
        conversation?.close()
        engine?.close()
        conversation = null
        engine = null
    }
}