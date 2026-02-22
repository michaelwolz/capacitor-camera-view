import type { PermissionState, PluginListenerHandle } from '@capacitor/core';

/**
 * Main plugin interface for Capacitor Camera View functionality.
 *
 * @since 1.0.0
 */
export interface CameraViewPlugin {
  /**
   * Start the camera view with optional configuration.
   *
   * @param options - Configuration options for the camera session
   * @returns A promise that resolves when the camera has started
   *
   * @since 1.0.0
   */
  start(options?: CameraSessionConfiguration): Promise<void>;

  /**
   * Stop the camera view and release resources.
   *
   * @returns A promise that resolves when the camera has stopped
   *
   * @since 1.0.0
   */
  stop(): Promise<void>;

  /**
   * Check if the camera view is currently running.
   *
   * @returns A promise that resolves with an object containing the running state of the camera
   *
   * @since 1.0.0
   */
  isRunning(): Promise<IsRunningResponse>;

  /**
   * Capture a photo using the current camera configuration.
   *
   * @param options - Capture configuration options
   * @returns A promise that resolves with an object containing either a base64 encoded string or file path of the captured photo
   *
   * @since 1.0.0
   */
  capture<T extends CaptureOptions>(options: T): Promise<CaptureResponse<T>>;

  /**
   * Captures a frame from the current camera preview without using the full camera capture pipeline.
   *
   * Unlike `capture()` which may trigger hardware-level photo capture on native platforms,
   * this method quickly samples the current video stream. This is suitable computer vision or
   * simple snapshots where high fidelity is not required.
   *
   * On web this method does exactly the same as `capture()` as it only captures a frame from the video stream
   * because unfortunately [ImageCapture API](https://developer.mozilla.org/en-US/docs/Web/API/ImageCapture) is
   * not yet well supported on the web.
   *
   * @param options - Capture configuration options
   * @returns A promise that resolves with an object containing either a base64 encoded string or file path of the captured sample
   *
   * @since 1.0.0
   */
  captureSample<T extends CaptureOptions>(options: T): Promise<CaptureResponse<T>>;

  /**
   * Start recording video from the current camera.
   * Camera must be running. Throws if already recording.
   *
   * @param options - Optional recording configuration
   * @returns A promise that resolves when recording has started
   *
   * @since 2.2.0
   */
  startRecording(options?: VideoRecordingOptions): Promise<void>;

  /**
   * Stop the current video recording and return the result.
   * Throws if no recording is in progress.
   *
   * @returns A promise that resolves with the recorded video file path
   *
   * @since 2.2.0
   */
  stopRecording(): Promise<VideoRecordingResponse>;

  /**
   * Switch between front and back camera.
   *
   * @returns A promise that resolves when the camera has been flipped
   *
   * @since 1.0.0
   */
  flipCamera(): Promise<void>;

  /**
   * Get available camera devices for capturing photos.
   *
   * @returns A promise that resolves with an object containing an array of available capture devices
   *
   * @since 1.0.0
   */
  getAvailableDevices(): Promise<GetAvailableDevicesResponse>;

  /**
   * Get current zoom level information and available range.
   *
   * @remarks
   * Make sure the camera is properly initialized before calling this method. Otherwise, this might
   * lead to returning default values on android.
   *
   * @returns A promise that resolves with an object containing min, max and current zoom levels
   *
   * @since 1.0.0
   */
  getZoom(): Promise<GetZoomResponse>;

  /**
   * Set the camera zoom level.
   *
   * @param options - Zoom configuration options
   * @param options.level - The zoom level to set
   * @param options.ramp - Whether to animate the zoom level change, defaults to false (iOS only)
   * @returns A promise that resolves when the zoom level has been set
   *
   * @remarks
   * On web platforms, zoom functionality may be limited by browser support.
   * When native zoom is not available, a CSS-based zoom simulation is applied.
   *
   * @since 1.0.0
   */
  setZoom(options: { level: number; ramp?: boolean }): Promise<void>;

  /**
   * Get current flash mode setting.
   *
   * @returns A promise that resolves with an object containing the current flash mode
   *
   * @since 1.0.0
   */
  getFlashMode(): Promise<GetFlashModeResponse>;

  /**
   * Get supported flash modes for the current camera.
   *
   * @returns A promise that resolves with an object containing an array of supported flash modes
   *
   * @since 1.0.0
   */
  getSupportedFlashModes(): Promise<GetSupportedFlashModesResponse>;

