package com.wahana.swbplugin

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.NativePlugin
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@NativePlugin(
    name = "SwbPlugin",
    requestCodes = [
        SwbPlugin.REQUEST_LOCATION_PERMISSION,
        SwbPlugin.REQUEST_CAMERA_PERMISSION,
        SwbPlugin.REQUEST_VIDEO_PERMISSION,
        SwbPlugin.REQUEST_PICK_IMAGE,
        SwbPlugin.REQUEST_TAKE_PHOTO,
        SwbPlugin.REQUEST_TAKE_VIDEO
    ],
    permissions = [
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ]
)
class SwbPlugin : SwbPluginCompat() {

    companion object {
        const val TAG = "SwbPlugin"

        const val REQUEST_LOCATION_PERMISSION = 1000
        const val REQUEST_CAMERA_PERMISSION = 1001
        const val REQUEST_VIDEO_PERMISSION = 1002

        const val REQUEST_PICK_IMAGE = 2001
        const val REQUEST_TAKE_PHOTO = 2002
        const val REQUEST_TAKE_VIDEO = 2003

        const val PREFS_NAME = "com.wahana.swbplugin_prefs"
        const val KEY_EXPLICIT_PROTECT = "key_explicit_protect"
        const val KEY_AUTO_PROTECT = "key_auto_protect"
        const val KEY_PROTECT_TITLE = "key_protect_title"
        const val KEY_PROTECT_TEXT = "key_protect_text"
        const val KEY_PENDING_PHOTO_PATH = "key_pending_photo_path"
        const val KEY_PENDING_VIDEO_PATH = "key_pending_video_path"

        @Volatile
        private var instance: SwbPlugin? = null

        fun onBackgroundLocationReceived(entity: LocationRecordEntity) {
            val plugin = instance ?: return
            val pos = entity.toJSObject().getJSObject("position") ?: return
            plugin.notifyListeners("backgroundLocation", pos)
            plugin.notifyListeners("location", pos)
        }

        fun onBackgroundUploadStatus(uploaded: Boolean, pending: Int, error: String?) {
            val plugin = instance ?: return
            val status = JSObject().apply {
                put("uploaded", uploaded)
                put("pending", pending)
                if (error != null) {
                    put("error", error)
                }
            }
            plugin.notifyListeners("backgroundUpload", status)
        }

        fun notifyGatingStatusChanged(context: Context) {
            val plugin = instance ?: return
            plugin.activity?.runOnUiThread {
                plugin.checkAndEnforce(null)
            }
        }
    }

    private val implementation = SwbPluginImpl()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var database: SwbLocationDatabase
    private var providerReceiver: LocationProviderReceiver? = null
    private val watchCallbacks = ConcurrentHashMap<String, LocationCallback>()
    private var lastKnownLocation: Location? = null
    private var gatingDialog: AlertDialog? = null
    private var isPermissionRequestPending = false
    private var hasRequestedPermissionsOnce = false

    // Pending media files
    private var tempPhotoFile: File? = null
    private var tempVideoFile: File? = null
    private var isCapturingMedia = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private enum class GatingReason {
        NONE,
        PERMISSION_DENIED,
        GPS_DISABLED,
        MOCK_DETECTED
    }
    private var currentGatingReason = GatingReason.NONE

