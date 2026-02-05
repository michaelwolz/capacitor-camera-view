import Foundation

/// Camera-related errors with detailed error codes and recovery suggestions.
/// Conforms to CustomNSError for integration with NSError-based APIs.
enum CameraError: Error, LocalizedError, CustomNSError {
    case cameraUnavailable
    case configurationFailed(Error)
    case frameCaptureError
    case inputAdditionFailed
    case outputAdditionFailed
    case photoOutputError
    case photoOutputNotConfigured
    case sessionNotRunning
    case unsupportedFlashMode
    case torchUnavailable
    case zoomFactorOutOfRange
    case permissionDenied
    case deviceLocked

    // MARK: - CustomNSError

    static var errorDomain: String {
        return "com.michaelwolz.capacitorcameraview.CameraError"
    }

    var errorCode: Int {
        switch self {
        case .cameraUnavailable:
            return 1001
        case .configurationFailed:
            return 1002
        case .frameCaptureError:
            return 1003
        case .inputAdditionFailed:
            return 1004
        case .outputAdditionFailed:
            return 1005
        case .photoOutputError:
            return 1006
        case .photoOutputNotConfigured:
            return 1007
        case .sessionNotRunning:
            return 1008
        case .unsupportedFlashMode:
            return 1009
        case .torchUnavailable:
            return 1010
        case .zoomFactorOutOfRange:
            return 1011
        case .permissionDenied:
            return 1012
        case .deviceLocked:
            return 1013
        }
    }

    var errorUserInfo: [String: Any] {
        var userInfo: [String: Any] = [
            NSLocalizedDescriptionKey: errorDescription ?? "Unknown error"
        ]

        if let recovery = recoverySuggestion {
            userInfo[NSLocalizedRecoverySuggestionErrorKey] = recovery
        }

        if case .configurationFailed(let underlyingError) = self {
            userInfo[NSUnderlyingErrorKey] = underlyingError
        }

        return userInfo
    }

    // MARK: - LocalizedError

    var errorDescription: String? {
        switch self {
        case .cameraUnavailable:
            return "No available camera for the requested position."
        case .configurationFailed(let error):
            return "Failed to configure the camera. \(error.localizedDescription)"
        case .frameCaptureError:
            return "Failed to capture a frame from the camera."
        case .inputAdditionFailed:
            return "Failed to add input to the capture session."
        case .outputAdditionFailed:
            return "Failed to add output to the capture session."
        case .photoOutputError:
            return "An error occurred when capturing a photo."
        case .photoOutputNotConfigured:
            return "The photo output has not been configured."
        case .sessionNotRunning:
            return "The capture session is not currently running."
        case .unsupportedFlashMode:
            return "The requested flash mode is not supported by the current camera."
        case .torchUnavailable:
            return "Torch is not available on this device."
        case .zoomFactorOutOfRange:
            return "The requested zoom factor is out of range."
        case .permissionDenied:
            return "Camera access has been denied."
        case .deviceLocked:
            return "The camera device is currently locked by another process."
        }
    }

    /// Provides actionable recovery suggestions for each error type.
    var recoverySuggestion: String? {
        switch self {
        case .cameraUnavailable:
            return "Try using a different camera position or check if the device has a camera."
        case .configurationFailed:
            return "Try stopping and restarting the camera session."
        case .frameCaptureError:
            return "Ensure the camera session is running and try again."
        case .inputAdditionFailed:
            return "The camera may be in use by another application. Close other camera apps and try again."
        case .outputAdditionFailed:
            return "Try stopping and restarting the camera session."
        case .photoOutputError:
            return "Try capturing the photo again. If the issue persists, restart the camera session."
        case .photoOutputNotConfigured:
            return "Start the camera session before attempting to capture a photo."
        case .sessionNotRunning:
            return "Call start() to begin the camera session before using this feature."
        case .unsupportedFlashMode:
            return "Use getSupportedFlashModes() to check available flash modes for this camera."
        case .torchUnavailable:
            return "This device or camera position does not support torch functionality."
        case .zoomFactorOutOfRange:
            return "Use getZoom() to check the supported zoom range for this camera."
        case .permissionDenied:
            return "Go to Settings > Privacy > Camera and enable access for this app."
        case .deviceLocked:
            return "Wait for the other process to release the camera or restart the app."
        }
    }
}
