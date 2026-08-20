# AuraSight

**AuraSight** is an AI-powered, voice-first Android application designed specifically to empower blind and visually impaired shopkeepers in Pakistan. 

By leveraging the cutting-edge Multimodal capabilities of the Gemini/Gemma models (`gemma-4-31b-it`) alongside on-device speech processing, AuraSight acts as a fully autonomous, Urdu-speaking assistant that can see the physical world and manage store operations.

## Key Features

- **True Multimodal Vision**: Ask questions about your surroundings. The app takes a picture and sends it directly to the cloud AI alongside your transcribed voice question. The AI natively "sees" the image and answers back in Urdu.
- **Hardware Accessibility**: No need to fumble for screen buttons. AuraSight overrides hardware volume buttons so a user can trigger the camera and microphone entirely by feel.
- **Urdu Native**: The entire conversation loop—both Speech-to-Text and Text-to-Speech—is optimized for spoken Urdu.
- **Offline Speech-to-Text (STT)**: Powered by `sherpa-onnx` (Whisper), the app transcribes user audio directly on the device for fast, private, and robust transcription before sending the text to the cloud.
- **Agentic Store Management**: More than just a chatbot, AuraSight acts as an agent. It uses tool-calling to:
  - Add items and quantities to a running **Cart**.
  - Finalize payments and calculate change.
  - Manage a **Khata** (digital ledger) for specific customers.
  - Ask for explicit voice confirmation before finalizing any financial transaction.

## Technical Architecture

- **UI Framework**: Built entirely with Jetpack Compose.
- **AI Core**: `generativeai:0.9.0` SDK communicating with Google's Gemini/Gemma models.
- **Multimodal Pipeline**: Jetpack CameraX takes near-instant captures (300ms delay) which are passed as raw `Bitmap` objects into the AI's prompt payload.
- **State Management**: Hilt for Dependency Injection and standard Android ViewModels for unidirectional data flow.

## Setup & Building

1. Clone this repository.
2. Ensure you have your Google AI Studio / Vertex AI API key.
3. Create a `local.properties` file in the root directory (if it doesn't exist) and add your API key:
   ```properties
   API_KEY=your_api_key_here
   ```
4. Build and run using Android Studio (Target SDK 37, Min SDK 26).

---
*Built to bring independence and empowerment through AI.*
