# swbplugin

Capacitor plugin for Wahana applications providing native GPS anti-Fake GPS detection, background location tracking, local history persistence, automatic HTTP position uploads, and native camera/media pickers.

Target platforms: **Android** (Kotlin) and **iOS** (Swift), compatible with both **Capacitor 2** and **Capacitor 3+**.

---

## Features

1. **Native Anti-Fake GPS & Gating**:
   - `Location.isMock` (Android 12+) and `isFromMockProvider` detection.
   - AppOps `OPSTR_MOCK_LOCATION` detection.
   - Known Fake GPS package and keyword scanning with cache for optimal performance.
   - Root and hooking detection (`su` binary paths, Xposed / LSPosed / substrate hooks, test-keys build).
   - Cross-provider discrepancy detection (GPS vs Network > 25 km).
   - iOS simulated location detection (`CLLocation.isSimulatedBySoftware`).
   - Native non-cancelable gating dialog with actions to open Settings or exit app.
2. **Background Location Tracking**:
   - **Android**: Foreground service (`START_STICKY`, `foregroundServiceType="location"`) with ongoing notification, stop action, and survival against app swipe / task kill.
   - **iOS**: Background location updates with `showsBackgroundLocationIndicator` and `allowsBackgroundLocationUpdates`.
   - Automatic resume on boot / reboot (if `restartOnBoot: true`).
3. **Local Location History**:
   - **Android**: SQLite database (`swb_locations.db`) with retention pruning and max record management.
   - **iOS**: Atomic JSON storage in Application Support directory with thread-safe queue.
4. **Native HTTP Position Upload**:
   - Automatic serial uploads (one JSON record per position) directly from native background service.
   - Configurable headers, custom metadata, and retry backoff schedule (`[5s, 10s, 30s, 60s, 300s]`).
   - Automatic retry on network reconnection.
5. **Native Media Pickers**:
   - **File Manager Image Picker**: Native SAF (Storage Access Framework) on Android / `UIDocumentPicker` on iOS (1 image per call).
   - **Camera Photo Capture**: Invokes the native system camera app; saves optimized JPEG in cache directory.
   - **Camera Video Recording**: Invokes the native system camera app; saves MP4/MOV in cache directory (path only, no base64).

---

## Installation

---

### Capacitor 2 Compatibility

If using Capacitor 2, register the plugin in your Android `MainActivity.java`:

```java
import com.wahana.swbplugin.SwbPlugin;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    this.init(savedInstanceState, new ArrayList<Class<? extends Plugin>>() {{
      add(SwbPlugin.class);
    }});
  }
}
```

On Capacitor 3+, the plugin is automatically registered without modifying `MainActivity`.

---

## Platform Configuration

### Android

1. Ensure the following permissions and declarations are present in your `android/app/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Location Permissions -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

    <!-- Foreground Service & Network Permissions -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <!-- Package Visibility for Fake GPS package scanning on Android 11+ (API 30+) -->
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"
        tools:ignore="QueryAllPackagesPermission" />

    <!-- Camera / Storage -->
    <uses-feature android:name="android.hardware.camera" android:required="false" />

    <application ...>
        <!-- Background Service -->
        <service
            android:name="com.wahana.swbplugin.SwbLocationService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="location" />

        <!-- Boot Receiver -->
        <receiver
            android:name="com.wahana.swbplugin.BootReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>

        <!-- Location Provider Receiver -->
        <receiver
            android:name="com.wahana.swbplugin.LocationProviderReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.location.PROVIDERS_CHANGED" />
            </intent-filter>
        </receiver>

        <!-- FileProvider for Camera Photo & Video -->
        <provider
            android:name="com.wahana.swbplugin.SwbFileProvider"
            android:authorities="${applicationId}.swbplugin.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/swb_file_paths" />
        </provider>
    </application>
</manifest>
```