  /**
   * Set the camera flash mode.
   *
   * @param options - Flash mode configuration options
   * @param options.mode - The flash mode to set
   * @returns A promise that resolves when the flash mode has been set
   *
   * @since 1.0.0
   */
  setFlashMode(options: { mode: FlashMode }): Promise<void>;

  /**
   * Check if the device supports torch (flashlight) functionality.
   *
   * @remarks
   * **Important**: You must call this method and verify torch availability before using
   * `setTorchMode()` or `getTorchMode()`. Calling torch methods on devices without
   * torch support will throw an exception.
   *
   * @returns A promise that resolves with an object containing torch availability status
   *
   * @since 1.2.0
   */
  isTorchAvailable(): Promise<IsTorchAvailableResponse>;

  /**
   * Get the current torch (flashlight) state.
   *
   * @remarks
   * **Important**: Call `isTorchAvailable()` first to ensure the device supports torch
   * functionality. This method will throw an exception if torch is not supported.
   *
   * @returns A promise that resolves with an object containing the current torch state
   *
   * @since 1.2.0
   */
  getTorchMode(): Promise<GetTorchModeResponse>;

  /**
   * Set the torch (flashlight) mode and intensity.
   *
   * @remarks
   * **Important**: Call `isTorchAvailable()` first to ensure the device supports torch
   * functionality. This method will throw an exception if torch is not supported.
   *
   * The torch provides continuous illumination, unlike flash which only activates during photo capture.
   * On iOS, you can control the torch intensity level. On Android, the torch is either on or off.
   *
   * @param options - Torch configuration options
   * @param options.enabled - Whether to enable or disable the torch
   * @param options.level - The torch intensity level (0.0 to 1.0, iOS only). Defaults to 1.0 when enabled
   * @returns A promise that resolves when the torch mode has been set
   *
   * @since 1.2.0
   */
  setTorchMode(options: { enabled: boolean; level?: number }): Promise<void>;

  /**
   * Check camera and microphone permission status without requesting permissions.
   *
   * @returns A promise that resolves with an object containing the camera and microphone permission status
   *
   * @since 1.0.0
   */
  checkPermissions(): Promise<PermissionStatus>;

  /**
   * Request camera and/or microphone permissions from the user.
   *
   * By default, only camera permission is requested. To also request microphone
   * permission (needed for video recording with audio), pass `{ permissions: ['camera', 'microphone'] }`.
   *
   * @param options - Optional object specifying which permissions to request
   * @returns A promise that resolves with an object containing the camera and microphone permission status
   *
   * @since 1.0.0
   */
  requestPermissions(options?: { permissions?: CameraPermissionType[] }): Promise<PermissionStatus>;

  /**
   * Listen for barcode detection events.
   * This event is emitted when a barcode is detected in the camera preview.
   *
   * @param eventName - The name of the event to listen for ('barcodeDetected')
   * @param listenerFunc - The callback function to execute when a barcode is detected
   * @returns A promise that resolves with an event subscription
   *
   * @since 1.0.0
   */
  addListener(
    eventName: 'barcodeDetected',
    listenerFunc: (data: BarcodeDetectionData) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Remove all listeners for this plugin.
   *
   * @param eventName - Optional event name to remove listeners for
   * @returns A promise that resolves when the listeners are removed
   *
   * @since 1.0.0
   */
  removeAllListeners(eventName?: string): Promise<void>;
}

// ------------------------------------------------------------------------------
// Camera Configuration Types
// ------------------------------------------------------------------------------

/**
 * Position options for the camera.
 * - 'front': Front-facing camera
 * - 'back': Rear-facing camera
 *
 * @since 1.0.0
 */
export type CameraPosition = 'front' | 'back';

/**
 * Flash mode options for the camera.
 * - 'off': Flash disabled
 * - 'on': Flash always on
 * - 'auto': Flash automatically enabled in low-light conditions
 *
 * @since 1.0.0
 */
export type FlashMode = 'off' | 'on' | 'auto';

/**
 * Represents a physical camera device on the device.
 *
 * @since 1.0.0
 */
export interface CameraDevice {
  /** The unique identifier of the camera device */
  id: string;

  /** The human-readable name of the camera device */
  name: string;

  /** The position of the camera device (front or back) */
  position: CameraPosition;

