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
   * @param options.quality - The JPEG quality of the captured photo on a scale of 0-100
   * @returns A promise that resolves with an object containing a base64 encoded string of the captured photo
   *
   * @since 1.0.0
   */
  capture(options: { quality: number }): Promise<CaptureResponse>;

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
   * @param options.quality - The JPEG quality of the captured sample on a scale of 0-100
   * @returns A promise that resolves with an object containing a base64 encoded string of the captured sample
   *
   * @since 1.0.0
   */
  captureSample(options: { quality: number }): Promise<CaptureResponse>;

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
   * Check camera permission status without requesting permissions.
   *
   * @returns A promise that resolves with an object containing the camera permission status
   *
   * @since 1.0.0
   */
  checkPermissions(): Promise<PermissionStatus>;

  /**
   * Request camera permission from the user.
   *
   * @returns A promise that resolves with an object containing the camera permission status
   *
   * @since 1.0.0
   */
  requestPermissions(): Promise<PermissionStatus>;

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
 * Response for capturing a photo.
 *
 * @since 1.0.0
 */
export interface CaptureResponse {
  /** The base64 encoded string of the captured photo */
  photo: string;
}

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
 * Response for the camera permission status.
 *
 * @since 1.0.0
 */
export interface PermissionStatus {
  /** The state of the camera permission */
  camera: PermissionState;
}
