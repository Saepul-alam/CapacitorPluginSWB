package com.wahana.swbplugin

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.location.LocationManager
import android.media.ExifInterface
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Base64
import androidx.core.location.LocationManagerCompat
import com.getcapacitor.JSObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class SwbPluginImpl {

    companion object {
        private val KNOWN_MOCK_PACKAGES = setOf(
            "com.lexa.fakegps",
            "com.incorporateapps.fakegps.fre",
            "com.incorporateapps.fakegps",
            "com.rosteam.gpsemulator",
            "com.location.mock",
            "com.fakegps.mock",
            "com.blogspot.newprocess.fakegps",
            "com.theappninjas.fakegpsjoystick",
            "com.theappninjas.gpsjoystick",
            "com.ninja.fakegps",
            "com.marlon.floating.fake.location",
            "com.gsmartstudio.fakegps",
            "com.fly.gps",
            "ru.gavrikov.mocklocations",
            "com.lkr.fakelocation",
            "com.app.fakelocation",
            "com.marlove.fackgps",
            "com.kfn.fakegps",
            "com.evezzon.fakegps",
            "com.nordeste.fakegps",
            "com.fakelocation.android",
            "com.cell411.fakegps",
            "com.change.location",
            "com.virtual.location",
            "com.ltp.pro.fakelocation",
            "com.fakegps.route",
            "com.pe.fakegps",
            "com.mock.gps",
            "org.hola.gps",
            "com.usefullapps.fakegpslocation",
            "cl.datacomputacion.fakelocation",
            "com.ilwt.fakegps",
            "com.blacksparrow.fakegps",
            "com.fakegps.location",
            "com.devpos.fakegps"
        )

        private val SUSPICIOUS_KEYWORDS = listOf(
            "fakegps",
            "fake.gps",
            "mocklocation",
            "mock.location",
            "gpsjoystick",
            "gps.joystick",
            "fakelocation",
            "fake.location",
            "location.spoofer",
            "gps.spoofer",
            "gpsemulator",
            "gps.emulator",
            "spoofgps",
            "spooflocation"
        )

        // Cache package scan results to prevent continuous high CPU package scanning
        private var cachedPackageScanResult: Boolean? = null
        private var lastPackageScanTime: Long = 0L
        private const val PACKAGE_SCAN_CACHE_TTL_MS = 60_000L // 60 seconds
    }

    /**
     * Checks if the device environment is compromised by root/hooking frameworks.
     */
    fun isSecurityCompromised(context: Context): Boolean {
        return isHookingFrameworkDetected()
    }

    /**
     * Comprehensive multi-layer detection for Fake GPS / Mock Location.
     */
    fun isLocationMock(context: Context, location: Location?): Boolean {
        // 1. Direct Location Object Inspection
        if (location != null && isLocationObjectMock(location)) {
            return true
        }

        // 2. Cross-Provider Discrepancy (GPS vs Network Cell Tower)
        if (location != null && isProviderDiscrepant(context, location)) {
            return true
        }

        // 3. Hooking / Root Framework Inspection (Smali Patcher, LSPosed, Magisk)
        if (isHookingFrameworkDetected()) {
            return true
        }

        // 4. System Mock Provider active
        if (isSystemMockProviderActive(context)) {
            return true
        }

        return false
    }

    /**
     * Backward-compatible location mock check without Context.
     */
    fun isLocationMock(location: Location?): Boolean {
        if (location == null) return false
        return isLocationObjectMock(location)
    }

    /**
     * Inspects attributes of the Location instance itself.
     */
    fun isLocationObjectMock(location: Location): Boolean {
        // Android 12+ (API 31+) isMock method (reflection for compile-time SDK 30 compatibility)
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val isMockMethod = Location::class.java.getMethod("isMock")
                val isMockVal = isMockMethod.invoke(location) as? Boolean
                if (isMockVal == true) return true
            } catch (_: Exception) {
            }
        }

        // Android 4.3+ (API 18+) isFromMockProvider
        try {
            @Suppress("DEPRECATION")
            if (location.isFromMockProvider) return true
        } catch (_: Exception) {
        }

        // Check Bundle Extras for mock indicators
        try {
            val extras = location.extras
            if (extras != null && extras.getBoolean("mockLocation", false)) {
                return true
            }
        } catch (_: Exception) {
        }

        // Check provider name
        val provider = location.provider
        if (provider != null) {
            val lower = provider.toLowerCase(java.util.Locale.ROOT)
            if (lower == "mock" || lower == "test") {
                return true
            }
        }

        return false
    }

    /**
     * Checks if a mock location provider is active in system settings or AppOps.
     */
    fun isSystemMockProviderActive(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        if (appOps != null) {
            try {
                // Check self
                val selfMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION,
                        Process.myUid(),
                        context.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION,
                        Process.myUid(),
                        context.packageName
                    )
                }
                if (selfMode == AppOpsManager.MODE_ALLOWED) {
                    return true
                }
            } catch (_: Exception) {
            }
        }

        // Legacy ALLOW_MOCK_LOCATION setting (Android < 6.0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            try {
                @Suppress("DEPRECATION")
                if (Settings.Secure.getInt(context.contentResolver, Settings.Secure.ALLOW_MOCK_LOCATION, 0) != 0) {
                    return true
                }
            } catch (_: Exception) {
            }
        }

        return false
    }

    /**
     * Scans for installed Fake GPS applications with caching.
     */
    fun hasMockLocationAppsInstalled(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val cached = cachedPackageScanResult
        if (cached != null && (now - lastPackageScanTime < PACKAGE_SCAN_CACHE_TTL_MS)) {
            return cached
        }

        val result = doMockPackageScan(context)
        cachedPackageScanResult = result
        lastPackageScanTime = now
        return result
    }

    private fun doMockPackageScan(context: Context): Boolean {
        val pm = context.packageManager

        // 1. Direct fast lookup for known mock packages
        for (knownPkg in KNOWN_MOCK_PACKAGES) {
            try {
                pm.getApplicationInfo(knownPkg, 0)
                return true
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: Exception) {
            }
        }

        // 2. Scan installed packages for suspicious names or ACCESS_MOCK_LOCATION
        try {
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            for (pkg in packages) {
                if (pkg.packageName == context.packageName) continue

                if (KNOWN_MOCK_PACKAGES.contains(pkg.packageName)) {
                    return true
                }

                val lowerPkg = pkg.packageName.toLowerCase(java.util.Locale.ROOT)
                for (keyword in SUSPICIOUS_KEYWORDS) {
                    if (lowerPkg.contains(keyword)) {
                        return true
                    }
                }

                val requestedPermissions = pkg.requestedPermissions ?: continue
                for (perm in requestedPermissions) {
                    if (perm == "android.permission.ACCESS_MOCK_LOCATION") {
                        return true
                    }
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    /**
     * Detects root binaries or runtime hooking frameworks.
     */
    fun isHookingFrameworkDetected(): Boolean {
        val suPaths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
            "/system/xbin/daemonsu"
        )
        for (path in suPaths) {
            try {
                if (File(path).exists()) return true
            } catch (_: Exception) {
            }
        }

        val hookClasses = arrayOf(
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XposedHelpers",
            "org.lsposed.lspd.nativebridge.NativeBridge",
            "me.weishu.exposed.ExposedBridge"
        )
        for (className in hookClasses) {
            try {
                Class.forName(className)
                return true
            } catch (_: ClassNotFoundException) {
            } catch (_: Exception) {
            }
        }

        try {
            val buildTags = Build.TAGS
            if (buildTags != null && buildTags.contains("test-keys")) {
                return true
            }
        } catch (_: Exception) {
        }

        return false
    }

    /**
     * Compares GPS provider against Network provider. Discrepancy > 25km = spoofed.
     */
    fun isProviderDiscrepant(context: Context, gpsLocation: Location): Boolean {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return false
            if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return false

            @Suppress("MissingPermission")
            val networkLocation = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: return false

            val ageDiff = Math.abs(gpsLocation.time - networkLocation.time)
            if (ageDiff > 15 * 60 * 1000) return false

            val distance = gpsLocation.distanceTo(networkLocation)
            if (distance > 25000f) {
                return true
            }
        } catch (_: Exception) {
        }
        return false
    }

    /**
     * Checks if system location services (GPS) are enabled.
     */
    fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return LocationManagerCompat.isLocationEnabled(locationManager)
    }

    /**
     * Formats Location into standard Capacitor Position JSObject.
     */
    fun formatLocation(location: Location?): JSObject {
        val result = JSObject()
        if (location == null) return result

        val coords = JSObject()
        coords.put("latitude", location.latitude)
        coords.put("longitude", location.longitude)
        coords.put("accuracy", location.accuracy.toDouble())

        if (location.hasAltitude()) {
            coords.put("altitude", location.altitude)
        } else {
            coords.put("altitude", null)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
            coords.put("altitudeAccuracy", location.verticalAccuracyMeters.toDouble())
        } else {
            coords.put("altitudeAccuracy", null)
        }

        if (location.hasBearing()) {
            coords.put("heading", location.bearing.toDouble())
        } else {
            coords.put("heading", null)
        }

        if (location.hasSpeed()) {
            coords.put("speed", location.speed.toDouble())
        } else {
            coords.put("speed", null)
        }

        coords.put("isMock", isLocationMock(location))

        result.put("timestamp", location.time)
        result.put("coords", coords)
        return result
    }

    /**
     * Formats Location with deep mock inspection using Context.
     */
    fun formatLocation(context: Context, location: Location?): JSObject {
        val result = formatLocation(location)
        if (location != null) {
            val coords = result.getJSObject("coords") ?: JSObject()
            coords.put("isMock", isLocationMock(context, location))
            result.put("coords", coords)
        }
        return result
    }

    /**
     * Computes LocationStatus JSObject with detailed diagnosis code, clear message, and actionable solution.
     */
    fun calculateStatus(
        enabled: Boolean,
        granted: Boolean,
        isMock: Boolean,
        isCoarseOnly: Boolean = false
    ): JSObject {
        val usable = enabled && granted && !isMock
        val code: String
        val message: String
        val solution: String

        when {
            !enabled -> {
                code = "GPS_DISABLED"
                message = "Layanan GPS perangkat sedang nonaktif."
                solution = "Aktifkan GPS melalui Pengaturan atau panel notifikasi."
            }
            !granted && isCoarseOnly -> {
                code = "COARSE_ONLY"
                message = "Izin lokasi hanya perkiraan (approximate)."
                solution = "Aktifkan opsi 'Gunakan Lokasi Akurat' di Pengaturan aplikasi."
            }
            !granted -> {
                code = "PERMISSION_DENIED"
                message = "Izin akses lokasi belum diberikan."
                solution = "Berikan izin lokasi di Pengaturan aplikasi."
            }
            isMock -> {
                code = "MOCK_DETECTED"
                message = "Lokasi palsu (Fake GPS) terdeteksi aktif."
                solution = "Nonaktifkan aplikasi Fake GPS pada perangkat Anda."
            }
            else -> {
                code = "OK"
                message = "Layanan lokasi aktif dan siap digunakan."
                solution = "Perangkat siap digunakan."
            }
        }

        return JSObject().apply {
            put("enabled", enabled)
            put("granted", granted)
            put("isMock", isMock)
            put("usable", usable)
            put("code", code)
            put("message", message)
            put("solution", solution)
        }
    }

    /**
     * Processes an image file: resize, orientation fix, compress to cache file, encode base64.
     */
    fun processImageFile(
        inputStream: InputStream,
        destinationFile: File,
        maxWidth: Int?,
        maxHeight: Int?,
        quality: Int,
        includeBase64: Boolean
    ): Triple<Int, Int, String?> {
        // Decode image bounds and sample size
        val originalBytes = inputStream.readBytes()
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, options)

        var srcWidth = options.outWidth
        var srcHeight = options.outHeight

        var targetWidth = srcWidth
        var targetHeight = srcHeight

        if (maxWidth != null && targetWidth > maxWidth) {
            targetHeight = (targetHeight.toDouble() * maxWidth / targetWidth).toInt()
            targetWidth = maxWidth
        }
        if (maxHeight != null && targetHeight > maxHeight) {
            targetWidth = (targetWidth.toDouble() * maxHeight / targetHeight).toInt()
            targetHeight = maxHeight
        }

        // Calculate inSampleSize
        var inSampleSize = 1
        while (srcWidth / (inSampleSize * 2) >= targetWidth && srcHeight / (inSampleSize * 2) >= targetHeight) {
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }
        var bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, decodeOptions)
            ?: throw IllegalStateException("Could not decode image")

        // Scale if needed
        if (bitmap.width != targetWidth || bitmap.height != targetHeight) {
            val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            if (scaled != bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
        }

        // Write compressed bitmap to destination
        val safeQuality = quality.coerceIn(1, 100)
        FileOutputStream(destinationFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, safeQuality, fos)
        }

        var base64String: String? = null
        if (includeBase64) {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, safeQuality, baos)
            val bytes = baos.toByteArray()
            base64String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        val finalWidth = bitmap.width
        val finalHeight = bitmap.height
        bitmap.recycle()

        return Triple(finalWidth, finalHeight, base64String)
    }
}

