import AVFoundation
import Foundation
import UIKit

extension CameraViewManager: AVCapturePhotoCaptureDelegate {
    /// Set up output for the capture session in case it's not configured yet
    /// Make sure to call `captureSession.beginConfiguration` before calling this
    ///
    /// - Throws: An error if the output cannot be set.
    internal func setupPhotoOutput() throws {
        if (captureSession.outputs.contains { $0 is AVCapturePhotoOutput }) {
            // Nothing todo, we already have an output and since we only
            // use outputs for taking photos here we don't need a new one
            return
        }

        // Balanced should be a good choice for most use cases
        avPhotoOutput.maxPhotoQualityPrioritization = .balanced

        if !captureSession.canAddOutput(avPhotoOutput) {
            throw CameraError.outputAdditionFailed
        }

        captureSession.addOutput(avPhotoOutput)
    }

    /// Delegate method called when a photo has been captured via `AVCapturePhotoCaptureDelegate`
    ///
    /// - Parameters:
    ///   - output: The photo output that captured the photo.
    ///   - photo: The captured photo.
    ///   - error: An error that occurred during photo capture.
    public func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        if let error = error {
            photoCaptureHandler?(nil, error)
            return
        }

        guard let data = photo.fileDataRepresentation(), let image = UIImage(data: data) else {
            photoCaptureHandler?(nil, CameraError.photoOutputError)
            return
        }

        photoCaptureHandler?(image, nil)
    }

}

