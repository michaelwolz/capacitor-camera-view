import { WebPlugin } from '@capacitor/core';
import type { PermissionState } from '@capacitor/core';

import type {
  CameraAspectRatio,
  CameraSessionConfiguration,
  CameraViewPlugin,
  CameraErrorCode,
  CameraPermissionType,
  CameraPosition,
  GetAvailableDevicesResponse,
  GetFlashModeResponse,
  GetSupportedFlashModesResponse,
  GetTorchModeResponse,
  GetZoomResponse,
  IsTorchAvailableResponse,
  IsRunningResponse,
  PermissionStatus,
  CaptureResponse,
  FlashMode,
  CaptureOptions,
  VideoRecordingOptions,
  VideoRecordingResponse,
  BarcodeType,
  PreviewScaleMode,
} from './definitions';
import {
  applyCssZoomCrop,
  calculateFullFrameArea,
  calculateVisibleArea,
  canvasToBase64,
  drawVisibleAreaToCanvas,
  transformBarcodeBoundingBox,
} from './utils';

/**
 * Suppression window in milliseconds during which a repeat of the same barcode
 * (identical value + type) is not re-emitted. A genuinely new code still emits
 * immediately. Kept consistent with the iOS and Android implementations.
 */
export const BARCODE_SUPPRESSION_WINDOW_MS = 500;

/**
 * Once the per-key dedupe map grows past this size, expired entries are pruned
 * so a long session scanning many different codes stays bounded. Kept
 * consistent with the iOS and Android implementations.
 */
export const BARCODE_DEDUPE_MAP_PRUNE_THRESHOLD = 64;

/**
 * Backstop for the "wait until the video is ready" step in
 * {@link CameraViewWeb.startBarcodeDetection}. If the video element never
 * fires `loadeddata` (e.g. a stalled stream), the wait settles anyway after
 * this many milliseconds instead of leaving a dangling listener and a
 * permanently pending promise.
 */
export const BARCODE_VIDEO_READY_TIMEOUT_MS = 5000;

/**
 * Baseline `ideal` capture resolution requested from `getUserMedia` in
 * {@link CameraViewWeb.start}. Without any width/height hint, browsers default
 * to a low-resolution stream (often 640x480), which caps capture quality.
 *
 * These are `ideal` (not `exact`/`min`) so devices that cannot deliver
 * 1080p-class video still start at their best available resolution rather
 * than failing acquisition.
 */
export const DEFAULT_IDEAL_CAPTURE_WIDTH = 1920;
export const DEFAULT_IDEAL_CAPTURE_HEIGHT = 1080;

/**
 * Bounds for the CSS `transform: scale()` zoom simulation used when the browser
 * does not expose a native `zoom` track capability. `getZoom` reports this
 * range and `setZoom` clamps the applied scale to it in fallback mode.
 */
export const SIMULATED_ZOOM_MIN = 1.0;
export const SIMULATED_ZOOM_MAX = 3.0;

/**
 * Non-standard MediaStream zoom shapes. The `zoom` field on capabilities /
 * settings / constraints is defined by the W3C Image Capture spec but is not
 * present in the TypeScript DOM lib, so it is declared here for typed access.
 */
interface ZoomCapability {
  min: number;
  max: number;
  step?: number;
}
type ZoomCapabilities = MediaTrackCapabilities & { zoom?: ZoomCapability };
type ZoomSettings = MediaTrackSettings & { zoom?: number };
type ZoomConstraintSet = MediaTrackConstraintSet & { zoom?: number };

export const BARCODE_TYPE_TO_WEB_FORMAT = {
  qr: 'qr_code',
  code128: 'code_128',
  code39: 'code_39',
  code39Mod43: null,
  code93: 'code_93',
  codabar: 'codabar',
  ean8: 'ean_8',
  ean13: 'ean_13',
  interleaved2of5: 'itf',
  itf14: 'itf',
  pdf417: 'pdf417',
  aztec: 'aztec',
  dataMatrix: 'data_matrix',
  upcA: 'upc_a',
  upce: 'upc_e',
} satisfies Record<BarcodeType, BarcodeFormat | null>;

/**
 * Inverse of {@link BARCODE_TYPE_TO_WEB_FORMAT}: maps the web BarcodeDetector
 * format back onto the cross-platform {@link BarcodeType} vocabulary so the
 * `barcodeDetected` event emits the same `type` values as iOS and Android.
 *
 * Note: `interleaved2of5` and `itf14` both map to the web format `itf`, so the
 * inversion has a collision that is resolved by insertion order — `itf14` comes
 * last in {@link BARCODE_TYPE_TO_WEB_FORMAT} and wins, which is the intended
 * result for `itf` detections.
 */
export const WEB_FORMAT_TO_BARCODE_TYPE: Readonly<Partial<Record<BarcodeFormat, BarcodeType>>> = Object.entries(
  BARCODE_TYPE_TO_WEB_FORMAT,
).reduce<Partial<Record<BarcodeFormat, BarcodeType>>>((acc, [barcodeType, webFormat]) => {
  if (webFormat) {
    acc[webFormat] = barcodeType as BarcodeType;
  }
  return acc;
}, {});

/**
 * Error thrown by the web implementation for a rejected plugin call.
 *
 * Carries a stable `code` from the {@link CameraErrorCode} vocabulary, the
 * same public contract iOS and Android provide, so consumers can `switch` on
 * `error.code` instead of matching on the human-readable `message`.
 *
 * Methods that reject via `WebPlugin.unimplemented()` are the one exception:
 * those keep Capacitor's own `UNIMPLEMENTED` convention.
 */
export class CameraViewError extends Error {
  public readonly code: CameraErrorCode;

  constructor(message: string, code: CameraErrorCode) {
    super(message);
    this.name = 'CameraViewError';
    this.code = code;
  }
}

