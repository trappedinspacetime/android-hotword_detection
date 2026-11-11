package com.uyan.wakeword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Sürekli mikrofon dinleyen foreground service.
 * "uyan" duyulunca ekranı uyandırır.
 */
class WakeWordService : Service() {

    companion object {
        const val CHANNEL_ID = "uyan_wakeword_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "UyanWakeWord"

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
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Dinleniyor: uyan")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startListening()
        return START_STICKY
    }

    private fun startListening() {
        // Model dosyası assets/uyan.onnx olmalı
        val models = listOf(
            WakeWordModel(
                name = "uyan",
                modelPath = "uyan.onnx",
                threshold = 0.45f          // İlk değer, sonra ayarlanır
            )
        )

        engine = WakeWordEngine(
            context = this,
            models = models,
            detectionCooldownMs = 3000L    // Aynı kelimeyi 3 sn içinde tekrar sayma
        )

        serviceScope.launch {
            engine?.detections?.collect { detection ->
                Log.i(TAG, "Tespit edildi: ${detection.model.name}  skor=${detection.score}")
                onWakeWordDetected()
            }
        }

        try {
            engine?.start()
            Log.i(TAG, "Dinleme başladı")
        } catch (e: Exception) {
            Log.e(TAG, "Engine başlatılamadı", e)
            updateNotification("Hata: mikrofon izni veya model eksik")
        }
    }

    private fun onWakeWordDetected() {
        // 1. Ekranı uyandır
        acquireWakeLock()

        // 2. MainActivity'yi kilide rağmen aç (full-screen intent tarzı)
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("from_wakeword", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Yüksek öncelikli bildirim (kilit ekranında görünür)
        val headsUp = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Uyan!")
            .setContentText("Wake word algılandı")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .setTimeoutAfter(8000)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID + 1, headsUp)

        // Doğrudan aktiviteyi de dene
        try {
            startActivity(launchIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Activity açılamadı (normal olabilir)", e)
        }
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Uyan Dinleme",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Arka planda uyan kelimesini dinler"
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
        engine?.stop()
        engine?.release()
        engine = null
        serviceScope.cancel()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
