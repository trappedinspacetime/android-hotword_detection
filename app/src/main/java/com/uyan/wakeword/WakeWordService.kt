package com.uyan.wakeword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Sürekli çalışan foreground service.
 * Mikrofon yönetimi ekran durumuna göredir:
 *  - Ekran KAPALI: "uyan" kelimesini dinler (mikrofonu alır)
 *  - Ekran AÇIK  : mikrofonu BIRAKIR; böylece Hey Google,
 *    Termux'taki sesle erişim programı vb. mikrofonu kullanabilir
 *
 * "uyan" duyulunca: ekranı uyandırır, Termux'u açar,
 * "Termux açıldı" diye sesli bildirir (Google TTS).
 */
class WakeWordService : Service() {

    companion object {
        const val CHANNEL_ID = "uyan_wakeword_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "UyanWakeWord"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_ACTIVITY = "com.termux.app.TermuxActivity"

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }

    private var engine: WakeWordEngine? = null
    private var detectionJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiver: BroadcastReceiver? = null

    // Sesli yanıt için TTS
    private var tts: TextToSpeech? = null
    @Volatile
    private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // TTS'i servisle birlikte başlat; algılama anında hazır olsun
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Uyan servisi başlatılıyor...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Ekran açılıp kapanmasını izle ve mikrofonu buna göre yönet
        registerScreenReceiver()
        applyMicPolicy()

        return START_STICKY
    }

    // ---- Ekran durumuna göre mikrofon yönetimi ----

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.i(TAG, "Ekran kapandı → mikrofon alınır, dinleme başlar")
                        takeMic()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        Log.i(TAG, "Ekran açıldı → mikrofon bırakılır")
                        releaseMic()
                    }
                }
            }
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun screenIsOn(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isInteractive

    private fun applyMicPolicy() {
        if (screenIsOn()) {
            Log.i(TAG, "Başlangıç: ekran açık → beklemede")
            releaseMic()
        } else {
            Log.i(TAG, "Başlangıç: ekran kapalı → dinliyor")
            takeMic()
        }
    }

    /** Mikrofonu al ve "uyan"ı dinlemeye başla */
    private fun takeMic() {
        if (engine != null) return // zaten dinliyor
        startListening()
    }

    /** Mikrofonu bırak: başka uygulama (sesle erişim, Hey Google) kullanabilsin */
    private fun releaseMic() {
        detectionJob?.cancel()
        detectionJob = null
        try {
            engine?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "engine.stop() hatası", e)
        }
        try {
            engine?.release()
        } catch (e: Exception) {
            Log.w(TAG, "engine.release() hatası", e)
        }
        engine = null
        updateNotification("Beklemede (ekran açık, mikrofon boşta)")
    }

    private fun startListening() {
        // Model dosyası assets/uyan.onnx olmalı
        val models = listOf(
            WakeWordModel(
                name = "uyan",
                modelPath = "uyan.onnx",
                threshold = 0.45f
            )
        )

        val newEngine = WakeWordEngine(
            context = this,
            models = models,
            detectionCooldownMs = 3000L    // Aynı kelimeyi 3 sn içinde tekrar sayma
        )
        engine = newEngine

        detectionJob?.cancel()
        detectionJob = serviceScope.launch {
            newEngine.detections.collect { detection ->
                Log.i(TAG, "Tespit edildi: ${detection.model.name}  skor=${detection.score}")
                onWakeWordDetected()
            }
        }

        try {
            newEngine.start()
            Log.i(TAG, "Dinleme başladı")
            updateNotification("Dinliyor: uyan (ekran kapalı)")
        } catch (e: Exception) {
            Log.e(TAG, "Engine başlatılamadı", e)
            try {
                newEngine.release()
            } catch (_: Exception) {
            }
            engine = null
            updateNotification("Mikrofon şu an başkasında; ekran kapanınca yeniden denenecek")
        }
    }

    // ---- Algılama sonrası akış ----

    private fun onWakeWordDetected() {
        // 1. Ekranı uyandır (SCREEN_ON gelince mikrofon otomatik bırakılır)
        acquireWakeLock()

        // 2. Kilit ekranından bile açılması için tam ekran bildirimi gönder
        postWakeNotification()

        // 3. Termux'u aç
        val opened = openTermux()
        if (opened) {
            // 4. Sesli bildir
            speak("Termux açıldı")
        } else {
            speak("Termux bulunamadı")
            updateNotification("Hata: Termux kurulu değil")
        }
    }

    private fun termuxLaunchIntent(): Intent {
        val launch = packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
        if (launch != null) {
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            return launch
        }
        // Yedek yol: bileşen adıyla doğrudan aç
        return Intent().setClassName(TERMUX_PACKAGE, TERMUX_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun openTermux(): Boolean {
        return try {
            startActivity(termuxLaunchIntent())
            Log.i(TAG, "Termux açılıyor")
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Termux bulunamadı", e)
            false
        } catch (e: Exception) {
            // Android 10+ arka plan kısıtlaması olabilir;
            // tam ekran bildirimi (full screen intent) devralır
            Log.w(TAG, "Doğrudan açılamadı, kilit üstü bildirim devralır", e)
            false
        }
    }

    private fun postWakeNotification() {
        val pending = PendingIntent.getActivity(
            this,
            1,
            termuxLaunchIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val headsUp = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Uyan!")
            .setContentText("Termux açılıyor")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .setTimeoutAfter(8000)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID + 1, headsUp)
    }

    // ---- Sesli yanıt (TTS) ----

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("tr", "TR"))
                ttsReady = result != null &&
                        result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED

                if (!ttsReady) {
                    // Türkçe veri yoksa sistem diline düş
                    val fallback = tts?.setLanguage(Locale.getDefault())
                    ttsReady = fallback != null &&
                            fallback != TextToSpeech.LANG_MISSING_DATA &&
                            fallback != TextToSpeech.LANG_NOT_SUPPORTED
                }

                tts?.setSpeechRate(1.0f)
                Log.i(TAG, "TTS hazır: $ttsReady")
            } else {
                Log.e(TAG, "TTS başlatılamadı, kod: $status")
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            Log.w(TAG, "TTS hazır olmadığı için sesli bildirim yapılamadı: $text")
            return
        }
        // TTS varsayılan olarak ortam (medya) kanalından ses verir
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "uyan_tts")
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
            "Uyan:WakeLock"
        ).apply {
            acquire(10_000) // 10 saniye
        }
    }

    // ---- Bildirim altyapısı ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Uyan Dinleme",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ekran kapalıyken uyan kelimesini dinler"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val openApp = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Uyan Wake Word")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onDestroy() {
        Log.i(TAG, "Servis durduruluyor")
        detectionJob?.cancel()
        detectionJob = null
        try {
            engine?.stop()
        } catch (_: Exception) {
        }
        try {
            engine?.release()
        } catch (_: Exception) {
        }
        engine = null
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        screenReceiver = null
        serviceScope.cancel()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
