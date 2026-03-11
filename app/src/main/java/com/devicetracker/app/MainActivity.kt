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

    // Sunucu adresi - IP'nizi buradan kontrol edin
    private val SERVER_URL = "http://10.1.1.46/api/device_report.php"
    private val SEND_INTERVAL_MINUTES = 5L
    private val TAG = "DeviceTracker"
    
    // ELLE GİRİLEN SÜRÜM NUMARASI
    private val MANUAL_VERSION_NAME = "2.0.1-TEST"

    private val handler = Handler(Looper.getMainLooper())
    private var isTaskRunning = false

    private lateinit var tvStatus: TextView
    private lateinit var tvLastSent: TextView
    private lateinit var tvErrorLog: TextView
    private lateinit var tvInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dinamik Arayüz Kurulumu
        val scroll = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 60, 40, 40)
        layout.setBackgroundColor(Color.parseColor("#0f3460"))

        val tvTitle = TextView(this)
        tvTitle.text = "Cihaz Takip Sistemi"
        tvTitle.textSize = 22f
        tvTitle.setTextColor(Color.WHITE)
        tvTitle.gravity = Gravity.CENTER
        tvTitle.setPadding(0, 0, 0, 30)

        tvStatus = TextView(this)
        tvStatus.text = "Sistem Başlatılıyor..."
        tvStatus.textSize = 16f
        tvStatus.setTextColor(Color.YELLOW)
        tvStatus.gravity = Gravity.CENTER
        tvStatus.setPadding(20, 20, 20, 20)

        tvLastSent = TextView(this)
        tvLastSent.text = "Son Güncelleme: Bekleniyor"
        tvLastSent.textSize = 12f
        tvLastSent.setTextColor(Color.LTGRAY)
        tvLastSent.gravity = Gravity.CENTER

        // HATA MESAJLARINI GÖRECEĞİMİZ ALAN
        tvErrorLog = TextView(this)
        tvErrorLog.text = "Log Ekranı: Hata yok"
        tvErrorLog.textSize = 12f
        tvErrorLog.setTextColor(Color.RED)
        tvErrorLog.setBackgroundColor(Color.parseColor("#1a1a2e"))
        tvErrorLog.setPadding(20, 20, 20, 20)
        val errorLayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        errorLayoutParams.setMargins(0, 30, 0, 30)
        tvErrorLog.layoutParams = errorLayoutParams

        tvInfo = TextView(this)
        tvInfo.text = getDeviceInfoText()
        tvInfo.textSize = 13f
        tvInfo.setTextColor(Color.WHITE)
        tvInfo.setPadding(20, 20, 20, 20)

        layout.addView(tvTitle)
        layout.addView(tvStatus)
        layout.addView(tvLastSent)
        layout.addView(tvErrorLog)
        layout.addView(tvInfo)
        scroll.addView(layout)
        setContentView(scroll)

        checkAndRequestPermissions()
    }

    private fun getDeviceInfoText(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return "Üretici: ${Build.MANUFACTURER}\n" +
                "Model: ${Build.MODEL}\n" +
                "Manuel Sürüm: $MANUAL_VERSION_NAME\n" +
                "Android: ${Build.VERSION.RELEASE}\n" +
                "ID: $androidId"
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.INTERNET
        )
        
        val needsPermission = permissions.any {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsPermission) {
            logError("İzinler eksik, kullanıcıdan onay bekleniyor...")
            ActivityCompat.requestPermissions(this, permissions, 100)
        } else {
            startTracking()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startTracking()
    }

    private fun startTracking() {
        if (isTaskRunning) return
        isTaskRunning = true
        
        updateStatus("Döngü Başlatıldı")
        
        val runnable = object : Runnable {
            override fun run() {
                lifecycleScopeSend()
                handler.postDelayed(this, SEND_INTERVAL_MINUTES * 60 * 1000)
            }
        }
        handler.post(runnable)
    }

    private fun updateStatus(msg: String) {
        runOnUiThread {
            tvStatus.text = msg
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            tvLastSent.text = "Son işlem: ${sdf.format(Date())}"
        }
    }

    private fun logError(error: String) {
        runOnUiThread {
            tvErrorLog.text = "HATA/LOG:\n$error"
            Log.e(TAG, error)
        }
    }

    private fun lifecycleScopeSend() {
        CoroutineScope(Dispatchers.Main).launch {
            updateStatus("Konum Alınmaya Çalışılıyor...")
            
            val fusedClient = LocationServices.getFusedLocationProviderClient(this@MainActivity)
            
            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                logError("Konum izni verilmediği için koordinat alınamadı.")
                sendToServer(buildJson(0.0, 0.0, 0f))
                return@launch
            }

            // Takılmayı önlemek için basit bir lastLocation kontrolü
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    updateStatus("Konum bulundu, gönderiliyor...")
                    sendToServer(buildJson(loc.latitude, loc.longitude, loc.accuracy))
                } else {
                    logError("GPS yanıt vermedi, 0.0 koordinatları gidiyor.")
                    sendToServer(buildJson(0.0, 0.0, 0f))
                }
            }.addOnFailureListener {
                logError("Konum servisi başarısız: ${it.message}")
                sendToServer(buildJson(0.0, 0.0, 0f))
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
            
            // ELLE GİRİLEN SÜRÜM BURADA GİDİYOR
            json.put("android_version", MANUAL_VERSION_NAME) 
            json.put("sdk_version", Build.VERSION.SDK_INT)
            
            json.put("latitude", lat)
            json.put("longitude", lng)
            json.put("location_accuracy", acc)
            
            json.put("battery_level", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            json.put("is_charging", bm.isCharging)
            
        } catch (e: Exception) {
            logError("JSON Oluşturma Hatası: ${e.message}")
        }
        return json
    }

    private fun sendToServer(json: JSONObject) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateStatus("Sunucuya bağlanılıyor...")
                val url = URL(SERVER_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                
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
                        updateStatus("Başarılı ✅")
                        logError("Sunucu Yanıtı: $response")
                    } else {
                        updateStatus("Hata: HTTP $code ❌")
                        logError("Sunucu Hatası ($code): $response")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateStatus("Bağlantı Başarısız ⚠️")
                    logError("İnternet/Bağlantı Hatası: ${e.message}\nIP Adresini ve WiFi'yi kontrol edin.")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
