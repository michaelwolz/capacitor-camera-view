import AVFoundation
import Foundation

/// Deferred Start (iOS 26+) lets `startRunning()` return as soon as the preview
/// pipeline is up and initializes the remaining outputs shortly afterwards,
/// roughly halving the time until the first frame is visible.
///
/// The photo and video data outputs are deferred; the preview layer is not,
/// since it is the one consumer that must be live immediately and its first
/// frame is what triggers the deferred initialization. The metadata output is
/// added in its own transaction after the session is already running, so it
/// never contributes to `startRunning()` latency.
extension CameraViewManager {

    /// Marks an output as deferrable so the session does not prepare its
    /// resources until after `startRunning()` has returned.
    ///
    /// No-op below iOS 26 and on outputs that don't support deferral (setting the
    /// flag on those raises `NSInvalidArgumentException`). Must be called inside
    /// the configuration transaction that adds the output.
    internal func deferStart(of output: AVCaptureOutput, _ deferred: Bool = true) {
        if #available(iOS 26.0, *), output.isDeferredStartSupported {
            output.isDeferredStartEnabled = deferred
        }
    }

    /// Lets the session decide when to run the deferred initialization.
    ///
    /// Set explicitly rather than relying on the default, which is only `true`
    /// for host apps linked against the iOS 26 SDK or later. Must be called
    /// inside a configuration transaction on the session queue.
    internal func configureAutomaticDeferredStart() {
        if #available(iOS 26.0, *) {
            captureSession.automaticallyRunsDeferredStart = true
        }
    }
}