    private val securityLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            lastKnownLocation = location
            val isMock = implementation.isLocationMock(context, location)
            if (isMock) {
                emitLocationStatusChanged()
                activity?.runOnUiThread {
                    showMockGatingDialog()
                }
            } else {
                if (currentGatingReason == GatingReason.MOCK_DETECTED) {
                    if (isLocationPermissionGranted() && implementation.isLocationEnabled(context)) {
                        dismissNativeGatingDialog()
                        emitLocationStatusChanged()
                    }
                }
            }
        }
    }

    override fun load() {
        super.load()
        instance = this
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        database = SwbLocationDatabase.getInstance(context)

        providerReceiver = LocationProviderReceiver {
            emitLocationStatusChanged()
            activity?.runOnUiThread {
                checkAndEnforce(null)
            }
        }

        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(providerReceiver, filter, 2)
        } else {
            context.registerReceiver(providerReceiver, filter)
        }

        startSecurityLocationWatch()

        activity?.runOnUiThread {
            checkAndEnforce(null)
        }

        injectGeolocationSafetyHook()
    }

    override fun handleOnDestroy() {
        instance = null
        dismissNativeGatingDialog()

        providerReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
            providerReceiver = null
        }

        try {
            fusedLocationClient.removeLocationUpdates(securityLocationCallback)
        } catch (_: Exception) {
        }

        for ((_, callback) in watchCallbacks) {
            try {
                fusedLocationClient.removeLocationUpdates(callback)
            } catch (_: Exception) {
            }
        }
        watchCallbacks.clear()

        super.handleOnDestroy()
    }

    override fun handleOnPause() {
        super.handleOnPause()
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoProtect = prefs.getBoolean(KEY_AUTO_PROTECT, true)
            val explicitProtect = prefs.getBoolean(KEY_EXPLICIT_PROTECT, false)

            if ((autoProtect || explicitProtect || isCapturingMedia) && !SwbLocationService.isServiceRunning) {
                val title = prefs.getString(KEY_PROTECT_TITLE, "Wahana Express")
                val text = prefs.getString(KEY_PROTECT_TEXT, "Aplikasi aktif di latar belakang")
                SwbKeepAliveService.start(context, title, text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleOnPause keep-alive check failed: ${e.message}")
        }
    }

    override fun handleOnResume() {
        super.handleOnResume()
        instance = this
        isPermissionRequestPending = false

        // Delay stop keep-alive if it was only started automatically for background protection
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val explicitProtect = prefs.getBoolean(KEY_EXPLICIT_PROTECT, false)
            val autoProtect = prefs.getBoolean(KEY_AUTO_PROTECT, true)

            if (!explicitProtect && autoProtect && !isCapturingMedia) {
                mainHandler.postDelayed({
                    try {
                        val currentPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        val stillExplicit = currentPrefs.getBoolean(KEY_EXPLICIT_PROTECT, false)
                        if (!stillExplicit && !isCapturingMedia && !SwbLocationService.isServiceRunning) {
                            SwbKeepAliveService.stop(context)
                        }
                    } catch (_: Exception) {
                    }
                }, 3000L)
            }
        } catch (_: Exception) {
        }

        // Check if background service flagged mock or GPS disabled
        val prefs = context.getSharedPreferences(SwbLocationService.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(SwbLocationService.KEY_MOCK_DETECTED_BG, false)) {
            prefs.edit().putBoolean(SwbLocationService.KEY_MOCK_DETECTED_BG, false).apply()
            activity?.runOnUiThread {
                showMockGatingDialog()
            }
            emitLocationStatusChanged()
            return
        }

        if (prefs.getBoolean(SwbLocationService.KEY_GPS_DISABLED_BG, false)) {
            prefs.edit().putBoolean(SwbLocationService.KEY_GPS_DISABLED_BG, false).apply()
        }

        activity?.runOnUiThread {
            checkAndEnforce(null)
        }
        startSecurityLocationWatch()
        injectGeolocationSafetyHook()
    }

    @SuppressLint("MissingPermission")
    private fun startSecurityLocationWatch() {
        if (!isLocationPermissionGranted()) return
        try {
            val request = LocationRequest.create().apply {
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                interval = 2000L
                fastestInterval = 1000L
                smallestDisplacement = 0f
            }
            fusedLocationClient.requestLocationUpdates(
                request,
                securityLocationCallback,
                Looper.getMainLooper()
            )
        } catch (_: Exception) {
        }
    }

    private fun injectGeolocationSafetyHook() {
        try {
            val webView = bridge?.webView ?: return
            val jsHook = """
                (function() {
                    if (window.__swbGeoHooked) return;
                    window.__swbGeoHooked = true;
                    if (!navigator.geolocation) return;
                    var _origGetCurrent = navigator.geolocation.getCurrentPosition.bind(navigator.geolocation);
                    navigator.geolocation.getCurrentPosition = function(success, error, options) {
                        if (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.SwbPlugin) {
                            window.Capacitor.Plugins.SwbPlugin.checkLocationStatus().then(function(res) {
                                if (res && (!res.usable || res.isMock)) {
                                    if (error) {
                                        error({
                                            code: 1,
                                            message: 'Fake GPS terdeteksi aktif. Matikan Fake GPS untuk melanjutkan.'
                                        });
                                    }
                                } else {
                                    _origGetCurrent(success, error, options);
                                }
                            }).catch(function() {
                                _origGetCurrent(success, error, options);
                            });
                        } else {
                            _origGetCurrent(success, error, options);
                        }
                    };
                })();
            """.trimIndent()

            webView.post {
                webView.evaluateJavascript(jsHook, null)
            }
        } catch (_: Exception) {
        }
    }

    private fun isLocationPermissionGranted(): Boolean {
        val runtimeFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!runtimeFine) return false

        // On Android (especially MIUI/HyperOS), when GPS is disabled globally in device settings,
        // AppOps returns MODE_IGNORED. We only check AppOps when location is enabled so that
        // a disabled GPS state is never misdiagnosed as PERMISSION_DENIED.
        if (implementation.isLocationEnabled(context)) {
            try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                if (appOps != null) {
                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        appOps.unsafeCheckOpNoThrow(
                            AppOpsManager.OPSTR_FINE_LOCATION,
                            Process.myUid(),
                            context.packageName
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        appOps.checkOpNoThrow(
                            AppOpsManager.OPSTR_FINE_LOCATION,
                            Process.myUid(),
                            context.packageName
                        )
                    }
                    val isAllowed = mode == AppOpsManager.MODE_ALLOWED ||
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mode == AppOpsManager.MODE_FOREGROUND)
                    if (!isAllowed) {
                        return false
                    }
                }
            } catch (_: Exception) {
            }
        }

        return true
    }

    private fun isCoarseLocationGranted(): Boolean {
        val runtimeCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!runtimeCoarse) return false

        if (implementation.isLocationEnabled(context)) {
            try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                if (appOps != null) {
                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        appOps.unsafeCheckOpNoThrow(
                            AppOpsManager.OPSTR_COARSE_LOCATION,
                            Process.myUid(),
                            context.packageName
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        appOps.checkOpNoThrow(
                            AppOpsManager.OPSTR_COARSE_LOCATION,
                            Process.myUid(),
                            context.packageName
                        )
                    }
                    val isAllowed = mode == AppOpsManager.MODE_ALLOWED ||
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mode == AppOpsManager.MODE_FOREGROUND)
                    if (!isAllowed) {
                        return false
                    }
                }
            } catch (_: Exception) {
            }
        }

        return true
    }

    private fun isAnyRuntimeLocationPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || isCoarseLocationGranted()
    }

    // --- Foreground Location Methods ---

    @SuppressLint("MissingPermission")
    @PluginMethod
    fun getCurrentPosition(call: PluginCall) {
        if (!implementation.isLocationEnabled(context)) {
            call.reject(
                "Layanan GPS perangkat sedang nonaktif. Silakan aktifkan GPS melalui Pengaturan atau panel notifikasi.",
                "GPS_DISABLED"
            )
            return
        }

        if (!isLocationPermissionGranted()) {
            val isCoarseOnly = isCoarseLocationGranted()
            val msg = if (isCoarseOnly) {
                "Izin lokasi presisi belum aktif. Silakan pilih opsi 'Gunakan lokasi akurat' pada Pengaturan aplikasi."
            } else {
                "Izin akses lokasi belum diberikan. Silakan berikan izin akses lokasi pada Pengaturan aplikasi."
            }
            call.reject(msg, if (isCoarseOnly) "COARSE_ONLY" else "PERMISSION_DENIED")
            return
        }

        if (implementation.isHookingFrameworkDetected()) {
            activity?.runOnUiThread {
                showMockGatingDialog()
            }
            emitLocationStatusChanged()
            call.reject(
                "Aplikasi pemalsu lokasi (Fake GPS) terdeteksi aktif. Silakan nonaktifkan Fake GPS pada perangkat Anda.",
                "MOCK_LOCATION"
            )
            return
        }

        val enableHighAccuracy = call.getBoolean("enableHighAccuracy", true) ?: true
        val priority = if (enableHighAccuracy) {
            LocationRequest.PRIORITY_HIGH_ACCURACY
        } else {
            LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val cancellationTokenSource = CancellationTokenSource()

        fusedLocationClient.getCurrentLocation(priority, cancellationTokenSource.token)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    lastKnownLocation = location
                    if (implementation.isLocationMock(context, location)) {
                        activity?.runOnUiThread {
                            showMockGatingDialog()
                        }
                        emitLocationStatusChanged()
                        call.reject(
                            "Aplikasi pemalsu lokasi (Fake GPS) terdeteksi aktif. Silakan nonaktifkan Fake GPS pada perangkat Anda.",
                            "MOCK_LOCATION"
                        )
                        return@addOnSuccessListener
                    }
                    dismissNativeGatingDialog()
                    call.resolve(implementation.formatLocation(context, location))
                } else {
                    fusedLocationClient.lastLocation.addOnSuccessListener { fallback: Location? ->
                        if (fallback != null) {
                            lastKnownLocation = fallback
                            if (implementation.isLocationMock(context, fallback)) {
                                activity?.runOnUiThread {
                                    showMockGatingDialog()
                                }
                                emitLocationStatusChanged()
                                call.reject(
                                    "Aplikasi pemalsu lokasi (Fake GPS) terdeteksi aktif. Silakan nonaktifkan Fake GPS pada perangkat Anda.",
                                    "MOCK_LOCATION"
                                )
                                return@addOnSuccessListener
                            }
                            dismissNativeGatingDialog()
                            call.resolve(implementation.formatLocation(context, fallback))
                        } else {
                            call.reject(
                                "Tidak dapat memperoleh koordinat GPS saat ini. Pastikan Anda berada di area terbuka dan coba kembali beberapa saat lagi.",
                                "LOCATION_UNAVAILABLE"
                            )
                        }
                    }.addOnFailureListener { e ->
                        call.reject(
                            "Gagal memperoleh sinyal GPS: ${e.message}. Pastikan GPS perangkat aktif dan sinyal satelit terjangkau.",
                            "LOCATION_UNAVAILABLE"
                        )
                    }
                }
            }
            .addOnFailureListener { e ->
                call.reject(
                    "Gagal memperoleh koordinat GPS: ${e.message}. Pastikan layanan Google Play Services aktif dan GPS menyala.",
                    "LOCATION_UNAVAILABLE"
                )
            }
    }

    @SuppressLint("MissingPermission")
    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    fun watchPosition(call: PluginCall) {
        if (!implementation.isLocationEnabled(context)) {
            call.reject(
                "Layanan GPS perangkat sedang nonaktif. Silakan aktifkan GPS melalui Pengaturan atau panel notifikasi.",
                "GPS_DISABLED"
            )
            return
        }

        if (!isLocationPermissionGranted()) {
            val isCoarseOnly = isCoarseLocationGranted()
            val msg = if (isCoarseOnly) {
                "Izin lokasi presisi belum aktif. Silakan pilih opsi 'Gunakan lokasi akurat' pada Pengaturan aplikasi."
            } else {
                "Izin akses lokasi belum diberikan. Silakan berikan izin akses lokasi pada Pengaturan aplikasi."
            }
            call.reject(msg, if (isCoarseOnly) "COARSE_ONLY" else "PERMISSION_DENIED")
            return
        }

        if (implementation.isHookingFrameworkDetected()) {
            activity?.runOnUiThread {
                showMockGatingDialog()
            }
            emitLocationStatusChanged()
            call.reject(
                "Aplikasi pemalsu lokasi (Fake GPS) terdeteksi aktif. Silakan nonaktifkan Fake GPS pada perangkat Anda.",
                "MOCK_LOCATION"
            )
            return
        }

        val interval = call.getInt("interval", 3000)?.toLong() ?: 3000L
        val fastestInterval = call.getInt("fastestInterval", 1000)?.toLong() ?: 1000L
        val minimumDistance = call.getFloat("minimumDistance", 0f) ?: 0f
        val enableHighAccuracy = call.getBoolean("enableHighAccuracy", true) ?: true

        val priority = if (enableHighAccuracy) {
            LocationRequest.PRIORITY_HIGH_ACCURACY
        } else {
            LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val locationRequest = LocationRequest.create().apply {
            this.priority = priority
            this.interval = interval
            this.fastestInterval = fastestInterval
            this.smallestDisplacement = minimumDistance
        }

        val callbackId = call.callbackId

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                lastKnownLocation = location
                val isMock = implementation.isLocationMock(context, location)
                if (isMock) {
                    activity?.runOnUiThread {
                        showMockGatingDialog()
                    }
                    emitLocationStatusChanged()
                    return
                }

                dismissNativeGatingDialog()
                val posData = implementation.formatLocation(context, location)
                notifyListeners("location", posData)
                call.resolve(posData)
            }
        }

        watchCallbacks[callbackId] = locationCallback
        setCallKeepAlive(call, true)

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun setCallKeepAlive(call: PluginCall, keepAlive: Boolean) {
        try {
            val method = call.javaClass.getMethod("setKeepAlive", Boolean::class.javaPrimitiveType)
            method.invoke(call, keepAlive)
        } catch (_: Exception) {
            if (keepAlive) {
                saveCall(call)
            }
        }
    }

    @PluginMethod
    fun stopWatch(call: PluginCall) {
        val id = call.getString("id") ?: call.callbackId
        if (id == null) {
            call.reject("ID pemantauan lokasi tidak valid atau tidak ditemukan.", "INVALID_ID")
            return
        }
        val callback = watchCallbacks.remove(id)

        if (callback != null) {
            fusedLocationClient.removeLocationUpdates(callback)
            val savedCall = bridge.getSavedCall(id)
            if (savedCall != null) {
                setCallKeepAlive(savedCall, false)
                bridge.releaseCall(savedCall)
            }
        }

        call.resolve()
    }

    @PluginMethod
    fun isLocationEnabled(call: PluginCall) {
        val enabled = implementation.isLocationEnabled(context)
        val ret = JSObject().apply {
            put("enabled", enabled)
            put("message", if (enabled) "Layanan GPS perangkat aktif." else "Layanan GPS perangkat sedang nonaktif.")
            put("solution", if (enabled) "Perangkat siap membaca lokasi." else "Aktifkan GPS melalui Pengaturan atau panel notifikasi.")
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun checkLocationStatus(call: PluginCall) {
        val enabled = implementation.isLocationEnabled(context)
        val granted = isLocationPermissionGranted()
        val isCoarseOnly = isCoarseLocationGranted() && !granted
        val isMock = implementation.isLocationMock(context, lastKnownLocation) || implementation.isHookingFrameworkDetected()
        call.resolve(implementation.calculateStatus(enabled, granted, isMock, isCoarseOnly))
    }

    @PluginMethod
    override fun checkPermissions(call: PluginCall) {
        val granted = isLocationPermissionGranted()
        val permString = if (granted) "granted" else "prompt"

        val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val bgGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        val missing = mutableListOf<String>()
        val solutions = mutableListOf<String>()
        if (!granted) {
            val isCoarseOnly = isCoarseLocationGranted()
            if (isCoarseOnly) {
                missing.add("Lokasi Akurat")
                solutions.add("Aktifkan 'Gunakan Lokasi Akurat' pada menu Pengaturan aplikasi")
            } else {
                missing.add("Lokasi")
                solutions.add("Pilih 'Izinkan saat aplikasi digunakan' pada menu Pengaturan aplikasi")
            }
        }
        if (!cameraGranted) {
            missing.add("Kamera")
            solutions.add("Berikan izin Kamera pada menu Pengaturan aplikasi")
        }
        if (!micGranted) {
            missing.add("Mikrofon")
            solutions.add("Berikan izin Mikrofon pada menu Pengaturan aplikasi")
        }

        val ret = JSObject().apply {
            put("location", permString)
            put("coarseLocation", if (isCoarseLocationGranted()) "granted" else "prompt")
            put("backgroundLocation", if (bgGranted) "granted" else "prompt")
            put("camera", if (cameraGranted) "granted" else "prompt")
            put("microphone", if (micGranted) "granted" else "prompt")

            if (missing.isEmpty()) {
                put("message", "Semua izin yang diperlukan telah aktif.")
                put("solution", "Aplikasi siap digunakan.")
            } else {
                put("message", "Izin belum diberikan: ${missing.joinToString(", ")}.")
                put("solution", "Silakan aktifkan melalui Pengaturan aplikasi: ${solutions.joinToString("; ")}.")
            }
        }
        call.resolve(ret)
    }

    @PluginMethod
    override fun requestPermissions(call: PluginCall) {
        val requestedArray = call.getArray("permissions")
        val permissionsToRequest = mutableListOf<String>()

        if (requestedArray != null && requestedArray.length() > 0) {
            for (i in 0 until requestedArray.length()) {
                val permName = requestedArray.optString(i)
                when (permName) {
                    "location", "coarseLocation" -> {
                        if (!isLocationPermissionGranted()) {
                            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
                            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                    }
                    "camera" -> {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                            permissionsToRequest.add(Manifest.permission.CAMERA)
                        }
                    }
                    "microphone" -> {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    "backgroundLocation" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                            permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    }
                }
            }
        } else {
            if (!isLocationPermissionGranted()) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
                permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }

        if (permissionsToRequest.isEmpty()) {
            checkPermissions(call)
        } else {
            saveCall(call)
            pluginRequestPermissions(
                permissionsToRequest.distinct().toTypedArray(),
                REQUEST_LOCATION_PERMISSION
            )
        }
    }

    override fun handleRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>?,
        grantResults: IntArray?
    ) {
        super.handleRequestPermissionsResult(requestCode, permissions, grantResults)
        val saved = try { savedCall } catch (_: Exception) { null }

        when (requestCode) {
            REQUEST_LOCATION_PERMISSION -> {
                isPermissionRequestPending = false
                hasRequestedPermissionsOnce = true
                emitLocationStatusChanged()
                val fineGranted = isLocationPermissionGranted()

                val isEnforceCall = saved?.methodName == "enforceLocation" || (saved != null && saved.hasOption("actionButtonLabel"))

                if (isEnforceCall) {
                    if (!fineGranted) {
                        val isCoarseOnly = isCoarseLocationGranted()
                        val messageText = if (isCoarseOnly) {
                            "Aplikasi membutuhkan akses lokasi akurat. Silakan aktifkan opsi 'Gunakan Lokasi Akurat' pada menu Pengaturan Lokasi aplikasi."
                        } else {
                            "Aplikasi membutuhkan izin akses lokasi agar dapat memproses data Anda. Silakan berikan izin melalui Pengaturan."
                        }

                        showNativeGatingDialog(
                            reason = GatingReason.PERMISSION_DENIED,
                            title = "Izin Lokasi Diperlukan",
                            message = messageText,
                            actionText = "Buka Pengaturan",
                            exitText = "Keluar",
                            onAction = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            }
                        )
                        val status = implementation.calculateStatus(
                            enabled = implementation.isLocationEnabled(context),
                            granted = false,
                            isMock = false,
                            isCoarseOnly = isCoarseOnly
                        )
                        saved?.resolve(status)
                    } else {
                        startSecurityLocationWatch()
                        checkAndEnforce(saved)
                    }
                } else {
                    if (saved != null) {
                        checkPermissions(saved)
                    }
                }
            }
            REQUEST_CAMERA_PERMISSION -> {
                val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (cameraGranted && saved != null) {
                    launchCameraPhoto(saved)
                    return
                } else {
                    saved?.reject(
                        "Izin kamera belum diberikan. Silakan izinkan akses kamera melalui Pengaturan aplikasi.",
                        "CAMERA_PERMISSION_DENIED"
                    )
                }
            }
            REQUEST_VIDEO_PERMISSION -> {
                val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (cameraGranted && micGranted && saved != null) {
                    launchCameraVideo(saved)
                    return
                } else {
                    val missing = mutableListOf<String>()
                    if (!cameraGranted) missing.add("Kamera")
                    if (!micGranted) missing.add("Mikrofon")
                    saved?.reject(
                        "Izin ${missing.joinToString(" dan ")} belum diberikan. Silakan aktifkan izin ${missing.joinToString(" & ")} melalui Pengaturan aplikasi.",
                        "PERMISSION_DENIED"
                    )
                }
            }
        }

        if (saved != null) {
            try {
                freeSavedCall()
            } catch (_: Exception) {
            }
        }
    }

    @PluginMethod
    fun enforceLocation(call: PluginCall) {
        activity?.runOnUiThread {
            checkAndEnforce(call)
        }
    }

    @PluginMethod
    fun dismissGatingDialog(call: PluginCall) {
        dismissNativeGatingDialog()
        call.resolve()
    }

    private fun checkAndEnforce(call: PluginCall?) {
        val currentActivity = activity
        if (currentActivity == null || currentActivity.isFinishing) return

        val title = call?.getString("title")
        val msgDenied = call?.getString("messagePermissionDenied")
            ?: "Aplikasi membutuhkan izin akses lokasi presisi (GPS) agar dapat digunakan. Silakan berikan izin akses lokasi melalui Pengaturan aplikasi."
        val msgGps = call?.getString("messageGpsDisabled")
            ?: "Layanan GPS pada perangkat Anda sedang nonaktif. Silakan aktifkan GPS untuk melanjutkan penggunaan aplikasi."
        val msgMock = call?.getString("messageMockDetected")
            ?: "Terdeteksi penggunaan aplikasi pemalsu lokasi (Fake GPS). Silakan nonaktifkan Fake GPS pada perangkat Anda untuk melanjutkan."
        val actionLabel = call?.getString("actionButtonLabel")
        val exitLabel = call?.getString("exitButtonLabel") ?: "Keluar"

        // 1. Cek apakah GPS perangkat mati TERLEBIH DAHULU
        if (!implementation.isLocationEnabled(context)) {
            showNativeGatingDialog(
                reason = GatingReason.GPS_DISABLED,
                title = title ?: "Aktifkan Layanan Lokasi",
                message = msgGps,
                actionText = actionLabel ?: "Aktifkan GPS",
                exitText = exitLabel,
                onAction = {
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            )
            val isCoarseOnly = isCoarseLocationGranted() && !isLocationPermissionGranted()
            val status = implementation.calculateStatus(
                enabled = false,
                granted = isLocationPermissionGranted(),
                isMock = false,
                isCoarseOnly = isCoarseOnly
            )
            notifyListeners("locationStatusChanged", status)
            call?.resolve(status)
            return
        }

        // 2. Cek izin lokasi presisi (hanya jika GPS aktif)
        if (!isLocationPermissionGranted()) {
            if (isPermissionRequestPending) {
                return
            }
            if (!hasRequestedPermissionsOnce && !isAnyRuntimeLocationPermissionGranted()) {
                isPermissionRequestPending = true
                if (call != null) {
                    saveCall(call)
                }
                pluginRequestPermissions(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    REQUEST_LOCATION_PERMISSION
                )
                return
            }

            val isCoarseOnly = isCoarseLocationGranted()
            val messageText = if (isCoarseOnly) {
                "Aplikasi membutuhkan akses izin lokasi presisi (akurat). Silakan aktifkan opsi 'Gunakan Lokasi Akurat' pada menu Pengaturan aplikasi."
            } else {
                msgDenied
            }

            showNativeGatingDialog(
                reason = GatingReason.PERMISSION_DENIED,
                title = "Izin Lokasi Diperlukan",
                message = messageText,
                actionText = "Buka Pengaturan",
                exitText = exitLabel,
                onAction = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            )
            val status = implementation.calculateStatus(
                enabled = true,
                granted = false,
                isMock = false,
                isCoarseOnly = isCoarseOnly
            )
            notifyListeners("locationStatusChanged", status)
            call?.resolve(status)
            return
        }

        // 3. Cek manipulasi Fake GPS yang aktif pada lastKnownLocation
        val isMock = (lastKnownLocation != null && implementation.isLocationMock(context, lastKnownLocation)) || implementation.isHookingFrameworkDetected()
        if (isMock) {
            showMockGatingDialog(msgMock)
            val status = implementation.calculateStatus(enabled = true, granted = true, isMock = true)
            notifyListeners("locationStatusChanged", status)
            call?.resolve(status)
            return
        }

        // 4. Jika lastKnownLocation masih null, periksa asynchronous
        if (lastKnownLocation == null) {
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { loc: Location? ->
                    if (loc != null) {
                        lastKnownLocation = loc
                        if (implementation.isLocationMock(context, loc)) {
                            activity?.runOnUiThread {
                                showMockGatingDialog(msgMock)
                            }
                            emitLocationStatusChanged()
                        } else {
                            if (isLocationPermissionGranted() && implementation.isLocationEnabled(context)) {
                                dismissNativeGatingDialog()
                                emitLocationStatusChanged()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 5. Cek root / hooking
        if (implementation.isHookingFrameworkDetected()) {
            showMockGatingDialog(msgMock)
            return
        }

        // 6. Valid
        dismissNativeGatingDialog()
        val status = implementation.calculateStatus(enabled = true, granted = true, isMock = false)
        notifyListeners("locationStatusChanged", status)
        call?.resolve(status)
    }

    private fun showMockGatingDialog(customMessage: String? = null) {
        showNativeGatingDialog(
            reason = GatingReason.MOCK_DETECTED,
            title = "Lokasi Tiruan Terdeteksi",
            message = customMessage ?: "Terdeteksi penggunaan aplikasi pemalsu lokasi (Fake GPS). Silakan nonaktifkan Fake GPS pada perangkat Anda untuk melanjutkan.",
            actionText = "Coba Lagi",
            exitText = "Keluar",
            onAction = {
                try {
                    fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                        if (loc != null) {
                            lastKnownLocation = loc
                        }
                        activity?.runOnUiThread {
                            checkAndEnforce(null)
                        }
                    }.addOnFailureListener {
                        activity?.runOnUiThread {
                            checkAndEnforce(null)
                        }
                    }
                } catch (_: Exception) {
                    activity?.runOnUiThread {
                        checkAndEnforce(null)
                    }
                }
            }
        )
    }

    private fun showNativeGatingDialog(
        reason: GatingReason,
        title: String,
        message: String,
        actionText: String,
        exitText: String,
        onAction: () -> Unit
    ) {
        val currentActivity = activity ?: return
        if (currentActivity.isFinishing) return

        currentGatingReason = reason

        currentActivity.runOnUiThread {
            if (gatingDialog?.isShowing == true) {
                gatingDialog?.dismiss()
            }
            gatingDialog = AlertDialog.Builder(currentActivity)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(actionText) { _, _ ->
                    onAction()
                }
                .setNegativeButton(exitText) { _, _ ->
                    currentActivity.finishAffinity()
                }
                .create()

            gatingDialog?.setCanceledOnTouchOutside(false)
            gatingDialog?.show()
        }
    }

    private fun dismissNativeGatingDialog() {
        currentGatingReason = GatingReason.NONE
        activity?.runOnUiThread {
            if (gatingDialog?.isShowing == true) {
                gatingDialog?.dismiss()
            }
            gatingDialog = null
        }
    }

    @PluginMethod
    fun openLocationSettings(call: PluginCall) {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            call.resolve()
        } catch (e: Exception) {
            call.reject("Tidak dapat membuka menu Pengaturan Lokasi secara otomatis. Silakan buka menu Pengaturan perangkat Anda.", "OPEN_SETTINGS_FAILED")
        }
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            call.resolve()
        } catch (e: Exception) {
            call.reject("Tidak dapat membuka menu Pengaturan Aplikasi secara otomatis. Silakan buka menu Pengaturan perangkat Anda.", "OPEN_SETTINGS_FAILED")
        }
    }

    private fun emitLocationStatusChanged() {
        val enabled = implementation.isLocationEnabled(context)
        val granted = isLocationPermissionGranted()
        val isCoarseOnly = isCoarseLocationGranted() && !granted
        val isMock = implementation.isLocationMock(context, lastKnownLocation) || implementation.isHookingFrameworkDetected()
        notifyListeners("locationStatusChanged", implementation.calculateStatus(enabled, granted, isMock, isCoarseOnly))
    }

    // --- Background Location & Upload Methods ---

    @PluginMethod
    fun startBackgroundLocation(call: PluginCall) {
        if (!implementation.isLocationEnabled(context)) {
            call.reject(
                "Layanan GPS perangkat sedang nonaktif. Silakan aktifkan GPS sebelum memulai pelacakan latar belakang.",
                "GPS_DISABLED"
            )
            return
        }

        if (!isLocationPermissionGranted()) {
            val isCoarseOnly = isCoarseLocationGranted()
            val msg = if (isCoarseOnly) {
                "Pelacakan lokasi latar belakang membutuhkan izin lokasi presisi. Silakan pilih opsi 'Gunakan lokasi akurat' pada Pengaturan aplikasi."
            } else {
                "Izin akses lokasi belum diberikan. Silakan berikan izin akses lokasi pada Pengaturan aplikasi sebelum memulai pelacakan latar belakang."
            }
            call.reject(msg, if (isCoarseOnly) "COARSE_ONLY" else "PERMISSION_DENIED")
            return
        }

        if (implementation.isHookingFrameworkDetected() || (lastKnownLocation != null && implementation.isLocationMock(context, lastKnownLocation))) {
            call.reject(
                "Aplikasi pemalsu lokasi (Fake GPS) terdeteksi aktif. Silakan nonaktifkan Fake GPS sebelum memulai pelacakan latar belakang.",
                "MOCK_LOCATION"
            )
            return
        }

        // Save options to SharedPreferences for service persistence & reboot
        val prefs = context.getSharedPreferences(SwbLocationService.PREFS_NAME, Context.MODE_PRIVATE)
        val configJson = JSONObject().apply {
            put("interval", call.getInt("interval", 3000))
            put("fastestInterval", call.getInt("fastestInterval", 1000))
            put("minimumDistance", call.getFloat("minimumDistance", 0f))
            put("enableHighAccuracy", call.getBoolean("enableHighAccuracy", true))
            put("uploadUrl", call.getString("uploadUrl", ""))
            put("uploadEnabled", call.getBoolean("uploadEnabled", true))
            put("allowInsecureHttp", call.getBoolean("allowInsecureHttp", false))
            put("historyRetentionDays", call.getInt("historyRetentionDays", 7))
            put("historyMaxRecords", call.getInt("historyMaxRecords", 10000))

            val headers = call.getObject("headers")
            if (headers != null) {
                put("headers", headers)
            }
            val metadata = call.getObject("metadata")
            if (metadata != null) {
                put("metadata", metadata)
            }
        }

        val restartOnBoot = call.getBoolean("restartOnBoot", false) ?: false
        val notifTitle = call.getString("notificationTitle", "Pelacakan Lokasi")
        val notifText = call.getString("notificationText", "Aplikasi sedang merekam lokasi di latar belakang")

        prefs.edit()
            .putString(SwbLocationService.KEY_CONFIG_JSON, configJson.toString())
            .putBoolean("restartOnBoot", restartOnBoot)
            .putString("notif_title", notifTitle)
            .putString("notif_text", notifText)
            .apply()

        val serviceIntent = Intent(context, SwbLocationService::class.java).apply {
            action = SwbLocationService.ACTION_START_SERVICE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        call.resolve()
    }

    @PluginMethod
    fun stopBackgroundLocation(call: PluginCall) {
        val serviceIntent = Intent(context, SwbLocationService::class.java).apply {
            action = SwbLocationService.ACTION_STOP_SERVICE
        }
        context.startService(serviceIntent)
        call.resolve()
    }

    @PluginMethod
    fun isBackgroundLocationRunning(call: PluginCall) {
        val prefs = context.getSharedPreferences(SwbLocationService.PREFS_NAME, Context.MODE_PRIVATE)
        val running = SwbLocationService.isServiceRunning || prefs.getBoolean(SwbLocationService.KEY_IS_RUNNING, false)
        val ret = JSObject().apply {
            put("running", running)
            put("message", if (running) "Pelacakan lokasi latar belakang sedang aktif." else "Pelacakan lokasi latar belakang nonaktif.")
            put("solution", if (running) "Layanan aktif merekam lokasi." else "Aktifkan pelacakan lokasi latar belakang jika diperlukan.")
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun getLocationHistory(call: PluginCall) {
        val limit = call.getInt("limit", 100) ?: 100
        val offset = call.getInt("offset", 0) ?: 0
        val from = call.getDouble("from")?.toLong()
        val to = call.getDouble("to")?.toLong()

        val (records, total, pending) = database.getHistory(limit, offset, from, to)

        val arr = JSArray()
        for (rec in records) {
            arr.put(rec.toJSObject())
        }

        val ret = JSObject().apply {
            put("records", arr)
            put("total", total)
            put("pendingUpload", pending)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun clearLocationHistory(call: PluginCall) {
        database.clearHistory()
        call.resolve()
    }

    // --- Media Methods ---

    @PluginMethod
    fun pickImage(call: PluginCall) {
        saveCall(call)
        isCapturingMedia = true
        SwbKeepAliveService.start(context, "Pilih Gambar Wahana", "Memilih gambar...")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(call, intent, REQUEST_PICK_IMAGE)
    }

    @PluginMethod
    fun takePhoto(call: PluginCall) {
        val cameraPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        if (cameraPerm != PackageManager.PERMISSION_GRANTED) {
            saveCall(call)
            pluginRequestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            return
        }

        launchCameraPhoto(call)
    }

    private fun launchCameraPhoto(call: PluginCall) {
        try {
            isCapturingMedia = true
            SwbKeepAliveService.start(context, "Kamera Wahana", "Mengambil foto...")

            val photoFile = File(context.cacheDir, "swb_photo_${System.currentTimeMillis()}.jpg")
            tempPhotoFile = photoFile
            savePendingCapturePath(KEY_PENDING_PHOTO_PATH, photoFile.absolutePath)

            val authority = "${context.packageName}.swbplugin.fileprovider"
            val photoUri = FileProvider.getUriForFile(context, authority, photoFile)

            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }

            saveCall(call)
            startActivityForResult(call, intent, REQUEST_TAKE_PHOTO)
        } catch (e: Exception) {
            isCapturingMedia = false
            call.reject(
                "Aplikasi kamera tidak dapat dibuka. Pastikan aplikasi kamera bawaan terpasang dan dapat digunakan.",
                "CAMERA_UNAVAILABLE"
            )
        }
    }

    @PluginMethod
    fun takeVideo(call: PluginCall) {
        val cameraPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        val micPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)

        if (cameraPerm != PackageManager.PERMISSION_GRANTED || micPerm != PackageManager.PERMISSION_GRANTED) {
            saveCall(call)
            pluginRequestPermissions(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                REQUEST_VIDEO_PERMISSION
            )
            return
        }

        launchCameraVideo(call)
    }

    private fun launchCameraVideo(call: PluginCall) {
        try {
            isCapturingMedia = true
            SwbKeepAliveService.start(context, "Video Wahana", "Merekam video...")

            val videoFile = File(context.cacheDir, "swb_video_${System.currentTimeMillis()}.mp4")
            tempVideoFile = videoFile
            savePendingCapturePath(KEY_PENDING_VIDEO_PATH, videoFile.absolutePath)

            val authority = "${context.packageName}.swbplugin.fileprovider"
            val videoUri = FileProvider.getUriForFile(context, authority, videoFile)

            val maxDuration = call.getInt("maxDuration", 60) ?: 60
            val quality = call.getString("quality", "high")

            val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, videoUri)
                putExtra(MediaStore.EXTRA_DURATION_LIMIT, maxDuration)
                putExtra(MediaStore.EXTRA_VIDEO_QUALITY, if (quality == "low") 0 else 1)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }

            saveCall(call)
            startActivityForResult(call, intent, REQUEST_TAKE_VIDEO)
        } catch (e: Exception) {
            isCapturingMedia = false
            call.reject(
                "Perekaman video tidak dapat dibuka. Pastikan aplikasi kamera video terpasang dan dapat digunakan.",
                "VIDEO_UNAVAILABLE"
            )
        }
    }

    override fun handleOnActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.handleOnActivityResult(requestCode, resultCode, data)
        isCapturingMedia = false

        // Delay stop keep alive so image processing completes safely
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_EXPLICIT_PROTECT, false) && !SwbLocationService.isServiceRunning) {
                mainHandler.postDelayed({
                    try {
                        val currentPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        if (!currentPrefs.getBoolean(KEY_EXPLICIT_PROTECT, false) && !isCapturingMedia && !SwbLocationService.isServiceRunning) {
                            SwbKeepAliveService.stop(context)
                        }
                    } catch (_: Exception) {
                    }
                }, 3000L)
            }
        } catch (_: Exception) {
        }

        val saved = savedCall ?: return

        if (resultCode == Activity.RESULT_CANCELED) {
            clearPendingCapturePath(KEY_PENDING_PHOTO_PATH)
            clearPendingCapturePath(KEY_PENDING_VIDEO_PATH)
            when (requestCode) {
                REQUEST_PICK_IMAGE -> saved.reject(
                    "Pemilihan gambar dibatalkan oleh pengguna.",
                    "USER_CANCELLED"
                )
                REQUEST_TAKE_PHOTO -> saved.reject(
                    "Pengambilan foto dibatalkan oleh pengguna.",
                    "USER_CANCELLED"
                )
                REQUEST_TAKE_VIDEO -> saved.reject(
                    "Perekaman video dibatalkan oleh pengguna.",
                    "USER_CANCELLED"
                )
                else -> saved.reject("Aktivitas dibatalkan oleh pengguna.", "USER_CANCELLED")
            }
            return
        }

        if (resultCode != Activity.RESULT_OK) {
            clearPendingCapturePath(KEY_PENDING_PHOTO_PATH)
            clearPendingCapturePath(KEY_PENDING_VIDEO_PATH)
            saved.reject(
                "Aktivitas kamera tidak berhasil diselesaikan. Silakan coba kembali.",
                "ACTIVITY_FAILED"
            )
            return
        }

        when (requestCode) {
            REQUEST_PICK_IMAGE -> {
                val uri = data?.data
                if (uri == null) {
                    saved.reject(
                        "Tidak ada berkas gambar yang dipilih.",
                        "NO_IMAGE_SELECTED"
                    )
                    return
                }

                try {
                    val destFile = File(context.cacheDir, "swb_picked_${System.currentTimeMillis()}.jpg")
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Could not open input stream for picked image")

                    val maxWidth = saved.getInt("maxWidth")
                    val maxHeight = saved.getInt("maxHeight")
                    val quality = saved.getInt("quality", 90) ?: 90
                    val includeBase64 = saved.getBoolean("includeBase64", true) ?: true

                    val (width, height, base64) = inputStream.use { stream ->
                        implementation.processImageFile(stream, destFile, maxWidth, maxHeight, quality, includeBase64)
                    }

                    val fileName = saved.getString("fileName") ?: destFile.name
                    val ret = JSObject().apply {
                        put("path", destFile.absolutePath)
                        put("uri", Uri.fromFile(destFile).toString())
                        put("fileName", fileName)
                        put("mimeType", "image/jpeg")
                        put("size", destFile.length())
                        put("width", width)
                        put("height", height)
                        if (base64 != null) {
                            put("base64", base64)
                        }
                    }
                    saved.resolve(ret)
                } catch (e: Exception) {
                    saved.reject(
                        "Gagal memproses gambar yang dipilih: ${e.message}",
                        "PROCESS_IMAGE_FAILED"
                    )
                }
            }

            REQUEST_TAKE_PHOTO -> {
                var photoFile = tempPhotoFile
                if (photoFile == null || !photoFile.exists()) {
                    val savedPath = getPendingCapturePath(KEY_PENDING_PHOTO_PATH)
                    if (savedPath != null) {
                        photoFile = File(savedPath)
                    }
                }
                clearPendingCapturePath(KEY_PENDING_PHOTO_PATH)

                if (photoFile == null || !photoFile.exists()) {
                    saved.reject(
                        "Berkas foto hasil kamera tidak ditemukan. Silakan ulangi pengambilan foto.",
                        "FILE_NOT_FOUND"
                    )
                    return
                }

                try {
                    val destFile = File(context.cacheDir, "swb_processed_${System.currentTimeMillis()}.jpg")
                    val maxWidth = saved.getInt("maxWidth")
                    val maxHeight = saved.getInt("maxHeight")
                    val quality = saved.getInt("quality", 90) ?: 90
                    val includeBase64 = saved.getBoolean("includeBase64", true) ?: true

                    val (width, height, base64) = FileInputStream(photoFile).use { stream ->
                        implementation.processImageFile(stream, destFile, maxWidth, maxHeight, quality, includeBase64)
                    }

                    // Clean up original capture
                    photoFile.delete()
                    tempPhotoFile = null

                    val fileName = saved.getString("fileName") ?: destFile.name
                    val ret = JSObject().apply {
                        put("path", destFile.absolutePath)
                        put("uri", Uri.fromFile(destFile).toString())
                        put("fileName", fileName)
                        put("mimeType", "image/jpeg")
                        put("size", destFile.length())
                        put("width", width)
                        put("height", height)
                        if (base64 != null) {
                            put("base64", base64)
                        }
                    }
                    saved.resolve(ret)
                } catch (e: Exception) {
                    saved.reject(
                        "Gagal memproses foto hasil kamera: ${e.message}",
                        "PROCESS_IMAGE_FAILED"
                    )
                }
            }

            REQUEST_TAKE_VIDEO -> {
                var videoFile = tempVideoFile
                if (videoFile == null || !videoFile.exists()) {
                    val savedPath = getPendingCapturePath(KEY_PENDING_VIDEO_PATH)
                    if (savedPath != null) {
                        videoFile = File(savedPath)
                    }
                }
                clearPendingCapturePath(KEY_PENDING_VIDEO_PATH)

                if (videoFile == null || !videoFile.exists()) {
                    saved.reject(
                        "Berkas rekaman video tidak ditemukan. Silakan ulangi perekaman.",
                        "FILE_NOT_FOUND"
                    )
                    return
                }

                tempVideoFile = null

                var duration: Int? = null
                var width: Int? = null
                var height: Int? = null

                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(videoFile.absolutePath)
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    if (durStr != null) {
                        duration = (durStr.toLong() / 1000).toInt()
                    }
                    val wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    if (wStr != null) {
                        width = wStr.toInt()
                    }
                    val hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    if (hStr != null) {
                        height = hStr.toInt()
                    }
                    retriever.release()
                } catch (_: Exception) {
                }

                val fileName = saved.getString("fileName") ?: videoFile.name
                val ret = JSObject().apply {
                    put("path", videoFile.absolutePath)
                    put("uri", Uri.fromFile(videoFile).toString())
                    put("fileName", fileName)
                    put("mimeType", "video/mp4")
                    put("size", videoFile.length())
                    if (duration != null) put("duration", duration)
                    if (width != null) put("width", width)
                    if (height != null) put("height", height)
                }
                saved.resolve(ret)
            }
        }
    }

    private fun savePendingCapturePath(key: String, path: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(key, path).apply()
        } catch (_: Exception) {
        }
    }

    private fun getPendingCapturePath(key: String): String? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(key, null)
        } catch (_: Exception) {
            null
        }
    }

    private fun clearPendingCapturePath(key: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(key).apply()
        } catch (_: Exception) {
        }
    }

    // --- Process Protection Methods ---

    @PluginMethod
    fun enableProcessProtection(call: PluginCall) {
        val title = call.getString("title") ?: "Wahana Express"
        val text = call.getString("text") ?: "Aplikasi aktif di latar belakang"
        val autoProtect = call.getBoolean("autoProtectOnBackground", true) ?: true

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_EXPLICIT_PROTECT, true)
            .putBoolean(KEY_AUTO_PROTECT, autoProtect)
            .putString(KEY_PROTECT_TITLE, title)
            .putString(KEY_PROTECT_TEXT, text)
            .apply()

        SwbKeepAliveService.start(context, title, text)
        call.resolve()
    }

    @PluginMethod
    fun disableProcessProtection(call: PluginCall) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_EXPLICIT_PROTECT, false)
            .apply()

        SwbKeepAliveService.stop(context)
        call.resolve()
    }

    @PluginMethod
    fun isProcessProtectionRunning(call: PluginCall) {
        val running = SwbKeepAliveService.isRunning || SwbLocationService.isServiceRunning
        val ret = JSObject().apply {
            put("running", running)
            put("message", if (running) "Proteksi proses latar belakang sedang aktif." else "Proteksi proses latar belakang nonaktif.")
            put("solution", if (running) "Aplikasi terlindungi di latar belakang." else "Aktifkan proteksi latar belakang jika diperlukan.")
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun setProcessProtectionConfig(call: PluginCall) {
        val autoProtect = call.getBoolean("autoProtectOnBackground", true) ?: true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_AUTO_PROTECT, autoProtect)
            .apply()
        call.resolve()
    }
}