/**
 * Classifies a `getUserMedia` failure into the closest-fitting
 * {@link CameraErrorCode}, shared by every acquisition site.
 *
 * `kind` selects which media type's dedicated codes apply for `NotFoundError`/
 * `NotReadableError` so a missing/busy camera and a missing/busy microphone
 * aren't conflated. Anything unmappable falls back to `UNKNOWN_ERROR`.
 */
export function classifyGetUserMediaErrorCode(err: unknown, kind: 'camera' | 'microphone'): CameraErrorCode {
  if (err instanceof DOMException) {
    switch (err.name) {
      case 'NotAllowedError':
        return 'PERMISSION_DENIED';
      case 'NotFoundError':
        return kind === 'camera' ? 'CAMERA_UNAVAILABLE' : 'AUDIO_DEVICE_UNAVAILABLE';
      case 'NotReadableError':
        return kind === 'camera' ? 'DEVICE_LOCKED' : 'AUDIO_INPUT_ADDITION_FAILED';
    }
  }
  return 'UNKNOWN_ERROR';
}

/**
 * Web implementation of the CameraViewPlugin.
 * Optimized for performance and battery efficiency.
 */
export class CameraViewWeb extends WebPlugin implements CameraViewPlugin {
  // DOM elements
  private videoElement: HTMLVideoElement | null = null;
  private canvasElement: HTMLCanvasElement | null = null;

  // Stream state
  private stream: MediaStream | null = null;
  #isRunning = false;

  // Configuration state
  private currentCamera = 'environment'; // Default to back camera
  private currentZoom = 1.0;
  // Whether the current zoom is applied through the native track `zoom`
  // capability (`applyConstraints`) rather than the CSS `transform: scale()`
  // simulation. Capture only needs to compensate for the transform in the
  // CSS-fallback case.
  private usingNativeZoom = false;
  private currentFlashMode: FlashMode = 'off';
  // The aspect ratio the current session was started with, or null when the
  // option was omitted. Selects the capture contract: with an explicit ratio,
  // capture() returns the full sensor-ratio frame (cross-platform contract);
  // without it, the legacy web behavior of capturing the visible
  // (cover-cropped) preview region is preserved.
  private sessionAspectRatio: CameraAspectRatio | null = null;
  // How the current session scales the preview into its container. `'fit'`
  // (object-fit: contain) letterboxes the whole frame; `'cover'` (the default)
  // center-crops it. Selects the barcode-transform variant and, together with
  // the aspect ratio, the capture crop.
  private sessionPreviewScaleMode: PreviewScaleMode = 'cover';
  // Resolution/aspect-ratio constraints of the current session, kept so
  // flipCamera() re-acquires the stream with the same resolution contract
  // instead of falling back to the browser default.
  private sessionResolutionConstraints: MediaTrackConstraints = {};

  // Barcode detection support
  private barcodeDetectionSupported = false;
  private barcodeDetector: BarcodeDetector | null = null;

  // Scopes the barcode detection loop to a single start()/stop() session so a
  // rapid stop() -> start() can't let the old loop mistake the new session's
  // running flag for its own and keep polling a detached video element.
  private barcodeDetectionAbortController: AbortController | null = null;
  // The most recently scheduled `requestAnimationFrame` id for the barcode
  // detection loop, so `stop()` can cancel a queued-but-not-yet-run frame
  // outright.
  private barcodeAnimationFrameId: number | null = null;

  // Recording state
  private mediaRecorder: MediaRecorder | null = null;
  private recordedChunks: Blob[] = [];
  private recordingAudioTrack: MediaStreamTrack | null = null;
  private recordingResolve: ((response: VideoRecordingResponse) => void) | null = null;
  private recordingReject: ((error: Error) => void) | null = null;

  constructor() {
    super();
    this.checkBarcodeDetectionSupport();
  }

