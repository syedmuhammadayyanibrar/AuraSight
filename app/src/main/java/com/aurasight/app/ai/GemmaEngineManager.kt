package com.aurasight.app.ai

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.FunctionDeclaration
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.defineFunction
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.Chat
import com.google.ai.client.generativeai.type.FunctionCallPart
import com.google.ai.client.generativeai.type.FunctionResponsePart
import com.google.ai.client.generativeai.type.TextPart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation
import kotlinx.coroutines.runBlocking
import kotlin.reflect.full.callSuspendBy

object GemmaEngineManager {
    private var chat: Chat? = null
    private val askMutex = Mutex()
    private val toolHandlers = mutableMapOf<String, suspend (Map<String, Any?>) -> String>()

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

        CRITICAL RULE: If the user provides an image with their request, DO NOT call describeScene(), readText(), or identifyCurrency(). Just analyze the provided image directly to answer their question.
        
        Never state a price, total, or change amount from memory — only from a tool result.
        
        CRITICAL RULE FOR TTS: Your entire output will be read aloud by a Text-to-Speech engine to a blind user. 
        - DO NOT explain your thought process.
        - DO NOT repeat the user's prompt or system instructions.
        - DO NOT explain what tool you just called.
        - ONLY output the final, direct, conversational Urdu answer. Nothing else.
    """

    suspend fun initialize(context: Context, toolSets: List<ToolSet>) {
        toolHandlers.clear()
        
        val functionDeclarations = mutableListOf<FunctionDeclaration>()
        
        for (toolSet in toolSets) {
            val kClass = toolSet::class
            for (func in kClass.declaredFunctions) {
                val toolAnn = func.findAnnotation<com.aurasight.app.ai.Tool>()
                if (toolAnn != null) {
                    val name = func.name
                    val desc = toolAnn.description
                    val params = mutableListOf<Schema<*>>()
                    val requiredParams = mutableListOf<String>()
                    
                    for (param in func.parameters) {
                        if (param.name == null || param.kind != kotlin.reflect.KParameter.Kind.VALUE) continue
                        val paramAnn = param.findAnnotation<com.aurasight.app.ai.ToolParam>()
                        val paramDesc = paramAnn?.description ?: ""
                        
                        val schema = when (param.type.classifier) {
                            String::class -> Schema.str(param.name!!, paramDesc)
                            Int::class -> Schema.int(param.name!!, paramDesc)
                            Double::class -> Schema.double(param.name!!, paramDesc)
                            Boolean::class -> Schema.bool(param.name!!, paramDesc)
                            else -> Schema.str(param.name!!, paramDesc)
                        }
                        params.add(schema)
                        if (!param.isOptional) {
                            requiredParams.add(param.name!!)
                        }
                    }
                    
                    functionDeclarations.add(
                        defineFunction(
                            name = name,
                            description = desc,
                            parameters = params,
                            requiredParameters = requiredParams
                        )
                    )
                    
                    toolHandlers[name] = { args ->
                        val callArgs = mutableMapOf<kotlin.reflect.KParameter, Any?>()
                        callArgs[func.parameters[0]] = toolSet
                        for (param in func.parameters) {
                            if (param.name != null && args.containsKey(param.name)) {
                                val argVal = args[param.name]
                                callArgs[param] = when (param.type.classifier) {
                                    Int::class -> if (argVal is Number) argVal.toInt() else argVal?.toString()?.toDoubleOrNull()?.toInt() ?: 0
                                    Double::class -> if (argVal is Number) argVal.toDouble() else argVal?.toString()?.toDoubleOrNull() ?: 0.0
                                    else -> argVal?.toString() ?: ""
                                }
                            }
                        }
                        
                        val result = if (func.isSuspend) {
                            func.callSuspendBy(callArgs)
                        } else {
                            func.callBy(callArgs)
                        }
                        
                        if (result is Map<*, *>) {
                            JSONObject(result).toString()
                        } else {
                            result.toString()
                        }
                    }
                }
            }
        }
        
        val generativeModel = GenerativeModel(
            modelName = "gemma-4-31b-it",
            apiKey = com.aurasight.app.BuildConfig.API_KEY,
            systemInstruction = content { text(SYSTEM_PROMPT.trimIndent()) },
            tools = if (functionDeclarations.isNotEmpty()) listOf(Tool(functionDeclarations)) else null
        )
        chat = generativeModel.startChat()
    }

    suspend fun ask(text: String, bitmap: android.graphics.Bitmap? = null): String = askMutex.withLock {
        val c = chat ?: error("Not initialized")
        var currentMsg = content { 
            if (bitmap != null) {
                image(bitmap)
            }
            text("$text\n\n[System Note: STRICTLY output ONLY the final conversational answer in Urdu. DO NOT output any internal thoughts, reasoning, or system instructions.]") 
        }
        var resultText = ""
        
        for (i in 0..10) { // Max 10 tool call hops
            try {
                val response = c.sendMessage(currentMsg)
                
                var hasToolCall = false
                var toolResList = mutableListOf<FunctionResponsePart>()
                
                for (part in response.candidates.firstOrNull()?.content?.parts.orEmpty()) {
                    if (part is FunctionCallPart) {
                        hasToolCall = true
                        val handler = toolHandlers[part.name]
                        val toolResultStr = if (handler != null) {
                            handler(part.args)
                        } else {
                            "Tool not found"
                        }
                        toolResList.add(FunctionResponsePart(part.name, JSONObject().put("result", toolResultStr)))
                    } else if (part is TextPart) {
                        resultText += part.text
                    }
                }
                
                if (!hasToolCall) {
                    if (resultText.isEmpty()) {
                        return response.text ?: "معذرت، مجھے سمجھ نہیں آیا۔"
                    }
                    return resultText
                } else {
                    currentMsg = content("function") {
                        for (res in toolResList) {
                            part(res)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GemmaEngineManager", "Cloud AI error", e)
                return "معذرت، انٹرنیٹ کا مسئلہ ہے۔"
            }
        }
        return resultText.ifEmpty { "معذرت، جواب دینے میں وقت لگ گیا۔ دوبارہ کوشش کریں۔" }
    }

    fun shutdown() {
        chat = null
    }
}
