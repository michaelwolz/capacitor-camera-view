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
    case sessionAlreadyRunning
    case unsupportedFlashMode
    case torchUnavailable
    case zoomFactorOutOfRange
    case focusNotSupported
    case permissionDenied
    case deviceLocked
    case recordingAlreadyInProgress
    case noRecordingInProgress
    case audioDeviceUnavailable
    case audioInputAdditionFailed
    case captureInProgress
    case captureTimeout
    case missingWebView
    case invalidArgument
    case imageCompressionFailed
    case pathConversionFailed
    case fileWriteFailed
    case captureOutputMissing

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
        case .sessionAlreadyRunning:
            return 1027
        case .unsupportedFlashMode:
            return 1009
        case .torchUnavailable:
            return 1010
        case .zoomFactorOutOfRange:
            return 1011
        case .focusNotSupported:
            return 1026
        case .permissionDenied:
            return 1012
        case .deviceLocked:
            return 1013
        case .recordingAlreadyInProgress:
            return 1014
        case .noRecordingInProgress:
            return 1015
        case .audioDeviceUnavailable:
            return 1016
        case .audioInputAdditionFailed:
            return 1017
        case .captureInProgress:
            return 1018
        case .captureTimeout:
            return 1019
        case .missingWebView:
            return 1020
        case .invalidArgument:
            return 1021
        case .imageCompressionFailed:
            return 1022
        case .pathConversionFailed:
            return 1023
        case .fileWriteFailed:
            return 1024
        case .captureOutputMissing:
            return 1025
        }
    }

    /// A stable, platform-independent string code identifying this error.
    ///
    /// Unlike `errorCode`, this value is part of the plugin's public JS/TS
    /// contract: consumers can `switch` on `error.code` from a rejected
    /// promise. Where the same failure class exists on Android, the string
    /// matches the one Android emits.
    var code: String {
        switch self {
        case .cameraUnavailable:
            return "CAMERA_UNAVAILABLE"
        case .configurationFailed:
            return "CONFIGURATION_FAILED"
        case .frameCaptureError:
            return "FRAME_CAPTURE_ERROR"
        case .inputAdditionFailed:
            return "INPUT_ADDITION_FAILED"
        case .outputAdditionFailed:
            return "OUTPUT_ADDITION_FAILED"
        case .photoOutputError:
            return "PHOTO_OUTPUT_ERROR"
        case .photoOutputNotConfigured:
            return "PHOTO_OUTPUT_NOT_CONFIGURED"
        case .sessionNotRunning:
            return "SESSION_NOT_RUNNING"
        case .sessionAlreadyRunning:
            return "SESSION_ALREADY_RUNNING"
        case .unsupportedFlashMode:
            return "UNSUPPORTED_FLASH_MODE"
        case .torchUnavailable:
            return "TORCH_UNAVAILABLE"
        case .zoomFactorOutOfRange:
            return "ZOOM_FACTOR_OUT_OF_RANGE"
        case .focusNotSupported:
            return "FOCUS_NOT_SUPPORTED"
        case .permissionDenied:
            return "PERMISSION_DENIED"
        case .deviceLocked:
            return "DEVICE_LOCKED"
        case .recordingAlreadyInProgress:
            return "RECORDING_ALREADY_IN_PROGRESS"
        case .noRecordingInProgress:
            return "NO_RECORDING_IN_PROGRESS"
        case .audioDeviceUnavailable:
            return "AUDIO_DEVICE_UNAVAILABLE"
        case .audioInputAdditionFailed:
            return "AUDIO_INPUT_ADDITION_FAILED"
        case .captureInProgress:
            return "CAPTURE_IN_PROGRESS"
        case .captureTimeout:
            return "CAPTURE_TIMEOUT"
        case .missingWebView:
            return "WEBVIEW_UNAVAILABLE"
        case .invalidArgument:
            return "INVALID_ARGUMENT"
        case .imageCompressionFailed:
            return "IMAGE_COMPRESSION_FAILED"
        case .pathConversionFailed:
            return "PATH_CONVERSION_FAILED"
        case .fileWriteFailed:
            return "FILE_WRITE_FAILED"
        case .captureOutputMissing:
            return "CAPTURE_OUTPUT_MISSING"
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
        case .sessionAlreadyRunning:
            return "A camera session is already running."
        case .unsupportedFlashMode:
            return "The requested flash mode is not supported by the current camera."
        case .torchUnavailable:
            return "Torch is not available on this device."
        case .zoomFactorOutOfRange:
            return "The requested zoom factor is out of range."
        case .focusNotSupported:
            return "The current camera does not support focus or exposure at a point of interest."
        case .permissionDenied:
            return "Camera access has been denied."
        case .deviceLocked:
            return "The camera device is currently locked by another process."
        case .recordingAlreadyInProgress:
            return "A video recording is already in progress"
        case .noRecordingInProgress:
            return "No video recording is in progress"
        case .audioDeviceUnavailable:
            return "No microphone is available on this device."
        case .audioInputAdditionFailed:
            return "Failed to add the microphone input to the capture session."
        case .captureInProgress:
            return "A capture is already in progress."
        case .captureTimeout:
            return "Timed out waiting for a camera frame."
        case .missingWebView:
            return "Could not find the web view to render the camera preview into."
        case .invalidArgument:
            return "An invalid argument was provided."
        case .imageCompressionFailed:
            return "Failed to compress the captured image."
        case .pathConversionFailed:
            return "Failed to create a web-accessible path for the file."
        case .fileWriteFailed:
            return "Failed to write the file to disk."
        case .captureOutputMissing:
            return "The capture completed but produced no output data."
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
        case .sessionAlreadyRunning:
            return "Call stop() before starting a new camera session."
        case .unsupportedFlashMode:
            return "Use getSupportedFlashModes() to check available flash modes for this camera."
        case .torchUnavailable:
            return "This device or camera position does not support torch functionality."
        case .zoomFactorOutOfRange:
            return "Use getZoom() to check the supported zoom range for this camera."
        case .focusNotSupported:
            return "This camera has a fixed focus and does not support tap-to-focus."
        case .permissionDenied:
            return "Go to Settings > Privacy > Camera and enable access for this app."
        case .deviceLocked:
            return "Wait for the other process to release the camera or restart the app."
        case .recordingAlreadyInProgress:
            return nil
        case .noRecordingInProgress:
            return nil
        case .audioDeviceUnavailable:
            return "Ensure the device has a microphone and that microphone access has been granted."
        case .audioInputAdditionFailed:
            return "The microphone may be in use by another application. Close other apps and try again."
        case .captureInProgress:
            return "Wait for the current capture to finish before starting a new one."
        case .captureTimeout:
            return "Ensure the camera session is running and not interrupted, then try again."
        case .missingWebView:
            return "Ensure the plugin is attached to a Capacitor bridge with a valid web view."
        case .invalidArgument:
            return nil
        case .imageCompressionFailed:
            return "Try capturing again or use a different quality setting."
        case .pathConversionFailed:
            return "Ensure the Capacitor bridge is available and try again."
        case .fileWriteFailed:
            return "Ensure the device has available storage and try again."
        case .captureOutputMissing:
            return "Try the operation again. If the issue persists, restart the camera session."
        }
    }
}

extension Error {
    /// The stable string code to surface in a Capacitor `call.reject`.
    ///
    /// Falls back to a generic code for errors that are not a `CameraError`
    /// (e.g. file-system errors from writing a captured file to disk).
    var cameraErrorCode: String {
        (self as? CameraError)?.code ?? "UNKNOWN_ERROR"
    }
}
