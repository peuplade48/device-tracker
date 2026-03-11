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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Sunucu adresi - IP adresinizin doğruluğunu kontrol edin
    private val SERVER_URL = "http://10.1.1.46/api/device_report.php"
    private val SEND_INTERVAL_MINUTES = 5L
    private val TAG = "DeviceTracker"
    
    // ELLE GİRİLEN SÜRÜM NUMARASI
    private val MANUAL_VERSION_NAME = "3.0-MANUAL-FIX"

    private val handler = Handler(Looper.getMainLooper())
    private var isTaskRunning = false

    private lateinit var tvStatus: TextView
    private lateinit var tvLastSent: TextView
    private lateinit var tvErrorLog: TextView
    private lateinit var tvInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dinamik Arayüz Tasarımı
        val scroll = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 60, 40, 40)
        layout.setBackgroundColor(Color.parseColor("#121212"))

        val tvTitle = TextView(this)
        tvTitle.text = "Cihaz Takip Sistemi v3"
        tvTitle.textSize = 22f
        tvTitle.setTextColor(Color.WHITE)
        tvTitle.gravity = Gravity.CENTER
        tvTitle.setPadding(0, 0, 0, 30)

        tvStatus = TextView(this)
        tvStatus.text = "Arayüz Hazırlandı..."
        tvStatus.textSize = 16f
        tvStatus.setTextColor(Color.CYAN)
        tvStatus.gravity = Gravity.CENTER
        tvStatus.setPadding(20, 20, 20, 20)

        tvLastSent = TextView(this)
        tvLastSent.text = "Başlatılıyor..."
        tvLastSent.textSize = 12f
        tvLastSent.setTextColor(Color.GRAY)
        tvLastSent.gravity = Gravity.CENTER

        // CANLI LOG EKRANI
        tvErrorLog = TextView(this)
        tvErrorLog.text = "LOG: Uygulama başlatıldı."
        tvErrorLog.textSize = 11f
        tvErrorLog.setTextColor(Color.parseColor("#ff6b6b"))
        tvErrorLog.setBackgroundColor(Color.parseColor("#1a1a1a"))
        tvErrorLog.setPadding(20, 20, 20, 20)
        val errorLayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        errorLayoutParams.setMargins(0, 30, 0, 30)
        tvErrorLog.layoutParams = errorLayoutParams

        tvInfo = TextView(this)
        tvInfo.text = "Sistem bilgileri okunuyor..."
        tvInfo.textSize = 13f
        tvInfo.setTextColor(Color.LTGRAY)
        tvInfo.setPadding(20, 20, 20, 20)

        layout.addView(tvTitle)
        layout.addView(tvStatus)
        layout.addView(tvLastSent)
        layout.addView(tvErrorLog)
        layout.addView(tvInfo)
        scroll.addView(layout)
        setContentView(scroll)

        // TAKILMAYI ÖNLEME: 1.5 saniye sonra işlemleri başlat
        handler.postDelayed({
            tvInfo.text = getDeviceInfoText()
            checkAndRequestPermissions()
        }, 1500)
    }

    private fun getDeviceInfoText(): String {
        return try {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            "Üretici: ${Build.MANUFACTURER}\n" +
            "Model: ${Build.MODEL}\n" +
            "Elle Girilen Sürüm: $MANUAL_VERSION_NAME\n" +
            "Android OS: ${Build.VERSION.RELEASE}\n" +
            "ID: $androidId"
        } catch (e: Exception) {
            "Bilgi okuma hatası: ${e.message}"
        }
    }

    private fun checkAndRequestPermissions() {
        try {
            logMessage("İzinler kontrol ediliyor...")
            val permissions = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.INTERNET
            )
            
            val needsPermission = permissions.any {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (needsPermission) {
                updateStatus("İzin Onayı Bekleniyor...")
                ActivityCompat.requestPermissions(this, permissions, 100)
            } else {
                logMessage("İzinler OK. Takip başlıyor.")
                startTracking()
            }
        } catch (e: Exception) {
            logMessage("İzin İsteme Hatası: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        logMessage("İzin isteği sonuçlandı. Başlatılıyor...")
        startTracking()
    }

    private fun startTracking() {
        if (isTaskRunning) return
        isTaskRunning = true
        
        updateStatus("Döngü Başlatıldı")
        
        val runnable = object : Runnable {
            override fun run() {
                executeTask()
                handler.postDelayed(this, SEND_INTERVAL_MINUTES * 60 * 1000)
            }
        }
        handler.post(runnable)
    }

    private fun updateStatus(msg: String) {
        runOnUiThread {
            tvStatus.text = msg
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            tvLastSent.text = "Son İşlem: ${sdf.format(Date())}"
        }
    }

    private fun logMessage(msg: String) {
        runOnUiThread {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val current = tvErrorLog.text.toString()
            tvErrorLog.text = "[$ts] $msg\n---\n$current".take(1200)
            Log.d(TAG, msg)
        }
    }

    private fun executeTask() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                updateStatus("Konum Alınıyor...")
                val fusedClient = LocationServices.getFusedLocationProviderClient(this@MainActivity)
                
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    logMessage("Konum izni yok. Boş koordinat gönderiliyor.")
                    sendToServer(buildJson(0.0, 0.0, 0f))
                    return@launch
                }

                fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            updateStatus("Konum Alındı. Gönderiliyor...")
                            sendToServer(buildJson(loc.latitude, loc.longitude, loc.accuracy))
                        } else {
                            logMessage("GPS/Konum sinyali boş döndü.")
                            sendToServer(buildJson(0.0, 0.0, 0f))
                        }
                    }
                    .addOnFailureListener { e ->
                        logMessage("Konum API Hatası: ${e.message}")
                        sendToServer(buildJson(0.0, 0.0, 0f))
                    }
            } catch (e: Exception) {
                logMessage("Görev Hatası: ${e.message}")
            }
        }
    }

    private fun buildJson(lat: Double, lng: Double, acc: Float): JSONObject {
        val json = JSONObject()
        try {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            
            json.put("android_id", androidId)
            json.put("serial_number", Build.SERIAL ?: "UNKNOWN")
            json.put("brand", Build.BRAND)
            json.put("manufacturer", Build.MANUFACTURER)
            json.put("model", Build.MODEL)
            
            // MANUEL SÜRÜM
            json.put("android_version", MANUAL_VERSION_NAME) 
            json.put("sdk_version", Build.VERSION.SDK_INT)
            
            json.put("latitude", lat)
            json.put("longitude", lng)
            json.put("location_accuracy", acc)
            
            json.put("battery_level", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            json.put("is_charging", bm.isCharging)
            
            logMessage("Veri paketi oluşturuldu.")
        } catch (e: Exception) {
            logMessage("JSON Hatası: ${e.message}")
        }
        return json
    }

    private fun sendToServer(json: JSONObject) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) { updateStatus("Sunucuya bağlanılıyor...") }
                
                val url = URL(SERVER_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                
                OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
                
                val code = conn.responseCode
                val response = if (code == 200) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Hata detayı yok"
                }
                conn.disconnect()

                withContext(Dispatchers.Main) {
                    if (code == 200) {
                        updateStatus("Veri Gönderildi ✅")
                        logMessage("Sunucu Yanıtı: $response")
                    } else {
                        updateStatus("Sunucu Hatası: $code")
                        logMessage("Sunucu Hatası ($code): $response")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateStatus("Bağlantı Hatası ⚠️")
                    logMessage("Ağ Hatası: ${e.message}\nIP/WiFi kontrolü yapın.")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
