package com.uyan.wakeword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Telefon açıldıktan sonra servisi yeniden başlatmaya çalışır.
 * Android 11+ mikrofon FGS'yi arka plandan başlatmaya izin vermez,
 * bu yüzden bildirimle kullanıcıyı uygulamaya yönlendiririz.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i("UyanBoot", "Boot completed")

            // Android 10 ve altı doğrudan başlatılabilir
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                WakeWordService.start(context)
            } else {
                // Android 11+ : kullanıcıya bildirim göster, tıklayınca app açılsın
                // (daha sonra Notification ile yapılabilir)
                Log.i("UyanBoot", "Android 11+: kullanıcı uygulamanın kendisi açmalı")
            }
        }
    }
}
