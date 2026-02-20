# Real-Time Voice Translation

An Android application that translates spoken English into French entirely on-device. It captures speech through Android's SpeechRecognizer API, then passes the transcribed text to a local quantized LLaMA model that streams the French translation token-by-token back to the UI.

## Architecture

The app is split into two layers:

**Kotlin UI layer** (`MainActivity.kt`) handles the Android lifecycle, runtime permissions, microphone capture via `SpeechRecognizer`, and coroutine-based coordination between recognition and translation. Recognized text is dispatched to the native layer on a background thread; translated tokens are streamed back to the main thread through a JNI callback.

**Native C++ layer** (`native-lib.cpp`) wraps `llama.cpp` behind two JNI entry points: one to load the GGUF model into memory at startup, and one to run inference. The translation function tokenizes a Llama 3 Instruct-format prompt, decodes it with greedy sampling, and calls back into Kotlin for each generated token. A mutex serializes concurrent translation requests. ARM NEON SIMD is enabled at the build level for vectorized matrix math on mobile processors.

## Prerequisites

- Android Studio with NDK and CMake support
- An Android device (API 21+)
- `Llama-3.2-1B-Instruct-Q4_K_M.gguf` placed in the device's Downloads folder

## Setup

1. Clone the repository and open it in Android Studio.
2. Let Gradle sync dependencies and build the native libraries.
3. Transfer the GGUF model to the device:
   ```
   adb push Llama-3.2-1B-Instruct-Q4_K_M.gguf /sdcard/Download/
   ```
4. Build and install the app.

## Usage

Grant storage and microphone permissions when prompted. The status text shows "Ready" once the model is loaded. Tap the microphone button to begin continuous speech recognition; tap again to stop. Transcribed English appears in the top card, and the French translation streams into the bottom card.

## Project Layout

| File | Role |
|------|------|
| `MainActivity.kt` | UI, permissions, speech recognition, coroutine management |
| `native-lib.cpp` | JNI bridge to llama.cpp for model loading and inference |
| `CMakeLists.txt` | Native build config with NEON and O3 flags |
| `vendor/llama.cpp` | LLM inference engine (third-party) |
