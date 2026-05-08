import AVFoundation
import Foundation

// MARK: - Typed Barcode Detection Event

/// Represents a detected barcode with its value, type, and position.
/// This struct is Sendable for safe use across concurrency boundaries.
public struct BarcodeDetectedEvent: Sendable {
    /// The decoded string value of the barcode.
    public let value: String

    /// The barcode value in a human readable format.
    public let displayValue: String?

    /// Raw bytes as they were encoded in the barcode when the platform exposes them.
    public let rawBytes: [UInt8]?

    /// The type of barcode detected (e.g., "org.iso.QRCode").
    public let type: String

    /// The bounding rectangle of the barcode in screen coordinates.
    public let boundingRect: BoundingRect

    /// Bounding rectangle coordinates.
    public struct BoundingRect: Sendable {
        public let x: Double
        public let y: Double
        public let width: Double
        public let height: Double

        public init(x: Double, y: Double, width: Double, height: Double) {
            self.x = x
            self.y = y
            self.width = width
            self.height = height
        }

        /// Converts to dictionary for Capacitor event emission.
        public func toDictionary() -> [String: Double] {
            return [
                "x": x,
                "y": y,
                "width": width,
                "height": height
            ]
        }
    }

    public init(
        value: String,
        displayValue: String? = nil,
        rawBytes: [UInt8]? = nil,
        type: String,
        boundingRect: BoundingRect
    ) {
        self.value = value
        self.displayValue = displayValue
        self.rawBytes = rawBytes
        self.type = type
        self.boundingRect = boundingRect
    }

    /// Converts to dictionary for Capacitor event emission.
    public func toDictionary() -> [String: Any] {
        var dictionary: [String: Any] = [
            "value": value,
            "type": type,
            "boundingRect": boundingRect.toDictionary()
        ]

        if let displayValue {
            dictionary["displayValue"] = displayValue
        }

        if let rawBytes {
            dictionary["rawBytes"] = rawBytes.map(Int.init)
        }

        return dictionary
    }
}

// MARK: - Camera Event Delegate Protocol

/// Protocol for receiving typed camera events.
/// Implement this protocol for type-safe event handling.
///
/// Usage:
/// ```swift
/// class MyHandler: CameraEventDelegate {
///     func cameraDidDetectBarcode(_ event: BarcodeDetectedEvent) {
///         print("Detected: \(event.value)")
///     }
/// }
/// ```
public protocol CameraEventDelegate: AnyObject {
    /// Called when a barcode is detected in the camera feed.
    /// - Parameter event: The barcode detection event with all relevant data.
    func cameraDidDetectBarcode(_ event: BarcodeDetectedEvent)
}

// MARK: - Notification Names

/// Extension for camera-related notification names.
/// These maintain backwards compatibility with the NotificationCenter-based event system.
public extension Notification.Name {
    /// Posted when a barcode is detected.
    /// UserInfo contains: "value", "displayValue", "rawBytes", "type", "boundingRect"
    static let cameraViewBarcodeDetected = Notification.Name("barcodeDetected")
}

// MARK: - Event Emitter Helper

/// Helper class for emitting camera events through both delegate and NotificationCenter.
/// This maintains backwards compatibility while enabling the new typed delegate pattern.
internal final class CameraEventEmitter {
    /// Weak reference to the delegate to avoid retain cycles.
    weak var delegate: CameraEventDelegate?

    /// Emits a barcode detected event through both channels.
    /// - Parameter event: The barcode detection event to emit.
    func emitBarcodeDetected(_ event: BarcodeDetectedEvent) {
        // Call typed delegate first (preferred path)
        delegate?.cameraDidDetectBarcode(event)

        // Also post to NotificationCenter for backwards compatibility
        NotificationCenter.default.post(
            name: .cameraViewBarcodeDetected,
            object: nil,
            userInfo: event.toDictionary()
        )
    }
}
