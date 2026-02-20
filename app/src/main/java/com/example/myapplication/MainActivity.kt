package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var mainActivityBinding: ActivityMainBinding
    private var androidSpeechRecognizer: SpeechRecognizer? = null
    private var isMicrophoneActive = false
    private var preferOfflineRecognition = false
    private var activeTranslationCoroutine: Job? = null

    private val requestCodeRequiredPermissions = 1
    private val requestCodeManageLocalStorage = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainActivityBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mainActivityBinding.root)

        mainActivityBinding.micButton.isEnabled = false
        mainActivityBinding.micButton.setOnClickListener { toggleMicrophoneCapture() }

        verifyAndRequestDevicePermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        androidSpeechRecognizer?.destroy()
    }

    private fun toggleMicrophoneCapture() {
        if (isMicrophoneActive) stopMicrophoneCapture() else startMicrophoneCapture()
    }

    private fun verifyAndRequestDevicePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                mainActivityBinding.statusText.text = "Grant \"All files access\" in Settings"
                val settingsIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                @Suppress("DEPRECATION")
                startActivityForResult(settingsIntent, requestCodeManageLocalStorage)
                return
            }
        } else if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), requestCodeRequiredPermissions
            )
            return
        }
        requestMicrophoneAccessPermission()
    }

    private fun requestMicrophoneAccessPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), requestCodeRequiredPermissions
            )
        } else {
            initializeTranslationModels()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCodeManageLocalStorage) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                requestMicrophoneAccessPermission()
            } else {
                mainActivityBinding.statusText.text = "All files access required"
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestCodeRequiredPermissions && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (permissions.contains(Manifest.permission.RECORD_AUDIO)) initializeTranslationModels()
            else requestMicrophoneAccessPermission()
        } else {
            mainActivityBinding.statusText.text = "Permission denied"
        }
    }

    @Suppress("DEPRECATION")
    private fun initializeTranslationModels() {
        mainActivityBinding.statusText.text = "Loading translation model…"
        lifecycleScope.launchWhenCreated {
            try {
                withContext(Dispatchers.IO) {
                    val languageModelsDirectoryPath = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS).absolutePath
                    Log.i("MainActivity", "Loading language model from: $languageModelsDirectoryPath")
                    initializeNativeTranslationEngine(languageModelsDirectoryPath, "Llama-3.2-1B-Instruct-Q4_K_M.gguf")
                }
                mainActivityBinding.statusText.text = "Ready"
                mainActivityBinding.micButton.isEnabled = true
                Log.i("MainActivity", "Language model loaded successfully")
            } catch (exception: Exception) {
                Log.e("MainActivity", "Model load failed", exception)
                mainActivityBinding.statusText.text = "Error: ${exception.message}"
            }
        }
    }

    private fun startMicrophoneCapture() {
        isMicrophoneActive = true
        mainActivityBinding.micButton.backgroundTintList =
            ContextCompat.getColorStateList(this, android.R.color.holo_red_light)
        mainActivityBinding.statusText.text = "● Listening"
        mainActivityBinding.transcribedText.text = ""
        mainActivityBinding.translatedText.text = ""

        androidSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        androidSpeechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.i("SpeechRecognition", "Ready for speech")
                mainActivityBinding.statusText.text = "● Listening — speak now"
            }

            override fun onBeginningOfSpeech() {
                Log.i("SpeechRecognition", "Speech started")
                mainActivityBinding.statusText.text = "● Hearing you…"
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.i("SpeechRecognition", "Speech ended")
                mainActivityBinding.statusText.text = "● Processing…"
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error (try online mode)"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    13 -> "Offline language unavailable"
                    else -> "Error code: $error"
                }
                Log.w("SpeechRecognition", "Recognition error: $errorMessage (code=$error, offline=$preferOfflineRecognition)")

                if (error == 13 && preferOfflineRecognition) {
                    preferOfflineRecognition = false
                    mainActivityBinding.statusText.text = "● Using online recognition (download offline pack for offline use)"
                    if (isMicrophoneActive) startAudioRecognition()
                    return
                }

                if (isMicrophoneActive) {
                    mainActivityBinding.statusText.text = "● $errorMessage — retrying…"
                    startAudioRecognition()
                }
            }

            override fun onResults(results: Bundle?) {
                val recognitionMatches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognizedText = recognitionMatches?.firstOrNull() ?: ""
                Log.i("SpeechRecognition", "Result: '$recognizedText'")

                if (recognizedText.isNotEmpty()) {
                    mainActivityBinding.transcribedText.append(recognizedText + "\n")
                    mainActivityBinding.statusText.text = "● Translating…"
                    translateRecognizedAudioText(recognizedText)
                }

                if (isMicrophoneActive) {
                    startAudioRecognition()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partialRecognitionMatches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partialRecognizedText = partialRecognitionMatches?.firstOrNull() ?: ""
                if (partialRecognizedText.isNotEmpty()) {
                    Log.d("SpeechRecognition", "Partial: '$partialRecognizedText'")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        startAudioRecognition()
    }

    private fun startAudioRecognition() {
        val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (preferOfflineRecognition) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        androidSpeechRecognizer?.startListening(recognitionIntent)
    }

    private fun stopMicrophoneCapture() {
        isMicrophoneActive = false
        androidSpeechRecognizer?.stopListening()
        androidSpeechRecognizer?.destroy()
        androidSpeechRecognizer = null
        activeTranslationCoroutine?.cancel()
        mainActivityBinding.micButton.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.mic_idle)
        mainActivityBinding.statusText.text = "Ready"
    }

    private fun translateRecognizedAudioText(recognizedText: String) {
        activeTranslationCoroutine?.cancel()
        activeTranslationCoroutine = lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i("Translation", "Starting translation of: '$recognizedText'")
                withContext(Dispatchers.Main) {
                    mainActivityBinding.statusText.text = "● Translating…"
                }
                startNativeTranslationStream(recognizedText)
                Log.i("Translation", "Translation complete")
                withContext(Dispatchers.Main) {
                    mainActivityBinding.translatedText.append("\n")
                    if (!isMicrophoneActive) mainActivityBinding.statusText.text = "Ready"
                    else mainActivityBinding.statusText.text = "● Listening"
                }
            } catch (exception: Exception) {
                Log.e("Translation", "Language model error", exception)
                withContext(Dispatchers.Main) {
                    mainActivityBinding.translatedText.append("[translation error]\n")
                }
            }
        }
    }

    @Suppress("unused")
    fun receiveTranslationWordToken(translationToken: String) {
        runOnUiThread {
            mainActivityBinding.translatedText.append(translationToken)
        }
    }

    external fun initializeNativeTranslationEngine(languageModelsDirectoryPath: String, languageModelFilename: String)
    external fun startNativeTranslationStream(inputTextString: String)

    companion object {
        init { System.loadLibrary("myapplication") }
    }
}
