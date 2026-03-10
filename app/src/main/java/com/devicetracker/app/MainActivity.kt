package com.devicetracker.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // ⚠️ SUNUCU ADRESİNİZİ BURAYA YAZIN
    private val SERVER_URL = "https://10.1.1.46/api/device_report.php"
    private val SEND_INTERVAL_MINUTES = 5L

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var lastSentText: TextView
    private lateinit var infoText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Basit UI oluştur
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 80, 60, 60)
            setBackgroundColor(Color.parseColor("#1a1a2e"))
        }

        // Başlık
        val title = TextView(this).apply {
            text = "📱 Device Tracker"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }

        val subtitle = TextView(this).apply {
            text = "Cihaz takip sistemi aktif"
            textSize = 14f
            setTextColor(Color.parseColor("#aaaaaa"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        // Durum kutusu
        statusText = TextView(this).apply {
            text = "⏳ Bağlanıyor..."
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#16213e"))
            setPadding(32, 32, 32, 32)
        }

        // Son gönderim
        lastSentText = TextView(this).apply {
            text = "Son gönderim: —"
            textSize = 13f
            setTextColor(Color.parseColor("#aaaaaa"))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }

        // Cihaz bilgileri
        infoText = TextView(this).apply {
            text = getDeviceInfoText()
            textSize = 13f
            setTextColor(Color.parseColor("#cccccc"))
            setBackgroundColor(Color.parseColor("#16213e"))
            setPadding(32, 32, 32, 32)
            lineSpacingMultiplier = 1.6f
        }

        val intervalText = TextView(this).apply {
            text = "🔄 Her $SEND_INTERVAL_MINUTES dakikada bir veri gönderilir"
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(statusText)
        layout.addView(lastSentText)
        layout.addView(infoText)
        layout.addView(intervalText)
        scroll.addView(layout)
        setContentView(scroll)

        checkAndRequestPermissions()
    }

    private fun getDeviceInfoText(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val serial = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial()
            else @Suppress("DEPRECATION") Build.SERIAL
        } catch (e: Exception) { "UNKNOWN" }

        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        return """
🏷️  Marka:  ${Build.BRAND} ${Build.MODEL}
🔢  Seri No:  $serial
🤖  Android:  ${Build.VERSION.RELEASE}
🔑  Android ID:  ${androidId.take(12)}...
🔋  Batarya:  %$battery
        """.trimIndent()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        val notGranted = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 100)
        } else {
            startTracking()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startTracking()
    }

    private fun startTracking() {
        updateStatus("🔄 İlk veri gönderiliyor...", "#f59e0b")
        sendDeviceData()
        handler.postDelayed(object : Runnable {
            override fun run() {
                sendDeviceData()
                handler.postDelayed(this, SEND_INTERVAL_MINUTES * 60 * 1000)
            }
        }, SEND_INTERVAL_MINUTES * 60 * 1000)
    }

    private fun updateStatus(msg: String, color: String) {
        runOnUiThread {
            statusText.text = msg
            statusText.setBackgroundColor(Color.parseColor(color.replace("#", "#33").padEnd(9, '0').let {
                "#${color.removePrefix("#")}"
            }))
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            lastSentText.text = "Son güncelleme: ${sdf.format(Date())}"
            infoText.text = getDeviceInfoText()
        }
    }

    private fun sendDeviceData() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            sendToServer(buildJson(null, null))
            return
        }
        LocationServices.getFusedLocationProviderClient(this).lastLocation
            .addOnSuccessListener { location ->
                sendToServer(buildJson(location?.latitude, location?.longitude))
            }
            .addOnFailureListener {
                sendToServer(buildJson(null, null))
            }
    }

    private fun buildJson(lat: Double?, lng: Double?): JSONObject {
        val json = JSONObject()
        json.put("android_id", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID))
        val serial = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial()
            else @Suppress("DEPRECATION") Build.SERIAL
        } catch (e: Exception) { "UNKNOWN" }
        json.put("serial_number", serial)
        json.put("brand", Build.BRAND)
        json.put("manufacturer", Build.MANUFACTURER)
        json.put("model", Build.MODEL)
        json.put("android_version", Build.VERSION.RELEASE)
        json.put("sdk_version", Build.VERSION.SDK_INT)
        json.put("latitude", lat ?: JSONObject.NULL)
        json.put("longitude", lng ?: JSONObject.NULL)
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        json.put("battery_level", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        json.put("is_charging", bm.isCharging)
        json.put("timestamp", System.currentTimeMillis() / 1000)
        return json
    }

    private fun sendToServer(json: JSONObject) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL(SERVER_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) {
                    updateStatus("✅ Sunucuya bağlandı", "#10b981")
                } else {
                    updateStatus("⚠️ Sunucu hatası: HTTP $code", "#f59e0b")
                }
            } catch (e: Exception) {
                Log.e("DeviceTracker", "Hata: ${e.message}")
                updateStatus("❌ Bağlantı hatası\n${e.message}", "#ef4444")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
