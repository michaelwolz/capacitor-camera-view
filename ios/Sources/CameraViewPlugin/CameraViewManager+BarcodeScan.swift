import AVFoundation
import CoreImage
import Foundation

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
            newOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
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
            BarcodeDetectedEvent(
                value: barcodeValue,
                rawBytes: rawBytes,
                type: barcodeType,
                boundingRect: boundingRect
            )
        )
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
