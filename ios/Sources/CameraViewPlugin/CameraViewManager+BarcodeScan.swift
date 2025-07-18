import AVFoundation
import Foundation

extension CameraViewManager: AVCaptureMetadataOutputObjectsDelegate {
    /// Set up metadata output for the capture session in case it's not configured yet
    /// Make sure to call `captureSession.beginConfiguration` before calling this
    ///
    /// - Throws: An error if the output cannot be set.
    internal func setupMetadataOutput() throws {
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

        // Set all available barcode types
        metadataOutput.metadataObjectTypes = [
            .qr,
            .code128,
            .code39,
            .code39Mod43,
            .code93,
            .ean8,
            .ean13,
            .interleaved2of5,
            .itf14,
            .pdf417,
            .aztec,
            .dataMatrix,
            .upce
        ]
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

        let boundingRect: [String: Double] = [
            "x": Double(transformedMetadataObject.bounds.origin.x),
            "y": Double(transformedMetadataObject.bounds.origin.y),
            "width": Double(transformedMetadataObject.bounds.width),
            "height": Double(transformedMetadataObject.bounds.height)
        ]

        NotificationCenter.default.post(
            name: Notification.Name("barcodeDetected"),
            object: nil,
            userInfo: [
                "value": barcodeValue,
                "type": barcodeType,
                "boundingRect": boundingRect
            ]
        )
    }
}