  /** The type of the camera device (e.g., wide, ultra-wide, telephoto) - iOS only */
  deviceType?: CameraDeviceType;
}

/**
 * Available camera device types for iOS.
 * Maps to AVCaptureDevice DeviceTypes in iOS.
 *
 * @see https://developer.apple.com/documentation/avfoundation/avcapturedevice/devicetype-swift.struct
 *
 * @since 1.0.0
 */
export type CameraDeviceType =
  /** builtInWideAngleCamera - standard camera */
  | 'wideAngle'
  /** builtInUltraWideCamera - 0.5x zoom level */
  | 'ultraWide'
  /** builtInTelephotoCamera - 2x/3x zoom level */
  | 'telephoto'
  /** builtInDualCamera - wide + telephoto combination */
  | 'dual'
  /** builtInDualWideCamera - wide + ultraWide combination */
  | 'dualWide'
  /** builtInTripleCamera - wide + ultraWide + telephoto */
  | 'triple'
  /** builtInTrueDepthCamera - front-facing camera with depth sensing */
  | 'trueDepth';

/**
 * Supported barcode types for detection.
 * Specifying only the barcode types you need can improve performance
 * and reduce battery consumption.
 *
 * @since 2.1.0
 */
export type BarcodeType =
  /** QR Code */
  | 'qr'
  /** Code 128 barcode */
  | 'code128'
  /** Code 39 barcode */
  | 'code39'
  /** Code 39 Mod 43 barcode */
  | 'code39Mod43'
  /** Code 93 barcode */
  | 'code93'
  /** EAN-8 barcode */
  | 'ean8'
  /** EAN-13 barcode */
  | 'ean13'
  /** Interleaved 2 of 5 barcode */
  | 'interleaved2of5'
  /** ITF-14 barcode */
  | 'itf14'
  /** PDF417 barcode */
  | 'pdf417'
  /** Aztec code */
  | 'aztec'
  /** Data Matrix code */
  | 'dataMatrix'
  /** UPC-E barcode */
  | 'upce';

/**
 * Configuration options for starting a camera session.
 *
 * @since 1.0.0
 */
export interface CameraSessionConfiguration {
  /**
   * Enables the barcode detection functionality
   * @default false
   */
  enableBarcodeDetection?: boolean;

  /**
   * Specific barcode types to detect. If not provided, all supported types are detected.
   * Specifying only the types you need can significantly improve performance and reduce
   * battery consumption, especially on mobile devices.
   *
   * @example ['qr', 'code128'] // Only detect QR codes and Code 128 barcodes
   * @default undefined - all supported types are detected
   * @since 2.1.0
   */
  barcodeTypes?: BarcodeType[];

  /**
   * Position of the camera to use
   * @default 'back'
   */
  position?: CameraPosition;

  /**
   * Specific device ID of the camera to use
   * If provided, takes precedence over position
   */
  deviceId?: string;

  /**
   * Whether to use the triple camera if available (iPhone Pro models only)
   * @default false
   */
  useTripleCameraIfAvailable?: boolean;

  /**
   * Ordered list of preferred camera device types to use (iOS only).
   * The system will attempt to use the first available camera type in the list.
   * If position is also provided, the system will use the first available camera type
   * that matches the position and is in the list.
   *
   * This will fallback to the default camera type if none of the preferred types are available.
   *
   * @example [CameraDeviceType.WideAngle, CameraDeviceType.UltraWide, CameraDeviceType.Telephoto]
   * @default undefined - system will decide based on position/deviceId
   */
  preferredCameraDeviceTypes?: CameraDeviceType[];

  /**
   * The initial zoom factor to use
   * @default 1.0
   */
  zoomFactor?: number;

  /**
   * Optional HTML ID of the container element where the camera view should be rendered.
   * If not provided, the camera view will be appended to the document body. Web only.
   * @example 'cameraContainer'
   */
  containerElementId?: string;
}

/**
 * Configuration options for capturing photos and samples.
 *
 * @since 1.1.0
 */
export interface CaptureOptions {
  /**
   * The JPEG quality of the captured photo/sample on a scale of 0-100
   * @since 1.1.0
   */
  quality: number;

