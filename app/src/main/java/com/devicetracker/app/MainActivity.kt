package com.devicetracker.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    // ⚠️ BURAYA KENDİ SUNUCU ADRESİNİZİ YAZIN
    private val SERVER_URL = "https://SUNUCUNUZ.COM/api/device_report.php"

    // Kaç dakikada bir veri gönderilsin?
    private val SEND_INTERVAL_MINUTES = 5L

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        checkAndRequestPermissions()
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
        sendDeviceData()
        handler.postDelayed(object : Runnable {
            override fun run() {
                sendDeviceData()
                handler.postDelayed(this, SEND_INTERVAL_MINUTES * 60 * 1000)
            }
        }, SEND_INTERVAL_MINUTES * 60 * 1000)
    }

    private fun sendDeviceData() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            sendToServer(buildDeviceJson(null, null))
            return
        }
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                val lat = location?.latitude
                val lng = location?.longitude
                sendToServer(buildDeviceJson(lat, lng))
            }
            .addOnFailureListener {
                sendToServer(buildDeviceJson(null, null))
            }
    }

    private fun buildDeviceJson(lat: Double?, lng: Double?): JSONObject {
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
                Log.d("DeviceTracker", "Gönderildi: HTTP ${conn.responseCode}")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("DeviceTracker", "Hata: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

