import AVFoundation
import ImageIO
import os
import UniformTypeIdentifiers

/// Shared logger for camera plugin diagnostics (visible in Console.app / `log stream`),
/// used instead of `print()` so failures are visible in production logs.
internal let cameraViewLogger = Logger(subsystem: "com.michaelwolz.capacitorcameraview", category: "CameraViewManager")

// MARK: - Camera Type Conversion

/// Converts string camera type identifiers from JavaScript to native AVCaptureDevice.DeviceType values
/// - Parameter stringType: The string camera type from JavaScript
/// - Returns: The corresponding AVCaptureDevice.DeviceType or nil if no match
public func convertToNativeCameraType(_ stringType: String) -> AVCaptureDevice.DeviceType? {
    switch stringType {
    case "wideAngle":
        return .builtInWideAngleCamera
    case "ultraWide":
        return .builtInUltraWideCamera
    case "telephoto":
        return .builtInTelephotoCamera
    case "dual":
        return .builtInDualCamera
    case "dualWide":
        return .builtInDualWideCamera
    case "triple":
        return .builtInTripleCamera
    case "trueDepth":
        return .builtInTrueDepthCamera
    default:
        return nil
    }
}

/// Converts AVCaptureDevice.DeviceType to JavaScript string value
/// - Parameter deviceType: The AVCaptureDevice.DeviceType
/// - Returns: The corresponding string or nil if no match
public func convertToStringCameraType(_ deviceType: AVCaptureDevice.DeviceType) -> String? {
    switch deviceType {
    case .builtInWideAngleCamera:
        return "wideAngle"
    case .builtInUltraWideCamera:
        return "ultraWide"
    case .builtInTelephotoCamera:
        return "telephoto"
    case .builtInDualCamera:
        return "dual"
    case .builtInDualWideCamera:
        return "dualWide"
    case .builtInTripleCamera:
        return "triple"
    case .builtInTrueDepthCamera:
        return "trueDepth"
    default:
        return nil
    }
}

/// Converts an array of string camera type identifiers to native AVCaptureDevice.DeviceType values
/// - Parameter stringTypes: Array of string camera types from JavaScript
/// - Returns: Array of AVCaptureDevice.DeviceType values (invalid types are filtered out)
public func convertToNativeCameraTypes(_ stringTypes: [String]) -> [AVCaptureDevice.DeviceType] {
    return stringTypes.compactMap { convertToNativeCameraType($0) }
}

// MARK: - Image Re-encoding

/// Re-encodes JPEG data at a lower compression quality while preserving the
/// original image metadata (EXIF, TIFF, GPS, orientation, etc.).
///
/// Uses ImageIO directly instead of round-tripping through `UIImage`/
/// `UIImage.jpegData(compressionQuality:)`, which silently drops all metadata
/// from the source JPEG.
///
/// - Parameters:
///   - data: The source JPEG data.
///   - compressionQuality: The lossy compression quality (0.0-1.0).
/// - Returns: The re-encoded JPEG data, or nil if the source data or
///   destination could not be created.
public func reencodeJPEG(data: Data, compressionQuality: CGFloat) -> Data? {
    guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
        return nil
    }

    let destinationData = NSMutableData()
    guard let destination = CGImageDestinationCreateWithData(
        destinationData,
        UTType.jpeg.identifier as CFString,
        1,
        nil
    ) else {
        return nil
    }

    // Start from the source image's own metadata so EXIF/GPS/orientation
    // survive the re-encode, then layer the compression quality on top.
    var properties = (CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]) ?? [:]
    properties[kCGImageDestinationLossyCompressionQuality] = compressionQuality

    CGImageDestinationAddImageFromSource(destination, source, 0, properties as CFDictionary)

    guard CGImageDestinationFinalize(destination) else {
        return nil
    }

    return destinationData as Data
}

// MARK: - Barcode Type Conversion

/// All supported barcode types for detection.
/// These are enabled by default when no specific types are configured.
///
/// Note: there is no UPC-A entry. AVFoundation has no UPC-A metadata object
/// type and reports UPC-A codes as `.ean13` (a UPC-A value is an EAN-13 with a
/// leading `0`), so on iOS the `upcA` union member is neither requestable nor
/// emitted — such codes arrive as `ean13`. `.codabar` is available since iOS
/// 15.4; the deployment target is 16, so no availability guard is needed.
public let ALL_SUPPORTED_BARCODE_TYPES: [AVMetadataObject.ObjectType] = [
    .qr,
    .code128,
    .code39,
    .code39Mod43,
    .code93,
    .codabar,
    .ean8,
    .ean13,
    .interleaved2of5,
    .itf14,
    .pdf417,
    .aztec,
    .dataMatrix,
    .upce
]

/// Converts a string barcode type identifier from JavaScript to native AVMetadataObject.ObjectType
/// - Parameter stringType: The string barcode type from JavaScript
/// - Returns: The corresponding AVMetadataObject.ObjectType or nil if no match
public func convertToNativeBarcodeType(_ stringType: String) -> AVMetadataObject.ObjectType? {
    switch stringType {
    case "qr":
        return .qr
    case "code128":
        return .code128
    case "code39":
        return .code39
    case "code39Mod43":
        return .code39Mod43
    case "code93":
        return .code93
    case "codabar":
        return .codabar
    // Note: "upcA" is intentionally unmapped. AVFoundation has no UPC-A metadata
    // type and reports UPC-A codes as .ean13, so it cannot be requested on iOS.
    case "ean8":
        return .ean8
    case "ean13":
        return .ean13
    case "interleaved2of5":
        return .interleaved2of5
    case "itf14":
        return .itf14
    case "pdf417":
        return .pdf417
    case "aztec":
        return .aztec
    case "dataMatrix":
        return .dataMatrix
    case "upce":
        return .upce
    default:
        return nil
    }
}

/// Converts AVMetadataObject.ObjectType to JavaScript string value
/// - Parameter barcodeType: The AVMetadataObject.ObjectType
/// - Returns: The corresponding string or nil if no match
public func convertToStringBarcodeType(_ barcodeType: AVMetadataObject.ObjectType) -> String? {
    switch barcodeType {
    case .qr:
        return "qr"
    case .code128:
        return "code128"
    case .code39:
        return "code39"
    case .code39Mod43:
        return "code39Mod43"
    case .code93:
        return "code93"
    case .codabar:
        return "codabar"
    case .ean8:
        return "ean8"
    case .ean13:
        return "ean13"
    case .interleaved2of5:
        return "interleaved2of5"
    case .itf14:
        return "itf14"
    case .pdf417:
        return "pdf417"
    case .aztec:
        return "aztec"
    case .dataMatrix:
        return "dataMatrix"
    case .upce:
        return "upce"
    default:
        return nil
    }
}

/// Converts an array of string barcode type identifiers to native AVMetadataObject.ObjectType values
/// - Parameter stringTypes: Array of string barcode types from JavaScript
/// - Returns: Array of AVMetadataObject.ObjectType values (invalid types are filtered out)
public func convertToNativeBarcodeTypes(_ stringTypes: [String]) -> [AVMetadataObject.ObjectType] {
    return stringTypes.compactMap { convertToNativeBarcodeType($0) }
}
