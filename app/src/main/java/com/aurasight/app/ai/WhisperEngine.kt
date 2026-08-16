package com.aurasight.app.ai

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/**
 * Offline speech-to-text powered by sherpa-onnx + Whisper ONNX models.
 *
 * Model files expected on /sdcard/ (same directory as the Gemma model):
 *   - tiny-encoder.int8.onnx   (~41 MB)
 *   - tiny-decoder.int8.onnx   (~42 MB)
 *   - tiny-tokens.txt          (< 1 MB)
 *
 * Download from: https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny
 * Push via adb:  adb push tiny-*.onnx tiny-tokens.txt /sdcard/
 *
 * Upgrade to "base" or "small" models for better Urdu accuracy — just
 * update ENCODER / DECODER / TOKENS constants and push the new files.
 */
object WhisperEngine {

    private const val SAMPLE_RATE = 16_000

    private var recognizer: OfflineRecognizer? = null

    /** True after initialize() succeeds. */
    val isReady: Boolean get() = recognizer != null

    /**
     * Load the Whisper ONNX model. Call once, on Dispatchers.IO.
     *
     * @param encoderPath Absolute path to *-encoder.int8.onnx
     * @param decoderPath Absolute path to *-decoder.int8.onnx
     * @param tokensPath  Absolute path to *-tokens.txt
     * @throws Exception if any file is missing or the model fails to load
     */
    fun initialize(encoderPath: String, decoderPath: String, tokensPath: String) {
        if (recognizer != null) return   // already loaded

        for ((label, path) in listOf(
            "encoder" to encoderPath,
            "decoder" to decoderPath,
            "tokens"  to tokensPath
        )) {
            require(File(path).exists()) { "Whisper $label not found: $path" }
        }

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 80
            ),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder      = encoderPath,
                    decoder      = decoderPath,
                    language     = "ur",          // Urdu
                    task         = "transcribe",
                    tailPaddings = -1             // auto-detect silence tail
                ),
                tokens     = tokensPath,
                numThreads = 2,
                debug      = false,
                provider   = "cpu"
            )
        )
        recognizer = OfflineRecognizer(config = config)
    }

    /**
     * Transcribe a float32 mono PCM waveform recorded at [SAMPLE_RATE].
     * Runs synchronously — call from Dispatchers.IO.
     *
     * @param samples Float32 PCM in range [-1, 1]
     * @return Transcribed text, or an empty string if nothing was heard.
     * @throws IllegalStateException if called before initialize()
     */
    fun transcribe(samples: FloatArray): String {
        val rec = recognizer
            ?: throw IllegalStateException("WhisperEngine not initialized — call initialize() first")

        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
            rec.decode(stream)
            rec.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** Release native resources. Engine can be re-initialized after this. */
    fun shutdown() {
        recognizer?.release()
        recognizer = null
    }
}
