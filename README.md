# Real-Time Voice Translation

An Android application that translates spoken English into French entirely on-device. It captures speech through Android's SpeechRecognizer API and translates the text instantly using Google ML Kit's offline translation engine.

## Architecture

The app runs in a single Kotlin activity:

**Speech recognition** uses Android's built-in `SpeechRecognizer`, which provides hardware-accelerated, low-latency recognition with support for both online and offline modes. Recognized text is dispatched for translation immediately.

**Translation** uses Google ML Kit Translation, which runs a lightweight TensorFlow Lite model (~30MB) entirely on-device after a one-time download. TFLite automatically leverages ARM NEON SIMD instructions on aarch64 devices for fast vectorized inference. Translation completes in milliseconds.

## Models

| Task | Model | Size | Runtime |
|------|-------|------|---------|
| Speech-to-Text | Android SpeechRecognizer (Google STT) | Built-in | Android OS service |
| Translation | Google ML Kit Translation (EN → FR) | ~30MB | TensorFlow Lite (NEON-accelerated) |

Both models run on-device. The translation model downloads once on first launch over WiFi, then works fully offline.

## Prerequisites

- Android Studio
- An Android device (API 29+)
- WiFi connection on first launch (to download the ~30MB French language pack)

## Setup

1. Clone the repository and open it in Android Studio.
2. Let Gradle sync dependencies.
3. Build and install the app on your device.
4. On first launch, grant microphone permission and wait for the translation model to download.

## Usage

Tap the microphone button to begin continuous speech recognition. Speak in English — the transcription appears in the top card, and the French translation appears instantly in the bottom card. Tap the button again to stop.

## Project Layout

| File | Role |
|------|------|
| `MainActivity.kt` | UI, permissions, speech recognition, ML Kit translation |
| `activity_main.xml` | Layout with source/target language cards and mic button |
| `AndroidManifest.xml` | Permissions (microphone, internet for initial download) |