> [!NOTE]
> `QUERY_ALL_PACKAGES` is required on Android 11+ (API 30+) to scan for installed Fake GPS spoofing applications. If publishing to Google Play Store, prepare a declaration justifying its use for anti-fraud location compliance.

### iOS

Add the following keys to your `ios/App/App/Info.plist`:

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>This app requires location access for accurate attendance and tracking.</string>

<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>This app requires background location access to track movement even when closed.</string>

<key>NSCameraUsageDescription</key>
<string>This app requires camera access to capture verification photos and videos.</string>

<key>NSMicrophoneUsageDescription</key>
<string>This app requires microphone access to record audio during video capture.</string>

<key>UIBackgroundModes</key>
<array>
    <string>location</string>
</array>
```

---

## HTTP Upload Format & Retry

When `uploadEnabled: true` is provided to `startBackgroundLocation`, the native service uploads each position record as a single JSON POST request:

### Request Payload

```json
{
  "id": "e4b6c310-7e10-4f3b-8212-005d7626920a",
  "timestamp": 1729560000000,
  "latitude": -6.200000,
  "longitude": 106.816666,
  "accuracy": 15.2,
  "altitude": 25.0,
  "altitudeAccuracy": 4.0,
  "heading": 90.0,
  "speed": 1.2,
  "isMock": false,
  "metadata": {
    "userId": "user-123",
    "shiftId": "shift-456"
  }
}
```

### Retry Backoff Schedule

Failed uploads are retained locally in the SQLite database / JSON store and retried using exponential backoff:
- Attempt 1: 5 seconds
- Attempt 2: 10 seconds
- Attempt 3: 30 seconds
- Attempt 4: 60 seconds
- Attempt 5+: 300 seconds (5 minutes max)

Uploads also resume immediately when the device reconnects to a network.

---

## Usage Examples

### 1. Import Plugin

```typescript
import { SwbPlugin } from 'swbplugin';
```

### 2. Check & Enforce Real Location (Anti-Mock Gating)

```typescript
// Check current status
const status = await SwbPlugin.checkLocationStatus();
console.log('Location status:', status);
// { enabled: true, granted: true, isMock: false, usable: true }

// Enforce location requirements with native gating dialog
const result = await SwbPlugin.enforceLocation({
  title: 'Location Verification Required',
  messagePermissionDenied: 'Please grant location permission to proceed.',
  messageGpsDisabled: 'Please enable GPS on your device.',
  messageMockDetected: 'Fake GPS / Mock Location detected! Please disable mock location apps.',
  actionButtonLabel: 'Open Settings',
  exitButtonLabel: 'Exit App',
});

if (!result.usable) {
  console.warn('Location cannot be used.');
}
```

### 3. Foreground Position & Watch

```typescript
// Single position
const pos = await SwbPlugin.getCurrentPosition({
  enableHighAccuracy: true,
  timeout: 10000,
});
console.log(`Lat: ${pos.coords.latitude}, Lng: ${pos.coords.longitude}, Mock: ${pos.coords.isMock}`);

// Watch position
const watchId = await SwbPlugin.watchPosition(
  {
    interval: 5000,
    fastestInterval: 2000,
    minimumDistance: 5,
  },
  (position, err) => {
    if (err) {
      console.error('Watch error:', err);
      return;
    }
    console.log('Watch update:', position);
  }
);

// Stop watch
await SwbPlugin.stopWatch(watchId);
```

### 4. Background Location Tracking & Auto Upload

```typescript
await SwbPlugin.startBackgroundLocation({
  interval: 10000,
  fastestInterval: 5000,
  minimumDistance: 10,
  uploadEnabled: true,
  uploadUrl: 'https://example.com/api/v1/locations',
  headers: {
    'Authorization': 'Bearer YOUR_TOKEN_HERE',
    'Content-Type': 'application/json'
  },
  metadata: {
    userId: '1001',
    deviceId: 'DEV-001'
  },
  notificationTitle: 'Wahana Tracking',
  notificationText: 'Tracking location in background',
  restartOnBoot: true,
  historyRetentionDays: 7,
  historyMaxRecords: 10000,
});

