import AVFoundation
import Foundation

extension CameraViewManager: AVCaptureVideoDataOutputSampleBufferDelegate {
    /// Set up video data output for the capture session in case it's not configured yet
    /// This is used for taking snapshots of the camera feed
    /// Make sure to call `captureSession.beginConfiguration` before calling this
    ///
    /// - Throws: An error if the output cannot be set.
    /// - Note: This method does not set the delegate for the output. The delegate is set
    ///         when a snapshot is requested.
    internal func setupVideoDataOutput() throws {
        if (captureSession.outputs.contains { $0 is AVCaptureVideoDataOutput }) {
            // Nothing todo, we already have an output and since we only
            // use video outputs for taking snapshots here we don't need a new one
            return
        }

        // Configure video data output
        avVideoDataOutput.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA)
        ]
        avVideoDataOutput.alwaysDiscardsLateVideoFrames = true

        // We're not setting the delegate here as we'll set it only when needed for snapshot capture

        if !captureSession.canAddOutput(avVideoDataOutput) {
            throw CameraError.outputAdditionFailed
        }

        captureSession.addOutput(avVideoDataOutput)
    }

    /// Capture a snapshot from the camera feed
    public func captureOutput(
        _ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        // Only process if we have a completion handler set
        guard let completionHandler = snapshotCompletionHandler else { return }

        // Clear the completion handler to ensure we only capture one frame
        snapshotCompletionHandler = nil

        avVideoDataOutput.setSampleBufferDelegate(nil, queue: nil)

        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            completionHandler(nil, CameraError.frameCaptureError)
            return
        }

        let ciImage = CIImage(cvPixelBuffer: imageBuffer)
        let context = CIContext()
        guard let cgImage = context.createCGImage(ciImage, from: ciImage.extent) else {
            completionHandler(nil, CameraError.frameCaptureError)
            return
        }

        let image = UIImage(cgImage: cgImage)
        completionHandler(image, nil)
    }

    /// Delegate method called when a frame is dropped via `AVCaptureVideoDataOutputSampleBufferDelegate`
    public func captureOutput(
        _ output: AVCaptureOutput, didDrop sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        // If we have a completion handler and a frame was dropped, report the error
        if let completionHandler = snapshotCompletionHandler {
            snapshotCompletionHandler = nil
            avVideoDataOutput.setSampleBufferDelegate(nil, queue: nil)

            completionHandler(nil, CameraError.frameCaptureError)
        }
    }
}
