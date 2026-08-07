package com.michaelwolz.capacitorcameraview

sealed class CameraError(message: String) : Exception(message) {
    class TorchUnavailable : CameraError("Torch is not available on this device")
    class CameraNotInitialized : CameraError("Camera controller not initialized")
    class PreviewNotInitialized : CameraError("Camera preview not initialized")
    class LifecycleOwnerMissing : CameraError("WebView context must be a LifecycleOwner")
    class ZoomFactorOutOfRange : CameraError("The requested zoom factor is out of range.")
    class FocusNotSupported : CameraError("The current camera does not support focus or exposure at a point of interest.")
    class SessionAlreadyRunning : CameraError("Camera session is already running. Call stop() first.")
    class RecordingAlreadyInProgress : CameraError("A video recording is already in progress")

    /**
     * A stable, platform-independent string code identifying this error.
     *
     * This value is part of the plugin's public JS/TS contract: consumers can
     * `switch` on `error.code` from a rejected promise. Where the same failure
     * class exists on iOS, the string matches the one iOS emits.
     */
    val code: String
        get() = when (this) {
            is TorchUnavailable -> "TORCH_UNAVAILABLE"
            // Camera/preview not yet set up is the same user-facing failure as
            // iOS's "session not running" - callers should call start() first.
            is CameraNotInitialized -> "SESSION_NOT_RUNNING"
            is PreviewNotInitialized -> "SESSION_NOT_RUNNING"
            is LifecycleOwnerMissing -> "LIFECYCLE_OWNER_MISSING"
            is ZoomFactorOutOfRange -> "ZOOM_FACTOR_OUT_OF_RANGE"
            is FocusNotSupported -> "FOCUS_NOT_SUPPORTED"
            is SessionAlreadyRunning -> "SESSION_ALREADY_RUNNING"
            is RecordingAlreadyInProgress -> "RECORDING_ALREADY_IN_PROGRESS"
        }

    companion object {
        /** Camera or microphone access has been denied. Matches iOS's `permissionDenied`. */
        const val PERMISSION_DENIED = "PERMISSION_DENIED"

        /** An argument passed to the method call was missing or invalid. Matches iOS's `invalidArgument`. */
        const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
    }
}

/**
 * The stable string code to surface in a Capacitor `call.reject`.
 *
 * Falls back to a generic code for errors that are not a [CameraError]
 * (e.g. CameraX or ML Kit exceptions), mirroring the iOS `cameraErrorCode`
 * extension.
 */
val Exception.cameraErrorCode: String
    get() = (this as? CameraError)?.code ?: "UNKNOWN_ERROR"
