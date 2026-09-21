import type { PermissionState, PluginListenerHandle } from '@capacitor/core';

/**
 * Main plugin interface for Capacitor Camera View functionality.
 *
 * When a method below rejects, the resulting error carries a `code` property
 * set to one of the stable `CameraErrorCode` strings so consumers can
 * `switch` on `error.code` instead of matching on the human-readable message.
 * See the `CameraErrorCode` type for the full vocabulary, including the one
 * divergence on web for methods with no web implementation.
 *
 * @since 1.0.0
 */
export interface CameraViewPlugin {
  /**
   * Start the camera view with optional configuration.
   *
   * Rejects if a camera session is already running. Call `stop()` first if you need to
   * start a new session with different options.
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
  capture<T extends CaptureOptions = CaptureOptions & { saveToFile?: undefined }>(
    options?: T,
  ): Promise<CaptureResponse<T>>;

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
  captureSample<T extends CaptureOptions = CaptureOptions & { saveToFile?: undefined }>(
    options?: T,
  ): Promise<CaptureResponse<T>>;

  /**
   * Start recording video from the current camera.
   * Camera must be running. Throws if already recording.
   *
   * @param options - Optional recording configuration
   * @returns A promise that resolves when recording has started
   *
   * @since 2.3.0
   */
  startRecording(options?: VideoRecordingOptions): Promise<void>;

  /**
   * Stop the current video recording and return the result.
   * Throws if no recording is in progress.
   *
   * @returns A promise that resolves with the recorded video file path
   *
   * @since 2.3.0
   */
  stopRecording(): Promise<VideoRecordingResponse>;

