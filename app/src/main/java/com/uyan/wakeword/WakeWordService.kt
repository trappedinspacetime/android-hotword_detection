package com.uyan.wakeword

import android.app.AlarmManager
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
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * Sürekli çalışan foreground service.
 *
 * Mikrofon yönetimi ekran durumuna ve arama durumuna göredir:
 *  - Ekran KAPALI + arama YOK : "uyan" kelimesini dinler (mikrofonu alır)
 *  - Ekran AÇIK               : mikrofonu BIRAKIR; böylece Hey Google,
 *    Termux'taki sesle erişim programı vb. mikrofonu kullanabilir
 *  - Arama var (RINGING/OFFHOOK) : mikrofonu KENDİMİZ bırakırız; Telecom ile
 *    çakışma olmaz, "Failed to initialize AudioRecord" hatası hiç oluşmaz
 *  - Arama bitince (IDLE)     : ekran kapalıysa dinleme ~3 sn sonra geri gelir
 *
 * Bu dosyadaki dayanıklılık korumaları (arama sonrası ölümün çözümü):
 *  1. CoroutineExceptionHandler : yakalanmayan hata process'i artık ÖLDÜRMEZ
 *     (eskiden arama sırasında mic alınca fırlayan hata uygulamayı öldürüyordu)
 *  2. Retry'li başlatma         : mikrofon o an kapaysa geri çekilerek (backoff)
 *     yeniden dener; artık "ekran kapanınca dener" deyip susmuyor
 *  3. detections .catch         : algılama akışı ölürse sessizce yeniden başlatır
 *  4. cancelAndJoin'li kapatma  : bırakma/inference yarışında native çökme olmaz
 *  5. Watchdog (60 sn)          : servis bir şekilde sustuysa kendi kendine toparlar
 *  6. onTaskRemoved             : son uygulamalardan kaydırılırsa 5 sn sonra geri gelir
 *  7. Periyodik tazeleme (5 dk) : kütüphane içinden bize bildirilmeyen "sessiz
 *     motor ölümlerine" karşı engine 5 dakikada bir tazelenir
 *  8. Durum ikonu + kalp atışı  : bildirim ikonu dinleme durumunu gösterir
 *     (mikrofon = dinliyor); her 60 sn'de durum logcat'e yazılır
 *  9. Crash guard (v3)          : kütüphanenin kendi coroutine'i içinde fırlayıp
 *     process'i öldüren "Failed to initialize AudioRecord" gibi hatalar artık
 *     yutulur; motor geri çekilerek (backoff) otomatik onarılır
 *
 * "uyan" duyulunca: ekranı uyandırır, Termux'u açar,
 * "Termux açıldı" diye sesli bildirir (Google TTS).
 */
class WakeWordService : Service() {

    companion object {
        // v2: kanal importance'ı güncellendiği için kimlik yenilendi
        // (Android, kanal importance'ını geriye dönük değiştirmeye izin vermez)
        const val CHANNEL_ID = "uyan_wakeword_channel_v2"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "UyanWakeWord"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_ACTIVITY = "com.termux.app.TermuxActivity"

        // YENİ: dayanıklılık ayarları
        private const val MAX_START_ATTEMPTS = 30         // start() için deneme sayısı
        private const val WATCHDOG_INTERVAL_MS = 60_000L  // kendi kendine toparlama aralığı
        private const val CALL_RESUME_DELAY_MS = 3_000L   // arama bitince bekleme süresi
        private const val RECYCLE_TICKS = 5               // YENİ: 5 tick x 60 sn = 5 dk'da bir engine tazeleme

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

    // YENİ: mikrofonun o anki durumu
    private enum class MicState {
        LISTENING,    // dinliyoruz
        RELEASED,     // boşta (ekran açık veya dinleme yok)
        PAUSED_CALL,  // arama sürüyor, arama bitince otomatik devam edecek
        RECOVERING    // dinleme başlatılmaya çalışılıyor (retry döngüsü çalışıyor)
    }

    @Volatile
    private var engine: WakeWordEngine? = null
    @Volatile
    private var detectionJob: Job? = null
    private var startJob: Job? = null       // YENİ: başlatma/retry döngüsü
    private var watchdogJob: Job? = null    // YENİ: kendi kendine toparlama
    private var recoveryJob: Job? = null    // v3: motor içi çökme sonrası gecikmeli toparlama

    @Volatile
    private var engineFailureCount = 0      // v3: üst üste motor içi hata sayısı (backoff için)

    @Volatile
    private var micState: MicState = MicState.RELEASED   // YENİ

    // DEĞİŞTİ: CoroutineExceptionHandler eklendi. Eskiden bu scope'a düşen bir
    // hata process'i öldürüyordu (arama sonrası ölümün asıl nedeni);
    // artık sadece loglanıyor ve servis çalışmaya devam ediyor.
    private val serviceScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() +
                CoroutineExceptionHandler { _, e ->
                    Log.e(TAG, "Yakalanmayan hata (servis çalışmaya devam ediyor)", e)
                }
    )

