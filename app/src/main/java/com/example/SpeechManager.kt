package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class SpeechManager(
    private val context: Context,
    private val onReady: () -> Unit,
    private val onBeginning: () -> Unit,
    private val onRmsChangedListener: (Float) -> Unit,
    private val onPartialResult: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) : RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognitionIntent: Intent? = null
    private var isListening = false

    init {
        initializeRecognizer()
    }

    private fun initializeRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(this@SpeechManager)
            }

            recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
        } else {
            Log.e("SpeechManager", "Speech recognition is not available on this device.")
            onError("سرویس تشخیص گفتار در این دستگاه فعال نیست.")
        }
    }

    fun startListening() {
        if (isListening) return
        try {
            if (speechRecognizer == null) {
                initializeRecognizer()
            }
            speechRecognizer?.startListening(recognitionIntent)
            isListening = true
        } catch (e: Exception) {
            Log.e("SpeechManager", "Error starting speech recognizer: ${e.message}")
            onError("خطا در شروع ضبط صدا")
        }
    }

    fun stopListening() {
        if (!isListening) return
        try {
            speechRecognizer?.stopListening()
            isListening = false
        } catch (e: Exception) {
            Log.e("SpeechManager", "Error stopping speech recognizer: ${e.message}")
        }
    }

    fun cancel() {
        try {
            speechRecognizer?.cancel()
            isListening = false
        } catch (e: Exception) {
            Log.e("SpeechManager", "Error canceling speech recognizer: ${e.message}")
        }
    }

    fun shutdown() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListening = false
    }

    // --- RecognitionListener Callbacks ---

    override fun onReadyForSpeech(params: Bundle?) {
        onReady()
    }

    override fun onBeginningOfSpeech() {
        onBeginning()
    }

    override fun onRmsChanged(rmsdB: Float) {
        onRmsChangedListener(rmsdB)
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        isListening = false
    }

    override fun onError(error: Int) {
        isListening = false
        val message = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "خطای صوتی"
            SpeechRecognizer.ERROR_CLIENT -> "خطای سرویس گیرنده"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "عدم دسترسی به میکروفون"
            SpeechRecognizer.ERROR_NETWORK -> "خطای شبکه"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "اتمام مهلت شبکه"
            SpeechRecognizer.ERROR_NO_MATCH -> "عبارتی یافت نشد"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "سرویس مشغول است"
            SpeechRecognizer.ERROR_SERVER -> "خطای سرور"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "عدم دریافت صدا"
            else -> "خطای ناشناخته تشخیص گفتار"
        }
        Log.e("SpeechManager", "Speech recognition error: $message ($error)")
        onError(message)
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull() ?: ""
        onResult(text)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull() ?: ""
        if (text.isNotEmpty()) {
            onPartialResult(text)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
