import Foundation
import CoreLocation
import UIKit
import AVFoundation

public struct SwbLocationRecord: Codable {
    public let id: String
    public let latitude: Double
    public let longitude: Double
    public let accuracy: Double
    public let altitude: Double?
    public let altitudeAccuracy: Double?
    public let heading: Double?
    public let speed: Double?
    public let timestamp: Int64
    public let isMock: Bool
    public let provider: String
    public let metadata: [String: String]?
    public var uploaded: Bool
    public var uploadedAt: Int64?
    public var attempts: Int

    public func toJSObject() -> [String: Any] {
        var coords: [String: Any] = [
            "latitude": latitude,
            "longitude": longitude,
            "accuracy": accuracy,
            "isMock": isMock
        ]
        coords["altitude"] = altitude ?? NSNull()
        coords["altitudeAccuracy"] = altitudeAccuracy ?? NSNull()
        coords["heading"] = heading ?? NSNull()
        coords["speed"] = speed ?? NSNull()

        var pos: [String: Any] = [
            "timestamp": timestamp,
            "coords": coords
        ]

        var root: [String: Any] = [
            "id": id,
            "isMock": isMock,
            "uploaded": uploaded,
            "attempts": attempts,
            "position": pos
        ]
        if let uploadedAt = uploadedAt {
            root["uploadedAt"] = uploadedAt
        }
        return root
    }

    public func toUploadPayload() -> [String: Any] {
        var payload: [String: Any] = [
            "id": id,
            "latitude": latitude,
            "longitude": longitude,
            "accuracy": accuracy,
            "altitude": altitude ?? NSNull(),
            "speed": speed ?? 0.0,
            "heading": heading ?? 0.0,
            "timestamp": timestamp,
            "isMock": isMock,
            "provider": provider,
            "metadata": metadata ?? [:]
        ]
        return payload
    }
}

public class SwbLocationStore {
    public static let shared = SwbLocationStore()
    private let queue = DispatchQueue(label: "com.wahana.swbplugin.store", attributes: .concurrent)
    private var records: [SwbLocationRecord] = []
    private let fileURL: URL

    private init() {
        let fileManager = FileManager.default
        let appSupport = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        if !fileManager.fileExists(atPath: appSupport.path) {
            try? fileManager.createDirectory(at: appSupport, withIntermediateDirectories: true, attributes: nil)
        }
        fileURL = appSupport.appendingPathComponent("swb_locations.json")
        loadFromDisk()
    }

    private func loadFromDisk() {
        guard let data = try? Data(contentsOf: fileURL) else { return }
        if let loaded = try? JSONDecoder().decode([SwbLocationRecord].self, from: data) {
            self.records = loaded
        }
    }

    private func saveToDisk() {
        guard let data = try? JSONEncoder().encode(records) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }

    public func insert(record: SwbLocationRecord, retentionDays: Int = 7, maxRecords: Int = 10000) {
        queue.async(flags: .barrier) {
            self.records.insert(record, at: 0)

            // Prune old records
            if retentionDays > 0 {
                let cutoff = Int64(Date().timeIntervalSince1970 * 1000) - Int64(retentionDays * 86400 * 1000)
                self.records.removeAll { $0.timestamp < cutoff && $0.uploaded }
            }
            if maxRecords > 0 && self.records.count > maxRecords {
                self.records = Array(self.records.prefix(maxRecords))
            }

            self.saveToDisk()
        }
    }

    public func getHistory(limit: Int = 100, offset: Int = 0, from: Int64? = nil, to: Int64? = nil) -> (records: [SwbLocationRecord], total: Int, pending: Int) {
        return queue.sync {
            var filtered = self.records
            if let from = from {
                filtered = filtered.filter { $0.timestamp >= from }
            }
            if let to = to {
                filtered = filtered.filter { $0.timestamp <= to }
            }
            let total = filtered.count
            let pending = filtered.filter { !$0.uploaded }.count

            let start = min(offset, filtered.count)
            let end = min(start + limit, filtered.count)
            let page = Array(filtered[start..<end])

            return (records: page, total: total, pending: pending)
        }
    }

    public func getPendingUploads(limit: Int = 50) -> [SwbLocationRecord] {
        return queue.sync {
            let pending = self.records.filter { !$0.uploaded }
            return Array(pending.prefix(limit))
        }
    }