  /**
   * Start the camera with the given configuration
   */
  async start(options?: CameraSessionConfiguration): Promise<void> {
    // A session is already running. Per the cross-platform contract, reject
    // instead of silently reconfiguring or no-op'ing: callers who need a
    // different configuration (e.g. a different position or resolution) must
    // call `stop()` first and then `start()` again with the new options.
    if (this.#isRunning) {
      throw new CameraViewError('Camera session is already running. Call stop() first.', 'SESSION_ALREADY_RUNNING');
    }

    try {
      // Set up video element if it doesn't exist
      if (!this.videoElement) {
        await this.setupVideoElement(options?.containerElementId);
      }
    } catch (err) {
      // The only failure path in setupVideoElement() is the target container
      // element not being found, which is the web equivalent of "could not
      // find the view to render the camera preview into".
      throw new CameraViewError(`Failed to start camera: ${this.formatError(err)}`, 'WEBVIEW_UNAVAILABLE');
    }

    // Apply the preview scale mode to the video element. `'fit'` letterboxes
    // the whole frame (object-fit: contain); the empty bars show the container's
    // own background. `'cover'` keeps the long-standing center-cropped preview.
    this.sessionPreviewScaleMode = options?.previewScaleMode ?? 'cover';
    if (this.videoElement) {
      this.videoElement.style.objectFit = this.sessionPreviewScaleMode === 'fit' ? 'contain' : 'cover';
    }

    // Set up video constraints based on options
    this.sessionAspectRatio = options?.aspectRatio ?? null;
    this.sessionResolutionConstraints = this.buildResolutionConstraints(options);
    const videoConstraints: MediaTrackConstraints = {
      ...this.sessionResolutionConstraints,
    };

    // Prefer deviceId if specified
    if (options?.deviceId) {
      videoConstraints.deviceId = { exact: options.deviceId };
      // Remember the current camera mode (though we're using a specific device)
      this.currentCamera = options?.position === 'front' ? 'user' : 'environment';
    } else {
      // Fall back to facing mode
      const facingMode = options?.position === 'front' ? 'user' : 'environment';
      this.currentCamera = facingMode;
      videoConstraints.facingMode = facingMode;
    }

    const constraints: MediaStreamConstraints = {
      video: videoConstraints,
      audio: false,
    };

    // Acquire the camera exactly once, using the real constraints, and derive
    // the permission outcome from this single call. Probing permission with a
    // throwaway acquisition first would double startup latency, flash the
    // camera indicator twice, and risk a spurious NotReadableError on devices
    // where the camera is exclusive.
    try {
      this.stream = await navigator.mediaDevices.getUserMedia(constraints);
    } catch (err) {
      throw this.mapStartAcquisitionError(err);
    }

    try {
      if (this.videoElement) {
        this.videoElement.srcObject = this.stream;

        // Some browsers' autoplay policies can reject `play()` (e.g. lack of a
        // recent user gesture). The element is muted + playsInline, which satisfies
        // autoplay policies in virtually all cases, but a rejection must still be
        // handled here rather than left as an unhandled promise rejection.
        try {
          await this.videoElement.play();
        } catch (err) {
          console.warn('[CameraView] Failed to autoplay the video preview', err);
        }

        this.#isRunning = true;

        // Apply the initial zoom. This also re-establishes zoom on the freshly
        // created video element after a stop()/start() cycle, keeping the
        // applied zoom in sync with `currentZoom` instead of silently resetting
        // to an untransformed element. A zoom failure must not abort start().
        try {
          await this.setZoom({ level: options?.zoomFactor ?? 1.0 });
        } catch (err) {
          console.warn('[CameraView] Failed to apply initial zoom factor', err);
        }

        // If barcode detection is enabled and supported, start detection
        if (options?.enableBarcodeDetection) {
          await this.checkBarcodeDetectionSupport();

          if (this.barcodeDetectionSupported) {
            await this.configureBarcodeDetector(options?.barcodeTypes);
            this.startBarcodeDetection();
          }
        }
      }
    } catch (err) {
      // The stream was already acquired here, so its tracks are still live.
      // Tear down the partially-initialized session state so the thrown error
      // leaves a clean slate for a subsequent start().
      this.stream?.getTracks().forEach((track) => track.stop());
      this.stream = null;
      this.#isRunning = false;
      if (this.videoElement) {
        this.videoElement.srcObject = null;
      }

      throw new CameraViewError(`Failed to start camera: ${this.formatError(err)}`, 'UNKNOWN_ERROR');
    }
  }

  /**
   * Builds the resolution/aspect-ratio part of the `getUserMedia` video
   * constraints for a session.
   *
   * `captureMaxDimension` replaces the ideal width (the longer edge in the
   * stream's landscape-oriented coordinate space) and the ideal height is
   * derived from the configured ratio. Everything stays `ideal` so acquisition
   * degrades gracefully on devices that cannot deliver the request.
   */
  private buildResolutionConstraints(options?: CameraSessionConfiguration): MediaTrackConstraints {
    const ratio = options?.aspectRatio === '4:3' ? 4 / 3 : 16 / 9;
    const idealWidth = options?.captureMaxDimension ?? DEFAULT_IDEAL_CAPTURE_WIDTH;
    const idealHeight = Math.round(idealWidth / ratio);

    const constraints: MediaTrackConstraints = {
      width: { ideal: idealWidth },
      height: { ideal: idealHeight },
    };

    if (options?.aspectRatio) {
      constraints.aspectRatio = { ideal: ratio };
    }

    return constraints;
  }

  /**
   * Map a `getUserMedia` failure from the real-constraints acquisition in
   * `start()` onto the plugin's error contract.
   *
   * `NotAllowedError` maps to `PERMISSION_DENIED`, `NotFoundError` (no
   * matching device) to `CAMERA_UNAVAILABLE`, and `NotReadableError` (device
   * claimed by another process) to `DEVICE_LOCKED`, matching the codes
   * iOS/Android use. Anything else falls back to `UNKNOWN_ERROR`.
   */
  private mapStartAcquisitionError(err: unknown): CameraViewError {
    if (err instanceof DOMException) {
      switch (err.name) {
        case 'NotAllowedError':
          return new CameraViewError('Camera permission was not granted', 'PERMISSION_DENIED');
        case 'NotFoundError':
          return new CameraViewError(
            'Failed to start camera: No camera matching the requested configuration was found.',
            'CAMERA_UNAVAILABLE',
          );
        case 'NotReadableError':
          return new CameraViewError(
            'Failed to start camera: The camera could not be started, possibly because it is already in use by another application.',
            'DEVICE_LOCKED',
          );
      }
    }
    return new CameraViewError(`Failed to start camera: ${this.formatError(err)}`, 'UNKNOWN_ERROR');
  }

