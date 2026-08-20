# Multimodal Vision Pipeline (Camera -> Mic -> Gemini)

We are upgrading the Camera flow to use the new cloud model's native Vision capabilities instead of relying on the intermediate MLKit text extractor.

## Proposed Changes

### 1. `CameraScreen.kt` (Manual Capture Flow)
- Change the `LaunchedEffect` so that when the camera is opened manually (`pendingAction == null`), it waits 1.5 seconds (to allow autofocus) and then **automatically takes a picture**.
- After taking the picture, it saves it to `latestCameraBitmap` and navigates back to the `VOICE` screen.

### 2. `GemmaViewModel.kt` (Voice & Camera Sync)
- Once the manual picture is taken and the app navigates back to `VOICE`, we will programmatically trigger the microphone (via `hardwareMicTrigger`) so the user can immediately ask their question (e.g., "What is this item?").
- When the user finishes speaking, `askAndSpeak` will retrieve the saved `latestCameraBitmap` and pass it to the AI.

### 3. `GemmaEngineManager.kt` (Multimodal Integration)
- Update `ask()` to accept an optional `Bitmap`.
- Use the `content { image(bitmap); text(prompt) }` API to send the image directly to the `gemma-4-31b-it` model alongside the user's spoken text.

## User Review Required
> [!IMPORTANT]
> The current system uses MLKit to extract text from images and sends that text to the AI. By sending the actual image to the AI instead, the AI will be able to "see" colors, objects, and scenes, not just text! Do you approve of removing the manual MLKit fallback and fully utilizing the AI's vision?
