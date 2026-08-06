import AVFoundation
import Foundation

extension CameraViewManager: AVCapturePhotoCaptureDelegate {
    /// Set up output for the capture session in case it's not configured yet
    /// Make sure to call `captureSession.beginConfiguration` before calling this
    ///
    /// - Parameter prioritizeQuality: When `true`, the iOS 17+ responsive-capture
    ///   optimizations are skipped so captures always prioritize quality.
    /// - Throws: An error if the output cannot be set.
    internal func setupPhotoOutput(prioritizeQuality: Bool = false) throws {
        if (captureSession.outputs.contains { $0 is AVCapturePhotoOutput }) {
            // The output already exists (session restart) - still reconfigure
            // the responsiveness pipeline, since prioritizeQuality may have
            // changed between sessions.
            configureResponsiveCapture(prioritizeQuality: prioritizeQuality)
            return
        }

        // Balanced should be a good choice for most use cases
        avPhotoOutput.maxPhotoQualityPrioritization = .balanced

        if !captureSession.canAddOutput(avPhotoOutput) {
            throw CameraError.outputAdditionFailed
        }

        captureSession.addOutput(avPhotoOutput)
        deferStart(of: avPhotoOutput)

        configureResponsiveCapture(prioritizeQuality: prioritizeQuality)
    }

    /// Applies the iOS 17+ capture-responsiveness state on the photo output where
    /// supported, reducing shot-to-shot latency.
    ///
    /// Ordering matters: responsive capture requires zero-shutter-lag, and fast
    /// capture prioritization requires responsive capture, so capabilities are
    /// enabled prerequisite-first and disabled dependent-first.
    ///
    /// - Parameter prioritizeQuality: When `true`, the optimizations are turned
    ///   off (not merely skipped) so a session restart that flips the option
    ///   always prioritizes quality.
    private func configureResponsiveCapture(prioritizeQuality: Bool) {
        if #available(iOS 17.0, *) {
            let enable = !prioritizeQuality

            if enable {
                if avPhotoOutput.isZeroShutterLagSupported {
                    avPhotoOutput.isZeroShutterLagEnabled = true
                }

                if avPhotoOutput.isResponsiveCaptureSupported {
                    avPhotoOutput.isResponsiveCaptureEnabled = true

                    // Fast capture prioritization requires responsive capture
                    // to be enabled first.
                    if avPhotoOutput.isFastCapturePrioritizationSupported {
                        avPhotoOutput.isFastCapturePrioritizationEnabled = true
                    }
                }
            } else {
                // Disable dependents before their prerequisites.
                if avPhotoOutput.isFastCapturePrioritizationSupported {
                    avPhotoOutput.isFastCapturePrioritizationEnabled = false
                }
                if avPhotoOutput.isResponsiveCaptureSupported {
                    avPhotoOutput.isResponsiveCaptureEnabled = false
                }
                if avPhotoOutput.isZeroShutterLagSupported {
                    avPhotoOutput.isZeroShutterLagEnabled = false
                }
            }
        }
    }

    /// Atomically consumes the photo completion handler so a photo-output
    /// delegate callback can never race handler assignment on the capture thread.
    internal func consumePhotoDataHandler() -> ((Data?, Error?) -> Void)? {
        captureHandlerLock.lock()
        defer { captureHandlerLock.unlock() }

        let dataHandler = photoDataCaptureHandler
        photoDataCaptureHandler = nil
        return dataHandler
    }

    /// Delegate method called when a photo has been captured via `AVCapturePhotoCaptureDelegate`.
    /// Returns the camera's native JPEG data directly, avoiding a double JPEG encode.
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
        guard let dataHandler = consumePhotoDataHandler() else { return }

        if let error = error {
            dataHandler(nil, error)
            return
        }

        guard let data = photo.fileDataRepresentation() else {
            dataHandler(nil, CameraError.photoOutputError)
            return
        }

        dataHandler(data, nil)
    }

}
