package com.example

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TtsManager(
    private val context: Context,
    private val onStartSpeaking: () -> Unit,
    private val onFinishedSpeaking: () -> Unit
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set locale to Persian
            val farsi = Locale("fa", "IR")
            val result = tts?.setLanguage(farsi)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TtsManager", "Farsi is not supported on this device. Trying generic Persian...")
                val generalFarsi = Locale("fa")
                val resultGen = tts?.setLanguage(generalFarsi)
                if (resultGen == TextToSpeech.LANG_MISSING_DATA || resultGen == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TtsManager", "Persian language fallback failed. Defaulting to system language.")
                } else {
                    isInitialized = true
                }
            } else {
                isInitialized = true
            }

            // Setup utterance listener to coordinate face talking animation
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId != "PalPainSpeakID") {
                        onStartSpeaking()
                    }
                }

                override fun onDone(utteranceId: String?) {
                    onFinishedSpeaking()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onFinishedSpeaking()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    onFinishedSpeaking()
                }
            })
        } else {
            Log.e("TtsManager", "Initialization of TextToSpeech failed.")
        }
    }

    fun speak(text: String, locale: Locale? = null, utteranceId: String = "PalSpeakID") {
        if (!isInitialized) {
            Log.e("TtsManager", "TTS is not initialized yet.")
            return
        }

        if (locale != null) {
            tts?.setLanguage(locale)
        } else {
            val farsi = Locale("fa", "IR")
            tts?.setLanguage(farsi)
        }

        val params = android.os.Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        
        // Speak using TTS
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