  /**
   * Switch between front and back camera.
   *
   * Rejects with `RECORDING_ALREADY_IN_PROGRESS` while a video recording is active, on
   * iOS, Android, and web alike: swapping the camera input/device mid-recording would
   * either drop the audio track or interrupt the recording outright, so the recording is
   * left intact and must be stopped before flipping.
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
   * On iOS, when the camera is a virtual device (e.g. the triple camera enabled via
   * `useTripleCameraIfAvailable`), the returned values are in the device's raw zoom domain and are
   * not UI multipliers like "0.5x"/"1x"/"2x". A `min` of `1.0` corresponds to the widest constituent
   * lens (the ultra-wide "0.5x" lens), so a session started at the default zoom reports a `current`
   * of the wide-lens switch-over factor (typically `2.0`) rather than `1.0`. Treat these numbers as
   * device-relative and derive the usable range from `min`/`max` instead of assuming `1.0` is the
   * default.
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
   * On iOS virtual devices (e.g. the triple camera) `level` is a raw device zoom factor, not a UI
   * multiplier. Derive valid values from the `min`/`max` returned by `getZoom()` rather than assuming
   * `1.0` maps to the wide "1x" lens.
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
   * Focus and meter the camera at a specific point (tap-to-focus).
   *
   * Because the WebView sits above the native camera preview and consumes every
   * touch, the native preview can never receive tap gestures itself. Instead,
   * the app catches the tap in the DOM and forwards its coordinates here; since
   * the native preview is always rendered fullscreen behind the WebView, the
   * mapping to the sensor is deterministic.
   *
   * The camera runs a one-shot focus/exposure at the given point and then
   * automatically restores continuous auto-focus/auto-exposure (immediately on a
   * subsequent tap, or after a short timeout), so focus is never left
   * permanently locked.
   *
   * @param options - The point to focus on
   * @param options.x - The horizontal coordinate in CSS/viewport pixels, measured
   *   from the left edge of the viewport. This is the same coordinate space the
   *   plugin emits for barcode `boundingRect`, just in the opposite direction.
   * @param options.y - The vertical coordinate in CSS/viewport pixels, measured
   *   from the top edge of the viewport.
   * @returns A promise that resolves when the focus/metering point has been applied
   *
   * @remarks
   * On fixed-focus cameras (e.g. some front cameras) the plugin degrades to
   * exposure-only metering where possible. If neither focus nor exposure metering
   * at a point is supported, the promise rejects with the `FOCUS_NOT_SUPPORTED`
   * error code. Rejects with `SESSION_NOT_RUNNING` when the camera is not running.
   *
   * Not supported on web: this method rejects with an `unimplemented` error there,
   * because the `pointsOfInterest` media-track constraint has effectively no
   * browser support.
   *
   * @since 3.0.0
   */
  setFocusPoint(options: { x: number; y: number }): Promise<void>;

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
   * You can control the torch intensity level on both iOS and, on API 35+ (Android 15+) devices
   * with multi-level torch hardware, Android. On older Android versions or single-level torch
   * hardware, `level` is best-effort and ignored - the torch is simply switched on or off.
   *
   * @param options - Torch configuration options
   * @param options.enabled - Whether to enable or disable the torch
   * @param options.level - The torch intensity level (0.0 to 1.0). Defaults to 1.0 when enabled
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
   * @remarks
   * Events are rate-controlled to avoid flooding the bridge. Repeated detections
   * of the same barcode (identical `value` and `type`) are suppressed while the
   * code stays in view: after an initial event, the same code re-emits at most
   * once per ~500 ms suppression window. Pointing the camera at a different
   * barcode (a different `value` or `type`) emits immediately rather than waiting
   * for the window to elapse. This behavior is consistent across iOS, Android,
   * and web.
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
   * Listen for camera interruption events.
   *
   * Emitted when the capture session is interrupted by the system, for example
   * an incoming phone call, another app claiming the camera or microphone,
   * losing the camera in iPad Split View, or system pressure. The preview
   * typically freezes for the duration of the interruption.
   *
   * @remarks
   * Currently emitted on iOS only. Android and web will follow.
   *
   * @param eventName - The name of the event to listen for ('cameraInterrupted')
   * @param listenerFunc - The callback function to execute when the camera is interrupted
   * @returns A promise that resolves with an event subscription
   *
   * @since 3.0.0
   */
  addListener(
    eventName: 'cameraInterrupted',
    listenerFunc: (data: CameraInterruptedData) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Listen for camera resume events.
   *
   * Emitted when a previous interruption ends and the capture session resumes,
   * for example after an incoming phone call finishes. Pair this with
   * `cameraInterrupted` to update your UI when the preview recovers.
   *
   * @remarks
   * Currently emitted on iOS only. Android and web will follow.
   *
   * @param eventName - The name of the event to listen for ('cameraResumed')
   * @param listenerFunc - The callback function to execute when the camera resumes
   * @returns A promise that resolves with an event subscription
   *
   * @since 3.0.0
   */
  addListener(eventName: 'cameraResumed', listenerFunc: () => void): Promise<PluginListenerHandle>;

  /**
   * Listen for camera runtime error events.
   *
   * Emitted when the capture session hits a runtime error. When the underlying
   * media services are reset, the plugin restarts the session automatically, so
   * this event is primarily informational for logging and diagnostics.
   *
   * @remarks
   * Currently emitted on iOS only. Android and web will follow.
   *
   * @param eventName - The name of the event to listen for ('cameraRuntimeError')
   * @param listenerFunc - The callback function to execute when a runtime error occurs
   * @returns A promise that resolves with an event subscription
   *
   * @since 3.0.0
   */
  addListener(
    eventName: 'cameraRuntimeError',
    listenerFunc: (data: CameraRuntimeErrorData) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Remove all listeners for this plugin.
   *
   * @remarks
   * This removes *every* listener registered on this plugin instance, regardless of event
   * name, on iOS, Android, and web. There is no way to remove listeners for a single event
   * name only; if you need that, keep track of the `PluginListenerHandle` returned by
   * `addListener()` and call `remove()` on it instead.
   *
   * @returns A promise that resolves when the listeners are removed
   *
   * @since 1.0.0
   */
  removeAllListeners(): Promise<void>;
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
 * Video recording quality presets.
 *
 * @remarks
 * On iOS this maps to `AVCaptureSession.Preset` values.
 * On Android this maps to CameraX `QualitySelector` values.
 *
 * @since 2.3.0
 */
export type VideoRecordingQuality = 'lowest' | 'sd' | 'hd' | 'fhd' | 'uhd' | 'highest';

/**
 * Sensor aspect ratio for a camera session, applied consistently to both the
 * live preview stream and photo capture.
 * - '4:3': The native photo aspect ratio of most mobile camera sensors
 * - '16:9': The typical video aspect ratio
 *
 * @since 3.0.0
 */
export type CameraAspectRatio = '4:3' | '16:9';

/**
 * How the camera preview is scaled to fill its container when the sensor
 * aspect ratio differs from the container's aspect ratio.
 * - 'cover': The preview fills the whole container, center-cropping the frame
 *   so no empty bars are shown. Parts of the frame outside the container are
 *   hidden from the preview (long-standing default behavior).
 * - 'fit': The whole sensor frame is scaled to fit inside the container
 *   (letterboxed), so the user sees the entire frame they are about to
 *   capture. Empty bars appear on the short axis.
 *
 * @since 3.0.0
 */
export type PreviewScaleMode = 'cover' | 'fit';

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

  /**
   * The type of the camera device (e.g., wide, ultra-wide, telephoto). Populated on iOS and
   * Android; Android only ever reports 'wideAngle', 'ultraWide', or 'telephoto', and omits it
   * for a physical sub-camera of a logical multi-camera, whose lens type CameraX can't resolve.
   */
  deviceType?: CameraDeviceType;
}

/**
 * Available camera device types. The full set of values maps to AVCaptureDevice DeviceTypes on
 * iOS; Android only ever reports 'wideAngle', 'ultraWide', or 'telephoto'.
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
  /** Codabar barcode. Not detectable on iOS below 15.4; the deployment target is 16, so it is available on all supported iOS versions. */
  | 'codabar'
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
  /**
   * UPC-A barcode.
   *
   * Detected as a distinct `upcA` type on Android (ML Kit) and web
   * (BarcodeDetector). iOS cannot: AVFoundation has no UPC-A metadata type and
   * reports UPC-A codes as `ean13` (a UPC-A value is an EAN-13 with a leading
   * `0`), so requesting `upcA` on iOS has no effect and such codes arrive as
   * `ean13`.
   */
  | 'upcA'
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
   * Whether to use the triple camera if available (iPhone Pro models only).
   *
   * The session starts directly on the virtual device, which lets iOS switch
   * between the ultra-wide, wide and telephoto lenses automatically. Takes
   * precedence over `preferredCameraDeviceTypes` for the rear camera.
   *
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
   * The sensor aspect ratio to use for the camera session, applied to both
   * the live preview stream and photo capture so the captured image matches
   * the framing the user sees.
   *
   * **Preview-vs-capture framing contract** (identical on iOS, Android and
   * web when this option is set):
   *
   * - By default (`previewScaleMode: 'cover'`) the preview fills its container
   *   (the fullscreen view behind the WebView on iOS/Android, the container
   *   element on web) using cover semantics: when the chosen sensor ratio
   *   differs from the container ratio, the preview is center-cropped to fill
   *   it — never letterboxed. `capture()` returns the full sensor-ratio image
   *   (matching this option), NOT the on-screen crop. Parts of the image that
   *   were cropped out of the preview by cover-scaling are therefore included
   *   in the capture.
   * - With `previewScaleMode: 'fit'` the whole sensor frame is letterboxed to
   *   fit inside the container, so the preview shows exactly the full captured
   *   frame: `capture()` returns what the preview shows, with nothing cropped
   *   out of view. See {@link previewScaleMode} for the letterbox-background
   *   note.
   *
   * When this option is omitted, each platform keeps its long-standing
   * default behavior: iOS uses the sensor's native photo format (4:3),
   * Android prefers 16:9 with an automatic fallback, and web requests a
   * 16:9 stream and returns the visible (cover-cropped) preview region from
   * `capture()` instead of the full frame. Captured output stays JPEG on all
   * platforms either way.
   *
   * @default undefined - platform default (see above)
   * @since 3.0.0
   */
  aspectRatio?: CameraAspectRatio;

  /**
   * Optional capture-resolution hint: an upper bound, in pixels, for the
   * longer edge of captured photos. The platform picks the largest supported
   * capture resolution whose longer edge does not exceed this value (falling
   * back to the closest supported resolution when none fits) while keeping
   * the configured `aspectRatio`.
   *
   * This is a best-effort hint: the exact output dimensions depend on the
   * resolutions the sensor/browser actually supports.
   *
   * - iOS: constrains `AVCapturePhotoOutput.maxPhotoDimensions`. Only affects
   *   `capture()`; `captureSample()` keeps sampling the preview stream.
   * - Android: bounds the CameraX ImageCapture resolution. Only affects
   *   `capture()`.
   * - Web: used as the ideal `getUserMedia` width constraint, so it affects
   *   the stream (preview and capture alike).
   *
   * @example 1280 // capture photos at most 1280px wide (longer edge)
   * @default undefined - platform default resolution
   * @since 3.0.0
   */
  captureMaxDimension?: number;

  /**
   * How the live preview is scaled into its container when the sensor aspect
   * ratio differs from the container's aspect ratio.
   *
   * - `'cover'` (default): the preview fills the container, center-cropping the
   *   frame. This is the long-standing behavior and is unchanged when the
   *   option is omitted.
   * - `'fit'`: the whole sensor frame is scaled to fit inside the container
   *   (letterboxed), so the user sees the entire frame they are about to
   *   capture. In `fit` mode the preview shows exactly the full captured frame,
   *   and `capture()` returns what the preview shows.
   *
   * Applied consistently on iOS (`AVCaptureVideoPreviewLayer.videoGravity`),
   * Android (`PreviewView.ScaleType`) and web (`object-fit`). Barcode
   * `boundingRect` and `setFocusPoint` coordinates stay correct in both modes.
   *
   * **Letterbox background**: the empty bars shown in `fit` mode are not painted
   * by the plugin — they show whatever is visually behind/around the preview.
   * On iOS/Android that is the app's own background showing through the
   * transparent WebView; on web it is the container element's background. Style
   * that background (e.g. a black or themed color) to control how the
   * letterbox bars look.
   *
   * @default 'cover'
   * @since 3.0.0
   */
  previewScaleMode?: PreviewScaleMode;

  /**
   * The initial zoom factor to use.
   *
   * Expressed relative to the wide-angle lens, so `1.0` is the familiar "1x" field of view on every
   * camera. On iOS virtual devices whose raw `1.0` is the ultra-wide lens (e.g. the triple camera
   * enabled via `useTripleCameraIfAvailable`) the factor is scaled into the device's own zoom domain,
   * which is why `getZoom()` reports a larger `current` than the value passed here.
   *
   * @default 1.0
   */
  zoomFactor?: number;

  /**
   * Prioritize photo quality over capture responsiveness (iOS 17+ only).
   *
   * By default the plugin opts into the iOS 17+ responsive-capture pipeline
   * (zero-shutter-lag, responsive capture and fast capture prioritization) so
   * consecutive `capture()` calls have a lower shot-to-shot latency. Rapid
   * consecutive captures may then be delivered at a slightly reduced quality
   * instead of queueing.
   *
   * Set this to `true` to opt out of that behavior and always prioritize photo
   * quality. Has no effect on iOS versions or hardware without support for the
   * responsive-capture APIs, and no effect on Android or Web.
   *
   * @default false
   * @since 3.0.0
   */
  prioritizeQuality?: boolean;

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
   * The JPEG quality of the captured photo/sample on a scale of 0-100. Cross-platform note:
   * for `quality >= 90`, iOS returns the original, unmodified JPEG produced by the camera
   * hardware instead of re-encoding it, to avoid unnecessary quality loss and CPU overhead.
   * Web always encodes at the exact requested quality. On Android, `quality` is honored
   * exactly by `captureSample()` and by `capture({ saveToFile: false })`, both of which
   * re-encode in software; `capture({ saveToFile: true })` ignores it and always saves at a
   * fixed internal quality, since CameraX re-encodes any cropped output at the `ImageCapture`
   * use case's build-time quality rather than a value supplied per call.
   * @default 90
   * @since 1.1.0
   */
  quality?: number;

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
 * @since 2.3.0
 */
export interface VideoRecordingOptions {
  /**
   * Whether to record audio with the video.
   * Requires microphone permission.
   * @default false
   * @since 2.3.0
   */
  enableAudio?: boolean;

  /**
   * Video recording quality preset.
   * Native platforms only (iOS/Android). Ignored on web.
   * @default 'highest'
   * @since 2.3.0
   */
  videoQuality?: VideoRecordingQuality;
}

/**
 * Response from stopping a video recording.
 * @since 2.3.0
 */
export interface VideoRecordingResponse {
  /**
   * Web-accessible path to the recorded video file that can be used to set the
   * `src` attribute of a video element for efficient loading and rendering.
   * On web, this is a blob URL created with `URL.createObjectURL()`; the plugin does not
   * revoke it automatically, so call `URL.revokeObjectURL(webPath)` once you are done with
   * it (e.g. after playback or upload) to release the underlying memory.
   * On iOS/Android, this is a Capacitor bridge path served by the local web server and does
   * not need to be revoked.
   * @since 2.3.0
   */
  webPath: string;

  /**
   * The full, platform-specific file URL (`file://...`) to the recorded video,
   * usable with the Filesystem API or `Capacitor.convertFileSrc()`.
   * Native only (iOS/Android); `undefined` on web.
   * @since 2.4.0
   */
  path?: string;
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
 * The file-path shaped result returned when `saveToFile` is `true`.
 * @since 1.0.0
 */
export interface CaptureFileResult {
  /**
   * The web path to the captured photo that can be used to set the src attribute of an image
   * for efficient loading and rendering (when saveToFile is true).
   *
   * On web, this is a blob URL created with `URL.createObjectURL()`. The plugin does not revoke
   * it automatically; once you are done with it (e.g. after the image has been displayed or
   * uploaded), call `URL.revokeObjectURL(webPath)` to release the underlying memory. On
   * iOS/Android this is a Capacitor bridge path served by the local web server and does not
   * need to be revoked.
   */
  webPath: string;

  /**
   * The full, platform-specific file URL (`file://...`) to the captured photo,
   * usable with the Filesystem API or `Capacitor.convertFileSrc()`.
   * Native only (iOS/Android); `undefined` on web.
   * @since 2.4.0
   */
  path?: string;
}

/**
 * The base64 shaped result returned when `saveToFile` is `false` or `undefined`.
 * @since 1.0.0
 */
export interface CaptureBase64Result {
  /** The base64 encoded string of the captured photo (when saveToFile is false or undefined) */
  photo: string;
}

/**
 * Response for capturing a photo
 * This will contain either a base64 encoded string or a web path to the captured photo,
 * depending on the `saveToFile` option in the CaptureOptions.
 *
 * @remarks
 * The narrowing is three-way on `saveToFile`:
 * - a literal `true` narrows to {@link CaptureFileResult}
 * - a literal `false`, an explicit `undefined`, or an options type that omits the key
 *   entirely narrows to {@link CaptureBase64Result}
 * - a non-literal `boolean` (or a `CaptureOptions` with `saveToFile` left generic) resolves
 *   to the union of both, since the runtime result can't be known at the type level
 *
 * @since 1.0.0
 */
export type CaptureResponse<T extends CaptureOptions = CaptureOptions> =
  SaveToFileOf<T> extends true
    ? CaptureFileResult
    : SaveToFileOf<T> extends false | undefined
      ? CaptureBase64Result
      : CaptureFileResult | CaptureBase64Result;

/**
 * `T['saveToFile']` resolves through the `CaptureOptions` constraint to `boolean | undefined`
 * when `T` omits the key, which would widen the result to the union. Treat an absent key as
 * `undefined` instead.
 */
type SaveToFileOf<T extends CaptureOptions> = 'saveToFile' extends keyof T ? T['saveToFile'] : undefined;

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
 * @remarks
 * On iOS virtual devices (e.g. the triple camera) these values are in the device's raw zoom domain,
 * not UI multipliers: `min` of `1.0` is the ultra-wide ("0.5x") lens and `current` reports the
 * wide-lens switch-over factor rather than `1.0` at the default zoom. See {@link CameraViewPlugin.getZoom}.
 *
 * @since 1.0.0
 */
export interface GetZoomResponse {
  /** The minimum zoom level supported. On iOS virtual devices `1.0` maps to the ultra-wide lens. */
  min: number;

  /** The maximum zoom level supported. On iOS virtual devices this is a raw device factor (capped at a 10x wide-equivalent zoom). */
  max: number;

  /** The current zoom level. On iOS virtual devices this is a raw device factor, not a UI multiplier. */
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
  /**
   * The current torch intensity level (0.0 to 1.0).
   *
   * On Android this reflects the real hardware strength only on API 35+ (Android 15+)
   * devices with multi-level torch hardware; below API 35, or on single-level hardware,
   * the torch is binary, so this is always 1.0 when enabled and 0.0 when off.
   */
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

  /**
   * Raw bytes as they were encoded in the barcode.
   *
   * On Android, this is forwarded from ML Kit.
   * On iOS, this is available for descriptor-backed formats such as QR, Aztec, PDF417, and Data Matrix.
   * On web, this is not available because the Barcode Detection API only exposes the decoded string value.
   *
   * @since 2.2.0
   */
  rawBytes?: number[];

  /**
   * The display value of the barcode on Android.
   *
   * This is forwarded from ML Kit and may contain a formatted, human-readable
   * representation that differs from the raw decoded value. iOS and web do not
   * expose a separate display value, so this property is only emitted on Android.
   */
  displayValue?: string;

  /**
   * The type/format of the detected barcode.
   *
   * For formats that are part of the `BarcodeType` union, all platforms emit
   * identical values, so scanning the same barcode yields the same `type` on
   * web, iOS, and Android (e.g. `'qr'`, `'code128'`, `'dataMatrix'`).
   *
   * The type is `BarcodeType | string` rather than `BarcodeType` because a
   * platform detector can occasionally report a format that has no
   * cross-platform union member; in that case the raw platform string is
   * forwarded unchanged instead of being dropped. In practice this is Android's
   * `'unknown'` (ML Kit's `FORMAT_UNKNOWN`) and the equivalent `'unknown'` from
   * the web BarcodeDetector. Narrow against the `BarcodeType` members you care
   * about and treat anything else as an opaque string.
   *
   * Platform-specific notes:
   * - iOS distinguishes `interleaved2of5` from `itf14`, whereas Android and web
   *   cannot tell them apart and always report `itf14` for their shared
   *   interleaved-2-of-5/ITF detector format.
   * - UPC-A is reported as `'upcA'` on Android and web, but as `'ean13'` on iOS:
   *   AVFoundation has no UPC-A metadata type and surfaces UPC-A codes as EAN-13
   *   (a UPC-A value is an EAN-13 with a leading `0`). This is a hardware/OS
   *   limitation, not a normalization choice.
   * - Codabar is reported as `'codabar'` on all three platforms.
   */
  type: BarcodeType | string;

  /** The bounding rectangle of the barcode in the camera frame. */
  boundingRect: BoundingRect;
}

/**
 * Rectangle defining the boundary of the barcode in the camera frame.
 * Coordinates are given in display/CSS pixels within the webview (display)
 * coordinate space, not normalized values. This lets you position an overlay
 * directly on top of the detected barcode without further scaling.
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
 * Reason why the camera session was interrupted.
 *
 * Mirrors `AVCaptureSession.InterruptionReason` on iOS. Unknown or future
 * reasons fall back to `'unknown'`.
 *
 * @since 3.0.0
 */
export type CameraInterruptionReason =
  /** The video device is not available because the app is in the background */
  | 'videoDeviceNotAvailableInBackground'
  /** The audio device is in use by another client (e.g. a phone call) */
  | 'audioDeviceInUseByAnotherClient'
  /** The video device is in use by another client */
  | 'videoDeviceInUseByAnotherClient'
  /** The video device is not available while multiple foreground apps share the screen (iPad Split View) */
  | 'videoDeviceNotAvailableWithMultipleForegroundApps'
  /** The video device is not available due to system pressure (e.g. thermal) */
  | 'videoDeviceNotAvailableDueToSystemPressure'
  /** The interruption reason could not be determined */
  | 'unknown';

/**
 * Data for a camera interruption event.
 *
 * @since 3.0.0
 */
export interface CameraInterruptedData {
  /** The reason the camera session was interrupted. */
  reason: CameraInterruptionReason;
}

/**
 * Data for a camera runtime error event.
 *
 * @since 3.0.0
 */
export interface CameraRuntimeErrorData {
  /** A human-readable description of the runtime error. */
  message: string;

  /**
   * The underlying platform error code, when available.
   * On iOS this is the `AVError` code.
   */
  code?: number;
}

/**
 * Stable error codes returned as the `code` property on the error of a
 * rejected plugin call.
 *
 * These codes are part of the plugin's public contract: they are safe to
 * `switch` on and will not change between releases the way human-readable
 * error messages might. Where the same failure class exists across
 * platforms, iOS, Android, and web emit the same code string.
 *
 * Emitted on iOS, Android, and web. One divergence: on web, methods that
 * have no web implementation (e.g. `setFocusPoint()`, `setTorchMode()`)
 * reject via Capacitor's own not-implemented convention instead of one of
 * the codes below - `error.code` is the string `'UNIMPLEMENTED'`, not a
 * `CameraErrorCode`.
 *
 * - 'CAMERA_UNAVAILABLE': No available camera for the requested position.
 * - 'CONFIGURATION_FAILED': Failed to configure the camera session.
 * - 'FRAME_CAPTURE_ERROR': Failed to capture a frame from the camera.
 * - 'INPUT_ADDITION_FAILED': Failed to add an input to the capture session.
 * - 'OUTPUT_ADDITION_FAILED': Failed to add an output to the capture session.
 * - 'PHOTO_OUTPUT_ERROR': An error occurred while capturing a photo.
 * - 'PHOTO_OUTPUT_NOT_CONFIGURED': The photo output has not been configured.
 * - 'SESSION_NOT_RUNNING': The capture session is not currently running. Call `start()` first.
 * - 'SESSION_ALREADY_RUNNING': A camera session is already running. Call `stop()` before starting a new one.
 * - 'UNSUPPORTED_FLASH_MODE': The requested flash mode is not supported by the current camera.
 * - 'TORCH_UNAVAILABLE': Torch is not available on this device or camera position.
 * - 'ZOOM_FACTOR_OUT_OF_RANGE': The requested zoom factor is out of the supported range.
 * - 'FOCUS_NOT_SUPPORTED': The current camera cannot focus or meter at a point (e.g. a fixed-focus camera).
 * - 'PERMISSION_DENIED': Camera or microphone access has been denied.
 * - 'DEVICE_LOCKED': The camera device is currently locked by another process.
 * - 'RECORDING_ALREADY_IN_PROGRESS': A video recording is already in progress.
 * - 'NO_RECORDING_IN_PROGRESS': `stopRecording()` was called but no recording is in progress.
 * - 'AUDIO_DEVICE_UNAVAILABLE': No microphone is available on this device.
 * - 'AUDIO_INPUT_ADDITION_FAILED': Failed to add the microphone input to the capture session.
 * - 'CAPTURE_IN_PROGRESS': A capture is already in progress.
 * - 'CAPTURE_TIMEOUT': Timed out waiting for a camera frame.
 * - 'WEBVIEW_UNAVAILABLE': Could not find the web view to render the camera preview into.
 * - 'INVALID_ARGUMENT': An argument passed to the method call was missing or invalid.
 * - 'IMAGE_COMPRESSION_FAILED': Failed to compress the captured image.
 * - 'PATH_CONVERSION_FAILED': Failed to create a web-accessible path for a captured file.
 * - 'FILE_WRITE_FAILED': Failed to write the captured file to disk.
 * - 'CAPTURE_OUTPUT_MISSING': The capture completed but produced no output data.
 * - 'LIFECYCLE_OWNER_MISSING': (Android only) The WebView's context is not a `LifecycleOwner`, so the camera session cannot be bound.
 * - 'UNKNOWN_ERROR': An unexpected error that does not map to a known camera error (e.g. an underlying OS error).
 *
 * @since 3.0.0
 */
export type CameraErrorCode =
  | 'CAMERA_UNAVAILABLE'
  | 'CONFIGURATION_FAILED'
  | 'FRAME_CAPTURE_ERROR'
  | 'INPUT_ADDITION_FAILED'
  | 'OUTPUT_ADDITION_FAILED'
  | 'PHOTO_OUTPUT_ERROR'
  | 'PHOTO_OUTPUT_NOT_CONFIGURED'
  | 'SESSION_NOT_RUNNING'
  | 'SESSION_ALREADY_RUNNING'
  | 'UNSUPPORTED_FLASH_MODE'
  | 'TORCH_UNAVAILABLE'
  | 'ZOOM_FACTOR_OUT_OF_RANGE'
  | 'FOCUS_NOT_SUPPORTED'
  | 'PERMISSION_DENIED'
  | 'DEVICE_LOCKED'
  | 'RECORDING_ALREADY_IN_PROGRESS'
  | 'NO_RECORDING_IN_PROGRESS'
  | 'AUDIO_DEVICE_UNAVAILABLE'
  | 'AUDIO_INPUT_ADDITION_FAILED'
  | 'CAPTURE_IN_PROGRESS'
  | 'CAPTURE_TIMEOUT'
  | 'WEBVIEW_UNAVAILABLE'
  | 'INVALID_ARGUMENT'
  | 'IMAGE_COMPRESSION_FAILED'
  | 'PATH_CONVERSION_FAILED'
  | 'FILE_WRITE_FAILED'
  | 'CAPTURE_OUTPUT_MISSING'
  | 'LIFECYCLE_OWNER_MISSING'
  | 'UNKNOWN_ERROR';

/**
 * Permission types that can be requested.
 * - 'camera': Camera access permission
 * - 'microphone': Microphone access permission (needed for video recording with audio)
 *
 * @since 2.3.0
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
