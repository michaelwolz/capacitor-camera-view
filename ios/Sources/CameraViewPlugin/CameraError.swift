import Foundation

enum CameraError: Error, LocalizedError {
    case cameraUnavailable
    case configurationFailed(Error)
    case inputAdditionFailed
    case outputAdditionFailed
    case sessionNotRunning
    case photoOutputNotConfigured
    case photoOutputError
    case unsupportedFlashMode
    case zoomFactorOutOfRange

    var errorDescription: String? {
        switch self {
        case .cameraUnavailable:
            return "No available camera for the requested position."
        case .configurationFailed(let error):
            return "Failed to configure the camera. \(error.localizedDescription)"
        case .inputAdditionFailed:
            return "Failed to add input to the capture session."
        case .outputAdditionFailed:
            return "Failed to add output to the capture session."
        case .sessionNotRunning:
            return "The capture session is not currently running."
        case .photoOutputNotConfigured:
            return "The photo output has not been configured."
        case .photoOutputError:
            return "An error occurred when capturing a photo."
        case .unsupportedFlashMode:
            return "The requested flash mode is not supported by the current camera."
        case .zoomFactorOutOfRange:
            return "The requested zoom factor is out of range."
        }
    }
}
