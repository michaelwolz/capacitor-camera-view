import AVFoundation
import Foundation

/// How long a one-shot tap-to-focus is held before continuous auto focus and
/// exposure are restored, so focus is never left permanently locked. A
/// subsequent `setFocusPoint` call cancels and replaces the pending restore.
private let cameraFocusResetDelay: TimeInterval = 3.0

extension CameraViewManager {
    /// Focuses and meters the camera at a point expressed in the preview
    /// layer's coordinate space (CSS/viewport pixels), i.e. tap-to-focus.
    ///
    /// The layer point is converted to a normalized device point on the main
    /// thread (preview-layer geometry is main-thread only), then the device is
    /// configured on `sessionQueue`. A one-shot `.autoFocus`/`.autoExpose` is
    /// applied and continuous auto modes are restored after a short delay.
    ///
    /// - Parameters:
    ///   - x: Horizontal coordinate in CSS/viewport pixels from the left edge.
    ///   - y: Vertical coordinate in CSS/viewport pixels from the top edge.
    ///   - completion: Called on the main queue with `nil` on success or an error.
    // swiftlint:disable:next identifier_name
    func setFocusPoint(x: CGFloat, y: CGFloat, completion: @escaping (Error?) -> Void) {
        guard captureSession.isRunning else {
            completion(CameraError.sessionNotRunning)
            return
        }

        let layerPoint = CGPoint(x: x, y: y)

        // `captureDevicePointConverted(fromLayerPoint:)` accounts for the
        // layer's `videoGravity` and its connection's rotation, so the mapping
        // holds in both scale modes and in both orientations.
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let devicePoint = self.videoPreviewLayer.captureDevicePointConverted(fromLayerPoint: layerPoint)

            self.sessionQueue.async {
                do {
                    try self.applyFocusPoint(devicePoint)
                    DispatchQueue.main.async { completion(nil) }
                } catch {
                    DispatchQueue.main.async { completion(error) }
                }
            }
        }
    }

    /// Applies the one-shot focus/exposure point of interest to the current
    /// device and schedules the continuous-mode restore. Must run on
    /// `sessionQueue`.
    ///
    /// Fixed-focus cameras (some front cameras) degrade to exposure-only
    /// metering. If neither focus nor exposure at a point is supported, throws
    /// `CameraError.focusNotSupported`.
    private func applyFocusPoint(_ devicePoint: CGPoint) throws {
        guard let device = currentCameraDevice else {
            throw CameraError.cameraUnavailable
        }

        let canFocus = device.isFocusPointOfInterestSupported && device.isFocusModeSupported(.autoFocus)
        let canExpose = device.isExposurePointOfInterestSupported && device.isExposureModeSupported(.autoExpose)

        guard canFocus || canExpose else {
            throw CameraError.focusNotSupported
        }

        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }

            if canFocus {
                device.focusPointOfInterest = devicePoint
                device.focusMode = .autoFocus
            }

            if canExpose {
                device.exposurePointOfInterest = devicePoint
                device.exposureMode = .autoExpose
            }
        } catch {
            throw CameraError.configurationFailed(error)
        }

        scheduleFocusReset()
    }

    /// Schedules restoration of continuous auto focus/exposure after
    /// `cameraFocusResetDelay`, cancelling any previously scheduled restore so
    /// rapid taps don't reset a later focus. Must run on `sessionQueue`.
    private func scheduleFocusReset() {
        focusResetWorkItem?.cancel()

        let workItem = DispatchWorkItem { [weak self] in
            self?.restoreContinuousFocusAndExposure()
        }
        focusResetWorkItem = workItem

        sessionQueue.asyncAfter(deadline: .now() + cameraFocusResetDelay, execute: workItem)
    }

    /// Restores continuous auto focus/exposure centered on the frame, so a
    /// one-shot tap-to-focus never leaves the camera permanently locked.
    /// Best-effort: configuration failures are ignored. Runs on `sessionQueue`.
    private func restoreContinuousFocusAndExposure() {
        guard let device = currentCameraDevice else { return }

        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }

            let center = CGPoint(x: 0.5, y: 0.5)

            if device.isFocusPointOfInterestSupported {
                device.focusPointOfInterest = center
            }
            if device.isFocusModeSupported(.continuousAutoFocus) {
                device.focusMode = .continuousAutoFocus
            }

            if device.isExposurePointOfInterestSupported {
                device.exposurePointOfInterest = center
            }
            if device.isExposureModeSupported(.continuousAutoExposure) {
                device.exposureMode = .continuousAutoExposure
            }
        } catch {
            // Best-effort restore; nothing actionable if the device is busy.
        }
    }
}
