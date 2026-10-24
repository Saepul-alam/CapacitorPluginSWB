import Foundation
import Capacitor
import CoreLocation
import UIKit
import AVFoundation
import MobileCoreServices
#if canImport(UniformTypeIdentifiers)
import UniformTypeIdentifiers
#endif

@objc(SwbPlugin)
public class SwbPlugin: CAPPlugin, CLLocationManagerDelegate, UIImagePickerControllerDelegate, UINavigationControllerDelegate, UIDocumentPickerDelegate {

    public let identifier = "SwbPlugin"
    public let jsName = "SwbPlugin"

    private let implementation = Swb()
    private var locationManager: CLLocationManager?
    private var watchCalls: [String: CAPPluginCall] = [:]
    private var singleCallQueue: [CAPPluginCall] = []
    private var pendingPermissionCall: CAPPluginCall?
    private var lastLocation: CLLocation?
    private var gatingAlert: UIAlertController?

    private var isBackgroundRunning = false
    private var backgroundOptions: [String: Any] = [:]
    private var mockDetectedInBackground = false

    // Pending media calls
    private var pendingImageCall: CAPPluginCall?
    private var pendingVideoCall: CAPPluginCall?

    override public func load() {
        super.load()
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.locationManager = CLLocationManager()
            self.locationManager?.delegate = self
            self.locationManager?.desiredAccuracy = kCLLocationAccuracyBest
            self.locationManager?.distanceFilter = kCLDistanceFilterNone
        }

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )

        SwbHttpUploader.shared.onUploadStatus = { [weak self] uploaded, pending, error in
            guard let self = self else { return }
            var data: [String: Any] = [
                "uploaded": uploaded,
                "pending": pending
            ]
            if let error = error {
                data["error"] = error
            }
            self.notifyListeners("backgroundUpload", data: data)
        }
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        locationManager?.stopUpdatingLocation()
        locationManager?.stopMonitoringSignificantLocationChanges()
        locationManager?.delegate = nil
    }

    @objc private func handleAppDidBecomeActive() {
        if mockDetectedInBackground {
            mockDetectedInBackground = false
            showMockGatingAlert()
            emitLocationStatusChanged()
            return
        }

        checkAndEnforce(nil)
    }

    private func getAuthorizationStatus() -> CLAuthorizationStatus {
        if #available(iOS 14.0, *) {
            return locationManager?.authorizationStatus ?? CLLocationManager().authorizationStatus
        } else {
            return CLLocationManager.authorizationStatus()
        }
    }

    private func isPermissionGranted() -> Bool {
        let status = getAuthorizationStatus()
        return status == .authorizedWhenInUse || status == .authorizedAlways
    }

    private func getPermissionString() -> String {
        switch getAuthorizationStatus() {
        case .authorizedAlways, .authorizedWhenInUse:
            return "granted"
        case .denied, .restricted:
            return "denied"
        case .notDetermined:
            return "prompt"
        @unknown default:
            return "prompt"
        }
    }

    // MARK: - Foreground Location

    @objc func getCurrentPosition(_ call: CAPPluginCall) {
        guard CLLocationManager.locationServicesEnabled() else {
            call.reject("Layanan Lokasi (GPS) iPhone sedang nonaktif. Silakan aktifkan Layanan Lokasi di Pengaturan.", "GPS_DISABLED")
            return
        }

        guard isPermissionGranted() else {
            call.reject("Izin akses lokasi belum diberikan. Silakan berikan izin akses lokasi melalui Pengaturan iPhone.", "PERMISSION_DENIED")
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self, let manager = self.locationManager else {
                call.reject("Layanan manajer lokasi tidak tersedia. Pastikan perangkat mendukung GPS.", "LOCATION_UNAVAILABLE")
                return
            }

            if let last = self.lastLocation, fabs(last.timestamp.timeIntervalSinceNow) < 5.0 {
                let isMock = self.implementation.isLocationSimulated(last)
                if isMock {
                    self.showMockGatingAlert()
                    self.emitLocationStatusChanged()
                    call.reject("Aplikasi pemalsu lokasi (Fake GPS) terdeteksi aktif. Silakan matikan simulasi lokasi pada perangkat.", "MOCK_LOCATION")
                    return
                }
                call.resolve(self.implementation.formatLocation(last))
                return
            }

            self.singleCallQueue.append(call)
            manager.requestLocation()
        }
    }

    @objc func watchPosition(_ call: CAPPluginCall) {
        guard CLLocationManager.locationServicesEnabled() else {
            call.reject("Layanan Lokasi (GPS) iPhone sedang nonaktif. Silakan aktifkan Layanan Lokasi di Pengaturan.", "GPS_DISABLED")
            return
        }

        guard isPermissionGranted() else {
            call.reject("Izin akses lokasi belum diberikan. Silakan berikan izin akses lokasi melalui Pengaturan iPhone.", "PERMISSION_DENIED")
            return
        }

        guard let callbackId = call.callbackId else {
            call.reject("Parameter callbackId tidak ditemukan.", "INVALID_ARGUMENT")
            return
        }
        call.keepAlive = true

        DispatchQueue.main.async { [weak self] in
            guard let self = self, let manager = self.locationManager else {
                call.reject("Location manager unavailable")
                return
            }

            let enableHighAccuracy = call.getBool("enableHighAccuracy", true)
            manager.desiredAccuracy = enableHighAccuracy ? kCLLocationAccuracyBestForNavigation : kCLLocationAccuracyHundredMeters
            manager.distanceFilter = kCLDistanceFilterNone

            self.watchCalls[callbackId] = call
            manager.startUpdatingLocation()

            if let last = self.lastLocation {
                call.resolve(self.implementation.formatLocation(last))
            }
        }
    }

    @objc func stopWatch(_ call: CAPPluginCall) {
        guard let id = call.getString("id") ?? call.callbackId else {
            call.resolve()
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                call.resolve()
                return
            }

            self.watchCalls.removeValue(forKey: id)
            if self.watchCalls.isEmpty && self.singleCallQueue.isEmpty && !self.isBackgroundRunning {
                self.locationManager?.stopUpdatingLocation()
            }
            call.resolve()
        }
    }

    @objc override public func checkPermissions(_ call: CAPPluginCall) {
        let perm = getPermissionString()
        var bgPerm = "prompt"
        if getAuthorizationStatus() == .authorizedAlways {
            bgPerm = "granted"
        }

        let cameraAuth = AVCaptureDevice.authorizationStatus(for: .video)
        let cameraPerm: String
        switch cameraAuth {
        case .authorized: cameraPerm = "granted"
        case .denied, .restricted: cameraPerm = "denied"
        default: cameraPerm = "prompt"
        }

        let micAuth = AVCaptureDevice.authorizationStatus(for: .audio)
        let micPerm: String
        switch micAuth {
        case .authorized: micPerm = "granted"
        case .denied, .restricted: micPerm = "denied"
        default: micPerm = "prompt"
        }

        var missing = [String]()
        var solutions = [String]()
        if perm != "granted" {
            missing.append("Lokasi")
            solutions.append("Buka Pengaturan iPhone -> Wahana -> Lokasi, pilih 'Saat Menggunakan App'")
        }
        if cameraPerm != "granted" {
            missing.append("Kamera")
            solutions.append("Buka Pengaturan iPhone -> Wahana -> aktifkan Kamera")
        }
        if micPerm != "granted" {
            missing.append("Mikrofon")
            solutions.append("Buka Pengaturan iPhone -> Wahana -> aktifkan Mikrofon")
        }

        var ret: [String: Any] = [
            "location": perm,
            "coarseLocation": perm,
            "backgroundLocation": bgPerm,
            "camera": cameraPerm,
            "microphone": micPerm
        ]

        if missing.isEmpty {
            ret["message"] = "Semua izin akses yang diperlukan telah diberikan oleh pengguna."
            ret["solution"] = "Aplikasi siap menggunakan seluruh fitur lokasi dan media."
        } else {
            ret["message"] = "Izin belum diberikan untuk: \(missing.joined(separator: ", "))."
            ret["solution"] = "\(solutions.joined(separator: "; "))."
        }

        call.resolve(ret)
    }

    @objc override public func requestPermissions(_ call: CAPPluginCall) {
        if isPermissionGranted() {
            checkPermissions(call)
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self, let manager = self.locationManager else {
                call.reject("Layanan manajer lokasi tidak tersedia. Pastikan GPS perangkat didukung.", "LOCATION_UNAVAILABLE")
                return
            }

            self.pendingPermissionCall = call
            manager.requestAlwaysAuthorization()
        }
    }

    @objc func isLocationEnabled(_ call: CAPPluginCall) {
        let enabled = CLLocationManager.locationServicesEnabled()
        call.resolve([
            "enabled": enabled,
            "message": enabled ? "Layanan Lokasi (GPS) iPhone aktif." : "Layanan Lokasi (GPS) iPhone sedang nonaktif.",
            "solution": enabled ? "Perangkat siap membaca koordinat lokasi." : "Buka Pengaturan iPhone -> Privasi & Keamanan -> Layanan Lokasi, lalu aktifkan 'Layanan Lokasi'."
        ])
    }

    @objc func checkLocationStatus(_ call: CAPPluginCall) {
        let enabled = CLLocationManager.locationServicesEnabled()
        let granted = isPermissionGranted()
        let isMock = lastLocation != nil ? implementation.isLocationSimulated(lastLocation!) : false

        call.resolve(implementation.calculateStatus(enabled: enabled, granted: granted, isMock: isMock))
    }

    @objc func openLocationSettings(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if let url = URL(string: UIApplication.openSettingsURLString), UIApplication.shared.canOpenURL(url) {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
        }
        call.resolve()
    }

    @objc func openAppSettings(_ call: CAPPluginCall) {
        openLocationSettings(call)
    }

    @objc func enforceLocation(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.checkAndEnforce(call)
        }
    }

    @objc func dismissGatingDialog(_ call: CAPPluginCall) {
        dismissNativeGatingAlert()
        call.resolve()
    }

    private func checkAndEnforce(_ call: CAPPluginCall?) {
        let authStatus = getAuthorizationStatus()

        if authStatus == .notDetermined {
            pendingPermissionCall = call
            locationManager?.requestAlwaysAuthorization()
            return
        }

        if !CLLocationManager.locationServicesEnabled() {
            showNativeGatingAlert(
                title: call?.getString("title") ?? "Aktifkan Layanan Lokasi",
                message: call?.getString("messageGpsDisabled") ?? "Layanan Lokasi (GPS) iPhone sedang nonaktif. Silakan aktifkan Layanan Lokasi di Pengaturan untuk melanjutkan.",
                actionText: call?.getString("actionButtonLabel") ?? "Buka Pengaturan"
            )
            let status = implementation.calculateStatus(enabled: false, granted: isPermissionGranted(), isMock: false)
            notifyListeners("locationStatusChanged", data: status)
            call?.resolve(status)
            return
        }

        if !isPermissionGranted() {
            showNativeGatingAlert(
                title: call?.getString("title") ?? "Izin Lokasi Diperlukan",
                message: call?.getString("messagePermissionDenied") ?? "Aplikasi membutuhkan izin akses lokasi presisi (GPS) agar dapat digunakan. Silakan berikan izin lokasi di Pengaturan.",
                actionText: call?.getString("actionButtonLabel") ?? "Buka Pengaturan"
            )
            let status = implementation.calculateStatus(enabled: true, granted: false, isMock: false)
            notifyListeners("locationStatusChanged", data: status)
            call?.resolve(status)
            return
        }

        if let loc = lastLocation, implementation.isLocationSimulated(loc) {
            showMockGatingAlert()
            let status = implementation.calculateStatus(enabled: true, granted: true, isMock: true)
            notifyListeners("locationStatusChanged", data: status)
            call?.resolve(status)
            return
        }

        dismissNativeGatingAlert()
        let status = implementation.calculateStatus(enabled: true, granted: true, isMock: false)
        notifyListeners("locationStatusChanged", data: status)
        call?.resolve(status)
    }

    private func showMockGatingAlert() {
        showNativeGatingAlert(
            title: "Lokasi Tiruan Terdeteksi",
            message: "Terdeteksi manipulasi lokasi (Fake GPS / Lokasi Simulasi). Silakan matikan simulasi lokasi pada perangkat untuk melanjutkan.",
            actionText: "Buka Pengaturan"
        )
    }

    private func showNativeGatingAlert(title: String, message: String, actionText: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.gatingAlert?.dismiss(animated: false, completion: nil)

            let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: actionText, style: .default, handler: { _ in
                if let url = URL(string: UIApplication.openSettingsURLString), UIApplication.shared.canOpenURL(url) {
                    UIApplication.shared.open(url, options: [:], completionHandler: nil)
                }
            }))

            self.bridge?.viewController?.present(alert, animated: true, completion: nil)
            self.gatingAlert = alert
        }
    }

    private func dismissNativeGatingAlert() {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.gatingAlert?.dismiss(animated: true, completion: nil)
            self.gatingAlert = nil
        }
    }

    private func emitLocationStatusChanged() {
        let enabled = CLLocationManager.locationServicesEnabled()
        let granted = isPermissionGranted()
        let isMock = lastLocation != nil ? implementation.isLocationSimulated(lastLocation!) : false

        notifyListeners("locationStatusChanged", data: implementation.calculateStatus(enabled: enabled, granted: granted, isMock: isMock))
    }

    // MARK: - Background Location & Upload

    @objc func startBackgroundLocation(_ call: CAPPluginCall) {
        guard CLLocationManager.locationServicesEnabled() else {
            call.reject("Layanan Lokasi (GPS) iPhone sedang nonaktif. Silakan aktifkan Layanan Lokasi di Pengaturan sebelum memulai pelacakan latar belakang.", "GPS_DISABLED")
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self, let manager = self.locationManager else {
                call.reject("Layanan manajer lokasi tidak tersedia. Pastikan perangkat mendukung GPS.", "LOCATION_UNAVAILABLE")
                return
            }

            manager.allowsBackgroundLocationUpdates = true
            manager.pausesLocationUpdatesAutomatically = false
            if #available(iOS 11.0, *) {
                manager.showsBackgroundLocationIndicator = true
            }

            let enableHighAccuracy = call.getBool("enableHighAccuracy", true)
            manager.desiredAccuracy = enableHighAccuracy ? kCLLocationAccuracyBest : kCLLocationAccuracyHundredMeters
            manager.distanceFilter = call.getDouble("minimumDistance") ?? kCLDistanceFilterNone

            if call.getBool("significantChanges", false) {
                manager.startMonitoringSignificantLocationChanges()
            }

            manager.startUpdatingLocation()
            self.isBackgroundRunning = true

            // Store options
            var options: [String: Any] = [:]
            options["uploadUrl"] = call.getString("uploadUrl")
            options["uploadEnabled"] = call.getBool("uploadEnabled", true)
            options["allowInsecureHttp"] = call.getBool("allowInsecureHttp", false)
            options["historyRetentionDays"] = call.getInt("historyRetentionDays", 7)
            options["historyMaxRecords"] = call.getInt("historyMaxRecords", 10000)

            if let headers = call.getObject("headers") as? [String: String] {
                options["headers"] = headers
            }
            if let metadata = call.getObject("metadata") as? [String: String] {
                options["metadata"] = metadata
            }

            self.backgroundOptions = options
            UserDefaults.standard.set(true, forKey: "swb_background_running")

            // Trigger any pending uploads
            let uploadUrl = options["uploadUrl"] as? String
            let headers = options["headers"] as? [String: String]
            let allowInsecure = options["allowInsecureHttp"] as? Bool ?? false
            SwbHttpUploader.shared.triggerUpload(uploadUrl: uploadUrl, headers: headers, allowInsecureHttp: allowInsecure)

            call.resolve()
        }
    }

    @objc func stopBackgroundLocation(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                call.resolve()
                return
            }

            self.isBackgroundRunning = false
            self.locationManager?.allowsBackgroundLocationUpdates = false
            self.locationManager?.stopMonitoringSignificantLocationChanges()
            if self.watchCalls.isEmpty {
                self.locationManager?.stopUpdatingLocation()
            }
            UserDefaults.standard.set(false, forKey: "swb_background_running")
            call.resolve()
        }
    }

    @objc func isBackgroundLocationRunning(_ call: CAPPluginCall) {
        call.resolve([
            "running": isBackgroundRunning,
            "message": isBackgroundRunning ? "Pelacakan lokasi latar belakang iOS sedang aktif." : "Pelacakan lokasi latar belakang sedang nonaktif.",
            "solution": isBackgroundRunning ? "Layanan aktif merekam lokasi." : "Jalankan startBackgroundLocation() untuk mengaktifkan pelacakan."
        ])
    }

    @objc func getLocationHistory(_ call: CAPPluginCall) {
        let limit = call.getInt("limit", 100)
        let offset = call.getInt("offset", 0)
        let from = call.getDouble("from").map { Int64($0) }
        let to = call.getDouble("to").map { Int64($0) }

        let result = SwbLocationStore.shared.getHistory(limit: limit, offset: offset, from: from, to: to)
        let arr = result.records.map { $0.toJSObject() }

        call.resolve([
            "records": arr,
            "total": result.total,
            "pendingUpload": result.pending
        ])
    }

    @objc func clearLocationHistory(_ call: CAPPluginCall) {
        SwbLocationStore.shared.clear()
        call.resolve()
    }

    // MARK: - Media Methods

    @objc func pickImage(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.pendingImageCall = call

            let picker: UIDocumentPickerViewController
            if #available(iOS 14.0, *) {
                picker = UIDocumentPickerViewController(forOpeningContentTypes: [.image], asCopy: true)
            } else {
                picker = UIDocumentPickerViewController(documentTypes: [kUTTypeImage as String], in: .import)
            }
            picker.delegate = self
            picker.allowsMultipleSelection = false

            self.bridge?.viewController?.present(picker, animated: true, completion: nil)
        }
    }

    @objc func takePhoto(_ call: CAPPluginCall) {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            call.reject("Hardware kamera tidak tersedia pada perangkat ini.", "CAMERA_UNAVAILABLE")
            return
        }

        let authStatus = AVCaptureDevice.authorizationStatus(for: .video)
        if authStatus == .denied || authStatus == .restricted {
            call.reject("Izin kamera belum diberikan. Silakan aktifkan izin Kamera melalui Pengaturan iPhone.", "CAMERA_PERMISSION_DENIED")
            return
        }

        if authStatus == .notDetermined {
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                if granted {
                    self?.presentCamera(isPhoto: true, call: call)
                } else {
                    call.reject("Izin kamera belum diberikan. Silakan aktifkan izin Kamera melalui Pengaturan iPhone.", "CAMERA_PERMISSION_DENIED")
                }
            }
            return
        }

        presentCamera(isPhoto: true, call: call)
    }

    @objc func takeVideo(_ call: CAPPluginCall) {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            call.reject("Hardware kamera tidak tersedia pada perangkat ini.", "CAMERA_UNAVAILABLE")
            return
        }

        let videoAuth = AVCaptureDevice.authorizationStatus(for: .video)
        let audioAuth = AVCaptureDevice.authorizationStatus(for: .audio)

        if videoAuth == .denied || audioAuth == .denied {
            call.reject("Izin kamera atau mikrofon belum diberikan. Silakan aktifkan izin Kamera dan Mikrofon melalui Pengaturan iPhone.", "PERMISSION_DENIED")
            return
        }

        if videoAuth == .notDetermined || audioAuth == .notDetermined {
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                if granted {
                    AVCaptureDevice.requestAccess(for: .audio) { audioGranted in
                        if audioGranted {
                            self?.presentCamera(isPhoto: false, call: call)
                        } else {
                            call.reject("Izin mikrofon belum diberikan. Silakan izinkan akses mikrofon di Pengaturan iPhone.", "PERMISSION_DENIED")
                        }
                    }
                } else {
                    call.reject("Izin kamera belum diberikan. Silakan aktifkan izin Kamera melalui Pengaturan iPhone.", "CAMERA_PERMISSION_DENIED")
                }
            }
            return
        }

        presentCamera(isPhoto: false, call: call)
    }

    // MARK: - Process Protection

    @objc func enableProcessProtection(_ call: CAPPluginCall) {
        call.resolve()
    }

    @objc func disableProcessProtection(_ call: CAPPluginCall) {
        call.resolve()
    }

    @objc func isProcessProtectionRunning(_ call: CAPPluginCall) {
        call.resolve([
            "running": false,
            "message": "Proteksi proses keep-alive tidak diperlukan di platform iOS.",
            "solution": "Manajemen memori ditangani langsung oleh sistem operasi iOS."
        ])
    }

    @objc func setProcessProtectionConfig(_ call: CAPPluginCall) {
        call.resolve()
    }

    private func presentCamera(isPhoto: Bool, call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let picker = UIImagePickerController()
            picker.sourceType = .camera
            picker.delegate = self

            if isPhoto {
                self.pendingImageCall = call
                picker.mediaTypes = [kUTTypeImage as String]
            } else {
                self.pendingVideoCall = call
                picker.mediaTypes = [kUTTypeMovie as String]
                let maxDuration = call.getInt("maxDuration", 60)
                picker.videoMaximumDuration = TimeInterval(maxDuration)

                let quality = call.getString("quality", "high")
                if quality == "low" {
                    picker.videoQuality = .typeLow
                } else if quality == "medium" {
                    picker.videoQuality = .typeMedium
                } else {
                    picker.videoQuality = .typeHigh
                }
            }

            self.bridge?.viewController?.present(picker, animated: true, completion: nil)
        }
    }

    // MARK: - UIDocumentPickerDelegate

    public func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first, let call = pendingImageCall else { return }
        pendingImageCall = nil

        let maxWidth = call.getDouble("maxWidth").map { CGFloat($0) }
        let maxHeight = call.getDouble("maxHeight").map { CGFloat($0) }
        let quality = call.getDouble("quality").map { CGFloat($0) } ?? 90.0
        let includeBase64 = call.getBool("includeBase64", true)

        let isAccessing = url.startAccessingSecurityScopedResource()
        defer {
            if isAccessing {
                url.stopAccessingSecurityScopedResource()
            }
        }

        guard let image = UIImage(contentsOfFile: url.path) else {
            call.reject("Could not load selected image")
            return
        }

        let processed = implementation.processImage(
            image: image,
            maxWidth: maxWidth,
            maxHeight: maxHeight,
            quality: quality,
            includeBase64: includeBase64
        )

        let tmpDir = FileManager.default.temporaryDirectory
        let fileName = call.getString("fileName") ?? "swb_picked_\(Int(Date().timeIntervalSince1970)).jpg"
        let destURL = tmpDir.appendingPathComponent(fileName)

        do {
            try processed.data.write(to: destURL, options: .atomic)
            var result: [String: Any] = [
                "path": destURL.path,
                "uri": destURL.absoluteString,
                "fileName": fileName,
                "mimeType": "image/jpeg",
                "size": processed.data.count,
                "width": processed.width,
                "height": processed.height
            ]
            if let b64 = processed.base64 {
                result["base64"] = b64
            }
            call.resolve(result)
        } catch {
            call.reject("Failed to save processed image: \(error.localizedDescription)")
        }
    }

    public func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        pendingImageCall?.reject("Pemilihan gambar dibatalkan oleh pengguna.", "USER_CANCELLED")
        pendingImageCall = nil
    }

    // MARK: - UIImagePickerControllerDelegate

    public func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        picker.dismiss(animated: true, completion: nil)

        if let call = pendingImageCall {
            pendingImageCall = nil
            guard let image = info[.originalImage] as? UIImage else {
                call.reject("Could not get captured photo")
                return
            }

            let maxWidth = call.getDouble("maxWidth").map { CGFloat($0) }
            let maxHeight = call.getDouble("maxHeight").map { CGFloat($0) }
            let quality = call.getDouble("quality").map { CGFloat($0) } ?? 90.0
            let includeBase64 = call.getBool("includeBase64", true)

            let processed = implementation.processImage(
                image: image,
                maxWidth: maxWidth,
                maxHeight: maxHeight,
                quality: quality,
                includeBase64: includeBase64
            )

            let tmpDir = FileManager.default.temporaryDirectory
            let fileName = call.getString("fileName") ?? "swb_photo_\(Int(Date().timeIntervalSince1970)).jpg"
            let destURL = tmpDir.appendingPathComponent(fileName)

            do {
                try processed.data.write(to: destURL, options: .atomic)
                var result: [String: Any] = [
                    "path": destURL.path,
                    "uri": destURL.absoluteString,
                    "fileName": fileName,
                    "mimeType": "image/jpeg",
                    "size": processed.data.count,
                    "width": processed.width,
                    "height": processed.height
                ]
                if let b64 = processed.base64 {
                    result["base64"] = b64
                }
                call.resolve(result)
            } catch {
                call.reject("Failed to save captured photo: \(error.localizedDescription)")
            }
        } else if let call = pendingVideoCall {
            pendingVideoCall = nil
            guard let mediaURL = info[.mediaURL] as? URL else {
                call.reject("Could not get recorded video URL")
                return
            }

            let tmpDir = FileManager.default.temporaryDirectory
            let fileName = call.getString("fileName") ?? "swb_video_\(Int(Date().timeIntervalSince1970)).mp4"
            let destURL = tmpDir.appendingPathComponent(fileName)

            try? FileManager.default.removeItem(at: destURL)
            do {
                try FileManager.default.copyItem(at: mediaURL, to: destURL)

                let asset = AVURLAsset(url: destURL)
                let durationSec = Int(CMTimeGetSeconds(asset.duration))
                var videoWidth: Int?
                var videoHeight: Int?

                if let track = asset.tracks(withMediaType: .video).first {
                    let size = track.naturalSize.applying(track.preferredTransform)
                    videoWidth = Int(abs(size.width))
                    videoHeight = Int(abs(size.height))
                }

                let fileSize = (try? destURL.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0

                var result: [String: Any] = [
                    "path": destURL.path,
                    "uri": destURL.absoluteString,
                    "fileName": fileName,
                    "mimeType": "video/mp4",
                    "size": fileSize,
                    "duration": durationSec
                ]
                if let w = videoWidth { result["width"] = w }
                if let h = videoHeight { result["height"] = h }

                call.resolve(result)
            } catch {
                call.reject("Failed to save recorded video: \(error.localizedDescription)")
            }
        }
    }

    public func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true, completion: nil)
        pendingImageCall?.reject("Pengambilan foto dibatalkan oleh pengguna.", "USER_CANCELLED")
        pendingImageCall = nil
        pendingVideoCall?.reject("Perekaman video dibatalkan oleh pengguna.", "USER_CANCELLED")
        pendingVideoCall = nil
    }

    // MARK: - CLLocationManagerDelegate

    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        emitLocationStatusChanged()

        if let call = pendingPermissionCall {
            pendingPermissionCall = nil
            checkAndEnforce(call)
        } else if gatingAlert != nil {
            checkAndEnforce(nil)
        }
    }

    public func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        emitLocationStatusChanged()

        if let call = pendingPermissionCall {
            pendingPermissionCall = nil
            checkAndEnforce(call)
        } else if gatingAlert != nil {
            checkAndEnforce(nil)
        }
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        lastLocation = location

        let isMock = implementation.isLocationSimulated(location)
        let formatted = implementation.formatLocation(location)

        for call in singleCallQueue {
            call.resolve(formatted)
        }
        singleCallQueue.removeAll()

        notifyListeners("location", data: formatted)

        for (_, call) in watchCalls {
            call.resolve(formatted)
        }

        // If background running, record to local store and upload
        if isBackgroundRunning {
            let record = SwbLocationRecord(
                id: UUID().uuidString,
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude,
                accuracy: location.horizontalAccuracy,
                altitude: location.verticalAccuracy >= 0 ? location.altitude : nil,
                altitudeAccuracy: location.verticalAccuracy >= 0 ? location.verticalAccuracy : nil,
                heading: location.course >= 0 ? location.course : nil,
                speed: location.speed >= 0 ? location.speed : nil,
                timestamp: Int64(location.timestamp.timeIntervalSince1970 * 1000),
                isMock: isMock,
                provider: "ios-corelocation",
                metadata: backgroundOptions["metadata"] as? [String: String],
                uploaded: false,
                uploadedAt: nil,
                attempts: 0
            )

            let retention = backgroundOptions["historyRetentionDays"] as? Int ?? 7
            let maxRecords = backgroundOptions["historyMaxRecords"] as? Int ?? 10000
            SwbLocationStore.shared.insert(record: record, retentionDays: retention, maxRecords: maxRecords)

            if let pos = record.toJSObject()["position"] as? [String: Any] {
                notifyListeners("backgroundLocation", data: pos)
            }

            if isMock {
                mockDetectedInBackground = true
                locationManager?.stopUpdatingLocation()
                if UIApplication.shared.applicationState == .active {
                    showMockGatingAlert()
                }
                emitLocationStatusChanged()
                return
            }

            let uploadUrl = backgroundOptions["uploadUrl"] as? String
            let uploadEnabled = backgroundOptions["uploadEnabled"] as? Bool ?? true
            let headers = backgroundOptions["headers"] as? [String: String]
            let allowInsecure = backgroundOptions["allowInsecureHttp"] as? Bool ?? false

            if uploadEnabled && uploadUrl != nil && !uploadUrl!.isEmpty {
                SwbHttpUploader.shared.triggerUpload(uploadUrl: uploadUrl, headers: headers, allowInsecureHttp: allowInsecure)
            }
        }

        if isMock {
            emitLocationStatusChanged()
            showMockGatingAlert()
        }
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        for call in singleCallQueue {
            call.reject("Location error: \(error.localizedDescription)")
        }
        singleCallQueue.removeAll()
    }
}