  /**
   * If true, saves to a temporary file and returns the web path instead of base64.
   * The web path can be used to set the src attribute of an image for efficient loading and rendering.
   * This reduces the data that needs to be transferred over the bridge, which can improve performance
   * especially for high-resolution images.
   * @default false
   * @since 1.1.0
   */
  saveToFile?: boolean;
}

/**
 * Configuration options for video recording.
 * @since 2.2.0
 */
export interface VideoRecordingOptions {
  /**
   * Whether to record audio with the video.
   * Requires microphone permission.
   * @default false
   * @since 2.2.0
   */
  enableAudio?: boolean;
}

/**
 * Response from stopping a video recording.
 * @since 2.2.0
 */
export interface VideoRecordingResponse {
  /**
   * Web-accessible path to the recorded video file.
   * On web, this is a blob URL.
   * On iOS/Android, this is a path accessible via Capacitor's filesystem.
   * @since 2.2.0
   */
  webPath: string;
}

// ------------------------------------------------------------------------------
// Response Interfaces
// ------------------------------------------------------------------------------

/**
 * Response for checking if the camera view is running.
 *
 * @since 1.0.0
 */
export interface IsRunningResponse {
  /** Indicates if the camera view is currently active and running */
  isRunning: boolean;
}

/**
 * Response for capturing a photo
 * This will contain either a base64 encoded string or a web path to the captured photo,
 * depending on the `saveToFile` option in the CaptureOptions.
 * @since 1.0.0
 */
export type CaptureResponse<T extends CaptureOptions = CaptureOptions> = T['saveToFile'] extends true
  ? {
      /** The web path to the captured photo that can be used to set the src attribute of an image for efficient loading and rendering (when saveToFile is true) */
      webPath: string;
    }
  : {
      /** The base64 encoded string of the captured photo (when saveToFile is false or undefined) */
      photo: string;
    };

/**
 * Response for getting available camera devices.
 *
 * @since 1.0.0
 */
export interface GetAvailableDevicesResponse {
  /** An array of available camera devices */
  devices: CameraDevice[];
}

/**
 * Response for getting zoom level information.
 *
 * @since 1.0.0
 */
export interface GetZoomResponse {
  /** The minimum zoom level supported */
  min: number;

  /** The maximum zoom level supported */
  max: number;

  /** The current zoom level */
  current: number;
}

/**
 * Response for getting the current flash mode.
 *
 * @since 1.0.0
 */
export interface GetFlashModeResponse {
  /** The current flash mode setting */
  flashMode: FlashMode;
}

/**
 * Response for getting supported flash modes.
 *
 * @since 1.0.0
 */
export interface GetSupportedFlashModesResponse {
  /** An array of flash modes supported by the current camera */
  flashModes: FlashMode[];
}

/**
 * Response for checking torch availability.
 *
 * @since 1.2.0
 */
export interface IsTorchAvailableResponse {
  /** Indicates if the device supports torch (flashlight) functionality */
  available: boolean;
}

/**
 * Response for getting the current torch mode.
 *
 * @since 1.2.0
 */
export interface GetTorchModeResponse {
  /** Indicates if the torch is currently enabled */
  enabled: boolean;
  /** The current torch intensity level (0.0 to 1.0, iOS only). Always 1.0 on Android when enabled */
  level: number;
}

/**
 * Data for a detected barcode.
 *
 * @since 1.0.0
 */
export interface BarcodeDetectionData {
  /** The decoded string value of the barcode */
  value: string;

  /** The display value of the barcode (may differ from the raw value) */
  displayValue?: string;

  /** The type/format of the barcode (e.g., 'qr', 'code128', etc.) */
  type: string;

  /** The bounding rectangle of the barcode in the camera frame. */
  boundingRect: BoundingRect;
}

/**
 * Rectangle defining the boundary of the barcode in the camera frame.
 * Coordinates are normalized between 0 and 1 relative to the camera frame.
 *
 * @since 1.0.0
 */
export interface BoundingRect {
  /** X-coordinate of the top-left corner */
  x: number;
  /** Y-coordinate of the top-left corner */
  y: number;
  /** Width of the bounding rectangle (should match the actual width of the barcode) */
  width: number;
  /** Height of the bounding rectangle (should match the actual height of the barcode) */
  height: number;
}

/**
 * Permission types that can be requested.
 * - 'camera': Camera access permission
 * - 'microphone': Microphone access permission (needed for video recording with audio)
 *
 * @since 2.2.0
 */
export type CameraPermissionType = 'camera' | 'microphone';

/**
 * Response for the camera and microphone permission status.
 *
 * @since 1.0.0
 */
export interface PermissionStatus {
  /** The state of the camera permission */
  camera: PermissionState;
  /** The state of the microphone permission */
  microphone: PermissionState;
}