// Check if running
const { running } = await SwbPlugin.isBackgroundLocationRunning();

// Stop background tracking
await SwbPlugin.stopBackgroundLocation();
```

### 5. Local History

```typescript
// Fetch records
const history = await SwbPlugin.getLocationHistory({
  limit: 50,
  offset: 0,
});
console.log(`Total: ${history.total}, Pending Upload: ${history.pendingUpload}`);
console.log(history.records);

// Clear history
await SwbPlugin.clearLocationHistory();
```

### 6. Media Pickers

```typescript
// Pick 1 image from native file manager (SAF / UIDocumentPicker)
const pickedImage = await SwbPlugin.pickImage({
  includeBase64: true,
  maxWidth: 1280,
  maxHeight: 1280,
  quality: 85,
});
console.log('Picked image:', pickedImage.path, pickedImage.size);

// Take photo with system camera
const photo = await SwbPlugin.takePhoto({
  includeBase64: false,
  maxWidth: 1920,
  maxHeight: 1080,
  quality: 80,
});
console.log('Photo path:', photo.path);

// Record video with system camera
const video = await SwbPlugin.takeVideo({
  maxDuration: 30, // seconds
  quality: 'high',
});
console.log('Video path:', video.path, 'Duration:', video.duration);
```

### 7. Event Listeners

```typescript
// Listen for location updates
const locSub = await SwbPlugin.addListener('location', (position) => {
  console.log('Location event:', position);
});

// Listen for status changes (GPS toggled, mock detected)
const statusSub = await SwbPlugin.addListener('locationStatusChanged', (status) => {
  console.log('Status changed:', status);
});

// Listen for background uploads
const uploadSub = await SwbPlugin.addListener('backgroundUpload', (status) => {
  console.log('Upload status:', status.uploaded, 'Pending:', status.pending);
});

