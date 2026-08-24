import AVFoundation
import Foundation
import UIKit

/// Rotation handling for the camera preview and capture outputs.
///
/// On iOS 17+ this adopts `AVCaptureDevice.RotationCoordinator`, which reports
/// horizon-level rotation angles for the preview and for capture and updates
/// them continuously without relying on `UIDevice` orientation notifications.
/// On iOS 16 it falls back to the legacy `videoOrientation` API driven by device
/// orientation changes (see `setupOrientationObserver`).
extension CameraViewManager {

    // MARK: - iOS 17+ Rotation Coordinator

    /// Typed accessor over the `Any?`-backed coordinator storage.
    ///
    /// Takes `rotationCoordinatorLock`: the storage is written on the main queue
    /// but read from the Capacitor call thread and the session queue, and an
    /// unsynchronized ARC reassignment racing a read is undefined behavior.
    @available(iOS 17.0, *)
    internal var rotationCoordinator: AVCaptureDevice.RotationCoordinator? {
        get {
            rotationCoordinatorLock.lock()
            defer { rotationCoordinatorLock.unlock() }
            return rotationCoordinatorStorage as? AVCaptureDevice.RotationCoordinator
        }
        set {
            rotationCoordinatorLock.lock()
            defer { rotationCoordinatorLock.unlock() }
            rotationCoordinatorStorage = newValue
        }
    }

    /// (Re)configures rotation handling for the current camera device. Safe to
    /// call whenever the active device changes (flip, triple-camera upgrade) or
    /// once the preview becomes available.
    internal func configureRotationHandling() {
        if #available(iOS 17.0, *) {
            setupRotationCoordinator()
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.updateLegacyPreviewOrientation()
            }
        }
    }

    /// Creates a rotation coordinator for the active device and observes its
    /// preview angle to keep the preview level with the horizon. Recreated on
    /// every device change so the angles track the physical camera in use.
    @available(iOS 17.0, *)
    private func setupRotationCoordinator() {
        DispatchQueue.main.async { [weak self] in
            guard let self = self,
                  let device = self.currentCameraDevice,
                  self.videoPreviewLayer.session != nil else { return }

            // Invalidate the previous observation before replacing the coordinator.
            self.rotationObservation?.invalidate()

            let coordinator = AVCaptureDevice.RotationCoordinator(
                device: device,
                previewLayer: self.videoPreviewLayer
            )
            self.rotationCoordinator = coordinator

            // `.initial` applies the current angle immediately; subsequent
            // changes keep the preview rotating continuously.
            self.rotationObservation = coordinator.observe(
                \.videoRotationAngleForHorizonLevelPreview,
                options: [.initial, .new]
            ) { [weak self] coordinator, _ in
                self?.applyPreviewRotationAngle(
                    coordinator.videoRotationAngleForHorizonLevelPreview
                )
            }
        }
    }

    /// Applies a preview rotation angle to the preview layer's connection.
    @available(iOS 17.0, *)
    private func applyPreviewRotationAngle(_ angle: CGFloat) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self,
                  let connection = self.videoPreviewLayer.connection,
                  connection.isVideoRotationAngleSupported(angle) else { return }

            connection.videoRotationAngle = angle

            // Keep the preview layer filling the (possibly rotated) bounds.
            if let view = self.webView {
                self.videoPreviewLayer.frame = view.bounds
            }
        }
    }

    // MARK: - Capture Orientation

    /// Applies the correct rotation to a capture output connection (photo,
    /// sample, or movie) so captured media matches what the preview shows.
    internal func applyCaptureOrientation(to connection: AVCaptureConnection) {
        if #available(iOS 17.0, *) {
            // A nil coordinator only happens for a capture racing session
            // teardown; leaving the connection at its default angle is harmless.
            guard let coordinator = rotationCoordinator else { return }

            // A detached preview layer makes the coordinator report a preview angle of 0.
            let angle = videoPreviewLayer.superlayer == nil
                ? coordinator.videoRotationAngleForHorizonLevelCapture
                : coordinator.videoRotationAngleForHorizonLevelPreview

            if connection.isVideoRotationAngleSupported(angle) {
                connection.videoRotationAngle = angle
            }
        } else {
            applyLegacyCaptureOrientation(to: connection)
        }
    }

    // MARK: - iOS 16 Legacy Path

    /// Sets up device orientation handling (called from `init`).
    ///
    /// No-op on iOS 17+, where the rotation coordinator takes over. On iOS 16
    /// the plugin generates the device orientation notifications itself rather
    /// than relying on the host app having started them.
    internal func setupOrientationObserver() {
        if #available(iOS 17.0, *) {
            return
        }

        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        orientationObserverToken = NotificationCenter.default.addObserver(
            forName: UIDevice.orientationDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.updateLegacyPreviewOrientation()
        }
    }

    /// Tears down the block-based orientation observer registered by
    /// `setupOrientationObserver`. `NotificationCenter.removeObserver(self)`
    /// does not remove block-based observers, so the token must be removed
    /// explicitly or it leaks for the process lifetime.
    internal func removeOrientationObserver() {
        if let token = orientationObserverToken {
            NotificationCenter.default.removeObserver(token)
            orientationObserverToken = nil
        }

        if #unavailable(iOS 17.0) {
            UIDevice.current.endGeneratingDeviceOrientationNotifications()
        }
    }

    /// Legacy capture orientation: mirror the preview connection's
    /// `videoOrientation` onto the capture connection.
    @available(iOS, introduced: 16.0, deprecated: 17.0,
               message: "Uses videoOrientation; replaced by RotationCoordinator on iOS 17+")
    private func applyLegacyCaptureOrientation(to connection: AVCaptureConnection) {
        guard let previewConnection = videoPreviewLayer.connection,
              connection.isVideoOrientationSupported else { return }
        connection.videoOrientation = previewConnection.videoOrientation
    }

    /// Legacy preview orientation: derive `videoOrientation` from the current
    /// interface orientation. Driven by `UIDevice` orientation notifications.
    @available(iOS, introduced: 16.0, deprecated: 17.0,
               message: "Uses videoOrientation; replaced by RotationCoordinator on iOS 17+")
    internal func updateLegacyPreviewOrientation() {
        guard let connection = videoPreviewLayer.connection,
              connection.isVideoOrientationSupported else { return }

        let interfaceOrientation = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first?.interfaceOrientation ?? .portrait

        let videoOrientation: AVCaptureVideoOrientation
        switch interfaceOrientation {
        case .portrait:
            videoOrientation = .portrait
        case .landscapeLeft:
            videoOrientation = .landscapeLeft
        case .landscapeRight:
            videoOrientation = .landscapeRight
        case .portraitUpsideDown:
            videoOrientation = .portraitUpsideDown
        default:
            videoOrientation = .portrait
        }

        connection.videoOrientation = videoOrientation

        // Update the frame of the preview layer to match the new bounds.
        if let view = webView {
            videoPreviewLayer.frame = view.bounds
        }
    }
}
