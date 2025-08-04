import Foundation

enum CameraError: Error, LocalizedError {
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
        }
    }
}
