package com.uyan.wakeword

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var batteryButton: Button
    private lateinit var overlayButton: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startService()
        } else {
            Toast.makeText(this, "Mikrofon ve bildirim izni gerekli", Toast.LENGTH_LONG).show()
            updateStatus("İzinler eksik")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Kilit ekranında görünmesi için
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        batteryButton = findViewById(R.id.batteryButton)
        overlayButton = findViewById(R.id.overlayButton)

        toggleButton.setOnClickListener {
            if (isServiceRunning()) {
                stopService()
            } else {
                checkPermissionsAndStart()
            }
        }

        batteryButton.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        // "Uyan" kelimesiyle açıldıysa ekran üstü izin önemli
        overlayButton.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Bu izin zaten verildi", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Listeden Uyan Wake Word'i bulup \"Diğer uygulamaların üzerinde göster\" iznini açın",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        // Wake word'ten geldiyse
        if (intent.getBooleanExtra("from_wakeword", false)) {
            statusText.text = "Uyan algılandı!"
        }

        updateStatus(if (isServiceRunning()) "Dinleniyor..." else "Durduruldu")
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startService() {
        WakeWordService.start(this)
        getSharedPreferences("uyan", MODE_PRIVATE)
            .edit().putBoolean("service_running", true).apply()
        updateStatus("Dinleniyor...")
        Toast.makeText(this, "Dinleme başladı", Toast.LENGTH_SHORT).show()
    }

    private fun stopService() {
        WakeWordService.stop(this)
        getSharedPreferences("uyan", MODE_PRIVATE)
            .edit().putBoolean("service_running", false).apply()
        updateStatus("Durduruldu")
        Toast.makeText(this, "Dinleme durduruldu", Toast.LENGTH_SHORT).show()
    }

    private fun isServiceRunning(): Boolean {
        val prefs = getSharedPreferences("uyan", MODE_PRIVATE)
        return prefs.getBoolean("service_running", false)
    }

    private fun updateStatus(text: String) {
        statusText.text = text
        toggleButton.text = if (text.contains("Dinleniyor")) "Durdur" else "Başlat"
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Zaten muaf", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
