package com.aurasight.app.ai

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Model files are pushed directly to external storage (adb push to /sdcard/)
 * instead of bundled in the APK — a multi-GB model in assets made every
 * install/reinstall unusably slow and could exceed device storage during
 * the install step itself. Requires "All files access" permission on
 * Android 11+ — see MainActivity for the permission request flow.
 */
object ModelAssetExtractor {

    private const val GEMMA_ASSET_NAME = "gemma-3n-E2B-it-int4.litertlm"

    // sherpa-onnx Whisper-small multilingual (ur supported)
    // Download from: https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small
    // Push with: adb push small-*.onnx small-tokens.txt /sdcard/
    private const val WHISPER_ENCODER = "small-encoder.int8.onnx"
    private const val WHISPER_DECODER = "small-decoder.int8.onnx"
    private const val WHISPER_TOKENS  = "small-tokens.txt"

    // Piper offline TTS (future)
    private const val PIPER_MODEL_ASSET = "ur_PK-piper-medium.onnx"
    private const val PIPER_TOKENS_ASSET = "ur_PK-piper-medium.onnx.json"

    /** True if the required "All files access" permission is granted (Android 11+). */
    fun hasStoragePermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    fun ensureModelExtracted(context: Context): String =
        resolveFromSdcardOrAssets(context, GEMMA_ASSET_NAME)

    /** Returns absolute path to the Whisper encoder ONNX (must exist on /sdcard/). */
    fun whisperEncoderPath(context: Context): String =
        resolveFromSdcardOrAssets(context, WHISPER_ENCODER)

    /** Returns absolute path to the Whisper decoder ONNX (must exist on /sdcard/). */
    fun whisperDecoderPath(context: Context): String =
        resolveFromSdcardOrAssets(context, WHISPER_DECODER)

    /** Returns absolute path to the Whisper tokens.txt (must exist on /sdcard/). */
    fun whisperTokensPath(context: Context): String =
        resolveFromSdcardOrAssets(context, WHISPER_TOKENS)

    fun ensurePiperModelExtracted(context: Context): String {
        resolveFromSdcardOrAssets(context, PIPER_MODEL_ASSET)
        resolveFromSdcardOrAssets(context, PIPER_TOKENS_ASSET)
        return context.filesDir.absolutePath
    }

    /** Checks /sdcard/<name> first (manually pushed, no copy needed).
     *  Falls back to extracting from assets if bundled there instead. */
    private fun resolveFromSdcardOrAssets(context: Context, name: String): String {
        val sdcardFile = File(Environment.getExternalStorageDirectory(), name)
        
        // Guard against corrupted 0-byte or incomplete adb push transfers 
        // that cause native C++ segfaults.
        val minSize = if (name.endsWith(".txt") || name.endsWith(".json")) 100L else 1_000_000L
        
        if (sdcardFile.exists() && sdcardFile.length() > minSize) {
            return sdcardFile.absolutePath
        }

        val outFile = File(context.filesDir, name)
        if (outFile.exists() && outFile.length() > minSize) {
            return outFile.absolutePath
        }

        context.assets.open(name).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        
        return outFile.absolutePath
    }
}