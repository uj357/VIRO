package com.deskclock.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceWakeService : Service() {
    companion object {
        const val ACTION_START = "com.deskclock.launcher.voice.START"
        const val ACTION_STOP = "com.deskclock.launcher.voice.STOP"
        private const val CHANNEL_ID = "viro_voice_channel"
        private const val NOTIFICATION_ID = 420
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ar", "JO")
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                getSharedPreferences("viro_voice", MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
                stopListening()
                stopForeground(true)
                stopSelf()
            }
            ACTION_START -> {
                if (getSharedPreferences("viro_voice", MODE_PRIVATE).getBoolean("enabled", false)) {
                    startForeground(NOTIFICATION_ID, notification())
                    beginRecognition()
                } else stopSelf()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "VIRO voice assistant", NotificationManager.IMPORTANCE_LOW)
            channel.description = "إشعار ظاهر أثناء استماع فيرو لكلمة التنبيه"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("VIRO يستمع")
            .setContentText("قل «يا فيرو» ثم الأمر — اضغط من اللانشر لإيقافه")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun beginRecognition() {
        if (!getSharedPreferences("viro_voice", MODE_PRIVATE).getBoolean("enabled", false)) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("التعرف على الصوت غير متوفر على هذا الجهاز")
            stopSelf()
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) { listening = true }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { listening = false }
                    override fun onError(error: Int) { listening = false; scheduleRestart() }
                    override fun onResults(results: android.os.Bundle?) {
                        listening = false
                        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        handleSpeech(spoken)
                        scheduleRestart(500)
                    }
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
            }
        }
        listen()
    }

    private fun listen() {
        if (listening || !getSharedPreferences("viro_voice", MODE_PRIVATE).getBoolean("enabled", false)) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-JO")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-JO")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            recognizer?.startListening(intent)
            listening = true
        } catch (_: Exception) {
            listening = false
            scheduleRestart(1200)
        }
    }

    private fun scheduleRestart(delay: Long = 900) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ beginRecognition() }, delay)
    }

    private fun handleSpeech(raw: String) {
        val text = raw.lowercase(Locale("ar", "JO")).replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
        val wake = listOf("يا فيرو", "فيرو", "يا viro", "viro").firstOrNull { text.contains(it) } ?: return
        val command = text.substringAfter(wake).trim()
        if (command.isBlank()) {
            speak("تفضل، شو أعمل؟")
            return
        }
        when {
            command.contains("يوتيوب") || command.contains("youtube") -> launchApp("com.google.android.youtube", "بفتح يوتيوب")
            command.contains("سبوتيفاي") || command.contains("spotify") -> launchApp("com.spotify.music", "بفتح سبوتيفاي")
            command.contains("كم الساعة") || command.contains("الساعة") -> {
                val now = java.text.SimpleDateFormat("h:mm a", Locale("ar", "JO")).format(java.util.Date())
                speak("الساعة الآن " + now)
            }
            command.contains("افتح") -> speak("لسه ما أضفت هذا التطبيق لقائمة أوامري")
            else -> speak("سمعتك، بس هذا الأمر لسه مش مضاف")
        }
    }

    private fun launchApp(packageName: String, response: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            speak("التطبيق مش مثبت")
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launchIntent)
            speak(response)
        } catch (_: Exception) {
            speak("ما قدرت أفتح التطبيق")
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "viro_speech")
    }

    private fun stopListening() {
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (_: Exception) {}
        recognizer?.destroy()
        recognizer = null
        listening = false
    }

    override fun onDestroy() {
        stopListening()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
