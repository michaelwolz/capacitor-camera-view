import AVFoundation
import Foundation

extension AVCaptureDevice {
    /// The zoom factor at which this device's wide-angle lens becomes active.
    ///
    /// On a virtual device including the ultra-wide lens, raw zoom `1.0` is the
    /// *ultra-wide* field of view and the wide-angle "1x" the user expects sits
    /// at the first switch-over factor. Every other device returns `1.0`.
    internal var wideAngleZoomFactor: CGFloat {
        guard constituentDevices.contains(where: { $0.deviceType == .builtInUltraWideCamera }),
              let switchOver = virtualDeviceSwitchOverVideoZoomFactors.first
        else {
            return 1.0
        }

        return CGFloat(truncating: switchOver)
    }
}

/// Zoom control. Values are in the active device's own zoom domain, which on a
/// virtual camera is not the wide-angle-relative domain callers think in — see
/// `wideAngleZoomFactor` and `applyInitialZoom(_:)`.
extension CameraViewManager {

    /// Gets the minimum, maximum, and current zoom factors supported by the current camera device.
    /// The maximum zoom factor is limited to a reasonable value of 10x to prevent excessive zooming
    /// because some devices report very high zoom factors that aren't useful.
    ///
    /// - Returns: A tuple containing the minimum, maximum, and current zoom factors.
    public func getSupportedZoomFactors() -> (
        min: CGFloat, max: CGFloat, current: CGFloat
    ) {
        guard let currentDevice = currentCameraDevice else {
            return (
                min: 1.0,
                max: 1.0,
                current: 1.0
            )
        }

        let minZoomFactor = currentDevice.minAvailableVideoZoomFactor
        // Scaling by the wide-lens factor keeps the wide-equivalent maximum at
        // 10x on virtual devices whose raw 1.0 is ultra-wide.
        let maxZoomFactor = min(
            currentDevice.activeFormat.videoMaxZoomFactor,
            10.0 * currentDevice.wideAngleZoomFactor
        )
        let currentZoomFactor = currentDevice.videoZoomFactor

        return (
            min: minZoomFactor,
            max: maxZoomFactor,
            current: currentZoomFactor
        )
    }

    /// Sets the zoom factor for the current camera device.
    ///
    /// - Parameters:
    ///   - factor: The zoom factor to set.
    ///   - ramp: If enabled the zoom will be applied via ramp
    /// - Throws: An error if the zoom factor cannot be set.
    public func setZoomFactor(_ factor: CGFloat, ramp: Bool = true) throws {
        guard let device = currentCameraDevice else {
            throw CameraError.cameraUnavailable
        }

        let supportedZoomFactors = getSupportedZoomFactors()
        guard
            factor >= supportedZoomFactors.min
                && factor <= supportedZoomFactors.max
        else {
            throw CameraError.zoomFactorOutOfRange
        }

        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }

            if ramp {
                device.ramp(toVideoZoomFactor: factor, withRate: 6.0)
            } else {
                device.videoZoomFactor = factor
            }
        } catch {
            throw CameraError.configurationFailed(error)
        }
    }

    /// Applies a session's initial zoom factor to the active camera device.
    ///
    /// The factor is expressed in the wide-angle domain the caller thinks in, so
    /// on a virtual device whose raw `1.0` is the ultra-wide lens it is scaled
    /// into the device's own zoom domain. Without this the first frame of a
    /// triple-camera session would show the 0.5x ultra-wide field of view.
    ///
    /// Must be called on the session queue after the configuration transaction
    /// that set the input has committed, since the supported zoom range is
    /// derived from the device's active format.
    ///
    /// - Parameter factor: The requested wide-angle-relative zoom factor.
    /// - Throws: An error if the resulting zoom factor cannot be set.
    internal func applyInitialZoom(_ factor: CGFloat?) throws {
        guard let wideAngle = currentCameraDevice?.wideAngleZoomFactor else { return }

        if let factor = factor {
            try setZoomFactor(factor * wideAngle, ramp: false)
        } else if wideAngle != 1.0 {
            try setZoomFactor(wideAngle, ramp: false)
        }
    }
}
