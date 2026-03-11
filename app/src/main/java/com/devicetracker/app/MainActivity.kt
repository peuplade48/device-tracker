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

/**
 * MainActivity: Cihaz bilgilerini toplar ve merkezi sunucuya raporlar.
 * Yerel ağ (10.x.x.x) bağlantı sorunları için optimize edilmiştir.
 */
class MainActivity : AppCompatActivity() {

    // Sunucu adresi. Eğer sunucuda SSL yoksa http:// olarak kalmalıdır.
    // Hata 443 portundan geliyorsa, sunucu tarafında HTTPS zorunluluğunu kontrol edin.
    private val SERVER_URL = "http://10.1.1.46/api/device_report.php"
    private val SEND_INTERVAL_MINUTES = 5L
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvLastSent: TextView
    private lateinit var tvInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Arayüz Kurulumu
        val scroll = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(60, 80, 60, 60)
        layout.setBackgroundColor(Color.parseColor("#1a1a2e"))

        val tvTitle = TextView(this)
        tvTitle.text = "Cihaz Takip Sistemi"
        tvTitle.textSize = 24f
        tvTitle.setTextColor(Color.WHITE)
        tvTitle.gravity = Gravity.CENTER
        tvTitle.setPadding(0, 0, 0, 48)

        tvStatus = TextView(this)
        tvStatus.text = "Başlatılıyor..."
        tvStatus.textSize = 14f
        tvStatus.setTextColor(Color.WHITE)
        tvStatus.gravity = Gravity.CENTER
        tvStatus.setBackgroundColor(Color.parseColor("#16213e"))
        tvStatus.setPadding(32, 32, 32, 32)

        tvLastSent = TextView(this)
        tvLastSent.text = "Son güncelleme: -"
        tvLastSent.textSize = 13f
        tvLastSent.setTextColor(Color.parseColor("#aaaaaa"))
        tvLastSent.gravity = Gravity.CENTER
        tvLastSent.setPadding(0, 24, 0, 24)

        tvInfo = TextView(this)
        tvInfo.text = getDeviceInfoText()
        tvInfo.textSize = 13f
        tvInfo.setTextColor(Color.parseColor("#cccccc"))
        tvInfo.setBackgroundColor(Color.parseColor("#16213e"))
        tvInfo.setPadding(32, 32, 32, 32)

        layout.addView(tvTitle)
        layout.addView(tvStatus)
        layout.addView(tvLastSent)
        layout.addView(tvInfo)
        scroll.addView(layout)
        setContentView(scroll)

        checkAndRequestPermissions()
    }

    private fun getDeviceInfoText(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Marka/Model: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "Android Sürümü: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "Cihaz ID: ${androidId.take(12)}...\n" +
                "Batarya Durumu: %$battery"
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
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
        updateStatus("Veri gönderimi aktif...")
        sendDeviceData()
        
        val runnable = object : Runnable {
            override fun run() {
                sendDeviceData()
                handler.postDelayed(this, SEND_INTERVAL_MINUTES * 60 * 1000)
            }
        }
        handler.postDelayed(runnable, SEND_INTERVAL_MINUTES * 60 * 1000)
    }

    private fun updateStatus(msg: String) {
        runOnUiThread {
            tvStatus.text = msg
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            tvLastSent.text = "Son güncelleme: ${sdf.format(Date())}"
            tvInfo.text = getDeviceInfoText()
        }
    }

    private fun sendDeviceData() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
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
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        
        json.put("android_id", androidId)
        json.put("brand", Build.BRAND)
        json.put("model", Build.MODEL)
        json.put("android_version", Build.VERSION.RELEASE)
        
        if (lat != null && lng != null) {
            json.put("latitude", lat)
            json.put("longitude", lng)
        }
        
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        json.put("battery_level", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        json.put("timestamp", System.currentTimeMillis() / 1000)
        
        return json
    }

    private fun sendToServer(json: JSONObject) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(SERVER_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                
                // Yerel ağ kararlılığı için zaman aşımı artırıldı
                conn.connectTimeout = 15000 
                conn.readTimeout = 15000
                
                OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
                
                val code = conn.responseCode
                val responseMsg = conn.responseMessage
                conn.disconnect()

                withContext(Dispatchers.Main) {
                    if (code in 200..299) {
                        updateStatus("Başarılı: Veri iletildi")
                    } else {
                        updateStatus("Sunucu Hatası: HTTP $code\n($responseMsg)")
                    }
                }
            } catch (e: Exception) {
                Log.e("DeviceTracker", "Bağlantı Hatası", e)
                withContext(Dispatchers.Main) {
                    val errorDetail = e.message ?: "Bilinmeyen hata"
                    updateStatus("Bağlantı Hatası!\nSunucuya (10.1.1.46) ulaşılamıyor.\nDetay: $errorDetail")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
