import AVFoundation
import CoreImage
import Foundation
import UIKit

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
        deferStart(of: avVideoDataOutput)
    }

    /// Atomically consumes the snapshot completion handler and cancels its
    /// timeout. Returns the handler to the caller that wins the race (frame
    /// delivery or timeout) and `nil` to any later caller.
    internal func consumeSnapshotHandler() -> ((UIImage?, Error?) -> Void)? {
        captureHandlerLock.lock()
        defer { captureHandlerLock.unlock() }

        guard let handler = snapshotCompletionHandler else { return nil }
        snapshotCompletionHandler = nil
        snapshotTimeoutWorkItem?.cancel()
        snapshotTimeoutWorkItem = nil
        return handler
    }

    /// Capture a snapshot from the camera feed using the shared Metal-backed CIContext.
    /// Using a shared CIContext eliminates the ~80% CPU overhead of creating one per frame.
    public func captureOutput(
        _ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        // Atomically claim the snapshot; returns nil if it was already consumed
        // (e.g. by the timeout) or if no capture is in flight. This also cancels
        // the timeout so the promise settles exactly once.
        guard let completionHandler = consumeSnapshotHandler() else { return }

        // Stop delivery to ensure we only capture one frame.
        avVideoDataOutput.setSampleBufferDelegate(nil, queue: nil)

        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            completionHandler(nil, CameraError.frameCaptureError)
            return
        }

        let ciImage = CIImage(cvPixelBuffer: imageBuffer)
        // Use the shared Metal-backed CIContext for efficient rendering
        guard let cgImage = CameraViewManager.sharedCIContext.createCGImage(ciImage, from: ciImage.extent) else {
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
        // With `alwaysDiscardsLateVideoFrames` enabled a transient dropped frame
        // is normal and must not fail the capture. Keep the delegate installed
        // and wait for the next delivered frame; the timeout guards against the
        // case where no frame ever arrives.
    }
}
