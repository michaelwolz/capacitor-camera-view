package com.michaelwolz.capacitorcameraview

sealed class CameraError(message: String) : Exception(message) {
    class TorchUnavailable : CameraError("Torch is not available on this device")
    class CameraNotInitialized : CameraError("Camera controller not initialized")
    class PreviewNotInitialized : CameraError("Camera preview not initialized")
    class LifecycleOwnerMissing : CameraError("WebView context must be a LifecycleOwner")
    class ZoomFactorOutOfRange : CameraError("The requested zoom factor is out of range.")
}
