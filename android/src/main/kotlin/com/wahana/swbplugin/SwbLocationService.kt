package com.wahana.swbplugin

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SwbLocationService : Service() {

    companion object {
        const val TAG = "SwbLocationService"
        const val CHANNEL_ID = "swb_location_channel"
        const val NOTIFICATION_ID = 9101

        const val ACTION_START_SERVICE = "com.wahana.swbplugin.ACTION_START"
        const val ACTION_STOP_SERVICE = "com.wahana.swbplugin.ACTION_STOP"

        const val PREFS_NAME = "swb_location_prefs"
        const val KEY_IS_RUNNING = "is_running"
        const val KEY_MOCK_DETECTED_BG = "mock_detected_in_bg"
        const val KEY_GPS_DISABLED_BG = "gps_disabled_in_bg"
        const val KEY_CONFIG_JSON = "config_json"

        private val RETRY_BACKOFF_MS = listOf(5_000L, 10_000L, 30_000L, 60_000L, 300_000L)

        @Volatile
        var isServiceRunning = false
            private set
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var database: SwbLocationDatabase
    private val pluginImpl = SwbPluginImpl()

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var locationManager: LocationManager? = null
    private var fallbackLocationListener: LocationListener? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val uploadExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val isUploading = AtomicBoolean(false)

    // Cached options
    private var uploadUrl: String? = null
    private var headersMap: Map<String, String> = emptyMap()
    private var uploadEnabled: Boolean = true
    private var metadataJson: String? = null
    private var allowInsecureHttp: Boolean = false
    private var historyRetentionDays: Int = 7
    private var historyMaxRecords: Int = 10000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        database = SwbLocationDatabase.getInstance(this)
        createNotificationChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopTracking()
            stopSelf()
            return START_NOT_STICKY
        }

        loadConfig()

        val notification = buildNotification(
            prefs.getString("notif_title", "Pelacakan Lokasi") ?: "Pelacakan Lokasi",
            prefs.getString("notif_text", "Aplikasi sedang merekam lokasi di latar belakang") ?: "Aplikasi sedang merekam lokasi di latar belakang"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isServiceRunning = true
        prefs.edit().putBoolean(KEY_IS_RUNNING, true).apply()

        startLocationTracking()

        // Trigger any pending uploads on start
        triggerUploadLoop()

        return START_STICKY
    }

    override fun onDestroy() {
        stopTracking()
        unregisterNetworkCallback()
        uploadExecutor.shutdownNow()
        isServiceRunning = false
        prefs.edit().putBoolean(KEY_IS_RUNNING, false).apply()
        super.onDestroy()
    }

    private fun loadConfig() {
        val configStr = prefs.getString(KEY_CONFIG_JSON, null)
        if (configStr != null) {
            try {
                val json = JSONObject(configStr)
                uploadUrl = json.optString("uploadUrl", "").takeIf { it.isNotEmpty() }
                uploadEnabled = json.optBoolean("uploadEnabled", uploadUrl != null)
                allowInsecureHttp = json.optBoolean("allowInsecureHttp", false)
                historyRetentionDays = json.optInt("historyRetentionDays", 7)
                historyMaxRecords = json.optInt("historyMaxRecords", 10000)

                if (json.has("metadata")) {
                    metadataJson = json.optJSONObject("metadata")?.toString()
                }

                if (json.has("headers")) {
                    val hObj = json.optJSONObject("headers")
                    if (hObj != null) {
                        val map = mutableMapOf<String, String>()
                        for (key in hObj.keys()) {
                            map[key] = hObj.getString(key)
                        }
                        headersMap = map
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse saved config: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        // Check GPS enabled
        if (!pluginImpl.isLocationEnabled(this)) {
            Log.e(TAG, "GPS is disabled when starting background service")
            prefs.edit().putBoolean(KEY_GPS_DISABLED_BG, true).apply()
            SwbPlugin.notifyGatingStatusChanged(this)
            return
        }

        val configStr = prefs.getString(KEY_CONFIG_JSON, null)
        var interval = 3000L
        var fastestInterval = 1000L
        var minimumDistance = 0f
        var enableHighAccuracy = true

        if (configStr != null) {
            try {
                val json = JSONObject(configStr)
                interval = json.optLong("interval", 3000L)
                fastestInterval = json.optLong("fastestInterval", 1000L)
                minimumDistance = json.optDouble("minimumDistance", 0.0).toFloat()
                enableHighAccuracy = json.optBoolean("enableHighAccuracy", true)
            } catch (_: Exception) {
            }
        }

        try {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            val priority = if (enableHighAccuracy) {
                LocationRequest.PRIORITY_HIGH_ACCURACY
            } else {
                LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
            }

            val request = LocationRequest.create().apply {
                this.priority = priority
                this.interval = interval
                this.fastestInterval = fastestInterval
                this.smallestDisplacement = minimumDistance
            }

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    handleNewLocation(location, "fused")
                }
            }

            fusedLocationClient?.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        } catch (e: Exception) {
            Log.w(TAG, "Fused location unavailable, using LocationManager fallback: ${e.message}")
            startLocationManagerFallback(interval, minimumDistance)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationManagerFallback(interval: Long, minDistance: Float) {
        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            fallbackLocationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    handleNewLocation(location, location.provider ?: "gps")
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    prefs.edit().putBoolean(KEY_GPS_DISABLED_BG, true).apply()
                    SwbPlugin.notifyGatingStatusChanged(this@SwbLocationService)
                }
            }

            val provider = if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                LocationManager.GPS_PROVIDER
            } else {
                LocationManager.NETWORK_PROVIDER
            }

            locationManager?.requestLocationUpdates(
                provider,
                interval,
                minDistance,
                fallbackLocationListener!!,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Fallback location manager failed: ${e.message}")
        }
    }

    private fun handleNewLocation(location: Location, provider: String) {
        val isMock = pluginImpl.isLocationMock(this, location)

        val entity = LocationRecordEntity(
            id = UUID.randomUUID().toString(),
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy.toDouble(),
            altitude = if (location.hasAltitude()) location.altitude else null,
            altitudeAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else null,
            heading = if (location.hasBearing()) location.bearing.toDouble() else null,
            speed = if (location.hasSpeed()) location.speed.toDouble() else null,
            timestamp = location.time,
            isMock = isMock,
            provider = provider,
            metadata = metadataJson,
            uploaded = false,
            uploadedAt = null,
            attempts = 0
        )

        // Save to SQLite
        database.insertLocation(entity)
        database.pruneOldRecords(historyRetentionDays, historyMaxRecords)

        // Notify active plugin if app is alive
        SwbPlugin.onBackgroundLocationReceived(entity)

        if (isMock) {
            Log.e(TAG, "Fake GPS detected in background service! Halting tracking.")
            prefs.edit().putBoolean(KEY_MOCK_DETECTED_BG, true).apply()
            SwbPlugin.notifyGatingStatusChanged(this)
            stopTracking()
            stopSelf()
            return
        }

        // Trigger upload if enabled
        if (uploadEnabled && !uploadUrl.isNullOrEmpty()) {
            triggerUploadLoop()
        }
    }

    private fun stopTracking() {
        locationCallback?.let {
            try {
                fusedLocationClient?.removeLocationUpdates(it)
            } catch (_: Exception) {
            }
            locationCallback = null
        }
        fallbackLocationListener?.let {
            try {
                locationManager?.removeUpdates(it)
            } catch (_: Exception) {
            }
            fallbackLocationListener = null
        }
    }

    private fun triggerUploadLoop() {
        val targetUrl = uploadUrl
        if (targetUrl.isNullOrEmpty() || !uploadEnabled) return

        if (!isUploading.compareAndSet(false, true)) {
            // Already running
            return
        }

        uploadExecutor.execute {
            try {
                processPendingUploads(targetUrl)
            } finally {
                isUploading.set(false)
            }
        }
    }

    private fun processPendingUploads(targetUrl: String) {
        // Enforce secure HTTP check
        if (targetUrl.startsWith("http://", ignoreCase = true) && !allowInsecureHttp) {
            Log.e(TAG, "Upload URL uses insecure http:// but allowInsecureHttp is false. Aborting upload.")
            SwbPlugin.onBackgroundUploadStatus(false, 0, "Insecure HTTP not allowed")
            return
        }

        val pending = database.getPendingUploads(limit = 100)
        if (pending.isEmpty()) return

        for (record in pending) {
            if (!isServiceRunning && !prefs.getBoolean(KEY_IS_RUNNING, false)) {
                break
            }

            var success = false
            var attemptError: String? = null

            try {
                success = uploadSinglePosition(targetUrl, record)
            } catch (e: Exception) {
                attemptError = e.message ?: "Upload network error"
                Log.w(TAG, "Upload attempt failed: $attemptError")
            }

            if (success) {
                val now = System.currentTimeMillis()
                database.markUploaded(record.id, now)
                val remaining = database.getHistory(limit = 1, offset = 0).third
                SwbPlugin.onBackgroundUploadStatus(true, remaining, null)
            } else {
                database.incrementAttempts(record.id)
                val remaining = database.getHistory(limit = 1, offset = 0).third
                SwbPlugin.onBackgroundUploadStatus(false, remaining, attemptError ?: "Upload failed")

                // Determine backoff delay: 5s, 10s, 30s, 60s, 300s
                val nextAttemptIndex = record.attempts.coerceIn(0, RETRY_BACKOFF_MS.size - 1)
                val backoffMs = RETRY_BACKOFF_MS[nextAttemptIndex]
                Log.d(TAG, "Backing off for ${backoffMs}ms before retrying uploads")

                // Schedule next attempt and break current loop
                uploadExecutor.schedule({
                    triggerUploadLoop()
                }, backoffMs, TimeUnit.MILLISECONDS)
                break
            }
        }
    }

    private fun uploadSinglePosition(targetUrl: String, record: LocationRecordEntity): Boolean {
        val url = URL(targetUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json")

        for ((key, value) in headersMap) {
            conn.setRequestProperty(key, value)
        }

        val payload = record.toUploadPayloadJson()
        OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
            writer.write(payload)
            writer.flush()
        }

        val responseCode = conn.responseCode
        conn.disconnect()

        return responseCode in 200..299
    }

    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available, resuming pending uploads")
                    triggerUploadLoop()
                }
            }

            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
            networkCallback = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Location Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi status pelacakan lokasi latar belakang Wahana"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val stopIntent = Intent(this, SwbLocationService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }
}

