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
import com.google.android.gms.tasks.CancellationTokenSource
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

    private val SERVER_URL = "http://10.1.1.46/api/device_report.php"
    private val SEND_INTERVAL_MINUTES = 5L
    private val TAG = "DeviceTracker"
    
    private val handler = Handler(Looper.getMainLooper())
    private var isTaskRunning = false

    private lateinit var tvStatus: TextView
    private lateinit var tvLastSent: TextView
    private lateinit var tvInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI Setup
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
        tvStatus.text = "Hazırlanıyor..."
        tvStatus.textSize = 14f
        tvStatus.setTextColor(Color.WHITE)
        tvStatus.gravity = Gravity.CENTER
        tvStatus.setBackgroundColor(Color.parseColor("#16213e"))
        tvStatus.setPadding(32, 32, 32, 32)

        tvLastSent = TextView(this)
        tvLastSent.text = "Son İşlem: Bekleniyor"
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
        val release = Build.VERSION.RELEASE
        val sdk = Build.VERSION.SDK_INT
        return "Cihaz: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "Android: $release (API $sdk)\n" +
                "ID: ${androidId.take(12)}...\n" +
                "Durum: Aktif"
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
            updateStatus("İzin İsteniyor...")
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
        
        updateStatus("Takip Başladı")
        
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
            tvLastSent.text = "Güncelleme: ${sdf.format(Date())}"
        }
    }

    private fun lifecycleScopeSend() {
        CoroutineScope(Dispatchers.Main).launch {
            updateStatus("Konum Alınıyor...")
            val locationData = fetchLocation()
            updateStatus("Veri Gönderiliyor...")
            sendToServer(buildJson(locationData))
        }
    }

    private suspend fun fetchLocation(): Triple<Double?, Double?, Float?> = withContext(Dispatchers.IO) {
        if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Triple(null, null, null)
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(this@MainActivity)
        val cts = CancellationTokenSource()
        
        // 5 saniye içinde cevap gelmezse iptal et
        Handler(Looper.getMainLooper()).postDelayed({ cts.cancel() }, 5000)

        return@withContext try {
            val loc = fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnCompleteListener { /* No-op */ }
                // await() benzeri bir yapı için basit bir bekleme (veya lastLocation fallback)
            val result = if (loc.isSuccessful && loc.result != null) {
                Triple(loc.result.latitude, loc.result.longitude, loc.result.accuracy)
            } else {
                // Fallback to last location
                val lastLoc = fusedClient.lastLocation.result
                Triple(lastLoc?.latitude, lastLoc?.longitude, lastLoc?.accuracy)
            }
            result
        } catch (e: Exception) {
            Triple(null, null, null)
        }
    }

    private fun buildJson(loc: Triple<Double?, Double?, Float?>): JSONObject {
        val json = JSONObject()
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        
        // Sürüm bilgisini direkt veritabanına gidecek şekilde netleştiriyoruz
        val ver = Build.VERSION.RELEASE // Örn: "11" veya "13"
        
        json.put("android_id", androidId)
        json.put("serial_number", "API_" + Build.VERSION.SDK_INT)
        json.put("brand", Build.BRAND)
        json.put("manufacturer", Build.MANUFACTURER)
        json.put("model", Build.MODEL)
        json.put("android_version", ver) 
        json.put("sdk_version", Build.VERSION.SDK_INT)
        
        json.put("latitude", loc.first ?: 0.0)
        json.put("longitude", loc.second ?: 0.0)
        json.put("location_accuracy", loc.third ?: 0.0)
        
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        json.put("battery_level", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        json.put("is_charging", bm.isCharging)
        
        return json
    }

    private suspend fun sendToServer(json: JSONObject) = withContext(Dispatchers.IO) {
        try {
            val url = URL(SERVER_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 8000
            
            OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
            
            val code = conn.responseCode
            val response = if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
            conn.disconnect()

            withContext(Dispatchers.Main) {
                if (code == 200) {
                    updateStatus("Başarılı ✅ (Sunucu: $response)")
                } else {
                    updateStatus("Hata: HTTP $code ❌")
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                updateStatus("Bağlantı Başarısız ⚠️")
                Log.e(TAG, "Server Error", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
