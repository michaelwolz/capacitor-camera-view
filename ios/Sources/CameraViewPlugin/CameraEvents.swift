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

    /// The type of barcode detected, normalized to the shared BarcodeType vocabulary (e.g., "qr").
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

    /// Called when the capture session is interrupted (e.g. phone call, another
    /// app claiming the camera, iPad Split View camera loss).
    /// - Parameter reason: A stable string describing the interruption reason.
    func cameraWasInterrupted(reason: String)

    /// Called when a capture-session interruption ends and the session resumes.
    func cameraInterruptionEnded()

    /// Called when the capture session hits a runtime error.
    /// - Parameters:
    ///   - message: A human-readable description of the error.
    ///   - code: The underlying `AVError` code, when available.
    func cameraRuntimeError(message: String, code: Int?)
}

/// Default no-op implementations so conformers only implement the events they
/// care about (and adding new events here stays source-compatible).
public extension CameraEventDelegate {
    func cameraWasInterrupted(reason: String) {}
    func cameraInterruptionEnded() {}
    func cameraRuntimeError(message: String, code: Int?) {}
}

// MARK: - Event Emitter Helper

/// Helper class for emitting camera events through the typed delegate.
internal final class CameraEventEmitter {
    /// Weak reference to the delegate to avoid retain cycles.
    weak var delegate: CameraEventDelegate?

    /// Emits a barcode detected event to the delegate.
    /// - Parameter event: The barcode detection event to emit.
    func emitBarcodeDetected(_ event: BarcodeDetectedEvent) {
        delegate?.cameraDidDetectBarcode(event)
    }

    /// Emits a camera interruption event to the delegate.
    /// - Parameter reason: A stable string describing the interruption reason.
    func emitCameraInterrupted(reason: String) {
        delegate?.cameraWasInterrupted(reason: reason)
    }

    /// Emits a camera resumed event to the delegate.
    func emitCameraResumed() {
        delegate?.cameraInterruptionEnded()
    }

    /// Emits a camera runtime error event to the delegate.
    /// - Parameters:
    ///   - message: A human-readable description of the error.
    ///   - code: The underlying `AVError` code, when available.
    func emitCameraRuntimeError(message: String, code: Int?) {
        delegate?.cameraRuntimeError(message: message, code: code)
    }
}
