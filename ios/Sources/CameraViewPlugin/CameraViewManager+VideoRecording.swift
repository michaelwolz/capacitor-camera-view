import AVFoundation
import Foundation

public enum VideoRecordingQuality: String {
    case lowest
    case sd
    case hd
    case fhd
    case uhd
    case highest
}

extension CameraViewManager: AVCaptureFileOutputRecordingDelegate {

    // MARK: - Public API

    /// Starts video recording to a temporary file.
    ///
    /// - Parameters:
    ///   - enableAudio: Whether to include audio in the recording
    ///   - videoQuality: Desired video recording quality preset
    ///   - completion: Called when recording starts (nil) or fails (error)
    public func startRecording(
        enableAudio: Bool,
        videoQuality: VideoRecordingQuality,
        completion: @escaping (Error?) -> Void
    ) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }

            guard self.captureSession.isRunning else {
                // Session may be temporarily stopped (e.g. iOS stops the capture session
                // when reconfiguring audio after a microphone permission grant). Wait for
                // it to resume and retry rather than failing immediately.
                self.waitForSessionThenStartRecording(
                    enableAudio: enableAudio,
                    videoQuality: videoQuality,
                    completion: completion
                )
                return
            }

            guard !self.avMovieOutput.isRecording else {
                DispatchQueue.main.async { completion(CameraError.recordingAlreadyInProgress) }
                return
            }

            self.captureSession.beginConfiguration()
            self.sessionPresetBeforeRecording = self.captureSession.sessionPreset

            let recordingPreset = self.resolveRecordingPreset(for: videoQuality)
            if self.captureSession.canSetSessionPreset(recordingPreset) {
                self.captureSession.sessionPreset = recordingPreset
            }

            // Add movie output if not already added
            if !self.captureSession.outputs.contains(self.avMovieOutput) {
                guard self.captureSession.canAddOutput(self.avMovieOutput) else {
                    self.restoreSessionPreset()
                    self.captureSession.commitConfiguration()
                    DispatchQueue.main.async {
                        completion(CameraError.outputAdditionFailed)
                    }
                    return
                }
                self.captureSession.addOutput(self.avMovieOutput)
            }

            // Add audio input if requested
            if enableAudio {
                do {
                    try self.addAudioInput()
                } catch {
                    self.restoreSessionPreset()
                    self.captureSession.commitConfiguration()
                    DispatchQueue.main.async {
                        completion(error)
                    }
                    return
                }
            }

            self.captureSession.commitConfiguration()

            // Set orientation on the movie output connection
            if let connection = self.avMovieOutput.connection(with: .video),
               let previewConnection = self.videoPreviewLayer.connection {
                if connection.isVideoOrientationSupported {
                    connection.videoOrientation = previewConnection.videoOrientation
                }
                if connection.isVideoMirroringSupported {
                    connection.isVideoMirrored = self.currentCameraDevice?.position == .front
                }
            }

            // Create a temp file URL and start recording
            let outputURL = TempFileManager.shared.createTempVideoFile()
            self.avMovieOutput.startRecording(to: outputURL, recordingDelegate: self)

            DispatchQueue.main.async {
                completion(nil)
            }
        }
    }

    /// Stops the current video recording.
    ///
    /// - Parameter completion: Called with the output file URL or an error
    public func stopRecording(completion: @escaping (URL?, Error?) -> Void) {
        // The entire body runs on sessionQueue so that the isRecording
        // check and the handler assignment are serialised with startRecording
        // and with the delegate callback that reads the handler.
        sessionQueue.async { [weak self] in
            guard let self = self else { return }

            guard self.avMovieOutput.isRecording else {
                DispatchQueue.main.async { completion(nil, CameraError.noRecordingInProgress) }
                return
            }

            self.videoRecordingCompletionHandler = completion
            self.avMovieOutput.stopRecording()
        }
    }

    // MARK: - AVCaptureFileOutputRecordingDelegate

    public func fileOutput(
        _ output: AVCaptureFileOutput,
        didFinishRecordingTo outputFileURL: URL,
        from connections: [AVCaptureConnection],
        error: Error?
    ) {
        // AVFoundation may invoke this delegate on an arbitrary thread.
        // Use sessionQueue.sync to read and clear the handler and the
        // recordingWithAudio flag while holding the same serial queue that
        // stopRecording uses to write them, preventing a data race.
        var handler: ((URL?, Error?) -> Void)?
        var hadAudio = false

        sessionQueue.sync { [weak self] in
            guard let self = self else { return }
            handler = self.videoRecordingCompletionHandler
            self.videoRecordingCompletionHandler = nil
            hadAudio = self.recordingWithAudio
        }

        // Remove movie output (and audio input if it was added) from session.
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            self.captureSession.beginConfiguration()
            if hadAudio {
                self.removeAudioInput()
            }
            self.captureSession.removeOutput(self.avMovieOutput)
            self.restoreSessionPreset()
            self.recordingWithAudio = false
            self.captureSession.commitConfiguration()
        }

        if let error = error {
            DispatchQueue.main.async {
                handler?(nil, error)
            }
            return
        }

        DispatchQueue.main.async {
            handler?(outputFileURL, nil)
        }
    }

    // MARK: - Private Helpers

    /// Waits for the capture session to start running, then retries `startRecording`.
    ///
    /// Called when `startRecording` finds the session temporarily stopped (e.g. after
    /// iOS reconfigures audio following a first-time microphone permission grant).
    /// Both the notification path and the timeout path serialize through `sessionQueue`,
    /// so `handled` is accessed on a single serial queue and needs no additional lock.
    private func waitForSessionThenStartRecording(
        enableAudio: Bool,
        videoQuality: VideoRecordingQuality,
        completion: @escaping (Error?) -> Void
    ) {
        let sessionQueue = self.sessionQueue
        // Keep the token so we can remove the observer on success and timeout paths.
        var observerToken: NSObjectProtocol?
        var handled = false

        observerToken = NotificationCenter.default.addObserver(
            forName: .AVCaptureSessionDidStartRunning,
            object: captureSession,
            queue: nil
        ) { [weak self] _ in
            sessionQueue.async {
                guard !handled else { return }
                handled = true
                if let token = observerToken {
                    NotificationCenter.default.removeObserver(token)
                    observerToken = nil
                }
                guard let self = self else { return }
                self.startRecording(
                    enableAudio: enableAudio,
                    videoQuality: videoQuality,
                    completion: completion
                )
            }
        }

        // Timeout: if the session hasn't restarted within 2 seconds, give up.
        sessionQueue.asyncAfter(deadline: .now() + 2.0) { [weak self] in
            guard !handled else { return }
            handled = true
            if let token = observerToken {
                NotificationCenter.default.removeObserver(token)
                observerToken = nil
            }
            guard let self = self else { return }
            // One final check in case the session started just as we timed out.
            if self.captureSession.isRunning {
                self.startRecording(
                    enableAudio: enableAudio,
                    videoQuality: videoQuality,
                    completion: completion
                )
            } else {
                DispatchQueue.main.async { completion(CameraError.sessionNotRunning) }
            }
        }
    }

    /// Resolves the appropriate AVCaptureSession.Preset for the given VideoRecordingQuality,
    private func resolveRecordingPreset(for videoQuality: VideoRecordingQuality) -> AVCaptureSession.Preset {
        let preferredPresets: [AVCaptureSession.Preset]
        switch videoQuality {
        case .lowest:
            preferredPresets = [.low]
        case .sd:
            preferredPresets = [.vga640x480, .medium, .low]
        case .hd:
            preferredPresets = [.hd1280x720, .high, .medium]
        case .fhd:
            preferredPresets = [.hd1920x1080, .hd1280x720, .high]
        case .uhd:
            preferredPresets = [.hd4K3840x2160, .hd1920x1080, .hd1280x720, .high]
        case .highest:
            preferredPresets = [.hd4K3840x2160, .hd1920x1080, .hd1280x720, .high, .medium, .low]
        }

        for preset in preferredPresets where captureSession.canSetSessionPreset(preset) {
            return preset
        }

        return captureSession.sessionPreset
    }

    /// Restores the session preset to its previous value before recording if it was changed for recording.
    private func restoreSessionPreset() {
        if let previousPreset = sessionPresetBeforeRecording,
           captureSession.canSetSessionPreset(previousPreset) {
            captureSession.sessionPreset = previousPreset
        }
        sessionPresetBeforeRecording = nil
    }

    /// Adds microphone input to the capture session.
    ///
    /// - Throws: `CameraError.audioDeviceUnavailable` if no microphone is present,
    ///   `CameraError.audioInputAdditionFailed` if the session rejects the input,
    ///   or the underlying `AVCaptureDeviceInput` error if device configuration fails.
    private func addAudioInput() throws {
        // Check if audio input already exists; nothing to do if so.
        let hasAudioInput = captureSession.inputs.contains { input in
            (input as? AVCaptureDeviceInput)?.device.hasMediaType(.audio) == true
        }

        guard !hasAudioInput else { return }

        guard let microphone = AVCaptureDevice.default(for: .audio) else {
            throw CameraError.audioDeviceUnavailable
        }

        let audioInput = try AVCaptureDeviceInput(device: microphone)

        guard captureSession.canAddInput(audioInput) else {
            throw CameraError.audioInputAdditionFailed
        }

        captureSession.addInput(audioInput)
        recordingWithAudio = true
    }

    /// Removes microphone input from the capture session.
    private func removeAudioInput() {
        captureSession.inputs
            .compactMap { $0 as? AVCaptureDeviceInput }
            .filter { $0.device.hasMediaType(.audio) }
            .forEach { captureSession.removeInput($0) }
    }
}
