import AVFoundation
import CoreImage
import Foundation
import QuartzCore

/// Suppression window (seconds) during which a repeat of the same barcode
/// (identical value + type) is not re-emitted. A genuinely new code still emits
/// immediately. Kept consistent with the Android and web implementations.
private let barcodeSuppressionWindow: TimeInterval = 0.5

/// Once the per-key dedupe map grows past this size, expired entries are pruned
/// so a long session scanning many different codes stays bounded. Kept
/// consistent with the Android and web implementations.
private let barcodeDedupeMapPruneThreshold = 64

extension CameraViewManager: AVCaptureMetadataOutputObjectsDelegate {
    /// Set up metadata output for the capture session in case it's not configured yet
    /// Make sure to call `captureSession.beginConfiguration` before calling this
    ///
    /// - Parameter barcodeTypes: Optional array of specific barcode types to detect.
    ///                          If nil, all supported types are detected (backwards compatible).
    /// - Throws: An error if the output cannot be set.
    internal func setupMetadataOutput(barcodeTypes: [AVMetadataObject.ObjectType]? = nil) throws {
        let requestedBarcodeTypes = barcodeTypes ?? ALL_SUPPORTED_BARCODE_TYPES

        let metadataOutput: AVCaptureMetadataOutput

        if let existingOutput = captureSession.outputs.first(where: { $0 is AVCaptureMetadataOutput }) as? AVCaptureMetadataOutput {
            metadataOutput = existingOutput
        } else {
            let newOutput = AVCaptureMetadataOutput()
            if !captureSession.canAddOutput(newOutput) {
                throw CameraError.outputAdditionFailed
            }

            captureSession.addOutput(newOutput)
            newOutput.setMetadataObjectsDelegate(self, queue: barcodeMetadataQueue)
            metadataOutput = newOutput
        }

        let supportedTypes = Set(metadataOutput.availableMetadataObjectTypes)
        let resolvedTypes = requestedBarcodeTypes.filter { supportedTypes.contains($0) }

        if metadataOutput.metadataObjectTypes != resolvedTypes {
            // Update the metadata output with the resolved types only if they differ from the current configuration
            metadataOutput.metadataObjectTypes = resolvedTypes
        }
    }

    /// Remove the metadata output if in case it is already configured, e.g. because
    /// the camera is restarted with a different setting where barcode detection was disabled
    /// again
    internal func removeMetadataOutput() {
        if let metadataOutput = captureSession.outputs.first(where: { $0 is AVCaptureMetadataOutput }) {
            captureSession.removeOutput(metadataOutput)
        }
        resetBarcodeDedupeState()
    }

    /// Clears the per-key dedupe map so a freshly (re)started detection session
    /// emits immediately. Hops onto `barcodeMetadataQueue` because the map is
    /// confined to that queue.
    internal func resetBarcodeDedupeState() {
        barcodeMetadataQueue.async { [weak self] in
            self?.recentBarcodeEmitTimes.removeAll()
        }
    }

    /// Delegate method called when metadata objects are detected in the camera feed
    ///
    /// This method processes barcode data detected in the camera stream. When a barcode is detected,
    /// it extracts the barcode value and type, then emits a Capacitor event with the barcode data.
    ///
    /// - Parameters:
    ///   - output: The metadata output object that captured the data.
    ///   - metadataObjects: An array of detected metadata objects, potentially including barcodes.
    ///   - connection: The connection through which the metadata objects were captured.
    public func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard let metadataObject = metadataObjects.first as? AVMetadataMachineReadableCodeObject
        else {
            return
        }

        let barcodeValue = metadataObject.stringValue ?? ""
        let rawBytes = getRawBytes(from: metadataObject)

        if barcodeValue.isEmpty && rawBytes == nil {
            return
        }

        // Normalize onto the shared BarcodeType vocabulary so the emitted `type`
        // matches web and Android.
        let barcodeType = convertToStringBarcodeType(metadataObject.type) ?? metadataObject.type.rawValue

        // Suppress re-emission of the same code within the suppression window so
        // a static barcode in view produces a bounded event rate instead of one
        // event per frame. Timestamps are tracked per key so multiple codes in
        // frame can't defeat the window by alternating. The raw-bytes hash is
        // part of the key so two distinct binary-only codes (empty `stringValue`)
        // don't suppress each other. Runs on the serial `barcodeMetadataQueue`,
        // so no locking is needed.
        let rawBytesKeyComponent = rawBytes.map { String($0.hashValue) } ?? ""
        let barcodeKey = "\(barcodeType)\u{0}\(barcodeValue)\u{0}\(rawBytesKeyComponent)"
        // Monotonic clock: a backward wall-clock jump must not extend the window.
        let now = CACurrentMediaTime()
        if let lastEmit = recentBarcodeEmitTimes[barcodeKey], now - lastEmit < barcodeSuppressionWindow {
            return
        }

        // Prune expired entries once the map grows, keeping it bounded during
        // long sessions that scan many different codes.
        if recentBarcodeEmitTimes.count > barcodeDedupeMapPruneThreshold {
            recentBarcodeEmitTimes = recentBarcodeEmitTimes.filter {
                now - $0.value < barcodeSuppressionWindow
            }
        }
        // Recorded before the main-queue hop below so the map is only ever
        // touched from `barcodeMetadataQueue`.
        recentBarcodeEmitTimes[barcodeKey] = now

        // `videoPreviewLayer` is a `CALayer` mutated concurrently by the main
        // thread, so every layer access has to be main-confined. Only the cheap
        // parse / dedupe work above stays on the private queue.
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            // Transform the metadata object into the preview layer's coordinate
            // space, which equals the webview's, so the bounding box can be used
            // as pixel coordinates directly.
            guard let transformedMetadataObject = self.videoPreviewLayer.transformedMetadataObject(for: metadataObject)
            else {
                return
            }

            let boundingRect = BarcodeDetectedEvent.BoundingRect(
                x: Double(transformedMetadataObject.bounds.origin.x),
                y: Double(transformedMetadataObject.bounds.origin.y),
                width: Double(transformedMetadataObject.bounds.width),
                height: Double(transformedMetadataObject.bounds.height)
            )

            self.eventEmitter.emitBarcodeDetected(
                BarcodeDetectedEvent(
                    value: barcodeValue,
                    rawBytes: rawBytes,
                    type: barcodeType,
                    boundingRect: boundingRect
                )
            )
        }
    }

    private func getRawBytes(from metadataObject: AVMetadataMachineReadableCodeObject) -> [UInt8]? {
        guard let descriptor = metadataObject.descriptor else {
            return nil
        }

        switch descriptor {
        case let descriptor as CIQRCodeDescriptor:
            return [UInt8](descriptor.errorCorrectedPayload)
        case let descriptor as CIAztecCodeDescriptor:
            return [UInt8](descriptor.errorCorrectedPayload)
        case let descriptor as CIPDF417CodeDescriptor:
            return [UInt8](descriptor.errorCorrectedPayload)
        case let descriptor as CIDataMatrixCodeDescriptor:
            return [UInt8](descriptor.errorCorrectedPayload)
        default:
            return nil
        }
    }
}
