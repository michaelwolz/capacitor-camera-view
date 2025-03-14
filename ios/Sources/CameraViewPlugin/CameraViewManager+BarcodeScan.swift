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
            .upce,
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

        let foo = videoPreviewLayer.transformedMetadataObject(for: metadataObject)

        let boundingRect: [String: Double] = [
            "x": Double(foo!.bounds.origin.x),
            "y": Double(foo!.bounds.origin.y),
            "width": Double(foo!.bounds.width),
            "height": Double(foo!.bounds.height),
        ]

        let barcodeData: [String: Any] = [
            "value": barcodeValue,
            "type": barcodeType,
            "boundingRect": boundingRect,
        ]

        NotificationCenter.default.post(
            name: Notification.Name("barcodeDetected"),
            object: nil,
            userInfo: barcodeData
        )
    }
}