// Remove listener
locSub.remove();
```

---

## Example App

An example application is included in `example-app/`. To run it:

```bash
cd example-app
npm install
npm run build
npx cap sync
npx cap open android # or npx cap open ios
```


---

## Error Handling, Status Codes, and Actionable Solutions

Setiap error dan penolakan (rejection) pada seluruh fungsi plugin `swbplugin` diformat dengan struktur standar:
`"[Masalah spesifik]. Solusi: [Tindakan konkret yang harus dilakukan pengguna/pengembang]."`

### 1. Diagnosis Status Lokasi (`checkLocationStatus`)

Mengembalikan objek diagnosis `LocationStatus`:

| Code | Usable | Kondisi | Pesan & Solusi Bawaan |
|---|---|---|---|
| `OK` | `true` | GPS aktif, izin akurat diberikan, tidak ada Fake GPS. | "Layanan GPS aktif dan izin lokasi telah diberikan. Perangkat siap digunakan." |
| `GPS_DISABLED` | `false` | GPS perangkat nonaktif/dimatikan. | "Layanan GPS perangkat sedang mati. Solusi: Nyalakan GPS melalui tombol cepat panel notifikasi atau Pengaturan -> Lokasi." |
| `PERMISSION_DENIED` | `false` | Izin lokasi presisi belum diizinkan. | "Izin akses lokasi presisi belum diizinkan. Solusi: Buka Pengaturan Aplikasi -> Izin -> Lokasi, lalu berikan izin lokasi presisi/akurat." |
| `COARSE_ONLY` | `false` | Pengguna hanya memberikan izin perkiraan (approximate). | "Izin lokasi hanya berstatus perkiraan (approximate). Solusi: Buka Pengaturan Aplikasi -> Izin -> Lokasi, lalu aktifkan toggle 'Gunakan Lokasi Akurat / Tepat'." |
| `MOCK_DETECTED` | `false` | Aplikasi Fake GPS / tiruan terdeteksi aktif. | "Aplikasi pemalsu lokasi (Fake GPS) terdeteksi aktif. Solusi: Nonaktifkan aplikasi Fake GPS dan ubah setelan Opsi Pengembang 'Pilih aplikasi lokasi tiruan' menjadi Tidak Ada." |

### 2. Standard Rejection Error Codes

| Error Code | Fungsi Terkait | Penyebab & Solusi Bawaan |
|---|---|---|
| `PERMISSION_DENIED` | `getCurrentPosition`, `watchPosition`, `startBackgroundLocation` | Izin lokasi presisi ditolak. Solusi: Buka Pengaturan -> Izin -> Lokasi -> Izinkan lokasi akurat. |
| `COARSE_ONLY` | `getCurrentPosition`, `watchPosition`, `startBackgroundLocation` | Izin lokasi hanya perkiraan. Solusi: Aktifkan toggle lokasi akurat di Pengaturan Lokasi aplikasi. |
| `GPS_DISABLED` | `getCurrentPosition`, `watchPosition`, `startBackgroundLocation` | GPS mati. Solusi: Nyalakan GPS perangkat melalui menu Pengaturan atau quick settings. |
| `MOCK_LOCATION` | `getCurrentPosition`, `watchPosition`, `startBackgroundLocation` | Fake GPS aktif. Solusi: Matikan Fake GPS di Developer Options. |
| `LOCATION_UNAVAILABLE` | `getCurrentPosition`, `watchPosition` | Sinyal satelit GPS terhalang / timeout. Solusi: Pindah ke area terbuka dan coba kembali. |
| `CAMERA_PERMISSION_DENIED` | `takePhoto`, `takeVideo` | Izin kamera atau mikrofon belum diberikan. Solusi: Berikan izin di Pengaturan Aplikasi -> Izin. |
| `CAMERA_UNAVAILABLE` | `takePhoto` | Aplikasi kamera sistem tidak ditemukan / dinonaktifkan. Solusi: Aktifkan aplikasi kamera bawaan perangkat. |
| `VIDEO_UNAVAILABLE` | `takeVideo` | Aplikasi perekam video sistem tidak tersedia. Solusi: Aktifkan kamera sistem yang mendukung video. |
| `USER_CANCELLED` | `takePhoto`, `takeVideo`, `pickImage` | Pengguna membatalkan pengambilan gambar atau video. Solusi: Ambil foto/video dan tekan tombol simpan (centang). |
| `FILE_NOT_FOUND` / `PROCESS_IMAGE_FAILED` | `takePhoto`, `takeVideo`, `pickImage` | Berkas rusak atau memori internal perangkat penuh. Solusi: Periksa ruang memori dan coba kembali. |


## API

<docgen-index>

* [`getCurrentPosition(...)`](#getcurrentposition)
* [`watchPosition(...)`](#watchposition)
* [`stopWatch(...)`](#stopwatch)
* [`checkLocationStatus()`](#checklocationstatus)
* [`isLocationEnabled()`](#islocationenabled)
* [`enforceLocation(...)`](#enforcelocation)
* [`dismissGatingDialog()`](#dismissgatingdialog)
* [`checkPermissions()`](#checkpermissions)
* [`requestPermissions(...)`](#requestpermissions)
* [`openLocationSettings()`](#openlocationsettings)
* [`openAppSettings()`](#openappsettings)
* [`startBackgroundLocation(...)`](#startbackgroundlocation)
* [`stopBackgroundLocation()`](#stopbackgroundlocation)
* [`isBackgroundLocationRunning()`](#isbackgroundlocationrunning)
* [`getLocationHistory(...)`](#getlocationhistory)
* [`clearLocationHistory()`](#clearlocationhistory)
* [`pickImage(...)`](#pickimage)
* [`takePhoto(...)`](#takephoto)
* [`takeVideo(...)`](#takevideo)
* [`enableProcessProtection(...)`](#enableprocessprotection)
* [`disableProcessProtection()`](#disableprocessprotection)
* [`isProcessProtectionRunning()`](#isprocessprotectionrunning)
* [`setProcessProtectionConfig(...)`](#setprocessprotectionconfig)
* [`addListener('location', ...)`](#addlistenerlocation)
* [`addListener('locationStatusChanged', ...)`](#addlistenerlocationstatuschanged)
* [`addListener('backgroundLocation', ...)`](#addlistenerbackgroundlocation)
* [`addListener('backgroundUpload', ...)`](#addlistenerbackgroundupload)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### getCurrentPosition(...)

```typescript
getCurrentPosition(options?: PositionOptions | undefined) => Promise<Position>
```

| Param         | Type                                                        |
| ------------- | ----------------------------------------------------------- |
| **`options`** | <code><a href="#positionoptions">PositionOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#position">Position</a>&gt;</code>

--------------------


### watchPosition(...)

```typescript
watchPosition(options: WatchPositionOptions, callback: WatchPositionCallback) => Promise<CallbackID>
```

| Param          | Type                                                                    |
| -------------- | ----------------------------------------------------------------------- |
| **`options`**  | <code><a href="#watchpositionoptions">WatchPositionOptions</a></code>   |
| **`callback`** | <code><a href="#watchpositioncallback">WatchPositionCallback</a></code> |

**Returns:** <code>Promise&lt;<a href="#callbackid">CallbackID</a>&gt;</code>

--------------------


### stopWatch(...)

```typescript
stopWatch(options: CallbackID) => Promise<void>
```

| Param         | Type                                              |
| ------------- | ------------------------------------------------- |
| **`options`** | <code><a href="#callbackid">CallbackID</a></code> |

--------------------


### checkLocationStatus()

```typescript
checkLocationStatus() => Promise<LocationStatus>
```

**Returns:** <code>Promise&lt;<a href="#locationstatus">LocationStatus</a>&gt;</code>

--------------------


### isLocationEnabled()

```typescript
isLocationEnabled() => Promise<{ enabled: boolean; message?: string; solution?: string; }>
```

**Returns:** <code>Promise&lt;{ enabled: boolean; message?: string; solution?: string; }&gt;</code>

--------------------


### enforceLocation(...)

```typescript
enforceLocation(options?: EnforceLocationOptions | undefined) => Promise<LocationStatus>
```

| Param         | Type                                                                      |
| ------------- | ------------------------------------------------------------------------- |
| **`options`** | <code><a href="#enforcelocationoptions">EnforceLocationOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#locationstatus">LocationStatus</a>&gt;</code>

--------------------


### dismissGatingDialog()

```typescript
dismissGatingDialog() => Promise<void>
```

--------------------


### checkPermissions()

```typescript
checkPermissions() => Promise<PermissionStatus>
```

**Returns:** <code>Promise&lt;<a href="#permissionstatus">PermissionStatus</a>&gt;</code>

--------------------


### requestPermissions(...)

```typescript
requestPermissions(options?: { permissions: PermissionType[]; } | undefined) => Promise<PermissionStatus>
```

| Param         | Type                                            |
| ------------- | ----------------------------------------------- |
| **`options`** | <code>{ permissions: PermissionType[]; }</code> |

**Returns:** <code>Promise&lt;<a href="#permissionstatus">PermissionStatus</a>&gt;</code>

--------------------


### openLocationSettings()

```typescript
openLocationSettings() => Promise<void>
```

--------------------


### openAppSettings()

```typescript
openAppSettings() => Promise<void>
```

--------------------


### startBackgroundLocation(...)

```typescript
startBackgroundLocation(options: BackgroundLocationOptions) => Promise<void>
```

| Param         | Type                                                                            |
| ------------- | ------------------------------------------------------------------------------- |
| **`options`** | <code><a href="#backgroundlocationoptions">BackgroundLocationOptions</a></code> |

--------------------


### stopBackgroundLocation()

```typescript
stopBackgroundLocation() => Promise<void>
```

--------------------


### isBackgroundLocationRunning()

```typescript
isBackgroundLocationRunning() => Promise<{ running: boolean; message?: string; solution?: string; }>
```

**Returns:** <code>Promise&lt;{ running: boolean; message?: string; solution?: string; }&gt;</code>

--------------------


### getLocationHistory(...)

```typescript
getLocationHistory(options?: { limit?: number | undefined; offset?: number | undefined; from?: number | undefined; to?: number | undefined; } | undefined) => Promise<HistoryResult>
```

| Param         | Type                                                                          |
| ------------- | ----------------------------------------------------------------------------- |
| **`options`** | <code>{ limit?: number; offset?: number; from?: number; to?: number; }</code> |

**Returns:** <code>Promise&lt;<a href="#historyresult">HistoryResult</a>&gt;</code>

--------------------


### clearLocationHistory()

```typescript
clearLocationHistory() => Promise<void>
```

--------------------


### pickImage(...)

```typescript
pickImage(options?: ImageOptions | undefined) => Promise<ImageResult>
```

| Param         | Type                                                  |
| ------------- | ----------------------------------------------------- |
| **`options`** | <code><a href="#imageoptions">ImageOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#imageresult">ImageResult</a>&gt;</code>

--------------------


### takePhoto(...)

```typescript
takePhoto(options?: ImageOptions | undefined) => Promise<ImageResult>
```

| Param         | Type                                                  |
| ------------- | ----------------------------------------------------- |
| **`options`** | <code><a href="#imageoptions">ImageOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#imageresult">ImageResult</a>&gt;</code>

--------------------


### takeVideo(...)

```typescript
takeVideo(options?: VideoOptions | undefined) => Promise<VideoResult>
```

| Param         | Type                                                  |
| ------------- | ----------------------------------------------------- |
| **`options`** | <code><a href="#videooptions">VideoOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#videoresult">VideoResult</a>&gt;</code>

--------------------


### enableProcessProtection(...)

```typescript
enableProcessProtection(options?: ProcessProtectionOptions | undefined) => Promise<void>
```

| Param         | Type                                                                          |
| ------------- | ----------------------------------------------------------------------------- |
| **`options`** | <code><a href="#processprotectionoptions">ProcessProtectionOptions</a></code> |

--------------------


### disableProcessProtection()

```typescript
disableProcessProtection() => Promise<void>
```

--------------------


### isProcessProtectionRunning()

```typescript
isProcessProtectionRunning() => Promise<{ running: boolean; message?: string; solution?: string; }>
```

**Returns:** <code>Promise&lt;{ running: boolean; message?: string; solution?: string; }&gt;</code>

--------------------


### setProcessProtectionConfig(...)

```typescript
setProcessProtectionConfig(options: { autoProtectOnBackground: boolean; }) => Promise<void>
```

| Param         | Type                                               |
| ------------- | -------------------------------------------------- |
| **`options`** | <code>{ autoProtectOnBackground: boolean; }</code> |

--------------------


### addListener('location', ...)

```typescript
addListener(eventName: 'location', listenerFunc: (position: Position) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                 |
| ------------------ | -------------------------------------------------------------------- |
| **`eventName`**    | <code>'location'</code>                                              |
| **`listenerFunc`** | <code>(position: <a href="#position">Position</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('locationStatusChanged', ...)

```typescript
addListener(eventName: 'locationStatusChanged', listenerFunc: (status: LocationStatus) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                           |
| ------------------ | ------------------------------------------------------------------------------ |
| **`eventName`**    | <code>'locationStatusChanged'</code>                                           |
| **`listenerFunc`** | <code>(status: <a href="#locationstatus">LocationStatus</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('backgroundLocation', ...)

```typescript
addListener(eventName: 'backgroundLocation', listenerFunc: (position: Position) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                 |
| ------------------ | -------------------------------------------------------------------- |
| **`eventName`**    | <code>'backgroundLocation'</code>                                    |
| **`listenerFunc`** | <code>(position: <a href="#position">Position</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('backgroundUpload', ...)

```typescript
addListener(eventName: 'backgroundUpload', listenerFunc: (status: UploadStatus) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                       |
| ------------------ | -------------------------------------------------------------------------- |
| **`eventName`**    | <code>'backgroundUpload'</code>                                            |
| **`listenerFunc`** | <code>(status: <a href="#uploadstatus">UploadStatus</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### Interfaces


#### Position

| Prop            | Type                                                                |
| --------------- | ------------------------------------------------------------------- |
| **`timestamp`** | <code>number</code>                                                 |
| **`coords`**    | <code><a href="#locationcoordinates">LocationCoordinates</a></code> |


#### LocationCoordinates

| Prop                   | Type                        |
| ---------------------- | --------------------------- |
| **`latitude`**         | <code>number</code>         |
| **`longitude`**        | <code>number</code>         |
| **`accuracy`**         | <code>number</code>         |
| **`altitude`**         | <code>number \| null</code> |
| **`altitudeAccuracy`** | <code>number \| null</code> |
| **`heading`**          | <code>number \| null</code> |
| **`speed`**            | <code>number \| null</code> |
| **`isMock`**           | <code>boolean</code>        |


#### PositionOptions

| Prop                     | Type                 |
| ------------------------ | -------------------- |
| **`enableHighAccuracy`** | <code>boolean</code> |
| **`timeout`**            | <code>number</code>  |
| **`maximumAge`**         | <code>number</code>  |


#### CallbackID

| Prop     | Type                |
| -------- | ------------------- |
| **`id`** | <code>string</code> |


#### WatchPositionOptions

| Prop                  | Type                |
| --------------------- | ------------------- |
| **`interval`**        | <code>number</code> |
| **`fastestInterval`** | <code>number</code> |
| **`minimumDistance`** | <code>number</code> |


#### LocationStatus

| Prop           | Type                                                              |
| -------------- | ----------------------------------------------------------------- |
| **`enabled`**  | <code>boolean</code>                                              |
| **`granted`**  | <code>boolean</code>                                              |
| **`isMock`**   | <code>boolean</code>                                              |
| **`usable`**   | <code>boolean</code>                                              |
| **`code`**     | <code><a href="#locationstatuscode">LocationStatusCode</a></code> |
| **`message`**  | <code>string</code>                                               |
| **`solution`** | <code>string</code>                                               |


#### EnforceLocationOptions

| Prop                          | Type                |
| ----------------------------- | ------------------- |
| **`title`**                   | <code>string</code> |
| **`messagePermissionDenied`** | <code>string</code> |
| **`messageGpsDisabled`**      | <code>string</code> |
| **`messageMockDetected`**     | <code>string</code> |
| **`actionButtonLabel`**       | <code>string</code> |
| **`exitButtonLabel`**         | <code>string</code> |


#### PermissionStatus

| Prop                     | Type                                                        |
| ------------------------ | ----------------------------------------------------------- |
| **`location`**           | <code><a href="#permissionstate">PermissionState</a></code> |
| **`coarseLocation`**     | <code><a href="#permissionstate">PermissionState</a></code> |
| **`backgroundLocation`** | <code><a href="#permissionstate">PermissionState</a></code> |
| **`camera`**             | <code><a href="#permissionstate">PermissionState</a></code> |
| **`microphone`**         | <code><a href="#permissionstate">PermissionState</a></code> |
| **`message`**            | <code>string</code>                                         |
| **`solution`**           | <code>string</code>                                         |


#### BackgroundLocationOptions

| Prop                       | Type                                                             |
| -------------------------- | ---------------------------------------------------------------- |
| **`uploadUrl`**            | <code>string</code>                                              |
| **`headers`**              | <code><a href="#record">Record</a>&lt;string, string&gt;</code>  |
| **`uploadEnabled`**        | <code>boolean</code>                                             |
| **`metadata`**             | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> |
| **`allowInsecureHttp`**    | <code>boolean</code>                                             |
| **`notificationTitle`**    | <code>string</code>                                              |
| **`notificationText`**     | <code>string</code>                                              |
| **`restartOnBoot`**        | <code>boolean</code>                                             |
| **`significantChanges`**   | <code>boolean</code>                                             |
| **`historyRetentionDays`** | <code>number</code>                                              |
| **`historyMaxRecords`**    | <code>number</code>                                              |


#### HistoryResult

| Prop                | Type                          |
| ------------------- | ----------------------------- |
| **`records`**       | <code>LocationRecord[]</code> |
| **`total`**         | <code>number</code>           |
| **`pendingUpload`** | <code>number</code>           |


#### LocationRecord

| Prop             | Type                                          |
| ---------------- | --------------------------------------------- |
| **`id`**         | <code>string</code>                           |
| **`position`**   | <code><a href="#position">Position</a></code> |
| **`isMock`**     | <code>boolean</code>                          |
| **`uploaded`**   | <code>boolean</code>                          |
| **`uploadedAt`** | <code>number</code>                           |
| **`attempts`**   | <code>number</code>                           |


#### ImageResult

| Prop           | Type                |
| -------------- | ------------------- |
| **`path`**     | <code>string</code> |
| **`uri`**      | <code>string</code> |
| **`fileName`** | <code>string</code> |
| **`mimeType`** | <code>string</code> |
| **`size`**     | <code>number</code> |
| **`width`**    | <code>number</code> |
| **`height`**   | <code>number</code> |
| **`base64`**   | <code>string</code> |


#### ImageOptions

| Prop                | Type                 |
| ------------------- | -------------------- |
| **`includeBase64`** | <code>boolean</code> |
| **`maxWidth`**      | <code>number</code>  |
| **`maxHeight`**     | <code>number</code>  |
| **`quality`**       | <code>number</code>  |
| **`fileName`**      | <code>string</code>  |


#### VideoResult

| Prop           | Type                |
| -------------- | ------------------- |
| **`path`**     | <code>string</code> |
| **`uri`**      | <code>string</code> |
| **`fileName`** | <code>string</code> |
| **`mimeType`** | <code>string</code> |
| **`size`**     | <code>number</code> |
| **`duration`** | <code>number</code> |
| **`width`**    | <code>number</code> |
| **`height`**   | <code>number</code> |


#### VideoOptions

| Prop              | Type                                     |
| ----------------- | ---------------------------------------- |
| **`maxDuration`** | <code>number</code>                      |
| **`quality`**     | <code>'low' \| 'medium' \| 'high'</code> |
| **`fileName`**    | <code>string</code>                      |


#### ProcessProtectionOptions

| Prop                          | Type                 |
| ----------------------------- | -------------------- |
| **`title`**                   | <code>string</code>  |
| **`text`**                    | <code>string</code>  |
| **`autoProtectOnBackground`** | <code>boolean</code> |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### UploadStatus

| Prop           | Type                 |
| -------------- | -------------------- |
| **`uploaded`** | <code>boolean</code> |
| **`pending`**  | <code>number</code>  |
| **`error`**    | <code>string</code>  |


### Type Aliases


#### CallbackID

<code>string</code>


#### WatchPositionCallback

<code>(position: <a href="#position">Position</a> | null, err?: any): void</code>


#### LocationStatusCode

<code>'OK' | 'PERMISSION_DENIED' | 'COARSE_ONLY' | 'GPS_DISABLED' | 'MOCK_DETECTED' | 'SIGNAL_TIMEOUT'</code>


#### PermissionState

<code>'prompt' | 'prompt-with-rationale' | 'granted' | 'denied'</code>


#### PermissionType

<code>'location' | 'coarseLocation' | 'backgroundLocation' | 'camera' | 'microphone'</code>


#### Record

Construct a type with a set of properties K of type T

<code>{ [P in K]: T; }</code>

</docgen-api>