    public func markUploaded(id: String, uploadedAt: Int64) {
        queue.async(flags: .barrier) {
            if let index = self.records.firstIndex(where: { $0.id == id }) {
                self.records[index].uploaded = true
                self.records[index].uploadedAt = uploadedAt
                self.saveToDisk()
            }
        }
    }

    public func incrementAttempts(id: String) {
        queue.async(flags: .barrier) {
            if let index = self.records.firstIndex(where: { $0.id == id }) {
                self.records[index].attempts += 1
                self.saveToDisk()
            }
        }
    }

    public func clear() {
        queue.async(flags: .barrier) {
            self.records.removeAll()
            self.saveToDisk()
        }
    }
}

public class SwbHttpUploader {
    public static let shared = SwbHttpUploader()
    private let uploadQueue = DispatchQueue(label: "com.wahana.swbplugin.uploader")
    private var isUploading = false
    private let backoffSeconds: [TimeInterval] = [5.0, 10.0, 30.0, 60.0, 300.0]

    public var onUploadStatus: ((_ uploaded: Bool, _ pending: Int, _ error: String?) -> Void)?

    private init() {}

    public func triggerUpload(
        uploadUrl: String?,
        headers: [String: String]?,
        allowInsecureHttp: Bool
    ) {
        guard let urlString = uploadUrl, !urlString.isEmpty else { return }

        uploadQueue.async {
            guard !self.isUploading else { return }
            self.isUploading = true
            self.processQueue(urlString: urlString, headers: headers ?? [:], allowInsecureHttp: allowInsecureHttp)
        }
    }

    private func processQueue(urlString: String, headers: [String: String], allowInsecureHttp: Bool) {
        if urlString.lowercased().hasPrefix("http://") && !allowInsecureHttp {
            self.onUploadStatus?(false, 0, "Insecure HTTP not allowed")
            self.isUploading = false
            return
        }

        let pending = SwbLocationStore.shared.getPendingUploads(limit: 50)
        guard !pending.isEmpty else {
            self.isUploading = false
            return
        }

        for record in pending {
            let semaphore = DispatchSemaphore(value: 0)
            var uploadSuccess = false
            var uploadError: String?

            uploadSingle(record: record, urlString: urlString, headers: headers) { success, err in
                uploadSuccess = success
                uploadError = err
                semaphore.signal()
            }

            _ = semaphore.wait(timeout: .now() + 20.0)

            if uploadSuccess {
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                SwbLocationStore.shared.markUploaded(id: record.id, uploadedAt: now)
                let remaining = SwbLocationStore.shared.getHistory(limit: 1).pending
                self.onUploadStatus?(true, remaining, nil)
            } else {
                SwbLocationStore.shared.incrementAttempts(id: record.id)
                let remaining = SwbLocationStore.shared.getHistory(limit: 1).pending
                self.onUploadStatus?(false, remaining, uploadError ?? "Upload failed")

                let attemptIdx = min(record.attempts, self.backoffSeconds.count - 1)
                let delay = self.backoffSeconds[attemptIdx]

                self.uploadQueue.asyncAfter(deadline: .now() + delay) {
                    self.isUploading = false
                    self.triggerUpload(uploadUrl: urlString, headers: headers, allowInsecureHttp: allowInsecureHttp)
                }
                return
            }
        }

        self.isUploading = false
    }

    private func uploadSingle(
        record: SwbLocationRecord,
        urlString: String,
        headers: [String: String],
        completion: @escaping (Bool, String?) -> Void
    ) {
        guard let url = URL(string: urlString) else {
            completion(false, "Invalid URL")
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        for (k, v) in headers {
            request.setValue(v, forHTTPHeaderField: k)
        }

        let payload = record.toUploadPayload()
        guard let httpBody = try? JSONSerialization.data(withJSONObject: payload, options: []) else {
            completion(false, "Failed to serialize JSON payload")
            return
        }
        request.httpBody = httpBody

        let task = URLSession.shared.dataTask(with: request) { _, response, error in
            if let error = error {
                completion(false, error.localizedDescription)
                return
            }
            if let httpRes = response as? HTTPURLResponse, (200...299).contains(httpRes.statusCode) {
                completion(true, nil)
            } else {
                let code = (response as? HTTPURLResponse)?.statusCode ?? -1
                completion(false, "HTTP status code: \(code)")
            }
        }
        task.resume()
    }
}

@objc public class Swb: NSObject {