    // YENİ: engine başlatma/durdurma işlemlerini sıraya koyar; iki işlemin
    // aynı anda engine'e dokunması (native çökme riski) engellenir
    private val micMutex = Mutex()

    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var phoneReceiver: BroadcastReceiver? = null   // YENİ

    // Sesli yanıt için TTS
    private var tts: TextToSpeech? = null
    @Volatile
    private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        // v3: kütüphane içi çökmeleri yutan koruma — process'i öldürmesin
        installCrashGuard()
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
        // YENİ: arama başlayınca/bitinca mikrofonu yönet
        registerPhoneStateReceiver()
        // YENİ: kendi kendine toparlama güvenlik ağı
        startWatchdog()
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
        val receiver = object : BroadcastReceiver() {
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
        screenReceiver = receiver
        registerReceiver(receiver, filter)
    }

    // ---- YENİ: arama durumuna göre mikrofon yönetimi ----
    // PHONE_STATE broadcast'i izin gerektirmez; sadece arama durumu okunur.

    private fun registerPhoneStateReceiver() {
        if (phoneReceiver != null) return
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                    TelephonyManager.EXTRA_STATE_RINGING,
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        // Arama çalıyor veya görüşme sürüyor:
                        // mikrofonu kendimiz bırakırız → Telecom çakışmaz
                        Log.i(TAG, "Arama aktif → mikrofon bırakılır")
                        releaseMic(pauseForCall = true)
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        // Arama bitti (kapandı, cevaplanıp bitti veya kaçtı)
                        Log.i(TAG, "Arama bitti (IDLE)")
                        if (micState == MicState.PAUSED_CALL) {
                            micState = MicState.RELEASED
                            // AudioRecord'in sistem tarafında serbest kalması
                            // için kısa bir bekleme; sonra dinleme geri gelir
                            serviceScope.launch {
                                delay(CALL_RESUME_DELAY_MS)
                                if (micState == MicState.RELEASED && !screenIsOn()) {
                                    Log.i(TAG, "Arama sonrası dinleme yeniden başlatılıyor")
                                    takeMic()
                                }
                            }
                        }
                    }
                }
            }
        }
        phoneReceiver = receiver
        registerReceiver(receiver, filter)
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

    /** Mikrofonu al ve "uyan"ı dinlemeye başla (DEĞİŞTİ: retry'li başlatma) */
    private fun takeMic() {
        if (micState == MicState.LISTENING) return   // zaten dinliyor
        if (micState == MicState.PAUSED_CALL) {      // arama sürüyor
            Log.i(TAG, "Arama sürüyor; dinleme arama bitince başlayacak")
            return
        }
        requestStart()
    }

    /** YENİ: tüm başlatmalar tek noktadan ve tek job üzerinden yapılır */
    private fun requestStart() {
        micState = MicState.RECOVERING
        updateNotification("Dinleme başlatılıyor...")
        startJob?.cancel()
        startJob = serviceScope.launch {
            micMutex.withLock {
                startListeningWithRetry()
            }
        }
    }

    /** Mikrofonu bırak: başka uygulama (sesle erişim, Hey Google) kullanabilsin */
    private fun releaseMic(pauseForCall: Boolean = false) {
        micState = if (pauseForCall) MicState.PAUSED_CALL else MicState.RELEASED
        startJob?.cancel()
        startJob = null
        recoveryJob?.cancel()   // v3
        recoveryJob = null
        // Engine'i güvenle kapat: collector iptal edilip bitmesi beklenir,
        // sonra stop/release çağrılır (native çökme yarışı önlenir)
        serviceScope.launch {
            micMutex.withLock {
                // Bu stop güncelliğini yitirdiyse (ardından tekrar dinleme
                // başlatıldıysa) dokunma; taze engine'i öldürme
                if (micState == MicState.RELEASED || micState == MicState.PAUSED_CALL) {
                    stopEngineSafely()
                }
            }
        }
        updateNotification(
            if (pauseForCall) "Arama sürüyor; mikrofon boşta"
            else "Beklemede (ekran açık, mikrofon boşta)"
        )
    }

    /**
     * YENİ: Engine'i güvenle kapat.
     * Önce algılama akışını iptal edip BİTMEYİ BEKLERİZ (cancelAndJoin);
     * böylece inference ortasında native tarafın serbest bırakılması gibi
     * yarışlar (SIGSEGV) önlenir, ancak ondan sonra stop/release çağrılır.
     */
    private suspend fun stopEngineSafely() {
        detectionJob?.cancelAndJoin()
        detectionJob = null
        val e = engine
        engine = null
        if (e != null) {
            try {
                e.stop()
            } catch (ex: Exception) {
                Log.w(TAG, "engine.stop() hatası", ex)
            }
            // v3: motorun iç pipeline'ının sürmekte olan inference'ını bitirmesine
            // kısa süre tanı; release() ile yarışı azaltır (OrtSession.run
            // içindeki SIGSEGV riski)
            delay(300)
            try {
                e.release()
            } catch (ex: Exception) {
                Log.w(TAG, "engine.release() hatası", ex)
            }
        }
    }

    private fun releaseEngineQuietly(e: WakeWordEngine) {
        try {
            e.stop()
        } catch (_: Exception) {
        }
        try {
            e.release()
        } catch (_: Exception) {
        }
    }

    /**
     * YENİ: Dinlemeyi başlat; mikrofon o an başkasındaysa (ör. hâlâ
     * kapanmamış bir arama) geri çekilerek yeniden dener.
     * micMutex.withLock İÇİNDE çağrılır (requestStart'e bakın).
     */
    private suspend fun startListeningWithRetry() {
        // Olası eski/kalan engine'i temizle
        stopEngineSafely()

        var attempt = 0
        while (micState == MicState.RECOVERING && attempt < MAX_START_ATTEMPTS) {
            // Bu sırada ekran açıldıysa politikaya göre beklemede kal
            if (screenIsOn()) {
                micState = MicState.RELEASED
                updateNotification("Beklemede (ekran açık, mikrofon boşta)")
                return
            }
            attempt++
            var newEngine: WakeWordEngine? = null
            try {
                // Model dosyası assets/uyan.onnx olmalı
                val models = listOf(
                    WakeWordModel(
                        name = "uyan",
                        modelPath = "uyan.onnx",
                        threshold = 0.45f
                    )
                )
                newEngine = WakeWordEngine(
                    context = this,
                    models = models,
                    detectionCooldownMs = 3000L    // Aynı kelimeyi 3 sn içinde tekrar sayma
                )
                newEngine.start()                  // Mikrofon başkasındaysa burada exception fırlar
                engine = newEngine
                watchDetections(newEngine)
                micState = MicState.LISTENING
                Log.i(TAG, "Dinleme başladı (deneme $attempt)")
                updateNotification("Dinliyor: uyan (ekran kapalı)")
                return
            } catch (e: CancellationException) {
                // releaseMic() vs. çağrıldı → sessizce çık
                newEngine?.let { releaseEngineQuietly(it) }
                throw e
            } catch (e: Exception) {
                newEngine?.let { releaseEngineQuietly(it) }
                Log.w(
                    TAG,
                    "Dinleme başlatılamadı (deneme $attempt/$MAX_START_ATTEMPTS): ${e.message}"
                )
                if (attempt < MAX_START_ATTEMPTS) {
                    // 2s, 4s, 6s ... en fazla 15s aralıkla yeniden dene
                    delay(minOf(2_000L * attempt, 15_000L))
                }
            }
        }

        if (micState == MicState.RECOVERING) {
            micState = MicState.RELEASED
            Log.w(TAG, "Dinleme $MAX_START_ATTEMPTS denemede başlatılamadı; watchdog yeniden deneyecek")
            updateNotification("Mikrofon alınamadı; periyodik olarak yeniden denenecek")
        }
    }

    /**
     * YENİ: Algılama akışını hata korumalı izler.
     * Eskiden akış içinde bir hata olursa (ör. mikrofon sistem tarafından
     * zorla alındıysa) handler yoktu → process ölüyordu.
     * Artık hata loglanır ve dinleme kısa süre sonra sessizce yeniden başlar.
     */
    private fun watchDetections(target: WakeWordEngine) {
        detectionJob = serviceScope.launch {
            target.detections
                .catch { e ->
                    Log.w(TAG, "Algılama akışı hata ile sonlandı: ${e.message}")
                    if (micState == MicState.LISTENING) {
                        micState = MicState.RECOVERING
                        serviceScope.launch {
                            delay(CALL_RESUME_DELAY_MS)
                            if (micState == MicState.RECOVERING) {
                                Log.i(TAG, "Dinleme akış hatası sonrası yeniden başlatılıyor")
                                requestStart()
                            }
                        }
                    }
                }
                .collect { detection ->
                    Log.i(TAG, "Tespit edildi: ${detection.model.name}  skor=${detection.score}")
                    onWakeWordDetected()
                }
        }
    }

    // ---- YENİ: kendi kendine toparlama (watchdog) ----

    /**
     * Her 60 sn'de bir durumu kontrol eder:
     *  - ekran kapalı ama dinleme yoksa → yeniden başlatır
     *    (retry döngüsü tükendiğinde veya servis sustuğunda kurtarır)
     *  - "dinliyoruz" ama engine kaybolmuşsa → yeniden başlatır
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            var listeningTicks = 0
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                try {
                    // YENİ: kalp atışı — teşhis için durum her 60 sn'de loglanır
                    Log.i(
                        TAG,
                        "Kalp atışı: durum=$micState engineVar=${engine != null} ekranAçık=${screenIsOn()}"
                    )
                    if (screenIsOn()) continue

                    // YENİ: sessiz ölüm sigortası. Engine "LISTENING" gözüküp
                    // içeriden ölmüş olabilir (kütüphane hatayı bize
                    // bildirmiyorsa bunu dışarıdan bilmek imkânsız).
                    // 5 dakikadır kesintisiz dinliyorsa engine'i tazeliyoruz:
                    // ~1-2 saniyelik bir dinleme boşluğu oluşur, karşılığında
                    // hiçbir sessiz ölüm 5 dakikadan uzun sürmez.
                    if (micState == MicState.LISTENING && engine != null) {
                        // v3: bir dakikayı sorunsuz geçirdiyse hata sayacını sıfırla
                        engineFailureCount = 0
                        listeningTicks++
                        if (listeningTicks >= RECYCLE_TICKS) {
                            Log.w(TAG, "Periyodik tazeleme: engine 5 dk'dır dinliyordu, tazeleniyor")
                            listeningTicks = 0
                            micState = MicState.RECOVERING
                            micMutex.withLock { stopEngineSafely() }
                            takeMic()
                            continue
                        }
                    } else {
                        listeningTicks = 0
                    }

                    when {
                        micState == MicState.RELEASED -> {
                            Log.w(TAG, "Watchdog: ekran kapalı ama dinleme yok → başlatılıyor")
                            takeMic()
                        }
                        micState == MicState.LISTENING && engine == null -> {
                            Log.w(TAG, "Watchdog: dinleme durumu tutarsız → yeniden başlatılıyor")
                            requestStart()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Watchdog hatası", e)
                }
            }
        }
    }

    // ---- v3: motor içi çökmelere karşı process koruması ----

    /**
     * KRİTİK DÜZELTME (crash.txt analizi):
     *
     * "Failed to initialize AudioRecord" (AudioRecorder.kt:64) hatası
     * kütüphanenin KENDİ coroutine'inin içinde fırlıyor — WakeWordEngine
     * kendi scope'unu CoroutineExceptionHandler'sız kuruyor. Bu hata bizim
     * try/catch'lerimize ve .catch'imize ULAŞMIYOR; doğrudan process'i
     * öldürüyor. Log'daki kalıp: process yeniden başlıyor → mikrofon bu
     * sırada Termux script'inde → init yine başarısız → 2 saniyede bir
     * öl-diren döngüsü... sabaha kadar. Hotword'ün geri gelmemesinin asıl
     * nedeni buydu.
     *
     * Çözüm: varsayılan UncaughtExceptionHandler'a bir "guard" takıyoruz.
     * Kütüphaneden gelen istisnalar YUTULUR (process yaşar) ve toparlama
     * tetiklenir. Diğer tüm istisnalar gerçek handler'a devredilir.
     */
    private fun installCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val fromLibrary = throwable.stackTrace.any { st ->
                st.className.startsWith("com.rementia.openwakeword") ||
                        st.className.startsWith("ai.onnxruntime")
            }
            if (fromLibrary) {
                handleEngineInternalFailure(throwable)
            } else {
                previous?.uncaughtException(thread, throwable)
            }
        }
        Log.i(TAG, "Crash guard kuruldu")
    }

    /**
     * Motor içi hata geldi (process hayatta kaldı). Motor bozulmuş olabilir:
     * durumdan çıkar, biraz bekle (backoff) ve temizce yeniden başlat.
     */
    private fun handleEngineInternalFailure(throwable: Throwable) {
        if (micState != MicState.LISTENING && micState != MicState.RECOVERING) return

        micState = MicState.RECOVERING
        engineFailureCount++
        val backoffMs = minOf(2_000L * engineFailureCount, 15_000L)
        Log.e(
            TAG,
            "Motor içi hata yutuldu (#$engineFailureCount): ${throwable.message} " +
                    "→ ${backoffMs} ms sonra yeniden denenecek"
        )
        updateNotification("Motor hatası; ${backoffMs / 1000} sn sonra yeniden denenecek")

        recoveryJob?.cancel()
        recoveryJob = serviceScope.launch {
            delay(backoffMs)
            micMutex.withLock { stopEngineSafely() }
            if (micState == MicState.RECOVERING) {
                Log.i(TAG, "Motor içi hata sonrası dinleme yeniden başlatılıyor")
                takeMic()
            }
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
            // DEĞİŞTİ: IMPORTANCE_LOW kanal bazı ROM'lar status bar'da sadece
            // küçük bir nokta olarak gösteriyordu. DEFAULT importance +
            // ses/titreme kapalı: ikon status bar'da GÖRÜNÜR ama sessizdir.
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Uyan Dinleme",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Ekran kapalıyken uyan kelimesini dinler"
                setShowBadge(false)
                setSound(null, null)    // ses yok
                enableVibration(false)  // titreme yok
                enableLights(false)     // ışık yok
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * YENİ: Duruma göre bildirim ikonu — durum çubuğundan tek bakışta
     * anlaşılır:
     *  - mikrofon ikonu  = dinliyor (hotword aktif)
     *  - senkron ikonu   = dinleme başlatılmaya çalışılıyor
     *  - bekleme ikonu   = mikrofon boşta (ekran açık / arama sürüyor)
     */
    private fun statusIcon(): Int = when (micState) {
        MicState.LISTENING -> android.R.drawable.ic_btn_speak_now
        MicState.RECOVERING -> android.R.drawable.ic_popup_sync
        else -> android.R.drawable.ic_media_pause
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
            .setSmallIcon(statusIcon())
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

    // ---- YENİ: son uygulamalardan kaydırılınca yeniden başlatma ----

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "Görev sonlandırıldı; servis 5 sn sonra yeniden başlatılacak")
        try {
            val restart = Intent(applicationContext, WakeWordService::class.java)
            val pending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    applicationContext, 2, restart,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    applicationContext, 2, restart,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val am = getSystemService(AlarmManager::class.java)
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 5_000,
                pending
            )
        } catch (e: Exception) {
            Log.e(TAG, "Yeniden başlatma planlanamadı", e)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Servis durduruluyor")
        micState = MicState.RELEASED
        startJob?.cancel()
        startJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        recoveryJob?.cancel()
        recoveryJob = null
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
        phoneReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        phoneReceiver = null
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
