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
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.text.SimpleDateFormat
import java.util.Date
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
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var listening = false
    private var speaking = false
    private var waitingForCommandUntil = 0L

    private fun enabled(): Boolean =
        getSharedPreferences("viro_voice", MODE_PRIVATE).getBoolean("enabled", false)

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val languageResult = tts?.setLanguage(Locale("ar", "JO"))
                if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("ar"))
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        handler.post { speaking = true }
                    }

                    override fun onDone(utteranceId: String?) {
                        finishSpeaking()
                    }

                    override fun onError(utteranceId: String?) {
                        finishSpeaking()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        finishSpeaking()
                    }
                })
                ttsReady = true
                pendingSpeech?.let {
                    pendingSpeech = null
                    speak(it)
                }
            }
        }
        createNotificationChannel()
    }

    private fun finishSpeaking() {
        handler.post {
            speaking = false
            if (enabled()) scheduleRestart(350)
        }
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
                if (enabled()) {
                    startForeground(NOTIFICATION_ID, notification("قل «يا فيرو» ثم الأمر"))
                    beginRecognition()
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VIRO voice assistant",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "إشعار ظاهر أثناء استماع فيرو لكلمة التنبيه"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder.setContentTitle("VIRO المساعد الصوتي")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message))
    }

    private fun beginRecognition() {
        if (!enabled() || speaking) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("خدمة التعرف على الكلام غير متاحة على الجهاز")
            speak("خدمة التعرف على الكلام غير متاحة على الجهاز")
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        listening = true
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        listening = false
                    }

                    override fun onError(error: Int) {
                        listening = false
                        if (!speaking) scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1400 else 850)
                    }

                    override fun onResults(results: android.os.Bundle?) {
                        listening = false
                        val spoken = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                        handleSpeech(spoken)
                        if (!speaking) scheduleRestart(500)
                    }

                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
            }
        }
        listen()
    }

    private fun listen() {
        if (listening || speaking || !enabled()) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Use general Arabic instead of a strict Jordanian locale for better device compatibility.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        try {
            listening = true
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            listening = false
            scheduleRestart(1400)
        }
    }

    private fun scheduleRestart(delay: Long = 900) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (enabled() && !speaking) beginRecognition()
        }, delay)
    }

    private fun normalizeSpeech(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ة', 'ه')
            .replace(Regex("[ًٌٍَُِّْـ]"), "")
            .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun handleSpeech(raw: String) {
        val spoken = normalizeSpeech(raw)
        if (spoken.isBlank()) return

        val wakePhrases = listOf("يا فيرو", "يافيرو", "ya viro", "viro", "فيرو")
        val wake = wakePhrases.firstOrNull { spoken.contains(it) }
        val command: String

        if (wake != null) {
            command = spoken.substringAfter(wake).trim()
            waitingForCommandUntil = 0L
        } else if (SystemClock.elapsedRealtime() < waitingForCommandUntil) {
            command = spoken
            waitingForCommandUntil = 0L
        } else {
            return
        }

        if (command.isBlank()) {
            waitingForCommandUntil = SystemClock.elapsedRealtime() + 12_000L
            speak("تفضل، سامعك. شو أعمل؟")
            return
        }

        when {
            command.contains("سبوتيفاي") || command.contains("سبوتفاي") ||
                command.contains("spotify") || command.contains("سبوتي فاي") -> {
                launchApp("com.spotify.music", listOf("spotify", "سبوتيفاي"), "سبوتيفاي")
            }

            command.contains("يوتيوب") || command.contains("youtube") -> {
                launchApp("com.google.android.youtube", listOf("youtube", "يوتيوب"), "يوتيوب")
            }

            command.contains("كم الساعة") || command.contains("الساعه") ||
                command.contains("الساعة") || command.contains("الوقت") -> {
                val now = SimpleDateFormat("h:mm", Locale("ar", "JO")).format(Date())
                speak("الساعة الآن $now")
            }

            command.contains("افتح") || command.contains("شغل") ||
                command.contains("شغلي") || command.contains("open") -> {
                val appName = command
                    .replace("افتح", "")
                    .replace("شغل", "")
                    .replace("شغلي", "")
                    .replace("open", "")
                    .trim()
                if (appName.isNotBlank() && launchAppByLabel(appName)) {
                    speak("بفتح $appName")
                } else {
                    speak("مش لاقي التطبيق على الجهاز")
                }
            }

            else -> speak("سمعتك، بس ما فهمت الأمر. جرّب تقول افتح سبوتيفاي أو افتح يوتيوب")
        }
    }

    private fun launchApp(packageName: String, labels: List<String>, spokenName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: findLaunchIntent(labels)
        if (launchIntent == null) {
            speak("$spokenName مش مثبت على الجهاز")
            return
        }
        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            speak("بفتح $spokenName")
        } catch (_: Exception) {
            speak("ما قدرت أفتح $spokenName")
        }
    }

    private fun launchAppByLabel(appName: String): Boolean {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = packageManager.queryIntentActivities(query, 0)
        val match = matches.firstOrNull {
            it.loadLabel(packageManager).toString().contains(appName, ignoreCase = true)
        } ?: return false
        val intent = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun findLaunchIntent(labels: List<String>): Intent? {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(query, 0).firstOrNull { info ->
            val label = info.loadLabel(packageManager).toString().lowercase(Locale.ROOT)
            val pkg = info.activityInfo.packageName.lowercase(Locale.ROOT)
            labels.any { label.contains(it) || pkg.contains(it) }
        } ?: return null
        return packageManager.getLaunchIntentForPackage(match.activityInfo.packageName)
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            pendingSpeech = text
            return
        }
        speaking = true
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "viro_speech")
        if (result == TextToSpeech.ERROR) {
            speaking = false
            scheduleRestart(900)
        }
    }

    private fun stopListening() {
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {}
        recognizer?.destroy()
        recognizer = null
        listening = false
        waitingForCommandUntil = 0L
    }

    override fun onDestroy() {
        stopListening()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