  /**
   * Stop the camera and release resources
   */
  async stop(): Promise<void> {
    if (!this.#isRunning) {
      return;
    }

    try {
      // Tear down the barcode detection session tied to this camera session
      // first: abort settles any pending video-ready wait in
      // startBarcodeDetection, and cancelling the animation frame stops a
      // queued detectFrame call from ever running. Together these ensure no
      // stale loop can survive into a subsequent start().
      this.barcodeDetectionAbortController?.abort();
      this.barcodeDetectionAbortController = null;
      if (this.barcodeAnimationFrameId !== null) {
        cancelAnimationFrame(this.barcodeAnimationFrameId);
        this.barcodeAnimationFrameId = null;
      }

      // Stop any active recording
      if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
        // Reject any pending stopRecording promise since we're force-stopping.
        // There is no dedicated code for this case, so it falls back to
        // UNKNOWN_ERROR per the plugin's documented "anything unmappable"
        // contract.
        this.recordingReject?.(new CameraViewError('Camera session stopped while recording', 'UNKNOWN_ERROR'));
        this.recordingResolve = null;
        this.recordingReject = null;
        this.mediaRecorder.stop();
        this.mediaRecorder = null;
      }
      this.recordedChunks = [];
      if (this.recordingAudioTrack) {
        this.recordingAudioTrack.stop();
        this.recordingAudioTrack = null;
      }

      // Stop all tracks in the stream
      if (this.stream) {
        this.stream.getTracks().forEach((track) => track.stop());
        this.stream = null;
      }

      // Detach the stream and remove the video element from the DOM
      if (this.videoElement) {
        this.videoElement.pause();
        this.videoElement.srcObject = null;
        this.videoElement.parentNode?.removeChild(this.videoElement);
        this.videoElement = null;
      }

      this.#isRunning = false;
    } catch (err) {
      // No dedicated code for a teardown failure; falls back to UNKNOWN_ERROR
      // per the plugin's documented "anything unmappable" contract.
      throw new CameraViewError(`Failed to stop camera: ${this.formatError(err)}`, 'UNKNOWN_ERROR');
    }
  }

  /**
   * Check if the camera is currently running
   */
  async isRunning(): Promise<IsRunningResponse> {
    return { isRunning: this.#isRunning };
  }

  /**
   * Capture a photo using the camera and return it as a base64-encoded JPEG image.
   * Preserves what the user actually sees in the UI, including cropping from object-fit: cover.
   */
  async capture<T extends CaptureOptions = CaptureOptions & { saveToFile?: undefined }>(
    options?: T,
  ): Promise<CaptureResponse<T>> {
    const videoElement = this.videoElement;

    if (!this.#isRunning || !videoElement) {
      throw new CameraViewError('Camera is not running', 'SESSION_NOT_RUNNING');
    }

    try {
      const canvas = this.getCanvasElement();
      // `fit` mode letterboxes the whole frame and an explicit `aspectRatio`
      // contractually returns the full sensor-ratio frame, so both capture
      // uncropped. Otherwise capture the visible (cover-cropped) region so the
      // output matches what the user sees.
      const captureArea =
        this.sessionPreviewScaleMode === 'fit' || this.sessionAspectRatio
          ? calculateFullFrameArea(videoElement)
          : calculateVisibleArea(videoElement);
      // In CSS-fallback zoom mode the preview is magnified about its center via
      // `transform: scale()`, so tighten the source crop to match the zoomed
      // viewport (this preserves the frame's aspect ratio). Native-zoom mode
      // needs no compensation: the track already delivers the zoomed frame.
      const visibleArea = applyCssZoomCrop(captureArea, this.getCssZoomScale());

      drawVisibleAreaToCanvas(canvas, videoElement, visibleArea);

      // Mirror the native platforms' default: `quality` is optional and defaults
      // to 90 when omitted. Without this, `options?.quality / 100` would be
      // `NaN` for an undefined quality, which is passed silently to `toBlob`/
      // `toDataURL` (both treat an invalid quality as "use the default"), so a
      // caller relying on the documented default would get a different result
      // on web than on iOS/Android.
      const requestedQuality = options?.quality ?? 90;
      const quality = Math.min(1.0, Math.max(0.1, requestedQuality / 100));

      if (options?.saveToFile) {
        // Create a blob from canvas and return a blob URL.
        // `path` is native-only (no filesystem path on web), so it is omitted here.
        return new Promise((resolve, reject) => {
          canvas.toBlob(
            (blob) => {
              if (!blob) {
                reject(new CameraViewError('Failed to create blob from canvas', 'IMAGE_COMPRESSION_FAILED'));
                return;
              }

              const url = URL.createObjectURL(blob);
              resolve({ webPath: url } as CaptureResponse<T>);
            },
            'image/jpeg',
            quality,
          );
        });
      } else {
        // Return base64 data
        const base64Data = canvasToBase64(canvas, quality);
        return { photo: base64Data } as CaptureResponse<T>;
      }
    } catch (err) {
      throw new CameraViewError(`Failed to capture photo: ${this.formatError(err)}`, 'FRAME_CAPTURE_ERROR');
    }
  }

  /**
   * Web implementation already uses images from the video stream, so this is the same as `capture()`
   */
  async captureSample<T extends CaptureOptions = CaptureOptions & { saveToFile?: undefined }>(
    options?: T,
  ): Promise<CaptureResponse<T>> {
    return this.capture(options);
  }

  /**
   * Start recording video using MediaRecorder API
   */
  async startRecording(options?: VideoRecordingOptions): Promise<void> {
    if (!this.#isRunning || !this.videoElement) {
      throw new CameraViewError('Camera is not running', 'SESSION_NOT_RUNNING');
    }

    if (this.mediaRecorder) {
      throw new CameraViewError('Recording is already in progress', 'RECORDING_ALREADY_IN_PROGRESS');
    }

    try {
      let stream = this.stream;

      // If audio is requested, get a new stream with audio track
      if (options?.enableAudio && stream) {
        const audioStream = await navigator.mediaDevices.getUserMedia({ audio: true });
        const audioTrack = audioStream.getAudioTracks()[0];
        this.recordingAudioTrack = audioTrack;
        const videoTracks = stream.getVideoTracks();
        stream = new MediaStream([...videoTracks, audioTrack]);
      }

      if (!stream) {
        throw new Error('No camera stream available');
      }

      this.recordedChunks = [];

      const mimeType = ['video/webm;codecs=vp9', 'video/webm', 'video/mp4'].find((type) =>
        MediaRecorder.isTypeSupported(type),
      );

      if (!mimeType) {
        throw new Error('No supported video recording format found');
      }

      this.mediaRecorder = new MediaRecorder(stream, { mimeType });

      this.mediaRecorder.ondataavailable = (event) => {
        if (event.data && event.data.size > 0) {
          this.recordedChunks.push(event.data);
        }
      };

      this.mediaRecorder.onstop = () => {
        // Stop audio track if it was added for recording
        if (this.recordingAudioTrack) {
          this.recordingAudioTrack.stop();
          this.recordingAudioTrack = null;
        }
        const blob = new Blob(this.recordedChunks, { type: mimeType });
        const url = URL.createObjectURL(blob);
        this.recordedChunks = [];
        this.mediaRecorder = null;
        this.recordingResolve?.({ webPath: url });
        this.recordingResolve = null;
        this.recordingReject = null;
      };

      this.mediaRecorder.onerror = (event) => {
        if (this.recordingAudioTrack) {
          this.recordingAudioTrack.stop();
          this.recordingAudioTrack = null;
        }
        this.mediaRecorder = null;
        this.recordedChunks = [];
        const errorMessage = (event as ErrorEvent).error?.message ?? 'Unknown recording error';
        // A generic MediaRecorder runtime failure has no dedicated code, so
        // it falls back to UNKNOWN_ERROR per the plugin's documented
        // "anything unmappable" contract.
        this.recordingReject?.(new CameraViewError('Recording error: ' + errorMessage, 'UNKNOWN_ERROR'));
        this.recordingResolve = null;
        this.recordingReject = null;
      };

      this.mediaRecorder.start(100); // Collect data in 100ms chunks
    } catch (err) {
      if (this.recordingAudioTrack) {
        this.recordingAudioTrack.stop();
        this.recordingAudioTrack = null;
      }
      this.mediaRecorder = null;
      this.recordedChunks = [];
      throw new CameraViewError(
        `Failed to start recording: ${this.formatError(err)}`,
        this.mapRecordingStartErrorCode(err),
      );
    }
  }

  /**
   * Maps a failure caught by `startRecording()`'s try/catch onto the closest-
   * fitting `CameraErrorCode`, without altering the existing wrapped message.
   *
   * A `DOMException` here can only come from the microphone `getUserMedia`
   * call, so it is classified with `kind: 'microphone'`. The two other
   * distinguishable failures are plain `Error`s matched by message text.
   */
  private mapRecordingStartErrorCode(err: unknown): CameraErrorCode {
    if (err instanceof DOMException) {
      return classifyGetUserMediaErrorCode(err, 'microphone');
    }
    if (err instanceof Error) {
      if (err.message === 'No camera stream available') {
        return 'CAMERA_UNAVAILABLE';
      }
      if (err.message === 'No supported video recording format found') {
        return 'CONFIGURATION_FAILED';
      }
    }
    return 'UNKNOWN_ERROR';
  }

  /**
   * Stop the current video recording
   */
  async stopRecording(): Promise<VideoRecordingResponse> {
    if (!this.mediaRecorder) {
      throw new CameraViewError('No recording is in progress', 'NO_RECORDING_IN_PROGRESS');
    }

    // A stop is already pending. Reject this second call instead of
    // overwriting the pending callbacks, which would orphan the first caller's
    // promise forever.
    if (this.recordingResolve || this.recordingReject) {
      throw new CameraViewError('stopRecording() is already pending', 'UNKNOWN_ERROR');
    }

    return new Promise<VideoRecordingResponse>((resolve, reject) => {
      this.recordingResolve = resolve;
      this.recordingReject = reject;
      this.mediaRecorder?.stop();
    });
  }

  /**
   * Flip between front and back camera
   */
  public async flipCamera(): Promise<void> {
    if (!this.#isRunning) {
      throw new CameraViewError('Camera is not running', 'SESSION_NOT_RUNNING');
    }

    // Flipping restarts the stream and stops the tracks the MediaRecorder is
    // consuming, which would silently freeze an in-progress recording. Reject
    // instead so the caller can stop recording first; the recording stays
    // intact.
    if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
      throw new CameraViewError('Cannot flip camera while a recording is in progress', 'RECORDING_ALREADY_IN_PROGRESS');
    }

    // The candidate facing mode, kept local until the new stream is actually
    // live: `currentCamera` (and the previous stream) must not be touched
    // before that point, or a failed re-acquisition below would leave the
    // session state pointing at a camera that isn't running while the
    // previous camera's tracks have already been stopped.
    const nextCamera = this.currentCamera === 'user' ? 'environment' : 'user';

    // Acquire the new-facing stream with the new facing mode, keeping the
    // session's resolution/aspect-ratio constraints so the flipped stream
    // honors the same contract as the one it replaces.
    const constraints: MediaStreamConstraints = {
      video: {
        ...this.sessionResolutionConstraints,
        facingMode: nextCamera,
      },
      audio: false,
    };

    let newStream: MediaStream;
    try {
      newStream = await navigator.mediaDevices.getUserMedia(constraints);
    } catch (err) {
      // Acquisition failed: `stream`/`currentCamera` haven't been touched, so
      // the previous camera is still attached and running. Just surface the
      // error. The only `getUserMedia` call in this method re-acquires the
      // camera (never the microphone), so classify with kind: 'camera'.
      throw new CameraViewError(
        `Failed to flip camera: ${this.formatError(err)}`,
        classifyGetUserMediaErrorCode(err, 'camera'),
      );
    }

    // The new stream is live: safe to stop the previous stream's tracks and
    // commit the flipped state.
    this.stream?.getTracks().forEach((track) => track.stop());
    this.stream = newStream;
    this.currentCamera = nextCamera;

    if (this.videoElement) {
      this.videoElement.srcObject = newStream;
    }

    // Re-apply the session's zoom level to the new stream through the normal
    // setZoom() path - a flip otherwise silently drops native-zoom's applied
    // constraint (reset on the fresh track) or leaves CSS-fallback's
    // transform stale against the fresh element state. Best-effort: a zoom
    // failure must not fail the flip itself.
    try {
      await this.setZoom({ level: this.currentZoom });
    } catch (err) {
      console.warn('[CameraView] Failed to re-apply zoom after flipping camera', err);
    }
  }

  /**
   * Get available camera devices.
   *
   * Position detection prefers the `facingMode` capability of the active video
   * track, since it is a standardized signal rather than a locale-dependent
   * string. Every other device falls back to matching the English word "front"
   * in `device.label` — which is empty for all devices until camera permission
   * has been granted once, so the fallback resolves to `'back'` in that case.
   */
  public async getAvailableDevices(): Promise<GetAvailableDevicesResponse> {
    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
      const videoDevices = devices.filter((device) => device.kind === 'videoinput');

      const activeTrack = this.getVideoTrack();
      const activeDeviceId = activeTrack?.getSettings().deviceId;
      const activeFacingMode =
        activeTrack && typeof activeTrack.getCapabilities === 'function'
          ? activeTrack.getCapabilities().facingMode?.[0]
          : undefined;

      return {
        devices: videoDevices.map((device) => {
          const position: CameraPosition =
            device.deviceId === activeDeviceId && activeFacingMode
              ? activeFacingMode === 'user'
                ? 'front'
                : 'back'
              : device.label.toLowerCase().includes('front')
                ? 'front'
                : 'back';

          return {
            id: device.deviceId,
            name: device.label || `Camera ${device.deviceId.substring(0, 5)}`,
            position,
          };
        }),
      };
    } catch (err) {
      console.error('Failed to get available devices', err);
      return { devices: [] };
    }
  }

  /**
   * Get current zoom information.
   *
   * When the active video track exposes a native `zoom` capability (Chromium
   * on capable cameras), the real min/max/current are reported. Otherwise the
   * simulated CSS-scale range is returned.
   */
  public async getZoom(): Promise<GetZoomResponse> {
    const track = this.getVideoTrack();
    const capability = this.getNativeZoomCapability(track);

    if (track && capability) {
      const settings = track.getSettings() as ZoomSettings;
      return {
        min: capability.min,
        max: capability.max,
        current: typeof settings.zoom === 'number' ? settings.zoom : this.currentZoom,
      };
    }

    // No native zoom: report the simulated CSS-scale range.
    return {
      min: SIMULATED_ZOOM_MIN,
      max: SIMULATED_ZOOM_MAX,
      current: this.currentZoom,
    };
  }

  /**
   * Set the zoom level.
   *
   * Prefers real zoom via `track.applyConstraints({ advanced: [{ zoom }] })`
   * when the browser exposes the native `zoom` capability, clamping to the
   * reported range. Falls back to a CSS `transform: scale()` simulation
   * otherwise.
   */
  public async setZoom(options: { level: number; ramp?: boolean }): Promise<void> {
    const track = this.getVideoTrack();
    const capability = this.getNativeZoomCapability(track);

    if (track && capability) {
      const clamped = Math.max(capability.min, Math.min(options.level, capability.max));
      await track.applyConstraints({ advanced: [{ zoom: clamped } as ZoomConstraintSet] });
      this.currentZoom = clamped;
      this.usingNativeZoom = true;

      // Clear any CSS transform left over from a previous fallback so the two
      // zoom mechanisms can't stack.
      if (this.videoElement) {
        this.videoElement.style.transform = '';
      }
      return;
    }

    // CSS-transform fallback.
    this.usingNativeZoom = false;
    this.currentZoom = options.level;

    if (this.videoElement) {
      this.videoElement.style.transition = options.ramp ? 'transform 0.2s ease-in-out' : 'none';
      this.videoElement.style.transform = `scale(${this.getCssZoomScale()})`;
      this.videoElement.style.transformOrigin = 'center';
    }
  }

  /**
   * The video track backing the active stream, or `null`.
   */
  private getVideoTrack(): MediaStreamTrack | null {
    return this.stream?.getVideoTracks()[0] ?? null;
  }

  /**
   * Reads the native `zoom` capability off a track, if the browser both
   * supports `getCapabilities()` and exposes a usable `zoom` range on this
   * device. Returns `null` when native zoom is unavailable (CSS fallback).
   */
  private getNativeZoomCapability(track: MediaStreamTrack | null): ZoomCapability | null {
    if (!track || typeof track.getCapabilities !== 'function') {
      return null;
    }

    const zoom = (track.getCapabilities() as ZoomCapabilities).zoom;
    if (zoom && typeof zoom.min === 'number' && typeof zoom.max === 'number' && zoom.max > zoom.min) {
      return zoom;
    }
    return null;
  }

  /**
   * The effective CSS `transform: scale()` factor currently applied to the
   * preview: `1` when native zoom is in use (no transform), otherwise
   * `currentZoom` clamped to the simulated range. Used both to set the
   * transform and to compensate captures for it.
   */
  private getCssZoomScale(): number {
    if (this.usingNativeZoom) {
      return 1;
    }
    return Math.max(SIMULATED_ZOOM_MIN, Math.min(this.currentZoom, SIMULATED_ZOOM_MAX));
  }

  /**
   * Set the focus/metering point (not supported in web).
   *
   * The `pointsOfInterest` media-track constraint has effectively no browser
   * support, so this rejects with `unimplemented` rather than silently doing
   * nothing. Left as a hook for a future implementation.
   */
  public async setFocusPoint(): Promise<void> {
    throw this.unimplemented('Focus point control is not supported in the web implementation.');
  }

  /**
   * Get current flash mode
   */
  public async getFlashMode(): Promise<GetFlashModeResponse> {
    return { flashMode: this.currentFlashMode };
  }

  /**
   * Get supported flash modes
   */
  public async getSupportedFlashModes(): Promise<GetSupportedFlashModesResponse> {
    // Web has limited flash control
    return { flashModes: ['off'] };
  }

  /**
   * Set flash mode (limited support in web).
   *
   * Only `'off'` is supported on web; any other mode rejects rather than
   * silently accepting a mode it cannot apply.
   */
  public async setFlashMode(options: { mode: FlashMode }): Promise<void> {
    if (options.mode !== 'off') {
      throw this.unimplemented('Flash mode control is not supported in the web implementation.');
    }
    this.currentFlashMode = 'off';
  }

  /**
   * Check if torch is available (not supported in web)
   */
  public async isTorchAvailable(): Promise<IsTorchAvailableResponse> {
    // Torch is not supported in web implementation
    return { available: false };
  }

  /**
   * Get torch mode (not supported in web).
   *
   * Follows the documented contract for `getTorchMode()`: callers must check
   * `isTorchAvailable()` first, which always reports `false` on web, so this throws
   * rather than returning a fabricated "off" state.
   */
  public async getTorchMode(): Promise<GetTorchModeResponse> {
    throw this.unimplemented('Torch control is not supported in web implementation.');
  }

  /**
   * Set torch mode (not supported in web)
   */
  public async setTorchMode(): Promise<void> {
    // Torch is not supported in web implementation
    throw this.unimplemented('Torch control is not supported in web implementation.');
  }

  /**
   * Check camera and microphone permission without requesting
   */
  public async checkPermissions(): Promise<PermissionStatus> {
    const [camera, microphone] = await Promise.all([
      this.checkSinglePermission('camera'),
      this.checkSinglePermission('microphone'),
    ]);
    return { camera, microphone };
  }

  /**
   * Resolves the current state of a single permission.
   *
   * Queried independently per permission name so one unsupported query (e.g.
   * Firefox does not support querying `'microphone'`) rejects on its own
   * instead of collapsing *both* permissions to `'prompt'`.
   */
  private async checkSinglePermission(name: CameraPermissionType): Promise<PermissionState> {
    if (navigator.permissions) {
      try {
        const result = await navigator.permissions.query({ name: name as PermissionName });
        return result.state === 'granted' ? 'granted' : result.state === 'denied' ? 'denied' : 'prompt';
      } catch {
        // This permission name is not supported by the Permissions API in this
        // browser; fall through to the best-effort fallback below instead of
        // failing the other permission's check too.
      }
    }

    // If the Permissions API is unavailable/unsupported for this name, fall back to
    // checking the active stream for camera; there is no equivalent signal for
    // microphone without an active audio track, so it stays 'prompt'.
    if (name === 'camera') {
      return this.stream ? 'granted' : 'prompt';
    }
    return 'prompt';
  }

  /**
   * Request camera and/or microphone permissions from the user.
   * By default, only camera permission is requested.
   */
  public async requestPermissions(options?: { permissions?: CameraPermissionType[] }): Promise<PermissionStatus> {
    const permissions = options?.permissions ?? ['camera'];
    const result: PermissionStatus = { camera: 'prompt', microphone: 'prompt' };

    // Request camera permission if included
    if (permissions.includes('camera')) {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ video: true });
        stream.getTracks().forEach((track) => track.stop());
        result.camera = 'granted';
      } catch {
        result.camera = 'denied';
      }
    } else {
      // Still report current status even if not requesting
      result.camera = (await this.checkPermissions()).camera;
    }

    // Request microphone permission only if explicitly included
    if (permissions.includes('microphone')) {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        stream.getTracks().forEach((track) => track.stop());
        result.microphone = 'granted';
      } catch {
        result.microphone = 'denied';
      }
    } else {
      result.microphone = (await this.checkPermissions()).microphone;
    }

    return result;
  }

  /**
   * Start barcode detection if supported
   */
  private async startBarcodeDetection() {
    const barcodeDetector = this.barcodeDetector;
    const videoElement = this.videoElement;

    if (!this.barcodeDetectionSupported || !barcodeDetector || !videoElement) {
      return;
    }

    // Scope this loop to its own session. Aborting the previous controller
    // (defensive - start() only calls in here once per session) and handing
    // out a fresh signal means a stale detectFrame closure from an earlier
    // session can never mistake a later session's #isRunning === true for
    // its own "keep going" signal.
    this.barcodeDetectionAbortController?.abort();
    const abortController = new AbortController();
    this.barcodeDetectionAbortController = abortController;
    const { signal } = abortController;

    // Make sure video is fully loaded before starting detection. The wait
    // settles - without starting detection - as soon as the session is
    // stopped (`signal` aborts), and a timeout backstops the case where the
    // video never fires `loadeddata` at all, so neither path leaves a
    // dangling listener or a permanently pending promise.
    if (videoElement.readyState < 2) {
      const videoReady = await new Promise<boolean>((resolve) => {
        const cleanup = () => {
          videoElement.removeEventListener('loadeddata', loadHandler);
          signal.removeEventListener('abort', abortHandler);
          clearTimeout(timeoutId);
        };
        const loadHandler = () => {
          cleanup();
          resolve(true);
        };
        const abortHandler = () => {
          cleanup();
          resolve(false);
        };
        const timeoutId = setTimeout(() => {
          cleanup();
          resolve(false);
        }, BARCODE_VIDEO_READY_TIMEOUT_MS);

        videoElement.addEventListener('loadeddata', loadHandler);
        signal.addEventListener('abort', abortHandler);
      });

      if (!videoReady || signal.aborted) {
        return;
      }
    }

    if (signal.aborted) {
      return;
    }

    // Add throttling to reduce CPU usage
    let lastDetectionTime = 0;
    const minTimeBetweenDetections = 100; // ms

    // Dedupe state: timestamps of recently emitted barcodes keyed by value +
    // type. A per-key map (rather than a single "last" slot) is required so
    // multiple codes in frame can't alternate and defeat the suppression
    // window. Closure-local, so it resets on every start().
    const recentBarcodeEmitTimes = new Map<string, number>();

    // Set up periodic frame analysis for barcode detection
    const detectFrame = async () => {
      // `signal.aborted` is this loop's own session check: it stays true for
      // this closure even if a rapid stop() -> start() flips #isRunning back
      // to true for a *new* session before this frame runs. `#isRunning` is
      // kept as a defensive secondary check.
      if (signal.aborted || !this.#isRunning || !videoElement || !barcodeDetector) {
        return;
      }

      // Monotonic clock: a backward wall-clock jump must not extend the
      // throttle or suppression windows arbitrarily.
      const now = performance.now();
      if (now - lastDetectionTime >= minTimeBetweenDetections) {
        try {
          const barcodes = await barcodeDetector.detect(videoElement);
          lastDetectionTime = now;

          if (barcodes.length > 0) {
            const barcode = barcodes[0];

            // Normalize the web BarcodeDetector format onto the shared BarcodeType
            // vocabulary. Fall back to the raw format for the few detector formats
            // that have no BarcodeType equivalent (e.g. 'unknown'); the emitted
            // `type` is therefore `BarcodeType | string`.
            const type = WEB_FORMAT_TO_BARCODE_TYPE[barcode.format] ?? barcode.format;

            // Rate control: suppress re-emission of the same code (value + type)
            // within the suppression window. A genuinely new code has a different
            // key and emits immediately. Timestamps are tracked per key so
            // multiple codes in frame can't alternate and defeat the window.
            const barcodeKey = `${type}\u0000${barcode.rawValue}`;
            const lastEmit = recentBarcodeEmitTimes.get(barcodeKey);
            if (lastEmit === undefined || now - lastEmit >= BARCODE_SUPPRESSION_WINDOW_MS) {
              // Prune expired entries once the map grows, keeping it bounded
              // during long sessions that scan many different codes.
              if (recentBarcodeEmitTimes.size > BARCODE_DEDUPE_MAP_PRUNE_THRESHOLD) {
                for (const [key, emitTime] of recentBarcodeEmitTimes) {
                  if (now - emitTime >= BARCODE_SUPPRESSION_WINDOW_MS) {
                    recentBarcodeEmitTimes.delete(key);
                  }
                }
              }
              recentBarcodeEmitTimes.set(barcodeKey, now);

              // Transform barcode coordinates using the utility function,
              // accounting for the session's preview scale mode (cover crops,
              // fit letterboxes) so the rect lands over the on-screen barcode.
              const boundingRect = transformBarcodeBoundingBox(
                barcode.boundingBox,
                videoElement,
                this.sessionPreviewScaleMode,
              );

              this.notifyListeners('barcodeDetected', {
                value: barcode.rawValue,
                type,
                boundingRect,
              });
            }
          }
        } catch (err) {
          console.error('Barcode detection error', err);
        }
      }

      if (!signal.aborted && this.#isRunning) {
        this.barcodeAnimationFrameId = requestAnimationFrame(detectFrame);
      }
    };

    this.barcodeAnimationFrameId = requestAnimationFrame(detectFrame);
  }

  /**
   * Check if barcode detection is supported in this browser
   */
  private async checkBarcodeDetectionSupport() {
    if ('BarcodeDetector' in window) {
      try {
        this.barcodeDetector = new BarcodeDetector();
        this.barcodeDetectionSupported = true;
      } catch (e) {
        console.warn('BarcodeDetector is not supported by this browser.');
        this.barcodeDetectionSupported = false;
      }
    }
  }

  /**
   * Configure the barcode detector with requested barcode formats.
   * Unsupported formats are ignored and logged.
   */
  private async configureBarcodeDetector(barcodeTypes?: BarcodeType[]): Promise<void> {
    if (!this.barcodeDetectionSupported) {
      return;
    }

    if (!barcodeTypes?.length) {
      this.barcodeDetector = new BarcodeDetector();
      return;
    }

    const requestedFormats = barcodeTypes
      .map((barcodeType) => {
        const webFormat = BARCODE_TYPE_TO_WEB_FORMAT[barcodeType];
        if (!webFormat) {
          console.warn(`[CameraView] Barcode type "${barcodeType}" is not supported by the web BarcodeDetector API.`);
        }
        return webFormat;
      })
      .filter((format): format is NonNullable<typeof format> => format !== null);

    if (!requestedFormats.length) {
      console.warn(
        '[CameraView] No requested barcode types are supported on web. Falling back to all supported formats.',
      );
      this.barcodeDetector = new BarcodeDetector();
      return;
    }

    const uniqueRequestedFormats = Array.from(new Set(requestedFormats));

    try {
      const supportedFormats = await BarcodeDetector.getSupportedFormats();
      const configuredFormats = uniqueRequestedFormats.filter((format) => supportedFormats.includes(format));
      const ignoredFormats = uniqueRequestedFormats.filter((format) => !supportedFormats.includes(format));

      if (ignoredFormats.length) {
        console.warn(
          `[CameraView] Ignoring unsupported barcode formats for this browser: ${ignoredFormats.join(', ')}.`,
        );
      }

      if (!configuredFormats.length) {
        console.warn(
          '[CameraView] No requested barcode formats are available in this browser. Falling back to all supported formats.',
        );
        this.barcodeDetector = new BarcodeDetector();
        return;
      }

      this.barcodeDetector = new BarcodeDetector({ formats: configuredFormats });
    } catch (error) {
      console.warn(
        '[CameraView] Failed to resolve supported barcode formats; falling back to unfiltered detector.',
        error,
      );
      this.barcodeDetector = new BarcodeDetector();
    }
  }

  /**
   * Set up the video element for the camera view
   */
  private async setupVideoElement(containerElementId?: string) {
    this.videoElement = document.createElement('video');
    this.videoElement.playsInline = true;
    this.videoElement.autoplay = true;
    this.videoElement.muted = true;
    this.videoElement.style.width = '100%';
    this.videoElement.style.height = '100%';
    this.videoElement.style.objectFit = 'cover';

    // If a container ID is provided, find that element and append the video to it
    if (containerElementId) {
      const container = document.getElementById(containerElementId);
      if (!container) {
        throw new Error(`Container element with ID ${containerElementId} not found`);
      }
      container.appendChild(this.videoElement);
    } else {
      // Otherwise, append to body as fallback
      document.body.appendChild(this.videoElement);
    }
  }

  /**
   * Ensures canvas element exists and returns it
   */
  private getCanvasElement(): HTMLCanvasElement {
    if (!this.canvasElement) {
      this.canvasElement = document.createElement('canvas');
    }
    return this.canvasElement;
  }

  /**
   * Format error message
   */
  private formatError(err: unknown): string {
    return err instanceof Error ? err.message : String(err);
  }
}
