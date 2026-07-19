package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Locale

class PalWakeUpService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    companion object {
        private const val CHANNEL_ID = "pal_wake_up_channel"
        private const val NOTIFICATION_ID = 2026
        private const val TAG = "PalWakeUpService"

        fun startService(context: Context) {
            val intent = Intent(context, PalWakeUpService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, PalWakeUpService::class.java)
            context.stopService(intent)
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
        startForegroundWithNotification()
        
        initializeSpeechRecognizer()
        initializeTts()
        observeIncomingSms()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "فعالیت پس‌زمینه دستیار پال",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "این کانال برای بیدار نگه داشتن دستیار صوتی پال در پس‌زمینه استفاده می‌شود."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("دستیار صوتی پال بیدار است")
            .setContentText("با گفتن «Hey Pal» یا «هی پال» می‌توانید دستیار صوتی خود را بیدار کنید.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initializeSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(this@PalWakeUpService)
            }

            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "fa-IR")
            }

            // Start listening
            startListeningLoop()
        } else {
            Log.e(TAG, "Speech recognition not available")
        }
    }

    private fun startListeningLoop() {
        try {
            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting speech recognizer: ${e.message}")
        }
    }

    private fun initializeTts() {
        textToSpeech = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val farsi = Locale("fa", "IR")
            val result = textToSpeech?.setLanguage(farsi)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
            }
        }
    }

    private fun observeIncomingSms() {
        PalSmsBus.smsFlow
            .onEach { sms ->
                Log.d(TAG, "Background Service observed SMS from ${sms.senderName}: ${sms.messageBody}")
                if (isTtsReady) {
                    val speechText = "پیام جدیدی از طرف ${sms.senderName} دریافت شد. او می‌گوید: ${sms.messageBody}"
                    textToSpeech?.speak(speechText, TextToSpeech.QUEUE_ADD, null, "BackgroundSms")
                }
            }
            .launchIn(serviceScope)
    }

    private fun launchApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("trigger_listening", true)
        }
        startActivity(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        serviceScope.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Speech Recognition Callbacks for Background Listening ---

    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "SpeechRecognizer onReadyForSpeech")
    }

    override fun onBeginningOfSpeech() {}

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {}

    override fun onError(error: Int) {
        Log.d(TAG, "SpeechRecognizer onError: $error")
        // To prevent rapid infinite loops, restart the loop after a safe delay
        serviceScope.launch {
            kotlinx.coroutines.delay(2500)
            startListeningLoop()
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.lowercase(Locale.ROOT) ?: ""
        Log.d(TAG, "SpeechRecognizer onResults: $text")

        // Look for the wake-up word: "hey pal", "سلام پال", "هی پال"
        if (text.contains("hey pal") || text.contains("هی پال") || text.contains("سلام پال") || text.contains("پال")) {
            Log.d(TAG, "Wake-up word detected!")
            launchApp()
        }

        // Restart listening loop
        startListeningLoop()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.lowercase(Locale.ROOT) ?: ""
        if (text.contains("hey pal") || text.contains("هی پال") || text.contains("سلام پال") || text.contains("پال")) {
            Log.d(TAG, "Wake-up word detected partially!")
            launchApp()
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
