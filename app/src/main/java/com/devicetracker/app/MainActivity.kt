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
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvLastSent: TextView
    private lateinit var tvInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(60, 80, 60, 60)
        layout.setBackgroundColor(Color.parseColor("#1a1a2e"))

        val tvTitle = TextView(this)
        tvTitle.text = "Device Tracker"
        tvTitle.textSize = 26f
        tvTitle.setTextColor(Color.WHITE)
        tvTitle.gravity = Gravity.CENTER
        tvTitle.setPadding(0, 0, 0, 48)

        tvStatus = TextView(this)
        tvStatus.text = "Baglaniyor..."
        tvStatus.textSize = 16f
        tvStatus.setTextColor(Color.WHITE)
        tvStatus.gravity = Gravity.CENTER
        tvStatus.setBackgroundColor(Color.parseColor("#16213e"))
        tvStatus.setPadding(32, 32, 32, 32)

        tvLastSent = TextView(this)
        tvLastSent.text = "Son gonderim: -"
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
        return "Marka: ${Build.BRAND} ${Build.MODEL}\n" +
               "Android: ${Build.VERSION.RELEASE}\n" +
               "ID: ${androidId.take(12)}...\n" +
               "Batarya: %$battery"
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startTracking()
    }

    private fun startTracking() {
        updateStatus("Ilk veri gonderiliyor...")
        sendDeviceData()
        handler.postDelayed(object : Runnable {
            override fun run() {
                sendDeviceData()
                handler.postDelayed(this, SEND_INTERVAL_MINUTES * 60 * 1000)
            }
        }, SEND_INTERVAL_MINUTES * 60 * 1000)
    }

    private fun updateStatus(msg: String) {
        runOnUiThread {
            tvStatus.text = msg
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            tvLastSent.text = "Son guncelleme: ${sdf.format(Date())}"
            tvInfo.text = getDeviceInfoText()
        }
    }

    private fun sendDeviceData() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
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
        val serial = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial()
            else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
        } catch (e: Exception) {
            "UNKNOWN"
        }
        json.put("serial_number", serial)
        json.put("brand", Build.BRAND)
        json.put("manufacturer", Build.MANUFACTURER)
        json.put("model", Build.MODEL)
        json.put("android_version", Build.VERSION.RELEASE)
        json.put("sdk_version", Build.VERSION.SDK_INT)
        if (lat != null && lng != null) {
            json.put("latitude", lat)
            json.put("longitude", lng)
        } else {
            json.put("latitude", JSONObject.NULL)
            json.put("longitude", JSONObject.NULL)
        }
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
                    updateStatus("Sunucuya baglandi - OK")
                } else {
                    updateStatus("Sunucu hatasi: HTTP $code")
                }
            } catch (e: Exception) {
                Log.e("DeviceTracker", "Hata: ${e.message}")
                updateStatus("Baglanti hatasi: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
