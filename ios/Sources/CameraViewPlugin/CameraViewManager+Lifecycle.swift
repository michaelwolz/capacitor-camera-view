import AVFoundation
import Foundation
import UIKit

/// Handles app lifecycle transitions and `AVCaptureSession` interruption /
/// runtime-error notifications.
///
/// Backgrounding pauses the session (and finalizes any in-flight recording so
/// its JS promise settles); returning to the foreground resumes it. Session
/// interruptions (phone calls, Control Center audio capture, iPad camera loss)
/// and runtime errors are surfaced to JS as `cameraInterrupted`,
/// `cameraResumed`, and `cameraRuntimeError` events, and a media-services reset
/// restarts the session automatically.
extension CameraViewManager {

    // MARK: - App Lifecycle Observers

    /// Sets up observers for app background/foreground transitions.
    ///
    /// Uses `didEnterBackground`/`willEnterForeground` rather than
    /// `willResignActive`/`didBecomeActive` on purpose: resign/active also fire
    /// for Control Center pull-downs, notification banners, and permission
    /// dialogs, none of which should tear down the session.
    internal func setupAppLifecycleObservers() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
    }

    /// Pauses the camera session when the app enters the background.
    ///
    /// Stopping the running session finalizes any in-flight recording, which
    /// invokes the recording delegate and settles a pending `stopRecording`
    /// promise (with the partial file or an error) rather than leaving it to
    /// hang.
    @objc internal func handleAppDidEnterBackground() {
        guard captureSession.isRunning else { return }
        sessionQueue.async { [weak self] in
            self?.captureSession.stopRunning()
        }
    }

    /// Resumes the camera session when the app returns to the foreground.
    @objc internal func handleAppWillEnterForeground() {
        guard !captureSession.isRunning else { return }
        sessionQueue.async { [weak self] in
            // Re-checked on the session queue: `isRunning` can have changed
            // since the cheap check above, and `isSessionStoppedByUser` is
            // confined to this queue.
            guard let self = self, !self.isSessionStoppedByUser, !self.captureSession.isRunning else { return }
            self.captureSession.startRunning()
        }
    }

    // MARK: - Interruption & Runtime-Error Observers

    /// Sets up observers for capture-session interruption and runtime-error
    /// notifications, scoped to this manager's `captureSession`.
    internal func setupInterruptionObservers() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSessionWasInterrupted(_:)),
            name: AVCaptureSession.wasInterruptedNotification,
            object: captureSession
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSessionInterruptionEnded(_:)),
            name: AVCaptureSession.interruptionEndedNotification,
            object: captureSession
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSessionRuntimeError(_:)),
            name: AVCaptureSession.runtimeErrorNotification,
            object: captureSession
        )
    }

    /// Handles a capture-session interruption (e.g. phone call, another app
    /// using the camera, iPad Split View camera loss) by emitting
    /// `cameraInterrupted` with a reason.
    @objc internal func handleSessionWasInterrupted(_ notification: Notification) {
        eventEmitter.emitCameraInterrupted(
            reason: interruptionReasonString(from: notification)
        )
    }

    /// Handles the end of a capture-session interruption. AVFoundation resumes
    /// the session automatically; we just surface `cameraResumed` to JS.
    @objc internal func handleSessionInterruptionEnded(_ notification: Notification) {
        eventEmitter.emitCameraResumed()
    }

    /// Handles a capture-session runtime error by emitting `cameraRuntimeError`
    /// and restarting the session when media services were reset.
    @objc internal func handleSessionRuntimeError(_ notification: Notification) {
        let error = notification.userInfo?[AVCaptureSessionErrorKey] as? AVError
        let message = error?.localizedDescription
            ?? "Unknown capture session runtime error"
        eventEmitter.emitCameraRuntimeError(
            message: message,
            code: error?.code.rawValue
        )

        // A media-services reset invalidates the session; restart it so the
        // preview recovers instead of staying frozen.
        guard error?.code == .mediaServicesWereReset else { return }
        sessionQueue.async { [weak self] in
            guard let self = self, !self.isSessionStoppedByUser, !self.captureSession.isRunning else { return }
            self.captureSession.startRunning()
        }
    }

    // MARK: - Helpers

    /// Maps an interruption notification's reason to a stable JS string that
    /// mirrors the `CameraInterruptionReason` TypeScript union.
    private func interruptionReasonString(from notification: Notification) -> String {
        guard
            let reasonValue = notification.userInfo?[
                AVCaptureSessionInterruptionReasonKey
            ] as? Int,
            let reason = AVCaptureSession.InterruptionReason(rawValue: reasonValue)
        else {
            return "unknown"
        }

        switch reason {
        case .videoDeviceNotAvailableInBackground:
            return "videoDeviceNotAvailableInBackground"
        case .audioDeviceInUseByAnotherClient:
            return "audioDeviceInUseByAnotherClient"
        case .videoDeviceInUseByAnotherClient:
            return "videoDeviceInUseByAnotherClient"
        case .videoDeviceNotAvailableWithMultipleForegroundApps:
            return "videoDeviceNotAvailableWithMultipleForegroundApps"
        case .videoDeviceNotAvailableDueToSystemPressure:
            return "videoDeviceNotAvailableDueToSystemPressure"
        @unknown default:
            return "unknown"
        }
    }
}
