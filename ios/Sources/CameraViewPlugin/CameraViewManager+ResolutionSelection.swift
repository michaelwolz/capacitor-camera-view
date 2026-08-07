import AVFoundation
import Foundation

/// Resolution / aspect-ratio selection for preview and photo capture.
///
/// The session preset drives the active format and therefore the aspect ratio
/// of both the preview stream and captured photos, keeping what the user sees
/// consistent with what gets captured.
extension CameraViewManager {

    /// Applies the session preset matching the configured aspect ratio.
    ///
    /// "16:9" prefers the HD video presets (highest first); "4:3" and `nil` use
    /// the `.photo` preset with the sensor's native 4:3 photo format.
    ///
    /// Must run inside a configuration transaction on the session queue.
    ///
    /// - Parameter aspectRatio: The configured aspect ratio ("4:3", "16:9" or `nil`).
    internal func applySessionPreset(forAspectRatio aspectRatio: String?) {
        let preferredPresets: [AVCaptureSession.Preset] =
            aspectRatio == "16:9"
            ? [.hd4K3840x2160, .hd1920x1080, .hd1280x720, .photo]
            : [.photo]

        for preset in preferredPresets where captureSession.canSetSessionPreset(preset) {
            captureSession.sessionPreset = preset
            return
        }
    }

    /// Re-applies the configured capture-resolution hint to the photo output.
    ///
    /// Picks the largest supported photo dimensions whose longer edge does not
    /// exceed the hint, falling back to the smallest supported dimensions.
    ///
    /// `maxPhotoDimensions` is only valid against the *current* active format's
    /// `supportedMaxPhotoDimensions` and is reset by AVFoundation whenever the
    /// active format changes, so it must be re-applied on the session queue
    /// after every committed transaction that changes the format.
    internal func applyConfiguredMaxPhotoDimensions() {
        guard let maxDimension = configuredCaptureMaxDimension,
              let device = currentCameraDevice else { return }

        let supported = device.activeFormat.supportedMaxPhotoDimensions

        let byArea: (CMVideoDimensions, CMVideoDimensions) -> Bool = {
            Int64($0.width) * Int64($0.height) < Int64($1.width) * Int64($1.height)
        }
        let fitting = supported.filter { Int(max($0.width, $0.height)) <= maxDimension }

        guard let chosen = fitting.max(by: byArea) ?? supported.min(by: byArea) else {
            return
        }

        avPhotoOutput.maxPhotoDimensions = chosen
    }
}
