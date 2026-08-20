package com.aurasight.app.ai

import android.util.Log
import kotlinx.coroutines.runBlocking

private const val TAG = "AuraSight/CameraTools"

interface CameraActionDelegate {
    /** 
     * Suspends until the UI has captured a frame, analyzed it, and returned the result.
     * @param action "SCENE" or "CURRENCY"
     */
    suspend fun requestCameraAction(action: String): String
}

class CameraToolSet(private val delegate: CameraActionDelegate) : ToolSet {

    @Tool(description = "Capture an image from the camera and describe the general scene or objects in front of the user.")
    fun describeScene(): String {
        Log.d(TAG, "describeScene() called by Gemma")
        // LiteRT tools are synchronous and run on a background thread.
        // We use runBlocking to pause this thread until the UI resolves the action.
        return runBlocking {
            delegate.requestCameraAction("SCENE")
        }
    }

    @Tool(description = "Capture an image from the camera and detect Pakistani currency notes by reading printed numbers on them.")
    fun identifyCurrency(): String {
        Log.d(TAG, "identifyCurrency() called by Gemma")
        return runBlocking {
            delegate.requestCameraAction("CURRENCY")
        }
    }
}