    @objc public func echo(_ value: String) -> String {
        return value
    }

    @objc public func isLocationSimulated(_ location: CLLocation) -> Bool {
        if #available(iOS 15.0, *) {
            return location.sourceInformation?.isSimulatedBySoftware ?? false
        }
        return false
    }

    @objc public func formatLocation(_ location: CLLocation) -> [String: Any] {
        var coords: [String: Any] = [
            "latitude": location.coordinate.latitude,
            "longitude": location.coordinate.longitude,
            "accuracy": location.horizontalAccuracy,
            "isMock": isLocationSimulated(location)
        ]

        if location.verticalAccuracy >= 0 {
            coords["altitude"] = location.altitude
            coords["altitudeAccuracy"] = location.verticalAccuracy
        } else {
            coords["altitude"] = NSNull()
            coords["altitudeAccuracy"] = NSNull()
        }

        if location.course >= 0 {
            coords["heading"] = location.course
        } else {
            coords["heading"] = NSNull()
        }

        if location.speed >= 0 {
            coords["speed"] = location.speed
        } else {
            coords["speed"] = NSNull()
        }

        return [
            "timestamp": Int64(location.timestamp.timeIntervalSince1970 * 1000),
            "coords": coords
        ]
    }

    @objc public func calculateStatus(enabled: Bool, granted: Bool, isMock: Bool) -> [String: Any] {
        let usable = enabled && granted && !isMock
        let code: String
        let message: String
        let solution: String

        if !enabled {
            code = "GPS_DISABLED"
            message = "Layanan Lokasi (GPS) iPhone sedang nonaktif."
            solution = "Buka Pengaturan iPhone -> Privasi & Keamanan -> Layanan Lokasi, lalu aktifkan 'Layanan Lokasi'."
        } else if !granted {
            code = "PERMISSION_DENIED"
            message = "Izin akses lokasi belum diberikan."
            solution = "Buka Pengaturan iPhone -> Privasi & Keamanan -> Layanan Lokasi, lalu pilih 'Saat Menggunakan App'."
        } else if isMock {
            code = "MOCK_DETECTED"
            message = "Terdeteksi manipulasi lokasi (Fake GPS / Lokasi Simulasi)."
            solution = "Hapus atau matikan profil simulasi lokasi pada perangkat."
        } else {
            code = "OK"
            message = "Layanan lokasi aktif dan valid."
            solution = "Aplikasi siap digunakan."
        }

        return [
            "enabled": enabled,
            "granted": granted,
            "isMock": isMock,
            "usable": usable,
            "code": code,
            "message": message,
            "solution": solution
        ]
    }

    public func processImage(
        image: UIImage,
        maxWidth: CGFloat?,
        maxHeight: CGFloat?,
        quality: CGFloat,
        includeBase64: Bool
    ) -> (processedImage: UIImage, data: Data, base64: String?, width: Int, height: Int) {
        var targetSize = image.size
        var width = targetSize.width
        var height = targetSize.height

        if let maxW = maxWidth, width > maxW {
            height = (height * maxW) / width
            width = maxW
        }
        if let maxH = maxHeight, height > maxH {
            width = (width * maxH) / height
            height = maxH
        }

        targetSize = CGSize(width: width, height: height)

        var finalImage = image
        if targetSize != image.size {
            UIGraphicsBeginImageContextWithOptions(targetSize, false, 1.0)
            image.draw(in: CGRect(origin: .zero, size: targetSize))
            if let resized = UIGraphicsGetImageFromCurrentImageContext() {
                finalImage = resized
            }
            UIGraphicsEndImageContext()
        }

        let safeQuality = max(0.1, min(1.0, quality / 100.0))
        let jpegData = finalImage.jpegData(compressionQuality: safeQuality) ?? Data()

        var base64String: String? = nil
        if includeBase64 {
            base64String = jpegData.base64EncodedString()
        }

        return (
            processedImage: finalImage,
            data: jpegData,
            base64: base64String,
            width: Int(targetSize.width),
            height: Int(targetSize.height)
        )
    }
}
