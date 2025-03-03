import Foundation

enum CameraError: Error, LocalizedError {
    case sessionCreationFailed
    case cameraUnavailable
    case configurationFailed(Error)
    case inputAdditionFailed
    case sessionNotInitialized
    case sessionNotRunning
    case photoOutputNotConfigured
    case unsupportedFlashMode

    var errorDescription: String? {
        switch self {
        case .sessionCreationFailed:
            return "Failed to create the capture session."
        case .cameraUnavailable:
            return "No available camera for the requested position."
        case .configurationFailed(let error):
            return "Failed to configure the camera. \(error.localizedDescription)"
        case .inputAdditionFailed:
            return "Failed to add input to the capture session."
        case .sessionNotInitialized:
            return "The capture session has not been initialized."
        case .sessionNotRunning:
            return "The capture session is not currently running."
        case .photoOutputNotConfigured:
            return "The photo output has not been configured."
        case .unsupportedFlashMode:
            return "The requested flash mode is not supported by the current camera."
        }
    }
}