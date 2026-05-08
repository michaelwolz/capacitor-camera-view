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
    /// This method handles both the legacy UIImage-based callback and the optimized Data-based
    /// callback to eliminate double JPEG encoding when possible.
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
        // Handle optimized Data-based callback first (avoids double encoding)
        if let dataHandler = photoDataCaptureHandler {
            photoDataCaptureHandler = nil

            if let error = error {
                dataHandler(nil, error)
                return
            }

            guard let data = photo.fileDataRepresentation() else {
                dataHandler(nil, CameraError.photoOutputError)
                return
            }

            dataHandler(data, nil)
            return
        }

        // Handle legacy UIImage-based callback
        if let imageHandler = photoCaptureHandler {
            photoCaptureHandler = nil

            if let error = error {
                imageHandler(nil, error)
                return
            }

            guard let data = photo.fileDataRepresentation() else {
                imageHandler(nil, CameraError.photoOutputError)
                return
            }

            guard let image = UIImage(data: data) else {
                imageHandler(nil, CameraError.photoOutputError)
                return
            }

            imageHandler(image, nil)
        }
    }

}
