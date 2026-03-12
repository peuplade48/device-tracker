package com.devicetracker.app

import android.Manifest
import android.annotation.SuppressLint
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
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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

    private val SERVER_URL            = "http://10.1.1.46/api/device_report.php"
    private val ZIMMET_URL            = "http://10.1.1.46/sayac/sayac_term.php"
    private val SEND_INTERVAL_MINUTES = 5L

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvLastSent: TextView
    private lateinit var tvInfo: TextView
    private lateinit var pageTracker: ScrollView
    private lateinit var pageZimmet: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#1a1a2e"))

        // Görünmez tracker widget'ları (arka planda çalışmaya devam eder)
        tvStatus    = TextView(this).also { it.visibility = View.GONE }
        tvLastSent  = TextView(this).also { it.visibility = View.GONE }
        tvInfo      = TextView(this).also { it.visibility = View.GONE }
        pageTracker = ScrollView(this).also { it.visibility = View.GONE }

        // WebView — tam ekran
        pageZimmet = WebView(this)
        pageZimmet.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        val ws: WebSettings = pageZimmet.settings
        ws.javaScriptEnabled    = true
        ws.domStorageEnabled    = true
        ws.loadWithOverviewMode = true
        ws.useWideViewPort      = true
        ws.builtInZoomControls  = false
        ws.setSupportZoom(false)
        pageZimmet.webViewClient = WebViewClient()
        pageZimmet.loadUrl(ZIMMET_URL)

        root.addView(pageZimmet)
        setContentView(root)

        checkAndRequestPermissions()
    }

    private fun getSerialNumber(): String {
        val props = listOf(
            "ro.serialno",
            "ro.boot.serialno",
            "persist.sys.serialno",
            "ro.serial",
            "ril.serialnumber",
            "gsm.serial"
        )
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            var result = ""
            for (prop in props) {
                val sn = get.invoke(null, prop, "") as String
                if (sn.isNotEmpty() && sn != "unknown" && sn != "0") {
                    Log.d("DeviceTracker", "Seri no: $prop = $sn")
                    result = sn
                    break
                }
            }
            if (result.isNotEmpty()) result
            else Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
        } catch (e: Exception) {
            Log.w("DeviceTracker", "Seri no alinamadi: ${e.message}")
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
        }
    }

    private fun getDeviceInfoText(): String {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Marka: ${Build.BRAND} ${Build.MODEL}\n" +
               "Android: ${Build.VERSION.RELEASE}\n" +
               "Seri No: ${getSerialNumber()}\n" +
               "Batarya: %$battery"
    }

    private fun updateStatus(msg: String) {
        runOnUiThread {
            tvStatus.text = msg
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            tvLastSent.text = "Son guncelleme: ${sdf.format(Date())}"
            tvInfo.text = getDeviceInfoText()
        }
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
        json.put("serial_number", getSerialNumber())
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
                if (code == 200) updateStatus("Sunucuya baglandi - OK")
                else updateStatus("Sunucu hatasi: HTTP $code")
            } catch (e: Exception) {
                Log.e("DeviceTracker", "Hata: ${e.message}")
                updateStatus("Baglanti hatasi: ${e.message}")
            }
        }
    }

    override fun onBackPressed() {
        if (pageZimmet.canGoBack()) {
            pageZimmet.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
