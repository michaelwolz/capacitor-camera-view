import AVFoundation
import Foundation

extension CameraViewManager: AVCaptureMetadataOutputObjectsDelegate {
    /// Set up metadata output for the capture session in case it's not configured yet
    /// Make sure to call `captureSession.beginConfiguration` before calling this
    ///
    /// - Parameter barcodeTypes: Optional array of specific barcode types to detect.
    ///                          If nil, all supported types are detected (backwards compatible).
    /// - Throws: An error if the output cannot be set.
    internal func setupMetadataOutput(barcodeTypes: [AVMetadataObject.ObjectType]? = nil) throws {
        let metadataOutput = AVCaptureMetadataOutput()

        if (captureSession.outputs.contains { $0 is AVCaptureMetadataOutput }) {
            // Nothing todo, we already have an output
            return
        }

        if !captureSession.canAddOutput(metadataOutput) {
            throw CameraError.outputAdditionFailed
        }

        captureSession.addOutput(metadataOutput)

        metadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)

        // Use provided barcode types or fall back to all supported types
        // This maintains backwards compatibility while allowing optimization
        metadataOutput.metadataObjectTypes = barcodeTypes ?? ALL_SUPPORTED_BARCODE_TYPES
    }

    /// Remove the metadata output if in case it is already configured, e.g. because
    /// the camera is restarted with a diffrent setting where barcode detection was disabled
    /// again
    internal func removeMetadataOutput() {
        if let metadataOutput = captureSession.outputs.first(where: { $0 is AVCaptureMetadataOutput }) {
            captureSession.removeOutput(metadataOutput)
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
        guard let barcodeValue = metadataObject.stringValue else { return }

        let barcodeType = metadataObject.type.rawValue

        // Transform the metadata object to the coordinate space of the video preview layer
        // This is necessary to get the correct bounding box for the detected barcode
        // Which in our case should always equal to the device's screen
        // This way we can simply use pixel coordinates to get the bounding box of the detected barcode and easily show it in the webview
        guard let transformedMetadataObject = videoPreviewLayer.transformedMetadataObject(for: metadataObject)
        else {
            return
        }

        let boundingRect = BarcodeDetectedEvent.BoundingRect(
            x: Double(transformedMetadataObject.bounds.origin.x),
            y: Double(transformedMetadataObject.bounds.origin.y),
            width: Double(transformedMetadataObject.bounds.width),
            height: Double(transformedMetadataObject.bounds.height)
        )

        eventEmitter.emitBarcodeDetected(
            BarcodeDetectedEvent(value: barcodeValue, type: barcodeType, boundingRect: boundingRect)
        )
    }
}
